package io.sandwitcher.internal

import android.util.Log
import io.sandwitcher.HookAction
import io.sandwitcher.HookCallback
import io.sandwitcher.HookHandle
import io.sandwitcher.MethodHookParam
import top.canyie.pine.Pine
import top.canyie.pine.PineConfig
import top.canyie.pine.callback.MethodHook
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal object HookEngine {

    private const val TAG = "Sandwitcher.Engine"
    @Volatile private var debug = false
    private val nextId = AtomicLong(1)
    private val activeHooks = ConcurrentHashMap<Long, PineHookEntry>()

    private class PineHookEntry(
        val pineCallback: MethodHook,
        val unhook: MethodHook.Unhook,
    )

    fun init(debug: Boolean) {
        this.debug = debug
        PineConfig.debug = debug
        PineConfig.debuggable = debug
        Pine.disableJitInline()
        if (debug) Log.w(TAG, "Pine/LSPlant engine initialized")
    }

    fun hook(method: Method, callback: HookCallback): HookHandle {
        val hookId = nextId.getAndIncrement()

        val pineCallback = object : MethodHook() {
            private val paramHolder = ThreadLocal<MethodHookParam>()

            override fun beforeCall(callFrame: Pine.CallFrame) {
                val param = MethodHookParam(
                    method = callFrame.method as? Method ?: method,
                    thisObject = callFrame.thisObject,
                    args = callFrame.args ?: emptyArray(),
                )
                paramHolder.set(param)

                try {
                    when (val action = callback.beforeMethod(param)) {
                        is HookAction.Continue -> {}
                        is HookAction.ReturnEarly -> {
                            callFrame.result = action.result
                            param.result = action.result
                        }
                    }
                    callFrame.args = param.args
                } catch (t: Throwable) {
                    Log.e(TAG, "beforeMethod threw for hook #$hookId", t)
                }
            }

            override fun afterCall(callFrame: Pine.CallFrame) {
                val param = paramHolder.get() ?: MethodHookParam(
                    method = callFrame.method as? Method ?: method,
                    thisObject = callFrame.thisObject,
                    args = callFrame.args ?: emptyArray(),
                )
                paramHolder.remove()
                param.result = callFrame.result
                param.throwable = callFrame.throwable

                try {
                    callback.afterMethod(param)
                    callFrame.result = param.result
                    if (param.throwable != callFrame.throwable) {
                        callFrame.throwable = param.throwable
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "afterMethod threw for hook #$hookId", t)
                }
            }
        }

        val unhook = Pine.hook(method, pineCallback)
        activeHooks[hookId] = PineHookEntry(pineCallback, unhook)

        val handle = HookHandle(hookId, method) { h ->
            removeHook(h.id)
            if (debug) Log.w(TAG, "Unhooked #${h.id}: ${method.declaringClass.simpleName}.${method.name}()")
        }

        if (debug) {
            Log.w(TAG, "Hooked #$hookId: ${method.declaringClass.name}.${method.name}() " +
                    "[${method.parameterTypes.joinToString { it.simpleName }}]")
        }

        return handle
    }

    private fun removeHook(hookId: Long) {
        val entry = activeHooks.remove(hookId) ?: return
        entry.unhook.unhook()
    }

    fun clear() {
        for (entry in activeHooks.values) {
            entry.unhook.unhook()
        }
        activeHooks.clear()
    }
}
