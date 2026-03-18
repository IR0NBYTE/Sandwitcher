# ART Method Interception: Comprehensive Research Survey

> **Date:** 2026-02-25
> **Scope:** Every known approach to intercepting Java/Kotlin method execution on Android's ART runtime
> **Constraint:** In-process, no root, no Zygote injection, SDK shipped as an AAR to third-party apps
> **Goal:** Identify the most robust, novel, and forward-compatible interception strategy

---

## Table of Contents

1. [ART Runtime Architecture Primer](#1-art-runtime-architecture-primer)
2. [Taxonomy of All Known Hooking Techniques](#2-taxonomy-of-all-known-hooking-techniques)
3. [Deep Dive: Each Framework](#3-deep-dive-each-framework)
4. [Comparison Matrix](#4-comparison-matrix)
5. [Google's Hardening Efforts & Future Threats](#5-googles-hardening-efforts--future-threats)
6. [Gap Analysis](#6-gap-analysis)
7. [Novel & Underexplored Directions](#7-novel--underexplored-directions)
8. [Recommendation](#8-recommendation)
9. [Academic & Research Sources](#9-academic--research-sources)
10. [All References](#10-all-references)

---

## 1. ART Runtime Architecture Primer

### 1.1 The ArtMethod Struct

Every Java/Kotlin method in ART is represented at runtime by a C++ `ArtMethod` struct. This is the single most important data structure for understanding all hooking approaches.

**Key fields (simplified across versions):**

```
struct ArtMethod {
    // GC root to the class that declares this method
    GcRoot<mirror::Class> declaring_class_;

    // Access flags (public, private, static, native, etc.)
    uint32_t access_flags_;

    // Index into the DEX file's method table
    uint32_t dex_method_index_;

    // Offset to the method's CodeItem in the DEX file
    uint32_t dex_code_item_offset_;

    // --- Pointer-sized fields (vary by arch: 4 or 8 bytes) ---

    // For JNI methods: pointer to native function
    // For ART internal use: pointer to various data
    void* data_;  // (was entry_point_from_jni_ in older versions)

    // THE KEY FIELD: pointer to the compiled machine code
    // This is what ART jumps to when invoking this method
    void* entry_point_from_quick_compiled_code_;
};
```

**Evolution across Android versions:**

| Version | Changes |
|---------|---------|
| Android 5.0 (L) | 3 entry points: interpreter, JNI, quick_compiled_code |
| Android 8.0 (O) | Simplified to: dex_cache_resolved_methods, data_, quick_compiled_code |
| Android 9.0 (P) | Further simplified: data_ and quick_compiled_code only |
| Android 10-11 | Nterp interpreter introduction begins |
| Android 12+ | Nterp becomes primary interpreter; struct mostly stable |
| Android 14+ | MTE/PAC support in surrounding infrastructure |

**Critical insight:** The `entry_point_from_quick_compiled_code_` field is the universal dispatch point. When ART needs to execute a method, it loads this field and jumps to it. Every Category A hooking technique modifies this field (or the code it points to).

Sources:
- AOSP art_method.h: https://android.googlesource.com/platform/art/+/master/runtime/art_method.h
- Kanxue forum ArtMethod analysis: https://bbs.pediy.com/thread-248898.htm

### 1.2 Execution Modes

ART uses multiple execution strategies for Java methods:

**Interpreter (Legacy switch-based):**
- Reads DEX bytecode instruction by instruction
- Slow but always correct
- `entry_point_from_quick_compiled_code_` → `art_quick_to_interpreter_bridge`

**Nterp (New interpreter, Android 12+):**
- Assembly-written interpreter (mostly ARM64/x86-64 assembly)
- Significantly faster than the old switch interpreter
- `entry_point_from_quick_compiled_code_` → `ExecuteNterpImpl`
- **Critical for hooking:** Nterp has its own fast paths that may bypass standard entry point checks for certain call patterns (e.g., when caller and callee are both in nterp)
- Method entry/exit/unwind events are handled by installing instrumentation stubs, so nterp need not be fully disabled for these
- `dex_pc_listeners` are handled only in the interpreter; methods that need these events are deoptimized out of nterp

Source: AOSP nterp.cc: https://cs.android.com/android/platform/superproject/+/master:art/runtime/interpreter/mterp/nterp.cc

**JIT (Just-In-Time compilation):**
- Compiles "hot" methods into native machine code at runtime
- `entry_point_from_quick_compiled_code_` → JIT-generated native code
- JIT can **recompile** methods at any time, potentially overwriting hooked entry points
- JIT performs **method inlining** — a short callee B can be inlined into caller A, making hooks on B invisible when called from A

**AOT (Ahead-Of-Time compilation):**
- Methods compiled during install (dex2oat) or via profile-guided compilation
- `entry_point_from_quick_compiled_code_` → precompiled code in .oat file
- More stable than JIT but still subject to deoptimization

**Key trampolines (in ClassLinker):**
- `quick_resolution_trampoline_` — resolves method on first call
- `quick_generic_jni_trampoline_` — bridges to JNI native code
- `quick_to_interpreter_bridge_trampoline_` — transitions from native to interpreter
- `quick_imt_conflict_trampoline_` — resolves interface method table conflicts

### 1.3 Method Dispatch

How ART dispatches method calls matters for hooking:

- **Direct methods** (static, private, constructors): Called by direct reference to ArtMethod
- **Virtual methods**: Dispatched through the class's **vtable** (array of ArtMethod pointers)
- **Interface methods**: Dispatched through **IMT** (Interface Method Table) with conflict resolution
- **invoke-polymorphic**: Used for MethodHandle; special ART handling

### 1.4 Deoptimization

ART's deoptimization mechanism is central to understanding hooking robustness:

- **Selective deoptimization:** Forces a specific method from compiled code back to interpreter
- **Full deoptimization:** Forces ALL methods to interpreter
- **On-stack replacement (OSR):** Can deoptimize methods that are currently executing on a thread's stack
- **Three states:** `kNothing` (no deopt needed), `kSelectiveDeoptimization`, `kFullDeoptimization`

When deoptimizing:
1. ART walks each thread's stack
2. Replaces return PCs with instrumentation exit stubs
3. Saves frame information in the instrumentation stack
4. Forces the method to run through the interpreter on next entry

**Relevance to hooking:** Deoptimization can be used both offensively (force a method to interpreter for easier interception) and defensively (detect that a method has been tampered with).

Source: AOSP instrumentation.cc: https://android.googlesource.com/platform/art/+/master/runtime/instrumentation.cc

### 1.5 GC Interaction

The Garbage Collector interacts with hooking in several ways:

- `declaring_class_` in ArtMethod is a `GcRoot` — GC may **move** the class object, updating this field
- If a hook creates a synthetic ArtMethod (copy/clone), the GC may not know about it → the `declaring_class_` pointer goes stale → crash
- JIT code caches may be invalidated during GC
- Moving GC can relocate objects that trampolines reference

---

## 2. Taxonomy of All Known Hooking Techniques

### Category A: ArtMethod Entry Point Replacement
Modify `entry_point_from_quick_compiled_code_` to redirect execution.
**Frameworks:** Frida, LSPlant, Pine, Xposed/LSPosed, YAHFA, SandHook

### Category B: Inline Code Patching (Callee-Side Rewriting)
Patch the actual machine code at the address the entry point points to.
**Frameworks:** Epic, SandHook (dual-mode)

### Category C: Vtable Tampering
Replace pointers in the class's virtual method dispatch table.
**Frameworks:** ARTDroid

### Category D: Native PLT/GOT Hooking
Modify the ELF linking tables to redirect native function calls.
**Frameworks:** ByteHook, bhook

### Category E: Native Inline Hooking
Patch the first instructions of a native function to jump to hook code.
**Frameworks:** ShadowHook, Dobby, And64InlineHook

### Category F: JVMTI Class Retransformation
Use the official Java debugging interface to rewrite class bytecode.
**Frameworks:** ART TI (Android 8.0+), JTIK, Stoic

### Category G: ClassLoader / DEX Manipulation
Intercept class loading to replace or modify classes/methods.
**Frameworks:** Tinker, Robust, hot-patching frameworks

### Category H: Compiler/Build-Time Instrumentation
Modify code at build time (dex2oat or D8/R8 level).
**Frameworks:** ARTist, ReVanced Patcher

### Category I: Java Proxy Mechanisms
Use standard Java APIs for interface-based interception.
**Frameworks:** java.lang.reflect.Proxy, Dexmaker ProxyBuilder

---

## 3. Deep Dive: Each Framework

### 3.1 Frida (frida-java-bridge / frida-gum)

**Repository:** https://github.com/frida/frida-java-bridge

**How `Java.use().method.implementation` works at the ART level:**

1. Frida resolves the target `ArtMethod*` using `FromReflectedMethod` (JNI)
2. Modifies `access_flags_` to include `kAccNative` — this tells ART the method is a JNI native method
3. Sets `entry_point_from_jni_` to Frida's native callback handler (data_ field on modern ART)
4. Replaces `entry_point_from_quick_compiled_code_` with a pointer to Frida's **trampoline** — a small native stub
5. The trampoline:
   - Saves all CPU registers (general purpose + floating point)
   - Calls into Frida's GumJS engine (V8 or QuickJS)
   - Executes user's JavaScript `onEnter` callback
   - Optionally calls original method (via saved backup)
   - Executes `onLeave` callback
   - Restores registers and returns

**Nterp handling:**
- If the target method is running in nterp, Frida first replaces its entry point with `art_quick_to_interpreter_bridge` to force it out of the nterp fast path
- Then applies the native flag trick described above
- This two-step process ensures the method goes through the standard entry point dispatch

**JIT recompilation survival:**
- Recent Frida versions (2024-2025) roll back each `ArtMethod`'s entrypoint to its previous value after hook installation
- This prevents JIT from "restoring" the entry point and bypassing the hook
- Frida also hooks JIT compilation notifications to re-apply hooks after recompilation

**GC survival:**
- Frida pins replacement `ArtMethod` objects to prevent GC collection
- Post-GC: synchronizes the `declaring_class_` field on the replacement ArtMethod so it doesn't go stale
- Recent releases added explicit post-GC synchronization logic

**Root requirement:**
- `frida-server` mode: **Requires root** (runs as a separate process, injects via ptrace)
- `frida-gadget` mode: **No root required** — loaded as a shared library (`libfrida-gadget.so`) embedded in the APK
  - Can be loaded via `System.loadLibrary` or injected as a dependency of an existing native library
  - Gadget mode is the relevant mode for AAR delivery

**API range:** Android 5.0 – 15+ (actively maintained)

**Known failure modes:**
- OEM-modified ArtMethod struct layouts (different field offsets) — Samsung, Huawei, OPPO etc.
- Methods inlined by JIT/AOT into callers — hook on callee never fires for inlined call sites
- Thread safety during hook installation on hot (frequently called) methods
- Detection vectors:
  - Check if methods have `kAccNative` flag when they shouldn't
  - Scan memory for Frida agent strings ("frida", "gadget", "gum-js-loop")
  - Check `entry_point_from_quick_compiled_code_` against known code ranges
  - Detect Frida's named pipes and D-Bus protocol
  - `/proc/self/maps` scanning for `frida-agent` or `frida-gadget`
- Heavy memory footprint (~10-20MB for the JS engine)

**Has Google patched against it?** Not directly, but Android's evolving security model (W^X enforcement, MTE, hidden API restrictions) makes Frida's job harder with each version.

Sources:
- https://medium.com/@identx_labs/learning-how-frida-works-a-deep-dive-into-the-hooking-framework-d522ae13cc67
- https://medium.com/@shilpashetty3003/frida-beginner-to-advance-series-part-2-how-frida-hooking-works-under-the-hood-6a49977d0468
- https://cdmana.com/2022/141/202205210357554405.html
- https://frida.re/docs/android/
- https://deepwiki.com/frida/frida-java-bridge

---

### 3.2 Xposed / LSPosed (Legacy Hooking Engine)

**Repository (original):** https://github.com/rovo89/XposedBridge

**The hooking flow:**

1. `XposedBridge.hookMethod(method, callback)` is called from Java
2. This calls native `XposedBridge_hookMethodNative` (in libxposed_art.cpp)
3. The native function gets the `ArtMethod*` from the Java `Method` object via `FromReflectedMethod`
4. Calls `EnableXposedHook(artMethod, additionalInfo)`:
   a. Allocates a **new ArtMethod** using the runtime's ClassLinker allocator
   b. **Copies** the entire original ArtMethod into this new allocation (byte-for-byte)
   c. Tags the backup with `kAccXposedOriginalMethod` flag in `access_flags_`
   d. Replaces the original method's `entry_point_from_quick_compiled_code_` with a bridge to `handleHookedMethod`
5. When the hooked method is called:
   - ART jumps to the bridge
   - Bridge calls Java `XposedBridge.handleHookedMethod(Member, int, Object, Object, Object[])`
   - `handleHookedMethod` iterates through registered before/after callbacks
   - To call the original: `invokeOriginalMethodNative` uses the backup ArtMethod

**The `access_flags_` manipulation:**
- Sets `kAccXposedOriginalMethod` on backup
- May modify flags on the original to indicate it's hooked
- This allows the framework to distinguish hooked from unhooked methods at runtime

**Why it requires root/Zygote:**
- Traditional Xposed replaces `/system/bin/app_process` with a modified version
- This modified process loads XposedBridge into every Zygote-forked process
- LSPosed uses Magisk + Riru (or Zygisk) to inject into Zygote without modifying system files
- **Both still require root** — cannot run as a normal AAR in an app

**Evolution from Dalvik to ART:**
- Dalvik: Hooked via `dvmUseJNIBridge` — simpler, more stable
- ART 5.0-6.0: Required understanding new ArtMethod layout (3 entry points)
- ART 7.0+: Entry points simplified; JIT added new complications
- ART 10+: Nterp introduced; old backup approach became fragile

**Known failure modes:**
- ArtMethod struct size changes across versions break the byte copy
- Backup ArtMethod is NOT a real ART-managed object — GC doesn't know about it
- If GC moves the declaring class, backup's `declaring_class_` goes stale → crash
- No handling for method inlining
- No handling for nterp in later versions (→ led to creation of LSPlant)
- Detectable via: Xposed class presence, loaded modules list, stack trace inspection

**API range:** Android 5.0 – ~12 (legacy); LSPosed extended to ~13

Sources:
- https://mssun.me/blog/understanding-xposed-framework-art-runtime.html
- https://binderfilter.github.io/xposed/
- https://www.oreateai.com/blog/analysis-and-implementation-principles-of-xposed-framework-technology-in-art-environment/49980dfe80d350bda7f643d3e4a4633f

---

### 3.3 LSPlant (LSPosed/LSPlant)

**Repository:** https://github.com/LSPosed/LSPlant

**THE MOST ARCHITECTURALLY SIGNIFICANT INNOVATION in modern ART hooking.**

**How it differs from old Xposed (the key insight):**

Instead of doing a raw byte-copy of ArtMethod (which creates an object ART doesn't know about), LSPlant generates **real ART methods** using runtime DEX generation.

**Step-by-step mechanism:**

1. **DEX Generation:** Uses `DexBuilder` to generate an auxiliary DEX file at runtime containing a synthetic class with two methods:
   - A **hook stub method** (will replace the target)
   - A **backup method** (will preserve the original behavior)

2. **Class Loading:** Loads this generated DEX into ART via `InMemoryDexClassLoader` (or equivalent), making both methods real, GC-tracked ArtMethods known to the runtime.

3. **Entry Point Replacement:**
   - Gets the ArtMethod pointers for target, hook stub, and backup
   - Copies target's ArtMethod data into backup (now a *real* ArtMethod with proper GC tracking)
   - Writes architecture-specific **trampoline code** into executable memory
   - Replaces target's `entry_point_from_quick_compiled_code_` with pointer to trampoline

4. **Trampoline Mechanics (per-architecture):**

   **ARM32:**
   ```asm
   ldr r0, [pc, #offset]    ; Load hooked ArtMethod* into r0 (first param)
   ldr pc, [r0, #entry_off]  ; Jump to hook's entry_point_from_quick_compiled_code_
   .word <hooked_art_method>  ; Embedded pointer to hook ArtMethod
   ```

   **ARM64:**
   ```asm
   ldr x0, #offset           ; Load hooked ArtMethod* into x0 (first param)
   ldr x16, [x0, #entry_off] ; Load hook's entry point
   br x16                     ; Branch to hook
   .quad <hooked_art_method>  ; Embedded 64-bit pointer
   ```

   The trampoline replaces the first parameter (which ART uses to pass the ArtMethod* for the method being called) with the hook's ArtMethod*, then jumps to the hook's entry point.

5. **Original Method Invocation:**
   - The backup method IS a real ArtMethod with the original's code
   - Calling it goes through normal ART dispatch — no special native bridge needed
   - Because it's a real method, ART's JIT, GC, and deoptimization handle it correctly

**Why this architecture is superior:**

| Property | Old Xposed | LSPlant |
|----------|-----------|---------|
| Backup is GC-tracked | No (raw copy) | Yes (real ArtMethod) |
| Backup survives GC class movement | No (stale declaring_class_) | Yes |
| JIT can optimize backup | No | Yes |
| Deoptimization works on backup | No | Yes |
| ART verification passes | Fragile | Yes |

**Inline deoptimization API:**
```cpp
// If method B is inlined into method A, hook on B won't fire from A.
// Solution: deoptimize A to force it through the interpreter.
bool LSPlant::Deoptimize(JNIEnv *env, jobject method);
```
- Safe to call on a hooked method (deoptimizes the backup instead)
- Forces the method to always be interpreted — no JIT inlining possible
- The caller A will now properly call through B's hooked entry point

**Root requirement:** **None.** LSPlant is a pure C++ library that runs in-process. It's designed to be embedded in other frameworks. LSPosed uses it with root for Zygote injection, but the library itself is rootless.

**API range:** Android 5.0 – 15 Beta2 (API 21 – 35)
**Architectures:** armeabi-v7a, arm64-v8a, x86, x86-64, riscv64

**Known failure modes:**
- Still depends on knowing ArtMethod struct layout per Android version (offset tables)
- OEM modifications to ArtMethod can break it
- Cannot hook methods in classes loaded before LSPlant initializes (unless you can get the ArtMethod pointer)
- Trampoline allocation requires executable memory (`mprotect` with `PROT_EXEC`)
- If nterp has a fast path that bypasses entry_point_from_quick_compiled_code_, the hook may be skipped (edge case)

**Has Google patched against it?** Not specifically, but each Android version requires testing and potentially new offset tables.

Sources:
- https://github.com/LSPosed/LSPlant
- https://lsposed.org/LSPlant/
- https://github.com/LSPosed/LSPlant/discussions/5
- https://github.com/LSPosed/LSPlant/discussions/11
- https://github.com/5ec1cff/my-notes/blob/master/lsplant.md

---

### 3.4 Pine (canyie/pine)

**Repository:** https://github.com/canyie/pine

**Mechanism:**

Pine is a trampoline-based ART method hooking framework with Xposed-style API. Its architecture is similar to LSPlant but with some distinct choices:

1. **Entry Point Replacement Mode:**
   - Modifies `entry_point_from_quick_compiled_code_` to point to Pine's trampoline
   - Creates a backup ArtMethod for calling the original
   - Supports Xposed-style before/after callbacks

2. **Inline Hook Mode (fallback):**
   - Patches the first few instructions of the compiled method code
   - Uses a trampoline to save original instructions and redirect
   - Falls back to entry point replacement if PC-relative instructions are detected in the code to be overwritten (avoids instruction relocation complexity)

3. **Hybrid Decision:**
   - For virtual methods on Android 8.0 and below: Prefers inline hook mode because of the "Sharpening" optimization (ART may hardcode entry addresses in machine code, bypassing entry_point_from_quick_compiled_code_)
   - For other cases: Uses entry point replacement (simpler, more reliable)

**Delay/Pending Hooks:**
- Can hook static methods **before** their declaring class is initialized
- When the class eventually loads, the hook activates
- This is useful for hooking classes in third-party libraries that load lazily

**Hidden API Bypass:**
- Pine automatically disables Android's hidden API restriction policy on initialization
- Uses `VMRuntime.setHiddenApiExemptions` or equivalent techniques
- **Warning:** Due to an ART bug, if one thread changes hidden API policy while another thread lists class members, an out-of-bounds write can occur → crash

**GC Handling:**
- The backup ArtMethod's `declaring_class_` is a GcRoot that may be relocated by GC
- ART automatically updates addresses in the original ArtMethod but NOT in dynamically created ones
- Pine must manually update the declaring_class_ pointer in the backup after GC

**Root requirement:** **None** — works in-process

**API range:** Android 4.4 (ART only) – 15 Beta4 (thumb-2 / arm64)

**Known issues:**
- ARM32 on Android 6.0: Method arguments may be incorrect
- Hidden API bypass race condition (described above)
- Crash on Huawei devices with certain method types (Issue #1)

Sources:
- https://github.com/canyie/pine
- https://blog.canyie.top/2020/04/27/dynamic-hooking-framework-on-art/

---

### 3.5 Epic (tiann/epic)

**Repository:** https://github.com/tiann/epic

**Created by:** weishu (tiann), also the creator of VirtualXposed and TaiChi

**The distinctive approach — callee-side dynamic rewriting:**

Unlike LSPlant/Pine which replace the entry point pointer, Epic patches the **actual machine code** at the address the entry point points to.

1. Reads the current `entry_point_from_quick_compiled_code_` to find where the compiled code lives
2. Writes a **jump instruction** (branch) at the very beginning of that compiled code
3. This jump redirects to Epic's bridge/handler code
4. The bridge implements before/after/origin semantics (Dexposed API)

**Why this is different:**
- Even if ART "restores" the entry point pointer (e.g., after deoptimization), the code at that address still contains Epic's jump instruction
- More resilient to entry point restoration than pure pointer replacement
- BUT: more fragile to code relocation, JIT recompilation, and nterp (which has no compiled code to patch)

**Trampoline implementation (from Trampoline.java):**
- Constructs architecture-specific shellcode at runtime
- Saves original instructions, replaces with jump to bridge
- Bridge dispatches to user's before/after hooks via reflection

**Root requirement:** **None** — works in-process

**API range:** Android 5.0 – 11 (thumb-2 / arm64). **NOT maintained for Android 12+.**

**Known limitations:**
- No support for nterp (Android 12+) — nterp-interpreted methods don't have compiled code to patch
- JIT recompilation can generate new code at a different address, leaving the old patched code orphaned
- Code page memory protection (`mprotect`) required — increasingly restricted by SELinux
- Not actively maintained — last significant updates around 2020-2021

**Historical significance:**
- Epic was a continuation of **Dexposed** (originally by Alibaba/Taobao)
- Dexposed worked on Dalvik; Epic ported the concept to ART
- Weishu's blog posts are the best technical reference for understanding callee-side rewriting

Sources:
- https://github.com/tiann/epic
- https://weishu.me/2017/03/20/dive-into-art-hello-world/
- https://weishu.me/2017/11/23/dexposed-on-art/

---

### 3.6 YAHFA (Yet Another Hook Framework for ART)

**Repository:** https://github.com/PAGalaxyLab/YAHFA

**Mechanism:**
- Replaces the **entire ArtMethod body** of the target with that of the hook method
- Saves the original ArtMethod data in a backup method
- Simpler than trampoline-based approaches — effectively a method swap

**Used by:** VirtualHook (for rootless hooking in virtual environments)

**Limitations:**
- Backup method has same GC issues as old Xposed (not a real ART-managed method)
- Less flexible than trampoline approaches (can't easily chain multiple hooks)
- Not actively maintained for modern Android versions

---

### 3.7 SandHook

**Repository:** https://github.com/asLody/SandHook

**Mechanism — multi-mode:**
- **ART method hook:** Entry point replacement (similar to Pine/LSPlant)
- **Native inline hook:** Patches native function instructions
- **Single instruction hook:** Patches a single instruction at a target address
- Provides **Xposed API compatibility** layer

**Deoptimization support:**
- `SandHook.disableVMInline()` — Disables JIT inlining globally (Android 7.0+)
- `SandHook.deCompile(method)` — Deoptimizes a specific method to prevent inlined callee bypass

**Root requirement:** **None** — works in-process

**API range:** Android 4.4 – 11.0 (32/64 bit). **Not maintained for 12+.**

Source: https://github.com/asLody/SandHook

---

### 3.8 ARTDroid (Vtable Hooking)

**Paper:** "ARTDroid: A Virtual-Method Hooking Framework on Android ART Runtime" (IMPS 2016)
**Repository:** https://github.com/steelcode/art-hook-vtable-gsoc15

**Mechanism:**
- Targets the **vtable** (virtual method dispatch table) in memory
- For virtual method calls, ART looks up the target method in the class's vtable (an array of ArtMethod pointers)
- ARTDroid replaces the pointer at the target method's vtable index with a pointer to the hook method
- Can intercept calls via both JNI and Java reflection

**Technical flow:**
1. Use JNI to find the target class and method references
2. Locate the vtable pointer via relative offset from the class address in memory
3. Scan the vtable to find the entry for the target method
4. Replace the vtable pointer with a pointer to the patch method's ArtMethod

**Advantages:**
- Does not modify the ArtMethod struct itself
- Works regardless of execution mode (interpreter, JIT, AOT) because dispatch always goes through vtable
- Conceptually simple

**Limitations:**
- **Only works for virtual methods** — cannot hook static methods, private methods, or constructors
- **Requires root** for library injection (process injection via ptrace)
- Vtable layout is version-dependent
- Cannot hook interface method calls that go through IMT (different dispatch path)
- Not maintained since ~2016

Source: https://ceur-ws.org/Vol-1575/paper_10.pdf

---

### 3.9 ShadowHook (ByteDance — Native Inline Hook)

**Repository:** https://github.com/bytedance/android-inline-hook

**Mechanism:**
1. Locates the target native function by symbol name or address
2. `mprotect` the code page to make it writable
3. Saves the first N instructions of the target function
4. Replaces them with a branch instruction to the hook function
5. Creates a **trampoline** containing:
   - The saved original instructions (relocated if necessary)
   - A branch back to the rest of the original function
6. `__builtin___clear_cache` to flush the instruction cache
7. `mprotect` back to original permissions

**Thread safety:**
- Uses a sophisticated mechanism to handle the case where a thread is executing the instructions being replaced
- Employs memory barriers and careful instruction sequencing

**Production usage:** TikTok, Douyin, Toutiao, Xigua Video, Lark (hundreds of millions of devices)

**API range:** Android 4.1 – 16 (API 16 – 36), armeabi-v7a / arm64-v8a

**Relevance to Java method hooking:**
- Can hook ART internal functions in libart.so:
  - `ArtMethod::Invoke` — the C++ method that ART uses to invoke Java methods
  - `art_quick_invoke_stub` — the assembly stub that sets up the call frame
  - `ExecuteSwitchImplCpp` — the switch-based interpreter dispatch
  - `artQuickToInterpreterBridge` — native→interpreter transition
- This provides an **alternative path** to intercept Java methods without touching ArtMethod structs
- BUT: extremely version-sensitive, as these are internal C++ functions with no stability guarantees

Source: https://github.com/bytedance/android-inline-hook

---

### 3.10 ByteHook (ByteDance — PLT/GOT Hook)

**Repository:** https://github.com/bytedance/bhook

**Mechanism:**
- Modifies the **Global Offset Table (GOT)** entries in loaded ELF shared libraries
- When a shared library calls a function in another library, it goes through the PLT (Procedure Linkage Table) which looks up the target address in the GOT
- ByteHook replaces the GOT entry with the hook function's address

**Key properties:**
- Only intercepts calls that go through PLT (cross-library calls)
- Does NOT intercept direct calls within the same library
- No instruction patching required — just a pointer replacement in the GOT
- Simpler and safer than inline hooking

**API range:** Android 4.1 – 15, all architectures (armeabi-v7a, arm64-v8a, x86, x86_64)

**Production usage:** Same ByteDance apps as ShadowHook

**Relevance to Java method hooking:**
- Can intercept calls FROM libart.so TO other libraries (libc, libdl, etc.)
- Cannot directly intercept Java method dispatch (which is internal to libart.so)
- Useful for hooking supporting infrastructure (file I/O, network, memory allocation)

Source: https://github.com/bytedance/bhook

---

### 3.11 Dobby (Cross-Platform Inline Hook)

**Repository:** https://github.com/jmpews/Dobby

**Mechanism:**
- Cross-platform inline hook engine (iOS, Android, Linux, macOS)
- ARM/ARM64 trampoline generation with code cave allocation
- Allocates trampoline memory **near the target function** (within branch reach)
- Uses `mprotect` for code page modification

**Relevance to Android:**
- Widely used in iOS jailbreak tooling
- On Android, competes with ShadowHook for native inline hooking
- Can hook libart.so functions for indirect Java method interception
- Some reports of issues on Android 10+ ARM64 (Issue #132)

---

### 3.12 JVMTI / ART TI (Class Retransformation)

**Documentation:** https://source.android.com/docs/core/runtime/art-ti

**Mechanism:**

JVMTI (Java Virtual Machine Tool Interface) is the official debugging/profiling interface. Android's implementation (ART TI) supports:

1. **ClassFileLoadHook** — intercepts class loading, allows bytecode transformation before the class is defined
2. **RetransformClasses** — retransforms an already-loaded class by rewriting its DEX bytecode
3. **RedefineClasses** — replaces a class definition entirely (more restrictive)

**How it's used for hooking:**
1. Load a JVMTI agent into the process (a shared library)
2. Register for `ClassFileLoadHook` events
3. When a target class loads, modify its DEX bytecode to insert hook dispatch logic
4. For already-loaded classes: call `RetransformClasses` to re-trigger the hook

**Agent attachment methods:**
- `Debug.attachJvmtiAgent(String library, String options, ClassLoader classLoader)` — public Java API
- `adb shell cmd activity attach-agent [process] /path/to/agent.so` — via ADB
- Direct native call to `Runtime::AttachAgent` via dlsym (bypasses Java-layer checks?)

**THE CRITICAL LIMITATION:**

> An agent may only be attached to a **debuggable** app (android:debuggable=true in manifest).
> For non-debuggable apps, `Debug.attachJvmtiAgent()` throws `SecurityException`.
> ADB attach-agent also checks debuggability.

This means **JVMTI cannot be used in production apps** without either:
- The app explicitly setting debuggable=true (unacceptable for release builds)
- Finding a bypass for the debuggability check (see Novel Directions section)

**If the check could be bypassed:**
- JVMTI would be the **gold standard** for method interception
- Bytecode rewriting is fully ART-native — survives JIT, GC, deoptimization, OEM modifications
- Forward-compatible by design (JVMTI is a stable API)
- No struct offset dependencies, no trampoline code, no `mprotect`

**API range:** Android 8.0+ (API 26+)

**Projects using JVMTI:**
- Stoic (https://github.com/block/stoic) — for debuggable apps
- JTIK (https://github.com/chancerly/jtik) — method hooking via JVMTI
- Android Studio's Apply Changes feature — uses JVMTI for hot-reload

Sources:
- https://source.android.com/docs/core/runtime/art-ti
- https://docs.oracle.com/javase/8/docs/platform/jvmti/jvmti.html

---

### 3.13 ARTist (Compiler-Based Instrumentation)

**Paper:** "ARTist: The Android Runtime Instrumentation and Security Toolkit" (EuroS&P 2017)
**Repository:** https://github.com/Project-ARTist/art
**Black Hat talk:** https://i.blackhat.com/us-18/Thu-August-9/us-18-Schranz-ARTist-A-Novel-Instrumentation-Framework-for-Reversing-and-Analyzing-Android-Apps-and-the-Middleware-wp.pdf

**Mechanism:**
- Instruments code at the **dex2oat compiler level**
- Modifies the compiler's intermediate representation (IR) during AOT compilation
- Inserts instrumentation callbacks directly into the compiled native code
- Operates entirely at the application layer — no OS modification required

**Advantages:**
- No runtime struct patching
- No trampolines
- No `mprotect` needed
- Instrumentation is "baked into" the compiled code

**Limitations:**
- Requires re-running dex2oat on the target code
- Doesn't work with JIT-only compilation (needs AOT)
- Complex to maintain as dex2oat internals change
- Not maintained for modern Android versions

---

### 3.14 ReVanced Patcher (Build-Time Bytecode Patching)

**Repository:** https://github.com/ReVanced/revanced-patcher

**Mechanism:**
- **Not a runtime hooking framework** — operates at build/patch time
- Uses **Smali** (human-readable Dalvik bytecode) and **Androlib** (Apktool) for APK manipulation
- Patches are written in Kotlin but operate on smali-level bytecode
- Disassembles DEX → finds target methods → modifies smali instructions → reassembles DEX

**Patch types:**
- `BytecodePatch` — modifies Dalvik VM bytecode
- `ResourcePatch` — modifies APK resources
- Arbitrary file patches

**Relevance:**
- Demonstrates that bytecode-level patching is a viable approach for method interception
- ReVanced's architecture (find method → rewrite instructions → reassemble) could inspire a runtime equivalent
- The challenge: ReVanced works offline on APK files; doing this at runtime requires DEX generation/loading

Source: https://github.com/ReVanced/revanced-patcher

---

### 3.15 Dexmaker / ByteBuddy Android (Runtime Proxy Generation)

**Dexmaker:** https://github.com/linkedin/dexmaker
**ByteBuddy Android:** https://github.com/raphw/byte-buddy (byte-buddy-android module)

**Dexmaker ProxyBuilder:**
- Generates DEX bytecode at runtime to create proxy classes
- Can proxy **concrete classes** (not just interfaces) by generating subclasses
- The generated subclass overrides all methods, delegating to an InvocationHandler
- Uses Android's DexClassLoader to load the generated DEX

**Limitations for hooking:**
- Generates a **subclass** — the proxy is a new class, not a replacement for the original
- Only intercepts calls made through the proxy instance, not calls to the original object
- Cannot intercept static methods or final classes/methods
- Cannot replace methods in already-loaded classes
- Primarily designed for mocking in tests (used by Mockito on Android)

**ByteBuddy Android:**
- Full bytecode manipulation library ported to Android
- Can generate new classes at runtime using Android's DexClassLoader
- `AndroidClassLoadingStrategy` handles DEX compilation requirements
- More powerful than Dexmaker but similar fundamental limitations for hooking

---

## 4. Comparison Matrix

### 4.1 Comprehensive Feature Comparison

| Framework | Category | Root? | In-Process? | API Range | Nterp Safe? | JIT Safe? | GC Safe? | Inline Deopt? | OEM Safe? | Active? |
|-----------|----------|-------|-------------|-----------|-------------|-----------|----------|---------------|-----------|---------|
| **Frida (gadget)** | A (entry point) | No | Yes | 5.0–15+ | Yes | Partial | Yes (recent) | No | Medium | **Yes** |
| **LSPlant** | A (entry point) | No | Yes | 5.0–15 | Yes | Yes | **Yes** | **Yes** | Medium | **Yes** |
| **Pine** | A (entry point) | No | Yes | 4.4–15 | Yes | Yes | Partial | No | Medium | **Yes** |
| **Epic** | B (inline code) | No | Yes | 5.0–11 | **No** | No | Partial | No | Low | No |
| **YAHFA** | A (body swap) | No | Yes | 5.0–? | Unknown | Partial | No | No | Low | No |
| **SandHook** | A+E (dual) | No | Yes | 4.4–11 | No | Partial | Partial | Yes | Low | No |
| **Xposed/LSPosed** | A (entry point) | **Yes** | No (Zygote) | 5.0–13 | No | No | No | No | Medium | Partial |
| **ARTDroid** | C (vtable) | **Yes** | No | 5.0–7 | N/A | N/A | N/A | N/A | Low | No |
| **ShadowHook** | E (native inline) | No | Yes | 4.1–16 | N/A | N/A | N/A | N/A | **High** | **Yes** |
| **ByteHook** | D (PLT/GOT) | No | Yes | 4.1–15 | N/A | N/A | N/A | N/A | **High** | **Yes** |
| **Dobby** | E (native inline) | No | Yes | 5.0–? | N/A | N/A | N/A | N/A | Medium | Partial |
| **JVMTI** | F (retransform) | No* | Yes | 8.0+ | **Yes** | **Yes** | **Yes** | N/A | **High** | **Yes** |
| **ARTist** | H (compiler) | No | Build-time | 5.0–? | N/A | N/A | N/A | N/A | High | No |

*JVMTI: No root needed, but requires debuggable app

### 4.2 Threat Model Comparison (Detection Resistance)

| Framework | Memory Footprint | Detectable By | Stealth Level |
|-----------|-----------------|---------------|---------------|
| Frida | ~10-20MB (JS engine) | Maps scan, string scan, D-Bus, native flag check | Low |
| LSPlant | ~100KB | Entry point range check, ArtMethod comparison | High |
| Pine | ~100KB | Entry point range check, ArtMethod comparison | High |
| Epic | ~50KB | Code integrity check, instruction scan | Medium |
| JVMTI | Minimal | Agent presence check, debuggable flag | Medium-High |
| ShadowHook | ~50KB | Code integrity check, instruction scan | Medium |

---

## 5. Google's Hardening Efforts & Future Threats

### 5.1 Hidden API Restrictions (Android 9+)

Android 9 introduced the **hidden API blacklist** — blocking access to internal framework APIs via reflection.

**Evolution:**
- Android 9-10: Bypassable via **meta-reflection** (double reflection) — using reflection to obtain the reflection API itself, so the caller appears to be the system
- Android 10: `VMRuntime.setHiddenApiExemptions()` could whitelist APIs
- Android 11: Google hardened the check — meta-reflection bypass no longer works; `setHiddenApiExemptions` was added to the force-blacklist
- Android 12+: Further restrictions on `Unsafe` class methods
- **Current state:** Pine and LSPlant both include hidden API bypass mechanisms; these need updating each version

**Impact on hooking:** Many hooking operations require accessing internal ART APIs. The hidden API restriction adds friction but is generally bypassable by frameworks like LSPlant that include dedicated bypass code.

Source: https://www.xda-developers.com/bypass-hidden-apis/

### 5.2 W^X (Write XOR Execute) Enforcement

**The principle:** A memory page should NEVER be both writable AND executable simultaneously.

**Android's implementation:**
- SELinux policies increasingly enforce W^X
- `mprotect` calls to add PROT_EXEC to previously writable pages may be denied
- Execute-only memory (XOM) is default for 64-bit binaries — code pages are not readable, only executable

**Impact on hooking:**
- Inline code patching (Epic, ShadowHook, Dobby) requires temporarily making code pages writable
- On devices with strict W^X enforcement, `mprotect(addr, size, PROT_READ|PROT_WRITE|PROT_EXEC)` may fail
- Entry point replacement (LSPlant, Pine) has an advantage here — they modify a data field (pointer), not code
- But trampoline allocation still requires executable memory

**Current state (2024-2025):** W^X is enforced for untrusted app domains in SELinux but ART's own JIT allocates executable memory, so apps running ART inherently have the capability. The question is whether third-party code can do the same.

Source: https://source.android.com/docs/security/test/execute-only-memory

### 5.3 MTE (Memory Tagging Extension)

**What it is:** ARM hardware feature (ARMv8.5+) that associates 4-bit tags with memory allocations. Every pointer carries a tag in its top bits; accessing memory with a mismatched tag causes a fault.

**Current deployment:**
- Hardware: Pixel 8/8 Pro (2023), select ARM v9 SoCs
- Software: Android 13+ supports MTE; Android 14 QPR3 adds stack tagging
- Adoption: **Not enabled by default** on any device as of 2025. Requires opt-in via developer options or Advanced Protection Mode (Android 16)

**Impact on hooking:**
- **Heap allocations:** Trampolines allocated on the heap will have MTE tags. Pointers must carry the correct tag.
- **Stack tagging:** Stack-allocated buffers used in hook dispatch could be tagged
- **Practical impact (2025):** Minimal — MTE is not enabled by default on any production device. But this WILL change.
- **Future risk:** When MTE is widely deployed, any hooking framework that does raw pointer arithmetic or memory allocation without respecting tags will crash

Source: https://developer.android.com/ndk/guides/arm-mte

### 5.4 PAC (Pointer Authentication Codes) and BTI (Branch Target Identification)

**PAC:** Cryptographically signs pointers (especially return addresses) using a per-process key. Modifying a signed pointer without re-signing it causes a fault on use.

**BTI:** Restricts where indirect branches can land. Only instructions marked with BTI landing pads are valid branch targets.

**Current deployment:**
- Hardware: All ARMv8.3+ processors support PAC (most modern Android SoCs)
- Software: Android's bionic libc and linker are compiled with PAC/BTI since ~2024
- ART itself: Compiled with `-mbranch-protection=standard` on supporting hardware

**Impact on hooking:**
- **Inline hooks:** A trampoline that branches to an address without a BTI landing pad will fault
- **Return address manipulation:** PAC-signed return addresses can't be tampered with
- **Practical impact (2025):** PAC is increasingly used in system libraries. Trampolines must include BTI landing pads. Most current hooking frameworks have NOT been updated for PAC/BTI.
- **Future risk:** HIGH. As PAC/BTI enforcement spreads, inline hooking (ShadowHook, Epic, Dobby) will need significant rework.

### 5.5 Play Integrity / SafetyNet

- Google's Play Integrity API (replacing SafetyNet) checks device integrity
- As of May 2025: requires hardware-backed security signals for stronger verdicts
- Can detect rooted devices, modified system partitions, and some hooking frameworks
- NOT directly relevant to in-process AAR hooking (Play Integrity checks device state, not specific app instrumentation)
- But apps using Play Integrity may refuse to run if they detect instrumentation

---

## 6. Gap Analysis

### 6.1 Universal Assumptions That Break

**Every existing ArtMethod-based hooking approach shares these assumptions:**

1. **ArtMethod struct layout is known and stable**
   - Reality: Changes across Android versions AND across OEMs
   - Samsung, Huawei, OPPO, Xiaomi, vivo all ship modified ART builds
   - Requires maintaining per-version, per-OEM offset tables
   - A single unknown offset change → silent corruption or crash

2. **`entry_point_from_quick_compiled_code_` is the universal dispatch point**
   - Reality: Nterp's assembly interpreter may have fast paths that bypass this field for some call patterns (e.g., when both caller and callee are in nterp's execution context)
   - The JIT's "Sharpening" optimization can hardcode entry addresses in machine code, bypassing the entry point field entirely

3. **Executable memory can be allocated or code pages can be made writable**
   - Reality: W^X enforcement, SELinux policies, MTE, and PAC all increasingly restrict this
   - ART's own JIT has this capability, but third-party code may not

4. **Methods exist in isolation (can be hooked independently)**
   - Reality: JIT/AOT inlining can merge methods. Hooking the callee's ArtMethod has no effect if it's been inlined into the caller's compiled code.
   - Only LSPlant and SandHook address this with explicit deoptimization APIs

5. **The hooking framework initializes before the target code runs**
   - Reality: For an AAR SDK, initialization order is not guaranteed. System classes and many framework classes load before any app code.
   - Classes loaded before the hook framework cannot be intercepted at load time.

### 6.2 Where Each Approach Falls Short

**Frida (gadget mode):**
- Enormous memory footprint (~20MB) for a JS engine — unacceptable for a production SDK
- Highly detectable (strings, artifacts, D-Bus protocol)
- Designed for reverse engineering, not production instrumentation

**LSPlant:**
- Best existing approach but still requires per-version offset tables
- OEM fragmentation is the biggest risk
- Trampoline allocation requires executable memory

**Pine:**
- Similar to LSPlant but less mature GC handling
- Backup ArtMethod GC tracking is manual, not automatic

**Epic:**
- Dead for Android 12+ (no nterp support)
- Inline code patching increasingly restricted

**JVMTI:**
- Debuggable-only restriction makes it unusable in production
- If this could be bypassed, it would be the best approach by far

**All entry-point approaches:**
- Method inlining is a fundamental blind spot
- Require native code (JNI) — adds complexity, security review burden for AAR consumers

### 6.3 The OEM Fragmentation Problem

The #1 practical challenge for any ART hooking framework shipping as an AAR:

| OEM | Known ART Modifications |
|-----|------------------------|
| Samsung | Modified ArtMethod layout in some One UI versions; custom GC tuning |
| Huawei/Honor | HarmonyOS-based ART fork; different memory management; crash reports on specific devices |
| Xiaomi/MIUI | Custom optimization passes; different hidden API enforcement |
| OPPO/ColorOS | Modified class loading; custom ART optimizations |
| vivo/FuntouchOS | Some reports of different ArtMethod offsets |

No hooking framework has a complete matrix of OEM-specific offsets. Most rely on **runtime probing** — determining the ArtMethod size and field offsets by comparing known methods at runtime.

---

## 7. Novel & Underexplored Directions

### 7.1 JVMTI Debuggability Bypass (HIGH POTENTIAL)

**The opportunity:**

`Debug.attachJvmtiAgent()` checks `android:debuggable` in the Java layer. But ART's native `Runtime::AttachAgent()` is the actual implementation. Questions:

1. Can `Runtime::AttachAgent()` be called directly via JNI + dlsym, bypassing the Java-layer debuggability check?
2. Does the native layer have its own debuggability check? If so, where in the code?
3. Could the app's own code load a JVMTI agent at startup (before the check matters) via `System.loadLibrary` with an `Agent_OnAttach` or `Agent_OnLoad` entry point?
4. Could a native hook on the debuggability check function flip the return value?

**If achievable:** JVMTI `RetransformClasses` is the gold standard:
- Rewrites DEX bytecode natively within ART
- No struct offsets, no trampolines, no `mprotect`
- Survives JIT, GC, deoptimization, OEM modifications
- Forward-compatible by design (JVMTI is a defined interface)
- Could intercept ANY method including system framework methods

**Research needed:** Deep analysis of AOSP `runtime/openjdkjvmti/` source code to understand all checks in the agent attachment path.

### 7.2 Hybrid ClassLoader Interception + Bytecode Rewriting (HIGH POTENTIAL)

**The concept:**

Instead of patching ArtMethod structs in memory, intercept the class loading process and rewrite bytecode before classes are defined:

1. **Early initialization:** In the AAR's ContentProvider or Application.onCreate, replace the app's PathClassLoader with a custom ClassLoader that wraps it
2. **Interception:** Override `loadClass()` / `findClass()` in the custom ClassLoader
3. **Bytecode rewriting:** When a target class is loaded, use a DEX manipulation library (dexlib2, baksmali/smali, or a lighter-weight bytecode rewriter) to insert hook dispatch logic into the method body:
   ```
   // Original: void targetMethod(args...) { original code }
   // Rewritten: void targetMethod(args...) {
   //     if (HookRegistry.hasHook("targetMethod")) {
   //         return HookRegistry.dispatch("targetMethod", this, args);
   //     }
   //     original code
   // }
   ```
4. **Registration:** At runtime, register/unregister hooks in the HookRegistry — pure Java, no native code
5. **Fallback:** For classes already loaded before initialization, fall back to LSPlant-style entry point replacement

**Advantages:**
- Hooked methods are real DEX bytecode — fully native to ART
- No struct offset dependencies for hooked methods
- Works with any execution mode (nterp, JIT, AOT compile the rewritten bytecode normally)
- GC-safe, JIT-safe, deoptimization-safe (it IS the real method)
- Forward-compatible (ClassLoader delegation is a stable Java API since Java 1.0)
- No `mprotect` needed for the hook itself (only for the fallback path)
- Hook dispatch is pure Java — no native code needed for the main path

**Challenges:**
- Must intercept classloading **early enough** (before target classes load)
- DEX bytecode rewriting at runtime requires a fast, lightweight DEX manipulation library
- Performance cost of rewriting at load time (can be mitigated with lazy/on-demand rewriting)
- Cannot hook methods in classes loaded before the SDK initializes
- System framework classes are loaded by the boot ClassLoader — unreachable
- The custom ClassLoader must perfectly preserve the parent delegation model

**Implementation path:**
- Use `InMemoryDexClassLoader` (Android 8.0+) or write to temp file for older versions
- Keep the bytecode rewriter minimal — only need to insert a dispatch check, not full method parsing
- Pre-compute which classes need hooking to avoid rewriting every class

### 7.3 Forced Deoptimization + Interpreter-Level Hooking (MEDIUM POTENTIAL)

**The concept:**

1. Force all target methods into interpreter mode (never JIT/AOT compiled)
2. Hook ART's interpreter dispatch functions (native-level) to intercept execution when target methods run

**Technical approach:**
1. Use LSPlant's deoptimization API (or direct ART internal calls) to deoptimize target methods
2. Use ShadowHook to hook `ExecuteSwitchImplCpp` or `ExecuteNterpImpl` in libart.so
3. In the hook, check if the method being interpreted is one of the targets
4. If yes, redirect to hook handler; if no, pass through to original interpreter

**Advantages:**
- No ArtMethod struct modification needed
- Works regardless of the method's compilation state (because we force it to interpreter)
- Interpreter dispatch functions are relatively stable across Android versions

**Challenges:**
- Hooking interpreter dispatch is a **hot path** — performance impact could be severe
- Every interpreted method invocation goes through the hook's check
- `ExecuteNterpImpl` is assembly code — harder to hook cleanly
- Still requires native inline hooking (ShadowHook) with its W^X/PAC concerns

### 7.4 MethodHandle-Based Interception (EXPLORATORY)

**The concept:**

`java.lang.invoke.MethodHandle` (Android 8.0+) provides a low-level method dispatch mechanism deeply integrated into ART. Could it provide a hooking surface?

**Ideas:**
- Use `MethodHandles.Lookup.findVirtual/findStatic` to get handles to target methods
- Use `MethodHandles.filterArguments`, `foldArguments`, `guardWithTest` to create modified dispatch chains that include hook logic
- Combine with field access (via `VarHandle` or `Unsafe`) to replace dispatch sites

**ART implementation detail:** `invoke-polymorphic` bytecode instruction backs MethodHandle invocation and has special ART handling separate from normal method dispatch.

**Current assessment:** Largely unexplored for hooking purposes. MethodHandle is primarily used for lambda implementation and method reference dispatch in ART. The API is powerful enough to build interceptors, but the challenge is *installing* them at call sites (you need the caller to use your MethodHandle, not the original method).

**Potential approach:** If combined with ClassLoader bytecode rewriting (7.2), call sites could be rewritten to use MethodHandle dispatch instead of direct invoke — giving you a clean interception point.

### 7.5 Annotation-Driven Compile-Time Weaving (MEDIUM POTENTIAL)

**The concept:**

Use a **Gradle transform plugin** operating on D8/R8 output to insert hook dispatch at build time:

1. App developer adds the AAR SDK dependency + Gradle plugin
2. At build time, the plugin scans bytecode for configured hook points
3. Rewrites method bodies to insert dispatch checks (similar to 7.2 but at build time)
4. At runtime, the SDK only manages a hook registry — zero native code

**Advantages:**
- Zero native dependencies
- Zero struct offset concerns
- Forward-compatible forever
- Passes all integrity checks (the code IS the app's code)
- No `mprotect`, no executable memory allocation

**Limitations:**
- Can only hook methods in code compiled by the app (app code + dependencies)
- Cannot hook system framework methods (android.*, java.*)
- Requires the app developer to apply the Gradle plugin — more integration effort than a pure AAR
- Build-time approach means hooks are static (can't add new hook points at runtime)

**Hybrid potential:** Combine with a lightweight runtime hook (LSPlant) for framework method interception → build-time for app code, runtime for system code.

### 7.6 ClassLoader.defineClass Hooking via Native (MEDIUM POTENTIAL)

**The concept:**

Instead of replacing the ClassLoader, hook the native `ClassLinker::DefineClass` function in libart.so:

1. Use ShadowHook to hook `art::ClassLinker::DefineClass`
2. When a target class is being defined, intercept the DEX data
3. Rewrite the DEX bytecode before it's processed by ART
4. Return the modified DEX data to the real DefineClass

**Advantages:**
- Intercepts ALL class loading, including boot classpath (if hooked early enough)
- Operates at the right level of abstraction (bytecode, not struct offsets)
- Combined with ClassFileLoadHook semantics but without JVMTI's debuggability restriction

**Challenges:**
- `DefineClass` is a C++ method with version-dependent signature
- Requires ShadowHook (native inline hooking) — subject to W^X/PAC concerns
- DEX bytecode rewriting in a native hook context adds complexity
- Thread safety during class loading is critical

### 7.7 Java Debug Interface (JDI) Self-Attach (LOW POTENTIAL)

**The concept:** Can an app attach a JDWP (Java Debug Wire Protocol) debugger to itself?

**Assessment:** JDWP also requires debuggable app. Same limitation as JVMTI. Not viable.

### 7.8 Service Binder Proxy (NICHE)

**The concept:** For intercepting calls to Android system services (ActivityManager, PackageManager, etc.), replace the Binder proxy object:

1. Get the IBinder for the target service
2. Replace it with a custom Proxy implementation
3. Intercept all IPC calls to that service

**Assessment:** Only works for Binder-based service calls. Not a general method interception technique. But useful for the specific case of monitoring framework service interactions.

---

## 8. Recommendation

### Given constraints: In-process, no root, AAR delivery, maximum robustness

### Tier 1 (Highest Potential): Hybrid Architecture

**Primary path: ClassLoader Interception + Bytecode Rewriting (7.2)**

For methods in classes that load after SDK initialization:
- Replace PathClassLoader with a wrapping ClassLoader
- Rewrite DEX bytecode at load time to insert hook dispatch
- Hook dispatch is pure Java — no native code, no struct offsets
- Automatically compatible with nterp, JIT, AOT, GC, deoptimization
- Forward-compatible — relies only on Java ClassLoader API

**Fallback path: LSPlant**

For methods in classes already loaded before SDK init, and for system framework methods:
- LSPlant is the most robust existing entry-point-based hooker
- Generates real ArtMethods via DexBuilder (GC-safe)
- Provides inline deoptimization to handle method inlining
- Android 5.0 – 15, all architectures

**Deoptimization layer:**

Proactively deoptimize known callers of hooked methods to prevent JIT inlining from bypassing hooks.

### Tier 2: Pure LSPlant with Enhanced Resilience

If ClassLoader interception proves too complex or too fragile:
- Use LSPlant as the sole hooking engine
- Add native hooking (ShadowHook) of JIT compilation functions to detect and respond to recompilation
- Implement runtime ArtMethod offset probing for OEM compatibility
- Maintain comprehensive test matrix across OEMs and Android versions

### Tier 3: JVMTI Exploitation

**Worth a dedicated research spike:**
- Investigate native-level JVMTI agent attachment bypassing debuggability check
- If achievable, this becomes the cleanest, most forward-compatible solution
- Risk: Google may strengthen the check in future versions

### What NOT to pursue:

| Approach | Why Not |
|----------|---------|
| Vtable hooking (ARTDroid) | Virtual methods only; requires root |
| Inline code patching (Epic) | Broken by nterp (Android 12+), W^X, PAC |
| PLT/GOT hooking alone | Doesn't intercept Java methods |
| Frida gadget as production SDK | 20MB footprint, highly detectable |
| Any root-required approach | Violates constraints |
| Pure Proxy/InvocationHandler | Interfaces only |

---

## 9. Academic & Research Sources

### Conference Papers

1. **ARTist: The Android Runtime Instrumentation and Security Toolkit**
   - Michael Backes, Sven Bugiel, Oliver Schranz, Philipp von Styp-Rekowsky, Sebastian Weisgerber
   - IEEE European Symposium on Security and Privacy (EuroS&P), 2017
   - https://ieeexplore.ieee.org/document/7961998/

2. **ARTDroid: A Virtual-Method Hooking Framework on Android ART Runtime**
   - Valerio Costamagna, Cong Zheng
   - Innovations in Mobile Privacy and Security (IMPS), 2016
   - https://ceur-ws.org/Vol-1575/paper_10.pdf

3. **TIRO: Tackling Runtime-based Obfuscation in Android**
   - Michelle Y. Wong, David Lie
   - 27th USENIX Security Symposium, 2018
   - https://www.usenix.org/conference/usenixsecurity18/presentation/wong

4. **An Android Inline Hooking Framework for the Securing Transmitted Data**
   - Sensors (MDPI), 2020
   - https://pmc.ncbi.nlm.nih.gov/articles/PMC7435958/

5. **Demystifying Android Non-SDK APIs: Measurement and Understanding**
   - ICSE 2022
   - https://diaowenrui.github.io/paper/icse22-yang.pdf

6. **Mobile Code Anti-Reversing Scheme Based on Bytecode Trapping in ART**
   - PMC, 2019
   - https://pmc.ncbi.nlm.nih.gov/articles/PMC6603642/

7. **Dynamic Analysis of the Android Application Framework (DYNAMO)**
   - NDSS 2021
   - https://cs.uwaterloo.ca/~yaafer/teaching/papers/ndss2021_ADaoud.pdf

### Technical Blog Posts

8. **Understanding the Xposed Framework for ART Runtime** — Mingshen Sun
   - https://mssun.me/blog/understanding-xposed-framework-art-runtime.html

9. **ART深度探索开篇：从Method Hook谈起** — Weishu
   - https://weishu.me/2017/03/20/dive-into-art-hello-world/

10. **我为Dexposed续一秒——论ART上运行时Method AOP实现** — Weishu
    - https://weishu.me/2017/11/23/dexposed-on-art/

11. **ART上的动态Java方法hook框架** — Canyie
    - https://blog.canyie.top/2020/04/27/dynamic-hooking-framework-on-art/

12. **Method Hooking Detection & Runtime Code Integrity Checks** — Shahid Raza (2025)
    - https://www.shahidraza.me/2025/08/09/method_hooking_root_android.html

13. **Fantastic Hooks and Where to Find Them (Xposed Edition)**
    - https://binderfilter.github.io/xposed/

14. **Bypassing the RASP Protections** — Joseph James (2025)
    - https://proandroiddev.com/bypassing-rasp-and-white-box-protections-24e677ad17ef

### Industry Reports

15. **App Threat Report: The State of Hooking Framework Detection** — Promon (2024)
    - https://promon.io/resources/downloads/app-threat-report-hooking-framework-frida-2024
    - Key finding: Only 2% of top Android apps detect Frida

---

## 10. All References

### Framework Repositories

| Framework | URL | Status |
|-----------|-----|--------|
| Frida | https://github.com/frida/frida | Active |
| frida-java-bridge | https://github.com/frida/frida-java-bridge | Active |
| LSPlant | https://github.com/LSPosed/LSPlant | Active |
| LSPosed | https://github.com/LSPosed/LSPosed | Active |
| Pine | https://github.com/canyie/pine | Active |
| Epic | https://github.com/tiann/epic | Archived |
| YAHFA | https://github.com/PAGalaxyLab/YAHFA | Inactive |
| SandHook | https://github.com/asLody/SandHook | Inactive |
| ARTDroid | https://github.com/steelcode/art-hook-vtable-gsoc15 | Archived |
| ShadowHook | https://github.com/bytedance/android-inline-hook | Active |
| ByteHook | https://github.com/bytedance/bhook | Active |
| Dobby | https://github.com/jmpews/Dobby | Partial |
| Dexmaker | https://github.com/linkedin/dexmaker | Active |
| ByteBuddy | https://github.com/raphw/byte-buddy | Active |
| ReVanced Patcher | https://github.com/ReVanced/revanced-patcher | Active |
| JTIK | https://github.com/chancerly/jtik | Inactive |
| Stoic | https://github.com/block/stoic | Active |
| ARTist | https://github.com/Project-ARTist/art | Archived |
| Aliucord Hook | https://github.com/Aliucord/hook | Active |
| AndroidHiddenApiBypass | https://github.com/LSPosed/AndroidHiddenApiBypass | Active |

### AOSP Source References

| Component | URL |
|-----------|-----|
| art_method.h | https://android.googlesource.com/platform/art/+/master/runtime/art_method.h |
| instrumentation.cc | https://android.googlesource.com/platform/art/+/master/runtime/instrumentation.cc |
| nterp.cc | https://cs.android.com/android/platform/superproject/+/master:art/runtime/interpreter/mterp/nterp.cc |
| class_linker.cc | https://android.googlesource.com/platform/art/+/master/runtime/class_linker.cc |

### Google Security & Android Documentation

| Topic | URL |
|-------|-----|
| ART TI (JVMTI) | https://source.android.com/docs/core/runtime/art-ti |
| JIT Compiler | https://source.android.com/docs/core/runtime/jit-compiler |
| ART Improvements (8.0) | https://source.android.com/docs/core/runtime/improvements |
| Execute-Only Memory | https://source.android.com/docs/security/test/execute-only-memory |
| MTE Guide | https://developer.android.com/ndk/guides/arm-mte |
| SELinux | https://source.android.com/docs/security/features/selinux |
| Android Security Paper 2024 | https://services.google.com/fh/files/misc/android-security-paper-2024.pdf |
| Google Security Blog 2024 | https://security.googleblog.com/2024/05/io-2024-whats-new-in-android-security.html |
| Google Security Blog 2025 | https://security.googleblog.com/2025/05/whats-new-in-android-security-privacy-2025.html |

---

*End of research survey. No code was written. This document is intended to inform implementation decisions.*
