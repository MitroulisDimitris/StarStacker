package com.starstacker.core

import android.content.Context
import com.starstacker.camera.CameraAccess
import com.starstacker.capture.DeviceEnvironment
import com.starstacker.session.SessionRoot
import com.starstacker.session.SessionStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * T-0.7 — the dispatchers, named rather than reached for.
 *
 * A hardcoded `Dispatchers.IO` inside a class is a dependency that cannot be replaced, which in a
 * test means either a real thread pool or a rewrite. Naming them costs one parameter.
 */
data class AppDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val main: CoroutineDispatcher = Dispatchers.Main,
)

/**
 * T-0.7 — one place that knows how to build the app's parts.
 *
 * ### What this is not
 *
 * Not a dependency-injection framework, and not an attempt to make everything injectable. The
 * seams that carry the weight already exist and were built where they were needed: [SessionStore]
 * hides SAF from the capture engine, and `CaptureEngine.Environment` hides the sensors and the
 * thermal API. Those are why 218 tests run on a laptop with no phone attached.
 *
 * What was missing is narrower and duller: **nothing owned the construction**. The Activity and
 * the Service each built their own store, camera and environment from a `Context` they happened to
 * be, so changing how any of them is made meant finding every place that made one. That is the
 * problem this fixes, and the reason it is small.
 *
 * ### Why the parts are factories, not fields
 *
 * [cameraAccess] and [deviceEnvironment] each own hardware and must be closed. Holding one on a
 * process-scoped container would keep the camera open between sessions and the gyro sampling all
 * night. The caller that opens one closes it; the container only knows how to build it.
 */
class AppContainer(
    private val context: Context,
    val clock: Clock = SystemClock,
    val dispatchers: AppDispatchers = AppDispatchers(),
) {

    /** The user's chosen folder when there is one, app-private storage otherwise (T-0.5). */
    fun sessionStore(): SessionStore = SessionRoot.store(context)

    /** Caller closes it. */
    fun cameraAccess(): CameraAccess = CameraAccess(context)

    /** Caller closes it — it holds a sensor listener. */
    fun deviceEnvironment(): DeviceEnvironment = DeviceEnvironment(context, clock)

    companion object {
        /**
         * Resolved from any `Context`, so a Service does not need a reference to the Activity and
         * neither needs to know the Application's type at the call site.
         */
        fun from(context: Context): AppContainer =
            (context.applicationContext as ContainerHost).container
    }
}

/** Implemented by the Application. An interface so tests can stand in a different container. */
interface ContainerHost {
    val container: AppContainer
}
