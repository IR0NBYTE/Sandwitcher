package io.sandwitcher

import java.lang.reflect.Method

class MethodHookParam(
    val method: Method,
    val thisObject: Any?,
    val args: Array<Any?>,
) {
    var result: Any? = null
    var throwable: Throwable? = null
}
