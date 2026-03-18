package io.sandwitcher

sealed class HookAction {
    data object Continue : HookAction()
    data class ReturnEarly(val result: Any?) : HookAction()
}
