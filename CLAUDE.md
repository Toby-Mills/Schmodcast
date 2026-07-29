# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**Keep this file current.** The Architecture section below describes the app's state as of when
it was written. If a change adds/removes a layer, swaps a library, or closes one of the gaps
noted here (e.g. downloads not feeding playback), update this file — and `README.md`'s "Notes"
section — in the same change. A stale architecture doc is worse than none: it actively misleads
whoever reads it next instead of the code.

## Commands

Build and install (Windows: use `gradlew.bat`; this repo has been developed against a device connected via adb rather than Android Studio's run button):

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.schmodcast/.MainActivity
```

If multiple devices/emulators are attached, target one explicitly with `adb -s <serial> ...` (check `adb devices -l` first).

Other useful tasks:
- `./gradlew lint` / `./gradlew lintDebug` — Android Lint (no ktlint/detekt configured)
- `./gradlew clean assembleDebug` — full rebuild, useful after touching Room schema/KSP-generated code

There are no unit or instrumented tests in the repo yet (`app/src/main` is the only source set) — `testDebugUnitTest` / `connectedDebugAndroidTest` exist as empty AGP scaffolding only.

`local.properties` (gitignored) must set `sdk.dir` to a local Android SDK install.

## Critical build gotchas

- **AGP is pinned to 9.1.1** in `gradle/libs.versions.toml`. AGP 9.3.1 (and the matching Gradle 9.3.1) crashes applying to any module with `NoClassDefFoundError: org/gradle/features/binding/ProjectTypeBinding` — a genuine upstream bug, reproduced and isolated during initial setup. Don't bump `agp` without testing a real build first.
- **AGP 9.0+ has built-in Kotlin support.** Do not re-add the `org.jetbrains.kotlin.android` plugin — applying it alongside AGP 9.x throws ("no longer required for Kotlin support since AGP 9.0"). Only `org.jetbrains.kotlin.plugin.compose` and `org.jetbrains.kotlin.plugin.serialization` are applied as Kotlin plugins.
- Kotlin compiler options (jvmTarget, etc.) go in the `kotlin { compilerOptions { ... } }` block in `app/build.gradle.kts` — the legacy `android.kotlinOptions` DSL doesn't exist under AGP's new API.
- `androidx.compose.material:material-icons-core` (the only Material icons dependency in this project — no `-extended`) has a small, fixed icon set. Icons like `Icons.Filled.Pause` and `Icons.Filled.LibraryMusic` aren't in it and fail with "unresolved reference"; when core doesn't have what's needed, add a small hand-drawn vector drawable instead (see `res/drawable/ic_pause.xml`) rather than pulling in `material-icons-extended`.
- Room's `SchmodcastDatabase` uses `fallbackToDestructiveMigration(dropAllTables = true)` — this is a pre-release app with no real installs to preserve, so bumping `@Database(version = ...)` just wipes local data on next launch instead of requiring a migration. Revisit this once the app has real users.

## Architecture

Single-module app (`:app`). Kotlin + Jetpack Compose (Material 3). No DI framework (no Hilt/Dagger) — `SchmodcastApplication` acts as a manual service locator, lazily constructing the Room database and repository/manager singletons and exposing them via `Context` extension functions (`Context.subscriptionsRepository()`, `Context.episodeRepository()`, `Context.episodeDownloadManager()`) that Composables and the playback `Service` call directly rather than receiving injected dependencies.

### Navigation
One `NavHost` in `MainActivity.kt`, three bottom-nav tabs defined in `ui/nav/Destinations.kt`:
- **Queue** (start destination) — `ui/queue/QueueScreen.kt` + `QueueViewModel`
- **Library** — `ui/library/LibraryScreen.kt`, the subscribed-shows list
- **Search** — `ui/search/SearchScreen.kt` + `SearchViewModel`, iTunes Search API lookup with subscribe/unsubscribe

### Data layer
- `data/model/` — plain domain models (`Podcast`, `Episode`)
- `data/local/` — Room: `PodcastEntity`/`PodcastDao`, `EpisodeEntity`/`EpisodeDao`, `SchmodcastDatabase`
- `data/remote/` — `ItunesSearchApi` (Retrofit + kotlinx-serialization, JSON, for podcast directory search) and `RssFeedParser` (a small hand-rolled `XmlPullParser`-based RSS 2.0 reader — deliberately no XML/RSS library dependency); `NetworkModule` holds the shared `OkHttpClient` and Retrofit instance
- `data/SubscriptionsRepository.kt` — subscribe/unsubscribe against Room
- `data/EpisodeRepository.kt` — fetches and parses each subscribed podcast's RSS feed, keeps only episodes published in the last 60 days (`QUEUE_WINDOW`), exposes the merged queue as `Flow<List<Episode>>` sorted newest-first. `RssFeedParser`'s date parsing normalizes named US timezone abbreviations (PDT/PST/EDT/etc.) to numeric offsets before handing off to `DateTimeFormatter.RFC_1123_DATE_TIME`, which otherwise silently rejects them — a real feed (WordPress/PowerPress-style) hit this and dropped every episode.
- `data/download/EpisodeDownloadManager.kt` — downloads an episode's audio to app-internal storage (`filesDir/episode_downloads`), tracking per-episode `DownloadState` (NotDownloaded/Downloading/Downloaded/Failed) in a `StateFlow<Map<episodeId, DownloadState>>`. Runs on an app-scoped coroutine scope, not WorkManager, so an in-progress download doesn't survive process death. `EpisodeEntity.localFilePath` is *not* currently read by `PlaybackService` — playback always streams from `audioUrl`, so downloading an episode doesn't yet change how it plays.

### The Queue's core behavior
There is deliberately no episode-browsing UI and no manual reordering: every subscribed podcast's recent episodes are merged into one global, date-sorted queue, and "current" is always whatever sits at its head.

### Playback
`playback/PlaybackService.kt` is a Media3 `MediaSessionService` + `ExoPlayer`, giving background playback with lock-screen/notification controls (requires `FOREGROUND_SERVICE_MEDIA_PLAYBACK` + `POST_NOTIFICATIONS` permissions declared in the manifest; `POST_NOTIFICATIONS` is requested at runtime from `MainActivity` on API 33+). It is the sole source of truth for "what's playing":
- It collects `EpisodeRepository.queue` directly (not through a ViewModel) and loads whatever is at the head.
- On natural completion (`Player.STATE_ENDED`) it calls `episodeRepository.markPlayed(...)`, which removes that episode from the queue `Flow`. The service treats "the episode I was playing just disappeared from the queue" as the auto-advance signal, and loads + auto-plays the new head. A queue reshuffle for any other reason (e.g. a background refresh surfacing a fresher episode) does *not* interrupt what's already playing.
- `ui/queue/QueueViewModel.kt` never touches the `ExoPlayer` directly — it connects a Media3 `MediaController` to `PlaybackService` via `SessionToken` and only issues transport commands (play/pause/seek), polling playback position on a 500ms ticker while playing.

Any change to episode fetching, queue filtering, or playback ordering should account for this split: `EpisodeRepository`/Room is the single source of truth for queue contents, and `PlaybackService` reacts to it rather than being told what to play by the UI layer.
