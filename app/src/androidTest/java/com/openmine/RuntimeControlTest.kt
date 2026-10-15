package com.openmine

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openmine.sandbox.RuntimeBridgeSettings
import com.openmine.sandbox.RuntimeControlReceiver
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercises the external-control authorization boundary without touching installed runtime data. */
@RunWith(AndroidJUnit4::class)
class RuntimeControlTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private var serviceStarts = 0

    @Before
    fun isolateSettings() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(target.cacheDir, "runtime-control-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun startForegroundService(service: Intent): ComponentName? {
                serviceStarts++
                return service.component
            }
        }
    }

    @After
    fun removeSettings() {
        directory.deleteRecursively()
    }

    @Test
    fun optInAndPersistedSecretAreRequiredForAuthentication() {
        val settings = RuntimeBridgeSettings(context)
        assertFalse(settings.enabled)
        val token = settings.token()
        assertTrue(token.matches(Regex("[a-f0-9]{64}")))
        assertFalse(settings.authenticates(token))
        settings.setEnabled(true)
        assertTrue(RuntimeBridgeSettings(context).authenticates(token))
        assertEquals(token, RuntimeBridgeSettings(context).token())
        assertFalse(settings.authenticates(null))
        assertFalse(settings.authenticates("0".repeat(63)))
        val wrongToken = (if (token[0] == '0') "1" else "0") + token.substring(1)
        assertFalse(settings.authenticates(wrongToken))
        settings.setEnabled(false)
        assertFalse(RuntimeBridgeSettings(context).authenticates(token))
    }

    @Test
    fun corruptSettingsFailClosedAndRegenerateCredentials() {
        val settings = RuntimeBridgeSettings(context)
        settings.setEnabled(true)
        val original = settings.token()
        File(directory, "runtime-control.json").writeText("not json")
        assertFalse(settings.enabled)
        assertFalse(settings.authenticates(original))
        assertNotEquals(original, settings.token())
        assertFalse(settings.enabled)
    }

    @Test
    fun receiverIgnoresDisabledUnauthorizedAndImplicitRequests() {
        val settings = RuntimeBridgeSettings(context)
        val token = settings.token()
        val receiver = RuntimeControlReceiver()
        fun request(action: String = RuntimeControlReceiver.ACTION_START, credential: String = token): Intent =
            Intent(context, RuntimeControlReceiver::class.java).setAction(action)
                .putExtra(RuntimeControlReceiver.EXTRA_TOKEN, credential)
        receiver.onReceive(context, request())
        settings.setEnabled(true)
        receiver.onReceive(context, request(credential = "wrong"))
        receiver.onReceive(context, request(action = "com.openmine.runtime.STOP"))
        receiver.onReceive(context, Intent(RuntimeControlReceiver.ACTION_START)
            .putExtra(RuntimeControlReceiver.EXTRA_TOKEN, token))
        assertEquals(0, serviceStarts)
    }
}
