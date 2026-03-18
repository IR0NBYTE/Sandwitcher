# Sandwitcher

[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://developer.android.com)
[![Min SDK](https://img.shields.io/badge/minSdk-24-blue.svg)](https://developer.android.com/about/versions/nougat)
[![License](https://img.shields.io/badge/license-Apache%202.0-orange.svg)](LICENSE)
[![Contributions Welcome](https://img.shields.io/badge/contributions-welcome-brightgreen.svg)](https://github.com/IR0NBYTE/Sandwitcher/issues)

<p align="center">
  <img src="assets/logo_git.png" alt="Sandwitcher" width="300" />
</p>

Hook any Java/Kotlin method at runtime on Android. No root needed, no recompilation, just drop the AAR into your project.

Sandwitcher swaps the entry point of any `ArtMethod` at runtime, letting you run your own code before or after the original method. It works on instance methods, static methods, native methods, final classes, private methods, constructors, framework classes -- anything the runtime can call, you can hook.

```kotlin
val method = URL::class.java.getDeclaredMethod("openConnection")

Sandwitcher.hook(method, object : HookCallback {
    override fun beforeMethod(param: MethodHookParam): HookAction {
        Log.d("Audit", "Connection to: ${param.thisObject}")
        return HookAction.Continue
    }
    override fun afterMethod(param: MethodHookParam) {
        val conn = param.result as HttpURLConnection
        conn.setRequestProperty("X-Custom-Header", "injected")
    }
})
```

No compile-time dependency on the target classes. Everything is resolved via reflection.

## Demo


https://github.com/user-attachments/assets/fd9b7639-c141-4f26-8b2b-55f8c172841d


## Setup

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("io.sandwitcher:sandwitcher:0.1.0")
}
```

## Usage

Initialize once in your Application class:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Sandwitcher.init(this)
    }
}
```

Hook a method by reflection:

```kotlin
val method = SomeClass::class.java.getDeclaredMethod("doWork", String::class.java)
val handle = Sandwitcher.hook(method, myCallback)
```

Or by class name if you don't have the class at compile time:

```kotlin
val handle = Sandwitcher.hook(
    className = "com.example.target.PaymentProcessor",
    methodName = "processPayment",
    parameterTypes = arrayOf(Double::class.java, String::class.java),
    callback = myCallback
)
```

Write your callback:

```kotlin
object myCallback : HookCallback {
    override fun beforeMethod(param: MethodHookParam): HookAction {
        val args = param.args
        val thisObj = param.thisObject

        return HookAction.Continue
        // or: return HookAction.ReturnEarly(customResult)
    }

    override fun afterMethod(param: MethodHookParam) {
        val result = param.result
        param.result = modifiedResult
    }
}
```

Remove the hook when you're done:

```kotlin
handle.unhook()
```

## Documentation

For more detailed guides, check the [docs/](docs/) folder:

- [Getting Started](docs/getting-started.md) -- setup, first hook, basic patterns
- [Advanced Usage](docs/advanced-usage.md) -- static methods, native methods, threading, performance
- [Troubleshooting](docs/troubleshooting.md) -- common issues and how to fix them

### Sandwitcher

The main entry point. Call `init` once, then `hook` as many methods as you want.

`Sandwitcher.init(application)` sets up the hooking engine. You can pass a `SandwitcherConfig` if you want debug logging:

```kotlin
Sandwitcher.init(this, SandwitcherConfig(debugLogging = true))
```

`Sandwitcher.hook(method, callback)` takes a `java.lang.reflect.Method` and a `HookCallback`. Returns a `HookHandle` you can use to remove the hook later.

`Sandwitcher.hook(className, methodName, parameterTypes, callback)` does the same thing but resolves the class by name at runtime. Useful when you don't have the target class in your classpath.

`Sandwitcher.reset()` removes all active hooks at once.

### HookCallback

An interface with two methods. Both are optional -- override the ones you need.

`beforeMethod(param)` runs before the original method. You get access to the arguments and can modify them. Return `HookAction.Continue` to let the original run, or `HookAction.ReturnEarly(value)` to skip it and return your own value instead.

`afterMethod(param)` runs after the original method (or after `beforeMethod` if you returned early). You can read the return value from `param.result` and change it if you want.

### MethodHookParam

This is what gets passed to your callback. It has:

- `method` -- the `java.lang.reflect.Method` being hooked
- `thisObject` -- the instance the method was called on, or `null` for static methods
- `args` -- the argument array, you can modify these in-place in `beforeMethod`
- `result` -- the return value, available in `afterMethod`
- `throwable` -- if the original method threw an exception, it shows up here

The same `MethodHookParam` instance is shared between `beforeMethod` and `afterMethod` within a single call, so any state you set in `beforeMethod` is still there in `afterMethod`.

### HookHandle

Returned by `Sandwitcher.hook()`. Call `handle.unhook()` to remove the hook and restore the original method. Check `handle.isActive` to see if the hook is still installed. Thread-safe -- calling `unhook()` multiple times is fine.

### HookAction

A sealed class with two cases:

- `HookAction.Continue` -- let the original method run
- `HookAction.ReturnEarly(result)` -- skip the original and return `result` instead

### SandwitcherConfig

Pass this to `Sandwitcher.init()` to configure the SDK. Currently has one option:

- `debugLogging` -- set to `true` to log hook install/uninstall events to logcat under the `Sandwitcher` tag

## How it works

Under the hood, Sandwitcher uses [Pine](https://github.com/canyie/pine) which builds on [LSPlant](https://github.com/LSPosed/LSPlant). When you hook a method:

1. The target `ArtMethod*` is resolved via JNI
2. A backup copy of the method is created (allocated through DexBuilder so it's GC-safe)
3. The original's `entry_point_from_quick_compiled_code_` is replaced with a trampoline
4. JIT inlining is disabled for hooked methods so the compiler can't optimize around the hook
5. Calls go through: your `beforeMethod` -> original (via backup) -> your `afterMethod`

This happens at the native level, below Java. Works on interpreted, JIT-compiled, and AOT-compiled methods.

## Compatibility

- Android 5.0 through 15 (API 21-35)
- ARM, ARM64, x86, x86_64
- No root required
- Works in release builds

## Building from source

```bash
./gradlew :sandwitcher:assembleRelease

# run the demo app
./gradlew :app:installDebug
```

You'll need JDK 17 and Android SDK 35.

## Project structure

```
sandwitcher/                     SDK module (ships as AAR)
  src/main/kotlin/io/sandwitcher/
    Sandwitcher.kt               entry point
    HookCallback.kt              before/after interface
    HookAction.kt                Continue or ReturnEarly
    HookHandle.kt                unhook handle
    MethodHookParam.kt           call context
    SandwitcherConfig.kt         config
    internal/
      HookEngine.kt              Pine/LSPlant bridge

app/                             demo app
  src/main/kotlin/.../demo/
    SandwitcherDemoApp.kt
    MainActivity.kt
```

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for details on how to get started, submit PRs, and what to work on.

## Acknowledgements

Built on [Pine](https://github.com/canyie/pine) by canyie and [LSPlant](https://github.com/LSPosed/LSPlant) by LSPosed.

## License

Apache 2.0. See [LICENSE](LICENSE) for details.
