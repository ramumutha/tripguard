# TripGuard Android development preview
Native Kotlin / Compose with a pure Kotlin domain module and Room local database. Existing Python/web source remains unchanged. This is development-only software, not qualified safety software. No internet permission. Use synthetic test data. Never place test calls to emergency numbers.

## Build prerequisites
Qualified build baseline: existing JDK 25, Gradle 9.7.0, AGP 9.3.1, Kotlin 2.4.21, KSP 2.3.12, Android SDK API 36. Android minimum API 26 and Java 17 bytecode target remain unchanged. Automatic JDK provisioning disabled. Clean build qualified at 94cfc0f (8/8 JVM tests, both APKs, lint, schema identities, zero SDK XML/KAPT warnings); verified legacy baseline ec1997cc6de467b907c13dba64c5f95174d48757 used JDK 17/Gradle 8.13.

Windows validation from repository root:
```powershell
. "D:\DevTools\Enter-DevEnvironment.ps1" -ProjectName TRIPGUARD
$env:JAVA_HOME = $env:JAVA25_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Set-Location android
& "D:\DevTools\Gradle\gradle-9.7.0\bin\gradle.bat" :domain:test :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
```
First dependency resolution requires internet. Wrapper not committed; use the verified installed Gradle. Do not commit local.properties, signing keys or build outputs. Preserve governed Room v1/v2 schema identities. Inspect lint and test evidence. Device test execution requires a dedicated test phone or emulator and synthetic data; privacy actions can remove local test records.

## Scope and status
Local vehicle profiles, guided component observations and inspection summaries, local journeys, trusted contacts, foreground location and manual dial/share, image preview and future-feature mock interfaces. No qualified OCR or AI model, persistent photo evidence, remote Guardian, telemetry or emergency dispatch. All 50 inventory IDs represented at source level, not fully qualified. See ../docs/STATUS.md for receipts and current limitations.
