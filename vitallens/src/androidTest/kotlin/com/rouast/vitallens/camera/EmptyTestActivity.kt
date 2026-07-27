package com.rouast.vitallens.camera

import android.app.Activity

/**
 * A minimal Activity whose only purpose is to bring the test process to the foreground.
 *
 * [androidx.lifecycle.ProcessLifecycleOwner] (used internally by [CameraSource]) only reaches
 * [androidx.lifecycle.Lifecycle.State.STARTED] once *some* Activity is started — instrumented
 * tests that never launch one leave CameraX's use cases bound but the camera never actually
 * opens, which is indistinguishable from a hang without this.
 */
class EmptyTestActivity : Activity()
