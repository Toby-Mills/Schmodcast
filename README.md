# Schmodcast

A native Android podcast player. Subscribe to shows via the iTunes Search API; every
subscription's recent episodes (last 60 days) merge into one auto-sorted Queue with no
manual reordering — background playback via Media3/ExoPlayer, with Android Auto support.
Tapping any episode in the queue loads it into the player out of turn; once it finishes,
playback reverts to whatever is at the head of the queue.

## Stack

- Kotlin 2.4.10, Jetpack Compose (Material 3), Navigation Compose
- Room (subscriptions/episodes), Retrofit + kotlinx-serialization (iTunes search), a small
  hand-rolled RSS parser (no XML library dependency), Coil (images)
- Media3 (ExoPlayer + `MediaLibraryService`) for background/notification-controlled playback
  and Android Auto integration — ExoPlayer's own timeline holds the full queue (not just the
  current episode) so Auto's built-in Queue screen shows real upcoming episodes, and a
  two-level browse tree (Queue folder → episodes) is exposed for `MediaBrowser` clients
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
- Episode downloads are tracked in Room (`EpisodeEntity.localFilePath`) and consumed by
  playback — `PlaybackService` plays from the downloaded file when present, falling back
  to streaming the episode's remote URL otherwise.
- Playback position persists per episode (`EpisodeEntity.lastPositionMs`), and the
  last-loaded episode ID persists separately (`PlaybackStateStore`, SharedPreferences) —
  on relaunch, `PlaybackService` resumes that episode at its saved position rather than
  always starting from the queue head; the same resume-seek also applies whenever any
  episode becomes current, including ExoPlayer auto-advancing onto one mid-timeline.
- `PlaybackService` must stay `android:exported="true"` in the manifest, with both the
  Media3 (`androidx.media3.session.MediaLibraryService`) and legacy
  (`android.media.browse.MediaBrowserService`) actions on its intent-filter — Android Auto
  binds via the legacy action for app discovery and gets a silent `SecurityException` if
  the service isn't exported.
- No automated tests yet.

## Keeping this current

This file and `CLAUDE.md` both describe the app's current architecture and state.
Whenever you land a change that affects what's described here (new module/layer, a
stack swap, a gap that's closed) — update both in the same change. Stale architecture
notes are worse than none, since they actively mislead the next person (or agent)
who trusts them instead of reading the code.
