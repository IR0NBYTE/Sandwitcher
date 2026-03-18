<p align="center">
  <img src="assets/logo.png" alt="Sandwitcher" width="200" />
</p>

# Sandwitcher

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

## API

`Sandwitcher.init(application, config?)` -- call once at startup.

`Sandwitcher.hook(method, callback)` -- hook a method. Returns a `HookHandle`.

`Sandwitcher.hook(className, methodName, paramTypes, callback)` -- same thing but resolves the class by name.

`Sandwitcher.reset()` -- remove all hooks.

`HookCallback.beforeMethod(param)` -- runs before the original. Return `Continue` or `ReturnEarly(result)`.

`HookCallback.afterMethod(param)` -- runs after the original. Read or modify `param.result`.

`HookHandle.unhook()` -- remove this specific hook.

`MethodHookParam` gives you `method`, `thisObject`, `args`, `result`, and `throwable`.

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

Contributions are welcome. Fork the repo, make your changes, and open a pull request.

If you're fixing a bug, include a description of what was broken and how to reproduce it. If you're adding a feature, explain the use case.

Keep changes focused -- one PR per fix or feature. Make sure the demo app still builds and runs before submitting.

For larger changes, open an issue first to discuss the approach.

## Acknowledgements

Built on [Pine](https://github.com/canyie/pine) by canyie and [LSPlant](https://github.com/LSPosed/LSPlant) by LSPosed.

## License

Apache 2.0. See [LICENSE](LICENSE) for details.
