# NEXA Launcher

A lightweight Android home launcher designed for low-end devices.

## Current features

- Android Home launcher entry point
- Installed app discovery using PackageManager
- App grid backed by RecyclerView
- Background app icon loading with an in-memory LRU cache
- App search
- Recently opened apps
- Launch apps directly from the home screen
- Simple settings menu and app-list refresh
- Android 8.0 (API 26) and newer

## Technology

- Kotlin
- Android Views
- RecyclerView
- Android PackageManager
- Minimum SDK 26
- Compile SDK 35

## Build

Use JDK 17 or newer and Android SDK Platform 35.

```bash
gradle assembleDebug
```

The launcher is under active development. Customization, favorites management, widgets, and further performance testing are still planned.
