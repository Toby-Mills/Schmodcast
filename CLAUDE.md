# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**Keep this file current.** The Architecture section below describes the app's state as of when
it was written. If a change adds/removes a layer, swaps a library, or closes one of the gaps
noted here, update this file — and `README.md`'s "Notes" section — in the same change. A stale
architecture doc is worse than none: it actively misleads whoever reads it next instead of the code.

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
- `data/download/EpisodeDownloadManager.kt` — downloads an episode's audio to app-internal storage (`filesDir/episode_downloads`), tracking per-episode `DownloadState` (NotDownloaded/Downloading/Downloaded/Failed) in a `StateFlow<Map<episodeId, DownloadState>>`. Runs on an app-scoped coroutine scope, not WorkManager, so an in-progress download doesn't survive process death. `PlaybackService.loadEpisode` reads `EpisodeEntity.localFilePath` and plays from that file when it exists on disk, falling back to streaming `audioUrl` otherwise — so a downloaded episode plays offline.

### The Queue's core behavior
There is deliberately no episode-browsing UI and no manual reordering: every subscribed podcast's recent episodes are merged into one global, date-sorted queue, and "current" is always whatever sits at its head.

`QueueScreen.kt`'s now-playing card has two content layouts sharing one `NowPlayingCard` composable: a compact `CollapsedPlayerBody` (default) and a full-screen-ish `ExpandedPlayerBody` (up to 80% of screen height). The transition is a continuous `Animatable<Float>` drag progress (0f–1f), not a boolean toggle — the card's height is `lerp`'d between a fixed collapsed height and 80% of the available height on every frame of the drag, so it visibly grows/shrinks under the finger instead of snapping at a fixed point (content still switches between the two bodies at `dragProgress > 0.5f`). Gesture logic lives in `QueueScreen.kt`, not a separate library — no `BottomSheetScaffold`/`ModalBottomSheet` is used.

`DragHandle` deliberately lives as a sibling *below* the `Card`, not nested inside either content body: nesting it inside meant the handle — and its in-flight gesture — got disposed and remounted the moment a drag crossed the halfway content-swap point, aborting the gesture; separately, `Card` clips its children to its own rounded-rect bounds, which was cutting off half of the handle's enlarged touch target back when the handle sat at the card's bottom edge. The touch target itself is grown well past the visible 4dp pill via a custom `expandTouchTarget` layout modifier (measures the touch-handling node larger than the size it reports upward, so the enlarged region doesn't affect surrounding layout/spacing) — note that `Modifier.clip()` also restricts hit-testing to its own bounds, so the pill's `clip`/`background` have to live on an inner, unclipped decorative child rather than the same node `pointerInput`/`draggable` is attached to, or the clip silently cancels the expanded region back out.

Releasing the drag also checks fling velocity (`Modifier.draggable`'s `onDragStopped`, converted to progress-units/second — `1.0` means "would cross the whole collapsed-to-expanded range in a second" — so the threshold, `FLING_VELOCITY_THRESHOLD`, is independent of screen density/size) before falling back to "which side is drag progress closer to." A fast short flick commits to fully opening/closing even if the drag never crossed the halfway mark.

The expanded layout adds skip ±30s/2min, a speed-cycling button, and mark-as-played, alongside the always-visible progress slider both layouts share via `PlaybackProgressSlider`.

Tapping an episode row in the "Up Next" list (`UpNextRow`) loads that episode into the player out of turn, without disturbing the queue's date order — see Playback below for how this is threaded through to the service. The now-playing card always reflects whichever episode is actually loaded in the player (`queue.firstOrNull { it.id == playbackState.currentEpisodeId } ?: queue.first()`), not just the queue head. "Up Next" deliberately still lists the full queue, including whatever's currently loaded — removing it from the list read as the episode vanishing — so `UpNextRow` instead takes an `isSelected` flag (`episode.id == nowPlaying.id`) and highlights that row (teal tint, bold title, play glyph) in place rather than promoting/hiding it.

### Playback
`playback/PlaybackService.kt` is a Media3 `MediaSessionService` + `ExoPlayer`, giving background playback with lock-screen/notification controls (requires `FOREGROUND_SERVICE_MEDIA_PLAYBACK` + `POST_NOTIFICATIONS` permissions declared in the manifest; `POST_NOTIFICATIONS` is requested at runtime from `MainActivity` on API 33+). It is the sole source of truth for "what's playing":
- It collects `EpisodeRepository.queue` directly (not through a ViewModel) and loads whatever is at the head.
- On natural completion (`Player.STATE_ENDED`) it calls `episodeRepository.markPlayed(...)`, which removes that episode from the queue `Flow`. The service treats "the episode I was playing just disappeared from the queue" as the auto-advance signal, and loads + auto-plays the new head. A queue reshuffle for any other reason (e.g. a background refresh surfacing a fresher episode) does *not* interrupt what's already playing.
- `ui/queue/QueueViewModel.kt` never touches the `ExoPlayer` directly — it connects a Media3 `MediaController` to `PlaybackService` via `SessionToken` and issues transport commands (play/pause, seek, ±2min/30s skip, playback speed via `setPlaybackSpeed`), polling playback position and speed on a 500ms ticker while playing. A manual "mark as played" tap instead calls `episodeRepository.markPlayed(...)` directly from the ViewModel, bypassing the controller entirely — `PlaybackService`'s queue collector can't tell that apart from natural completion (both just remove the episode from the queue `Flow`), so the existing auto-advance path handles it for free.
- Tapping an episode in the queue UI (`QueueViewModel.onEpisodeClick`) sends a Media3 custom session command (`PlaybackService.CUSTOM_COMMAND_PLAY_EPISODE`, episode ID as a `Bundle` extra) rather than going through a regular transport command, because loading an arbitrary episode — as opposed to play/pause/seek on whatever's already loaded — needs to run inside the service so it can update `PlaybackService`'s own `currentEpisodeId`/`loadEpisode` bookkeeping. `PlaybackService.sessionCallback` (a `MediaSession.Callback`) declares and handles this command, looks the episode up in the last collected queue snapshot (`latestQueue`), and calls the existing `loadEpisode(episode, autoPlay = false)` — matching the load-without-autoplay behavior already used for the initial queue head, so the user still has to press play. Because `loadEpisode` sets `currentEpisodeId`, once that episode naturally finishes the existing queue-collector auto-advance logic (STATE_ENDED → `markPlayed` → episode drops out of the queue `Flow` → collector loads the new head with `autoPlay = true`) takes back over with no special-casing — playing an out-of-order episode doesn't touch queue order or need its own "what's next" logic.

Any change to episode fetching, queue filtering, or playback ordering should account for this split: `EpisodeRepository`/Room is the single source of truth for queue contents, and `PlaybackService` reacts to it rather than being told what to play by the UI layer.

### Theming
`ui/theme/Theme.kt`'s `SchmodcastTheme` defaults `dynamicColor = true`, so on API 31+ devices the app actually renders Material You's wallpaper-derived palette, not the static `SchmodcastNavy`/`SchmodcastOrange`/`SchmodcastCream`/`SchmodcastTeal` scheme sitting next to it in `Color.kt` — `MaterialTheme.colorScheme.*` will not reflect the brand colors on those devices. Composables that need the actual logo colors regardless of theme (e.g. the expanded player's play/pause button and skip buttons) reference the `Schmodcast*` constants from `Color.kt` directly instead of going through `MaterialTheme.colorScheme`.
