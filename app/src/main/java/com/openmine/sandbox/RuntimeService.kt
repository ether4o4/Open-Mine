package com.openmine.sandbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.openmine.MainActivity
import com.openmine.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Owns runtime lifetime independently of activities and individual shell requests. */
class RuntimeService : Service() {
    private var runtime: OpenMineRuntime? = null
    private var bridge: RuntimeBridgeServer? = null
    private var foregroundReady = false
    private var serviceGeneration = 0L

    override fun onCreate() {
        super.onCreate()
        serviceGeneration = state.value.generation
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Linux runtime", NotificationManager.IMPORTANCE_LOW
            ))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), pendingFlags)
            val stop = PendingIntent.getService(this, 1,
                Intent(this, RuntimeService::class.java).setAction(ACTION_STOP), pendingFlags)
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Open Mine runtime is active")
                .setContentText("Linux sessions continue when you close the app.")
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(Notification.Action.Builder(null, "Stop runtime", stop).build())
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundReady = true
        } catch (error: Exception) {
            failed(this, serviceGeneration, error)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = RuntimeBridgeSettings(this)
        if (intent?.action == ACTION_STOP) {
            stop(this)
            return START_NOT_STICKY
        }
        if (!foregroundReady || !settings.runtimeRequested) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val requestedGeneration = if (intent == null) {
            resumeGeneration(this) ?: run {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        } else intent.getLongExtra(EXTRA_GENERATION, -1)
        if (requestedGeneration != state.value.generation) {
            // A newer request owns the service. Never let a queued old start cancel it.
            if (state.value is StartState.Stopped || state.value is StartState.Failed) stopSelf(startId)
            return START_NOT_STICKY
        }
        serviceGeneration = requestedGeneration
        try {
            val firstStart = runtime == null
            val current = runtime ?: OpenMineRuntime.get(this).also { runtime = it }
            configureBridge(current, settings)
            if (!markRunning(serviceGeneration)) return START_NOT_STICKY
            if (firstStart) current.onServiceStarted()
        } catch (error: Exception) {
            failed(this, serviceGeneration, error)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // OS reclamation may recreate this service; explicit Stop clears runtimeRequested.
        // Android force-stop, battery restrictions, and reboot still end the runtime.
        return START_STICKY
    }

    private fun configureBridge(current: OpenMineRuntime, settings: RuntimeBridgeSettings) {
        if (!settings.enabled) {
            bridge?.close()
            bridge = null
        } else if (bridge == null) {
            val server = RuntimeBridgeServer(this, current) { stop(this) }
            try {
                server.start()
                bridge = server
            } catch (error: Exception) {
                server.close()
                throw error
            }
        }
    }

    override fun onDestroy() {
        bridge?.close()
        bridge = null
        runtime?.stopRuntime()
        runtime = null
        markDestroyed(serviceGeneration)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "openmine-linux-runtime"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_START = "com.openmine.runtime.INTERNAL_START"
        private const val ACTION_STOP = "com.openmine.runtime.INTERNAL_STOP"
        private const val ACTION_RECONFIGURE = "com.openmine.runtime.INTERNAL_RECONFIGURE"
        private const val EXTRA_GENERATION = "generation"
        private val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running
        private val _failure = MutableStateFlow<String?>(null)
        val failure: StateFlow<String?> = _failure
        private sealed interface StartState {
            val generation: Long
            data class Stopped(override val generation: Long) : StartState
            data class Starting(override val generation: Long) : StartState
            data class Running(override val generation: Long) : StartState
            data class Failed(override val generation: Long, val message: String) : StartState
        }
        private val state = MutableStateFlow<StartState>(StartState.Stopped(0))

        fun start(context: Context) { requestStart(context) }

        @Synchronized
        private fun requestStart(context: Context): Long {
            val previous = state.value
            if (previous is StartState.Running || previous is StartState.Starting) return previous.generation
            val app = context.applicationContext
            val generation = previous.generation + 1
            _failure.value = null
            state.value = StartState.Starting(generation)
            try {
                RuntimeBridgeSettings(app).setRuntimeRequested(true)
                app.startForegroundService(Intent(app, RuntimeService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_GENERATION, generation))
            } catch (error: Exception) {
                failed(app, generation, error)
                throw IllegalStateException(_failure.value, error)
            }
            return generation
        }

        suspend fun ensureStarted(context: Context) {
            val generation = requestStart(context)
            val result = withTimeoutOrNull(15_000) {
                state.first { it.generation != generation || it !is StartState.Starting }
            } ?: run {
                failTimedOutStart(context, generation)
                error("Timed out starting the Linux runtime. Open Open Mine and try again.")
            }
            check(result.generation == generation) { "Runtime start was cancelled or replaced" }
            when (result) {
                is StartState.Failed -> error(result.message)
                is StartState.Running -> Unit
                else -> error("Runtime stopped before startup completed")
            }
        }

        @Synchronized
        fun stop(context: Context) {
            _running.value = false
            _failure.value = null
            state.value = StartState.Stopped(state.value.generation + 1)
            try {
                RuntimeBridgeSettings(context).setRuntimeRequested(false)
            } finally {
                context.applicationContext.stopService(Intent(context, RuntimeService::class.java))
            }
        }

        @Synchronized
        fun reconfigure(context: Context) {
            val current = state.value
            if (current !is StartState.Running) return
            context.applicationContext.startService(Intent(context, RuntimeService::class.java).setAction(ACTION_RECONFIGURE)
                .putExtra(EXTRA_GENERATION, current.generation))
        }

        @Synchronized
        private fun resumeGeneration(context: Context): Long? {
            if (!RuntimeBridgeSettings(context).runtimeRequested) return null
            val current = state.value
            if (current is StartState.Starting || current is StartState.Running) return current.generation
            return (current.generation + 1).also { state.value = StartState.Starting(it) }
        }

        @Synchronized
        private fun markRunning(generation: Long): Boolean {
            val current = state.value
            if (current.generation != generation || (current !is StartState.Starting && current !is StartState.Running)) return false
            _running.value = true
            _failure.value = null
            state.value = StartState.Running(generation)
            return true
        }

        @Synchronized
        private fun markDestroyed(generation: Long) {
            if (state.value.generation != generation) return
            _running.value = false
            if (state.value !is StartState.Failed) state.value = StartState.Stopped(generation)
        }

        @Synchronized
        private fun failTimedOutStart(context: Context, generation: Long) {
            if (state.value != StartState.Starting(generation)) return
            failed(context, generation, IllegalStateException("Android did not start the foreground service in time"))
            context.applicationContext.stopService(Intent(context, RuntimeService::class.java))
        }

        @Synchronized
        private fun failed(context: Context, generation: Long, error: Exception) {
            if (state.value.generation != generation) return
            runCatching { RuntimeBridgeSettings(context).setRuntimeRequested(false) }
            val message = "Cannot start the Linux runtime: ${error.message ?: error.javaClass.simpleName}. Open Open Mine and try again."
            _running.value = false
            _failure.value = message
            state.value = StartState.Failed(generation, message)
        }
    }
}
