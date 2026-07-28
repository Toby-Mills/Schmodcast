# Schmodcast

A native Android podcast player.

## Stack

- Kotlin 2.4.10, Jetpack Compose (Material 3)
- Android Gradle Plugin 9.1.1 (built-in Kotlin support — no separate `kotlin-android` plugin)
- Gradle 9.3.1
- `minSdk` 26, `compileSdk`/`targetSdk` 37

## Setup

1. Open the project root in Android Studio, or build from the command line:
   ```
   ./gradlew assembleDebug
   ```
2. `local.properties` (not committed) must point `sdk.dir` at your Android SDK location.

## Notes

- AGP 9.3.1 currently fails to apply to any module under Gradle 9.3.1
  (`NoClassDefFoundError: org/gradle/features/binding/ProjectTypeBinding`). The project is
  pinned to AGP 9.1.1 until that's fixed upstream — bump `agp` in
  `gradle/libs.versions.toml` once a patch release resolves it.
- No networking, persistence, or playback dependencies are wired up yet — this is just
  the buildable skeleton (single screen, theme, launcher icon).

## Next up

Feed discovery/subscriptions, episode list + persistence (Room), and audio playback
(Media3/ExoPlayer) are still to be designed and built.
