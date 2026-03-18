package io.sandwitcher

import android.app.Application
import android.util.Log
import io.sandwitcher.internal.HookEngine
import java.lang.reflect.Method

object Sandwitcher {

    private const val TAG = "Sandwitcher"
    @Volatile private var initialized = false
    @Volatile private var config = SandwitcherConfig()

    fun init(application: Application, config: SandwitcherConfig = SandwitcherConfig()) {
        if (initialized) {
            Log.w(TAG, "Already initialized")
            return
        }
        this.config = config
        HookEngine.init(config.debugLogging)
        initialized = true
        Log.w(TAG, "Initialized (debug=${config.debugLogging})")
    }

    fun hook(method: Method, callback: HookCallback): HookHandle {
        check(initialized) { "Sandwitcher.init() must be called before hooking" }
        return HookEngine.hook(method, callback)
    }

    fun hook(
        className: String,
        methodName: String,
        parameterTypes: Array<Class<*>> = emptyArray(),
        callback: HookCallback,
    ): HookHandle {
        val clazz = Class.forName(className)
        val method = clazz.getDeclaredMethod(methodName, *parameterTypes)
        return hook(method, callback)
    }

    fun reset() {
        HookEngine.clear()
        initialized = false
        Log.w(TAG, "Reset")
    }
}
