# Android Source Extractor

Native Android app for inspecting an APK and exporting its packaged files. It extracts assets/resources, DEX bytecode files, and native `.so` libraries, with progress reporting and ZIP export.

## Features
- Pick an APK using Android's Storage Access Framework.
- Extract packaged APK entries into app-private storage.
- Categorize `assets/`, `res/`, `resources.arsc`, `classes*.dex`, and `lib/**/*.so`.
- Progress bar and per-file status.
- Export all extracted files as a ZIP using Android's Save dialog.
- ZIP path traversal protection and safe entry-name checks.

## Requirements
- Android Studio Ladybug or newer.
- JDK 17.
- Android SDK Platform 35 (Android 15).
- Gradle 8.9 or compatible; Android Studio can generate the Gradle wrapper.

## Build
1. Open this repository in Android Studio.
2. Let Gradle sync and install Android SDK Platform 35 if prompted.
3. Build using **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

If no Gradle wrapper is present, generate one with a compatible Gradle installation:

```bash
gradle wrapper --gradle-version 8.9
gradle assembleDebug
```

## Notes
- DEX files contain bytecode, not original Kotlin/Java source.
- `AndroidManifest.xml` may be binary Android XML and is extracted as-is.
- Use APKs you own or are authorized to inspect.

## Project structure
```text
android-source-extractor/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/androextractor/MainActivity.kt
│       └── res/values/styles.xml
├── build.gradle.kts
├── gradle.properties
├── settings.gradle.kts
└── .gitignore
```
