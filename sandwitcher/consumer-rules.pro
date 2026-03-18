# Sandwitcher SDK - keep public API
-keep class io.sandwitcher.Sandwitcher { *; }
-keep class io.sandwitcher.HookCallback { *; }
-keep class io.sandwitcher.MethodHookParam { *; }
-keep class io.sandwitcher.HookAction { *; }
-keep class io.sandwitcher.HookHandle { *; }
-keep class io.sandwitcher.SandwitcherConfig { *; }

# Pine internals
-keep class top.canyie.pine.** { *; }
