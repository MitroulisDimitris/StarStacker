package com.starstacker

import android.app.Application
import com.starstacker.diag.FieldLog

/**
 * Exists for one reason: [FieldLog] has to be running before anything can go wrong.
 *
 * Starting it from an Activity would leave the window between process start and `onCreate`
 * uncovered — which is exactly where a startup crash happens, during class loading and static
 * initialisation. A crash log that cannot record startup crashes is missing the case it is least
 * able to reproduce afterwards.
 */
class StarStackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FieldLog.start(this)
    }
}
