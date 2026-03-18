# Contributing to Sandwitcher

Thanks for considering contributing. This page covers how to get involved.

## Getting started

1. Fork the repo
2. Clone your fork
3. Open the project in Android Studio or build from the command line with `./gradlew :app:assembleDebug`
4. Make your changes
5. Test on a real device (the demo app is in the `app/` module)
6. Open a pull request against `main`

You'll need JDK 17 and Android SDK 35 installed.

## Reporting bugs

Open an issue with:

- What you expected to happen
- What actually happened
- Android version and device model
- A minimal code snippet that reproduces the issue

Stack traces and logcat output are helpful.

## Pull requests

Keep PRs focused. One bug fix or one feature per PR. If you're doing a larger refactor, open an issue first so we can talk about the approach before you put in the work.

Before submitting, make sure:

- `./gradlew :sandwitcher:assembleRelease` builds without errors
- `./gradlew :app:assembleDebug` builds without errors
- The demo app runs and hooks still work on a real device

Write a clear description of what the PR does and why. If it fixes an issue, reference it.

## Code style

Follow the existing patterns in the codebase. Kotlin, no wildcard imports, keep things simple. Don't add dependencies unless there's a good reason.

No need to over-document. If the code is clear, let it speak for itself. Add a comment when something is non-obvious, skip it when it's not.

## What to work on

Check the open issues for things that need help. If you want to work on something that doesn't have an issue yet, open one first to make sure it's something we want to add.

Some areas where contributions are useful:

- Testing on different devices and Android versions
- Constructor hooking support in the public API
- Better error messages when hooks fail
- Performance benchmarks

## License

By contributing, you agree that your contributions will be licensed under the Apache 2.0 license, same as the rest of the project.
