# NEXA Launcher

A lightweight Android home launcher designed for low-end devices.

## Goals

- Fast startup and low memory use
- Native Android UI with recycled lists
- Background app discovery and icon loading
- Smooth scrolling and restrained animations
- Home-screen role support
- Android 8.0 (API 26) and newer

## Technology

- Kotlin
- Android Views
- RecyclerView
- Android PackageManager
- Minimum SDK 26
- Compile SDK 35

## Project structure

- `app/` Android application
- `app/src/main/java/com/nexa/launcher/` application source
- `app/src/main/res/` Android resources

## Build

Use JDK 17 or newer and Android SDK Platform 35.

```bash
gradle assembleDebug
```

The first commit establishes the native Android project and Home launcher entry point. App discovery, recycled app lists, icon caching, customization, and performance testing will be added in separate steps.
