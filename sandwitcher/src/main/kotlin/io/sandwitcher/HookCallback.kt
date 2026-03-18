package io.sandwitcher

interface HookCallback {
    fun beforeMethod(param: MethodHookParam): HookAction = HookAction.Continue
    fun afterMethod(param: MethodHookParam) {}
}
