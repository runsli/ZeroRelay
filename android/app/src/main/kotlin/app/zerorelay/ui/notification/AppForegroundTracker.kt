package app.zerorelay.ui.notification

import android.app.Activity
import android.app.Application

/** 跟踪应用是否在前台，用于决定是否展示本地通知。 */
object AppForegroundTracker : Application.ActivityLifecycleCallbacks {
    private var startedCount = 0
    private var attached = false

    fun attach(application: Application) {
        if (attached) return
        attached = true
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedCount++
        ActiveChatTracker.appInForeground = startedCount > 0
    }

    override fun onActivityStopped(activity: Activity) {
        startedCount = (startedCount - 1).coerceAtLeast(0)
        ActiveChatTracker.appInForeground = startedCount > 0
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
