package com.openmine

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openmine.sandbox.RuntimeBridgeSettings
import com.openmine.sandbox.RuntimeService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Validates Android service ownership without downloading Linux or starting a model. */
@RunWith(AndroidJUnit4::class)
class RuntimeServiceLifecycleTest {
    @Test(timeout = 45_000)
    fun activityClosureDoesNotStopServiceAndExplicitStopAllowsImmediateRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = RuntimeBridgeSettings(context)
        val bridgeWasEnabled = settings.enabled
        try {
            settings.setEnabled(false)
            RuntimeService.reconfigure(context)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                runBlocking { RuntimeService.ensureStarted(context) }
                assertTrue(RuntimeService.running.value)
                scenario.moveToState(Lifecycle.State.CREATED)
                try {
                    instrumentation.waitForIdleSync()
                    assertTrue("Stopping the Activity must not stop the foreground service", RuntimeService.running.value)
                } finally {
                    // ActivityScenario leaves its EmptyActivity on top when stopped.
                    // Closing from CREATED launches that helper again and can wait for
                    // a resume callback from an activity that is already resumed.
                    // Close still destroys the real Activity after this transition.
                    scenario.moveToState(Lifecycle.State.RESUMED)
                }
            }
            instrumentation.waitForIdleSync()
            assertTrue("Destroying the Activity must not stop the foreground service", RuntimeService.running.value)

            // Keep the Activity visible for the new foreground-service start on Android 12+.
            ActivityScenario.launch(MainActivity::class.java).use {
                RuntimeService.stop(context)
                assertFalse(RuntimeService.running.value)
                runBlocking { RuntimeService.ensureStarted(context) }
                assertTrue("An immediate restart must not reuse a stale running state", RuntimeService.running.value)
                RuntimeService.stop(context)
                assertFalse(RuntimeService.running.value)
            }
        } finally {
            RuntimeService.stop(context)
            instrumentation.waitForIdleSync()
            settings.setEnabled(bridgeWasEnabled)
        }
    }
}
