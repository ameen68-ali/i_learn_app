package com.ilearn.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.test.runner.AndroidJUnitRunner

/** Keep owned test windows awake; never dismiss the keyguard or change device lock settings. */
class ValidationTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        val app = targetContext.applicationContext as Application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (app.packageName == "com.ilearn.app.validation") activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        try { super.onStart() } finally { app.unregisterActivityLifecycleCallbacks(callbacks) }
    }
}
