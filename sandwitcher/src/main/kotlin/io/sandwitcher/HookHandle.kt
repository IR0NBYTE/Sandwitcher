package io.sandwitcher

import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

class HookHandle internal constructor(
    internal val id: Long,
    internal val target: Method,
    private val onUnhook: (HookHandle) -> Unit,
) {
    private val active = AtomicBoolean(true)

    val isActive: Boolean get() = active.get()

    fun unhook() {
        if (active.compareAndSet(true, false)) {
            onUnhook(this)
        }
    }
}
