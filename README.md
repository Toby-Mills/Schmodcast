# Schmodcast

A native Android podcast player. Subscribe to shows via the iTunes Search API; every
subscription's recent episodes (last 60 days) merge into one auto-sorted Queue with no
manual reordering — background playback via Media3/ExoPlayer.

## Stack

- Kotlin 2.4.10, Jetpack Compose (Material 3), Navigation Compose
- Room (subscriptions/episodes), Retrofit + kotlinx-serialization (iTunes search), a small
  hand-rolled RSS parser (no XML library dependency), Coil (images)
- Media3 (ExoPlayer + MediaSessionService) for background/notification-controlled playback
- Android Gradle Plugin 9.1.1 (built-in Kotlin support — no separate `kotlin-android` plugin)
- Gradle 9.3.1
- `minSdk` 26, `compileSdk`/`targetSdk` 37

## Setup

1. Open the project root in Android Studio, or build from the command line:
   ```
   ./gradlew assembleDebug
   ```
2. `local.properties` (not committed) must point `sdk.dir` at your Android SDK location.
3. To run on a connected device without Android Studio:
   ```
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n com.schmodcast/.MainActivity
   ```

## Notes

- AGP 9.3.1 currently fails to apply to any module under Gradle 9.3.1
  (`NoClassDefFoundError: org/gradle/features/binding/ProjectTypeBinding`). The project is
  pinned to AGP 9.1.1 until that's fixed upstream — bump `agp` in
  `gradle/libs.versions.toml` once a patch release resolves it.
- Episode downloads are tracked in Room (`EpisodeEntity.localFilePath`) but not yet
  consumed by playback — `PlaybackService` always streams from the episode's remote URL.
- No automated tests yet.

## Keeping this current

This file and `CLAUDE.md` both describe the app's current architecture and state.
Whenever you land a change that affects what's described here (new module/layer, a
stack swap, a gap that's closed) — update both in the same change. Stale architecture
notes are worse than none, since they actively mislead the next person (or agent)
who trusts them instead of reading the code.
