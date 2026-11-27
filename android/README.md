# TripGuard Android UI preview
Native Kotlin / Compose, separate pure Kotlin domain module. Existing FastAPI/web source unchanged. This is a development-only session-memory preview, not production safety software. No internet permission. Do not use actual personal details in preview. Never place test calls to emergency numbers.
## Build prerequisites
Android Studio, JDK 17, Android SDK API 36, Gradle 8.13. Initial dependency pins require clean-build validation. Wrapper not committed yet: use installed Gradle or Android Studio to generate wrapper, then run local validation.
From repository root in PowerShell:
```powershell
Set-Location D:\Projects\tripguard-api
git status --short
git branch --show-current
# Only with clean tree and intended branch:
git pull --ff-only origin feat/society-pilot-polish
Set-Location android
gradle wrapper --gradle-version 8.13
.\gradlew.bat :domain:test :app:assembleDebug :app:lintDebug
# With emulator or test phone connected:
.\gradlew.bat :app:connectedDebugAndroidTest
```
First dependency resolution requires internet; no paid service required. Do not commit local.properties, signing keys or build outputs. APK path app/build/outputs/apk/debug/app-debug.apk.
## Current scope
Home, garage list, add/edit/delete vehicle, vehicle-type/propulsion checklist, unknown/potential/critical observations, history, destination-only journey, emergency dialer confirmation, settings. Session-only data survives activity recreation via ViewModel but not process death. No GPS, camera, real device, Room persistence, remote alerts, account, production signing or dispatch. All builds and tests pending execution. Five domain tests and two instrumentation tests authored.
