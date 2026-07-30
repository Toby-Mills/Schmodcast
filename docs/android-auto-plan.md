# Android Auto support — implementation plan

## Goal

Make Schmodcast usable as a media app inside Android Auto (phone-projected, not
Android Automotive OS). Auto doesn't render this app's Compose UI at all — it drives
playback entirely through the `MediaLibrarySession`/`MediaBrowser` API, so the work is
almost entirely in `playback/PlaybackService.kt`, not the UI layer.

The existing service (`playback/PlaybackService.kt`) was already a Media3
`MediaSessionService` with a real `MediaSession`, transport commands, and one custom
session command (`CUSTOM_COMMAND_PLAY_EPISODE`) for out-of-order episode playback. That
plumbing was reused, not rebuilt — the new work was the browse tree, the manifest
declaration, and (discovered only through real on-device/DHU testing, not in the
original plan below) making ExoPlayer's own timeline carry the whole queue instead of
one episode at a time.

**Status: steps 1–3 done** (with real design changes along the way — see "What actually
happened" under each step, and "Findings" at the end). Steps 4–8 not started.

## Steps

### 1. Declare the app as an Auto media app — done

- Added `app/src/main/res/xml/automotive_app_desc.xml` (`<uses name="media"/>`) and the
  matching `<meta-data>` in `AndroidManifest.xml`.
- **What actually happened:** the manifest's service `<intent-filter>` needed a *second*
  action alongside the Media3 one. See Findings §1.

### 2. Migrate `PlaybackService` from `MediaSessionService` to `MediaLibraryService` — done

- Base class, `MediaSession`/`MediaLibrarySession` fields, `MediaLibrarySession.Builder`,
  and `onGetSession`'s return type all updated. The phone UI's `MediaController` (in
  `QueueViewModel`) needed no changes — it connects to a `MediaLibrarySession` exactly
  the way it connected to a plain `MediaSession`.
- **What actually happened:** compiled and ran cleanly, but real on-device verification
  (installing to a connected Pixel 10, not just a clean build) caught that the service
  was `android:exported="false"` — silently blocking Android Auto (a different process
  entirely) from binding at all. See Findings §2.

### 3. Build the browse tree — done, but the design changed

- `onGetLibraryRoot` / `onGetChildren` / `onGetItem` implemented in `sessionCallback`.
- **What actually happened, in order:**
  1. First attempt used a flat tree (root's children = the episodes directly). Testing
     in the Desktop Head Unit showed Auto collapsing straight to the Now Playing screen
     instead of ever showing a browse list.
  2. Restructured to the two-level shape Android Auto actually expects: invisible root →
     one browsable **"Queue"** folder → episodes as playable leaves. This matches
     Google's documented structure (a root's direct children must be browsable
     tabs/folders, not playable items) — see Findings §3.
  3. Even with the corrected structure, DHU still opened straight to Now Playing rather
     than the browse tree — across three different entry points (the app-grid icon, the
     current-media shortcut, and DHU's simulated search keycode). This turned out not to
     matter: **Auto surfaces the queue through its own built-in Queue screen** (the same
     screen every media app gets, driven by the player's own timeline/playlist), not
     through a custom `MediaLibraryService` browse screen — see step 3.5 below. The
     browse tree is still implemented and correct per Google's docs (voice/search clients
     or other head units may still use it), but isn't what makes the queue visible in
     this Auto version.

### 3.5. Make the real queue visible in Auto — done (not in the original plan)

Discovered via user testing against a real second data point: Android Auto's built-in
**Queue** screen (reachable from Now Playing, the same screen YouTube Music uses to show
its "Up next" list) renders directly from the player's own ExoPlayer timeline — how many
`MediaItem`s are loaded, in what order — not from anything `MediaLibraryService`-specific.
`PlaybackService` originally only ever loaded one `MediaItem` at a time (replaced via
`player.setMediaItem()` on every episode change), so that screen only ever showed one row.

Reworked `loadEpisode`/`onSetMediaItems` to build the *entire* queue into the ExoPlayer
timeline instead (current episode at index 0, the rest of `latestQueue` following it in
date order via `player.setMediaItems(items, 0, startPositionMs)`), so Auto's Queue screen
now shows real upcoming episodes. Confirmed via `dumpsys media_session` (`size=1` →
`size=92`) and visually in the DHU Queue screen.

This traded away a subtlety and required two follow-up fixes, both landed:

- **Losing control of "what plays next."** Once ExoPlayer holds the whole queue, its own
  auto-advance (not our code) decides what plays after the current item finishes — but
  "what's next" is supposed to always be *the live queue's actual head*, not whatever
  happened to be adjacent to the current episode when the timeline was last built
  (relevant for out-of-order plays, and for a queue that's changed since). Fixed by
  treating ExoPlayer's auto-advance as a suggestion: `onMediaItemTransition` re-derives
  the true next episode from `latestQueue` and forcibly reloads (`loadEpisode`) if they
  disagree.
- **Index drift after a natural advance.** `refreshTail()` (which keeps the *upcoming*
  portion of the timeline in sync with the live queue on every `Flow` emission, without
  touching whatever's currently playing) assumed "index 0 is always current." That
  invariant broke the first time ExoPlayer auto-advanced without a full timeline rebuild
  — the finished episode was left sitting at index 0 while the actually-playing one
  moved to index 1, so the next background tail-refresh clobbered the *playing* episode
  instead of the tail after it, causing an abrupt jump to the wrong episode. Fixed by
  trimming the now-finished leading item(s) (`player.removeMediaItems(0,
  player.currentMediaItemIndex)`) right after adopting a naturally-advanced episode, so
  index 0 is "current" again.
  - A related gap: tail items built for display (`orderedMediaItems`/`refreshTail`)
    carry no resume-position info, unlike an explicitly-loaded episode. Fixed by having
    `onMediaItemTransition` seek to the new episode's `lastPositionMs` itself when
    ExoPlayer auto-advances onto a partially-played one.

See `CLAUDE.md`'s Playback and Android Auto sections for the settled description of all
of this.

### 4. Expose the expanded-player actions as custom session commands with icons — not started

Auto's now-playing screen only shows a handful of custom action slots, each requiring an
icon resource, not a Compose button. Skip ±30s/+2min and the speed-cycle button need
equivalent `SessionCommand`s declared in `onConnect` and advertised as custom layout
`CommandButton`s with `setIconResId(...)`. See the original plan notes below (unchanged
from first draft — nothing about this has been attempted yet):

- Reuse whatever seek/speed logic already backs `QueueViewModel`'s transport calls — the
  service-side seek/`setPlaybackSpeed` handling shouldn't need new logic, just a new
  command surface that invokes it.
- Mark-as-played can likely stay phone-only (it already bypasses the session entirely via
  `episodeRepository.markPlayed(...)`) — decide whether Auto needs an equivalent action or
  whether natural-completion auto-advance is sufficient in-car.

### 5. Make episode artwork resolvable by the head unit — not started

Confirm episode artwork URLs (from the RSS feed / iTunes search metadata) are plain
`http(s)` URIs Media3/Auto can load directly via `MediaMetadata.artworkUri`. Not verified
either way yet — worth checking with DHU once step 4 is done, since the Queue/Now Playing
screens already render *some* artwork (the Now Playing card showed the podcast artwork
correctly in testing so far), but a systematic check across feeds hasn't been done.

### 6. Playback resumption — mostly covered by 3.5, worth a final check

The original concern here was root-hints-based resumption coordinating with
`PlaybackStateStore`. In practice, resumption is already handled the same way regardless
of Auto: `PlaybackService.onCreate`'s queue collector picks up `playbackStateStore
.currentEpisodeId` on the first emission and resumes at `lastPositionMs`, before Auto (or
the phone UI) ever connects. Nothing Auto-specific has come up here yet — revisit only if
testing surfaces a cold-start case (e.g. Auto binding before the queue's first Flow
emission arrives) that isn't already handled.

### 7. Update project docs — done (this change)

`CLAUDE.md` (Playback + a new Android Auto section) and `README.md` updated in the same
change that landed the 3.5 rework, per this repo's "keep docs current" convention.

### 8. Testing — DHU set up and used throughout steps 1–3.5; full pass still pending

Desktop Head Unit installed via Android Studio's SDK Manager
(`extras/google/auto/desktop-head-unit.exe`), driven against a real connected Pixel 10 (no
emulator). Real gotchas hit along the way, worth knowing for next time:

- The Android Auto app has **no app-drawer icon** on current versions — reach its
  settings via the Settings app's search bar ("Android Auto"), not the launcher.
  Confirmed by querying `PackageManager` for a `LAUNCHER`-category activity: none exists.
- **Developer settings** and **Start head unit server** are two *separate* entries in the
  same **⋮ overflow menu** on Android Auto's settings screen — developer mode
  (unlocked by tapping the version/header ~10 times) reveals the "Developer settings"
  entry there, but "Start head unit server" is a sibling menu item on the main settings
  page, not something nested inside Developer settings.
- DHU showing a stuck **"Waiting for phone..."** screen after connecting over ADB is a
  documented condition (`developer.android.com/training/cars/testing/dhu`): close DHU,
  stop/restart the head unit server on the phone, relaunch DHU, and grant any permission
  prompt that appears on the phone (DHU may exit once more and need one more relaunch).
- Launch DHU as a detached process (e.g. PowerShell's `Start-Process`), not from a shell
  whose stdin will be closed — its interactive console reads stdin, and closing it kills
  the whole process (and the car UI window with it) almost immediately.
- adb's device-`unauthorized` state and a stopped head-unit-server can both resurface
  after a long gap (USB re-auth prompts, the phone reconnecting) — re-check both before
  assuming something in the app broke.

Still pending: a full pass once steps 4–6 land (custom actions, artwork, resumption
edge cases), plus actually exercising the `MediaLibraryService` browse tree through some
client that isn't this particular Auto version (since normal taps never reach it here).

## Findings (cross-cutting, referenced from steps above)

1. **Two intent-filter actions needed for discovery.** Auto discovers media apps via the
   legacy `android.media.browse.MediaBrowserService` action (a `PackageManager` query),
   not the newer Media3-specific `androidx.media3.session.MediaLibraryService` action —
   both need to be on the service's `<intent-filter>`, confirmed by querying
   `PackageManager` for each action directly.
2. **`exported="true"` is required, and fails silently.** Without it, Android Auto's
   process gets `Permission Denial: ... not exported from uid ...` and a `MediaBrowser`
   `onConnectionFailed` — visible in logcat, but with zero indication inside the app
   itself (no crash, no error shown to the user). This is the standard/expected
   configuration for any `MediaSessionService`/`MediaLibraryService` meant to be reached
   by system components (Auto, Assistant, Bluetooth, notification controls) — access
   itself is still gated by the session's own `onConnect`, not by the manifest export.
3. **Root children must be browsable, not playable.** Confirmed against
   `developer.android.com/media/media3/session/serve-content` and
   `github.com/androidx/media` issues #561/#2093/#3158: Android Auto expects a root's
   direct children to be tabs/folders (`isBrowsable = true`), and collapses/skips a root
   whose children are playable leaves directly instead of ever showing a browse screen.
