# NEXA Launcher

A lightweight Android home launcher designed for speed and everyday usability, including lower-end devices.

## Current features

- Android Home launcher entry point
- Live clock and date dashboard
- Installed app discovery through PackageManager
- Responsive RecyclerView app grid
- Background icon loading with an in-memory LRU cache
- App search by app name or package name
- Favorites with persistent storage
- Recently opened apps
- Long-press app actions for favorites, app info, and uninstall
- Refresh installed apps
- Shortcut to Android's default Home app settings
- Android 8.0 (API 26) and newer

## Technology

- Kotlin
- Native Android Views
- RecyclerView and DiffUtil
- Android PackageManager
- Background app and icon loading

## Build

Use JDK 17 or newer and Android SDK Platform 35.

```bash
gradle assembleDebug
```

NEXA is actively being developed. Wallpaper controls, icon packs, gestures, widgets, and deeper performance tuning are not implemented yet.
