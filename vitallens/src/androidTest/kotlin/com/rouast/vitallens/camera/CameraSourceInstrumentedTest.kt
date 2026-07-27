package com.rouast.vitallens.camera

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraSourceInstrumentedTest {

    @get:Rule
    val cameraPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    // runBlocking, not runTest: runTest's virtual-time scheduler doesn't wait for real wall-clock
    // events (camera HAL warm-up, real frame delivery) — it's a JVM-unit-test tool for skipping
    // through simulated delays, not appropriate against genuine hardware/system async behavior.
    @Test
    fun startCapturesARealFrameFromTheFrontCamera() = runBlocking {
        // CameraSource uses ProcessLifecycleOwner internally (see its KDoc) so callers don't need
        // to pass in their own LifecycleOwner. That only reaches STARTED once *some* Activity is
        // in the foreground — without this, CameraX binds the use cases successfully but the
        // camera never actually opens, which looks identical to a hang. A real host app always
        // has a foregrounded Activity when it calls start(); a test harness doesn't unless we
        // launch one ourselves.
        ActivityScenario.launch(EmptyTestActivity::class.java).use {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val source = CameraSource(context)

            try {
                source.start()
                val frame = withTimeout(15_000) { source.stream.first() }

                assertTrue("bitmap width should be positive", frame.bitmap.width > 0)
                assertTrue("bitmap height should be positive", frame.bitmap.height > 0)
                assertTrue("isMirrored should be true for the front camera", frame.isMirrored)
            } finally {
                source.stop()
            }
        }
    }
}
