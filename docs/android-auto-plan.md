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

**Status: steps 1–5 done** (with real design changes along the way — see "What actually
happened" under each step, and "Findings" at the end). Steps 6–8 not started/pending. Step 4's
custom actions are confirmed working end-to-end via a real DHU pass (all three tapped
through the actual DHU window, verified via `dumpsys media_session` before/after each tap).
Step 4's icon-rendering conclusion was later found to be partly wrong, and corrected in step 4
point 6: a real bug (the session-wide custom layout was never re-pushed after the initial
connect, so it silently reverted to empty once Auto's real controller connected) was
misdiagnosed as more of the same platform-enforced substitution, since a screenshot taken in
the brief populated window looked like confirmation. Once actually fixed (`onPostConnect`
pushing `session.setCustomLayout(...)` on every connect), skip-back/forward and speed all
render as distinct, real icons. `SLOT_BACK`/`SLOT_FORWARD` specifically are still hardcoded to
the standard previous/next chevrons regardless of icon — but that's confirmed via source
tracing to be about `Player.COMMAND_SEEK_TO_PREVIOUS`/`_NEXT` availability (which also gates
hardware-button dispatch, so can't be removed without breaking step 4.5), not a slot-icon
substitution at all. Step 4.5 (not in the original plan) makes hardware transport buttons —
steering wheel, Bluetooth, wired remote — skip ±2min/30s within the episode instead of jumping
to a different one, confirmed on a real device via `adb shell cmd media_session dispatch`.

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

### 4. Expose the expanded-player actions as custom session commands with icons — done; icons are a confirmed platform limitation, not fixed

Auto's now-playing screen only shows a handful of custom action slots, each requiring an
icon resource, not a Compose button. Skip ±30s/+2min and the speed-cycle button are now
equivalent `SessionCommand`s (`CUSTOM_COMMAND_SKIP_BACK`/`_SKIP_FORWARD`/`_CYCLE_SPEED`)
declared in `onConnect` and advertised as a custom layout of `CommandButton`s, each built
from this app's own `ic_skip_back`/`ic_skip_forward`/`ic_speed` vectors via
`CommandButton.Builder(CommandButton.ICON_UNDEFINED).setCustomIconResId(...)` — see
"What actually happened" for why those icons don't visually render as designed, and why
that was deliberately left as-is rather than worked around.

- Reused the seek/speed logic, but not literally through `QueueViewModel`'s controller
  calls (the service has direct `player` access, no controller indirection needed) —
  extracted the actual shared bits (skip amounts, speed steps/cycling, speed label
  formatting) into `playback/PlaybackTuning.kt` so `PlaybackService`'s command handlers and
  `QueueViewModel`/`QueueScreen` both call the same functions instead of maintaining two
  copies of "what does skip-forward mean" that could drift apart. `SPEED_OPTIONS` stayed at
  its original `1/1.2/1.4/1.6/1.8/2x` — see point 4 below for why an earlier version of this
  work changed it and then reverted that change.
- Mark-as-played stayed phone-only, per the original guess above — it already bypasses the
  session via `episodeRepository.markPlayed(...)`, and natural-completion auto-advance
  covers the in-car case without a dedicated Auto action.
- **What actually happened, in order:**
  1. First attempt used `CommandButton.Builder(CommandButton.ICON_UNDEFINED).setCustomIconResId(...)`,
     reusing the phone UI's own `ic_skip_back`/`ic_skip_forward` vectors plus a new `ic_speed`
     vector, on the assumption that a custom app-drawn icon would just work the way notification
     custom actions always have. Media3 1.10.1 turned out to have a richer `CommandButton` API
     than the plan assumed either way — predefined icon constants
     (`CommandButton.ICON_SKIP_BACK_30`, `ICON_PLAYBACK_SPEED_1_5`, etc.) and a slot system
     (`SLOT_BACK`/`SLOT_FORWARD`/`SLOT_FORWARD_SECONDARY`/...) instead of a flat list.
     `setIconResId(...)` (the method this plan originally named) turned out to be deprecated in
     favor of `setCustomIconResId(...)`, and the bare `CommandButton.Builder()` constructor is
     *also* deprecated in favor of `Builder(int icon)` — both confirmed by decompiling
     `media3-session-1.10.1.aar` directly (`javap` against its `classes.jar`), since the
     installed docs/samples didn't make either deprecation obvious.
  2. A real DHU pass (see step 8's screenshot technique) confirmed the custom-icon buttons
     *worked* — all three showed up, and tapping each one through the actual DHU window
     correctly reached `onCustomCommand` (skip-back moved `position` back exactly 30000ms,
     skip-forward moved it forward exactly 120000ms, cycling speed updated the custom action's
     label from `1x speed` to `1.2x speed`, all confirmed via before/after `dumpsys
     media_session` snapshots) — but the *icons* didn't render as designed. The speed button
     showed as a generic concentric-circle "bullseye" instead of `ic_speed.xml`'s hand-drawn
     gauge.
  3. **First (wrong) diagnosis:** decompiling `MediaSessionLegacyStub.createPlaybackStateCompat(...)`
     — the method that builds the `PlaybackStateCompat` Auto actually consumes — showed a
     predefined icon constant gets its raw int stashed into the `CustomAction`'s extras under
     `MediaConstants.EXTRAS_KEY_COMMAND_BUTTON_ICON_COMPAT`, while a custom resource id doesn't.
     This looked like a plausible explanation (predefined constants get a recognition signal,
     custom ones don't) and was acted on: switched all three buttons to predefined constants
     (`ICON_SKIP_BACK_30`, generic `ICON_SKIP_FORWARD`, `ICON_PLAYBACK_SPEED_*`), which required
     realigning `SPEED_OPTIONS` to `1/1.2/1.5/1.8/2x` to match Media3's numeral badge set — a
     phone-visible product change made **without the app owner's sign-off**, straight after being
     asked to keep the app's own icons and investigate rendering instead. That was wrong to do
     unilaterally and was reverted in full once flagged, back to custom icons and the original
     `1/1.2/1.4/1.6/1.8/2x` steps.
  4. **Real root cause, found by direct experimentation, not more decompiling:** a real
     third-party app (Pocket Casts) was confirmed — by the app owner, looking at the same DHU —
     to render its own custom "Change speed" icon correctly, including live-updating to show the
     current numeral (e.g. "1.4x"). That ruled out "custom icons can't cross-process load in this
     DHU" as a category, meaning something about *our* setup specifically was the problem, not the
     platform in general — so the `EXTRAS_KEY_COMMAND_BUTTON_ICON_COMPAT` theory in point 3, while
     real, wasn't actually the explanation for our failure. Zooming into the DHU screenshot at 6x
     showed skip-back's icon rendering as genuinely asymmetric (open arc + arrowhead) — i.e. it
     *was* loading our actual resource — while speed's icon was a perfectly symmetric ring+dot,
     structurally impossible to be our own asymmetric arc+needle art at any resolution. Ruled out
     hypotheses one at a time, each requiring only a rebuild/reinstall/re-screenshot: the pivot
     dot's unusual double-arc path construction (removed it — no change); which slot the button
     occupied (`SLOT_FORWARD_SECONDARY` vs `SLOT_OVERFLOW` vs `SLOT_BACK_SECONDARY` — all three
     showed the identical bullseye). The decisive test: swapped which icon *file* sat in which
     *slot* (`ic_speed`'s exact resource into `SLOT_FORWARD`, `ic_skip_forward`'s exact resource
     into `SLOT_FORWARD_SECONDARY`) and re-screenshotted. The rendered icon followed the **slot**,
     not the file, both directions — `ic_speed` rendered as a normal forward arrow in
     `SLOT_FORWARD`, and `ic_skip_forward` rendered as the same bullseye in
     `SLOT_FORWARD_SECONDARY`. Conclusion, fully evidenced: **Android Auto enforces its own
     canonical icon per `CommandButton` slot, ignoring whatever drawable the app supplies** —
     `SLOT_BACK`/`SLOT_FORWARD` always show a direction glyph (numbered if a matching predefined
     constant like `ICON_SKIP_BACK_30` was declared, generic/unnumbered otherwise — still never
     the app's own resource), every other slot tried always shows the same generic "adjust"
     glyph. This is a platform-level constraint of Media3's `CommandButton`/slot abstraction, not
     a bug in this app's icons, and not fixable by drawing better art. Pocket Casts' working
     custom icon is almost certainly explained by *not* using Media3's `CommandButton`/slot system
     at all — its `dumpsys` custom-action extras came back completely `null` (not even an empty
     bundle), consistent with building `PlaybackStateCompat.CustomAction`s directly through the
     older `MediaSessionCompat` API, which has no slot concept and no canonicalization to bypass.
  5. **Decision:** left all three buttons on `CommandButton.ICON_UNDEFINED` with this app's own
     custom resources — functionally correct (all three commands work end-to-end) and visually
     consistent (all three show a platform-generic icon rather than mixing a numbered one, like
     `ICON_SKIP_BACK_30`, with unnumbered ones that have no equivalent). Replicating Pocket Casts'
     approach would mean building a parallel legacy `MediaSessionCompat` custom-action path
     bypassing Media3's session/slot abstraction for this one row of buttons — a real architecture
     option, deliberately not pursued for now; revisit if a genuinely custom/numeral-bearing Auto
     icon becomes a priority.
  6. **Revisited in a later session, after the app owner noticed the speed button missing
     entirely in Auto's full-screen Now Playing template.** Decompiling
     `media3-session-1.10.1`'s actual sources (not just symptom-watching) found two distinct,
     previously-conflated issues:
     - `CommandButton.DisplayConstraints` defaults `SLOT_FORWARD_SECONDARY` (where the speed
       button lives) to a max of **zero** buttons — a host template that doesn't explicitly
       raise that limit drops the button entirely rather than re-icon-ing it. Fixed by declaring
       `.setSlots(SLOT_FORWARD_SECONDARY, SLOT_OVERFLOW)` — a *preference order*, not a set — so
       the button falls back to `SLOT_OVERFLOW` (unlimited by default) instead of vanishing.
     - The real reason all three custom actions rendered as generic/missing rather than this
       app's own art, re-examined: `ConnectionResult.setCustomLayout(...)` in `onConnect` only
       seeds *that one connecting controller's* initial layout, not the session-wide legacy
       `PlaybackStateCompat` that Auto's persistent rendering and `dumpsys media_session` read.
       Without an explicit session-wide push, `dumpsys` showed `custom actions=[]` — completely
       empty — once Auto's real controller had connected, even though a `dumpsys` check taken
       *immediately* after installing a fresh build showed all three actions populated with real
       icon resource IDs. That immediately-after-install snapshot was mistakenly reported as
       confirmation the fix worked; it wasn't re-checked after a real settle period, and the app
       owner caught the regression live in DHU minutes later when it had already reverted to
       empty. Root cause found by decompiling `MediaSessionLegacyStub`/`CommandButton` directly:
       fixed by adding `sessionCallback.onPostConnect` (the hook `MediaSession.Callback.onConnect`
       itself documents for exactly this) to call `session.setCustomLayout(customLayout())` on
       every connect, not just reactively after a speed change. Verified this time by checking
       `dumpsys` after a 20s+ settle, past when Auto's controller has fully connected, not just
       immediately after install. Also switched skip-back/forward from `ICON_UNDEFINED` to the
       generic (unnumbered) predefined `ICON_SKIP_BACK`/`ICON_SKIP_FORWARD` constants, on the
       theory that Auto might use the icon *type* to distinguish a skip action from a
       previous/next-episode one — confirmed via `dumpsys`: these now appear as genuine
       additional custom actions (distinct generic arrow icons) alongside the still-unfixable
       `SLOT_BACK`/`SLOT_FORWARD` chevrons, rather than being folded into the chevrons as before.
     - This means the original point 4 conclusion above — "every slot other than `SLOT_BACK`/
       `SLOT_FORWARD` always shows the same generic glyph, never the app's own resource" — was at
       least partly a symptom of this same bug (the action was never actually reaching Auto with
       a populated icon at all under steady-state connection), not purely a platform-enforced
       icon substitution. After the fix, three visibly distinct icons render in Auto's full-screen
       Now Playing where before there was one generic glyph or nothing.
     - The app owner separately asked whether the still-standard `SLOT_BACK`/`SLOT_FORWARD`
       chevrons could be removed now that the real skip icons render alongside them (confusing to
       have two pairs of controls doing the same thing). Traced via source: those chevrons come
       from the legacy `ACTION_SKIP_TO_PREVIOUS`/`_NEXT` bits, set purely by whether
       `Player.COMMAND_SEEK_TO_PREVIOUS`/`_NEXT` are in the session's negotiated available player
       commands (`MediaSessionLegacyStub.createPlaybackStateCompat`) — entirely independent of
       any `CommandButton` or icon. That is the *exact same* availability flag
       `ConnectedControllersManager.isPlayerCommandAvailable` checks before a hardware
       media-button press is allowed to reach `onPlayerCommandRequest` at all
       (`MediaSessionLegacyStub.dispatchSessionTaskWithPlayerCommand` returns early otherwise) —
       so removing those commands to kill the chevrons would silently break step 4.5's
       hardware-button redirect too. Raised with the app owner rather than resolved unilaterally;
       decision was to keep the chevrons and accept the visual redundancy over losing
       hardware-button skip.

### 4.5. Make hardware transport buttons (steering wheel, Bluetooth, wired remote) skip within the episode — done (not in the original plan)

Prompted by a direct question during step 4's aftermath: does the slot system used for Auto's
touch UI have anything to do with a car's physical steering wheel buttons? It doesn't — they're
two completely separate input paths. Steering wheel/Bluetooth/wired-remote button presses send
standard Android media-key events, which Media3 translates into `Player.COMMAND_SEEK_TO_NEXT`/
`_PREVIOUS` and dispatches directly to the player, with no way to reach any of `PlaybackService`'s
custom `SessionCommand`s (`CUSTOM_COMMAND_SKIP_BACK`/etc.) — those only exist for touch UI (the
phone app, Auto's Now Playing screen).

Left alone, since ExoPlayer's timeline holds the whole queue (see Playback in `CLAUDE.md`),
`COMMAND_SEEK_TO_NEXT`/`_PREVIOUS` would jump to the next/previous *episode* — not an obviously
wrong choice (it matches the app's whole "one continuous queue" model), but explicitly **not**
what was wanted here: jumping to a different episode via a physical button while driving was
judged too risky/surprising, in favor of matching what many podcast apps do physically instead —
skip forward/back *within* the current episode.

Implemented via `MediaSession.Callback.onPlayerCommandRequest` — a gate hook, confirmed by
decompiling `MediaSessionImpl`/`MediaSessionStub`'s bytecode before relying on it, given how
`onCustomCommand` had already tripped Claude Code up on assumed-vs-actual Media3 semantics once
this session: returning `SessionResult.RESULT_SUCCESS` lets the requested player command proceed
as normal; any other code blocks it from ever reaching the actual `Player` call. `PlaybackService`
intercepts `COMMAND_SEEK_TO_NEXT`/`_PREVIOUS` (and their `_MEDIA_ITEM` variants) here, calls the
same `skipForward()`/`skipBack()` (±2min/30s) the custom actions already use, and returns
`RESULT_INFO_SKIPPED` to block ExoPlayer's own default handling. `onPlayerCommandRequest` is
itself deprecated in media3-session 1.10.1 with no non-deprecated replacement that still supports
intercept-and-substitute (`onPlayerInteractionFinished` only fires *after* the default behavior
already ran) — suppressed deliberately at the call site, not an oversight.

**Verification, on a real device — DHU can't simulate a steering wheel at all, so this needed a
different approach than the screenshot technique used elsewhere in this doc:**
- `adb shell input keyevent 87` (`KEYCODE_MEDIA_NEXT`) was tried first and did *nothing* — no
  position change, no `dumpsys` timestamp change. Root cause: `input keyevent` injects into
  whatever window currently has UI focus, which is an entirely different path from real
  media-button dispatch (`AudioManager`/`MediaSessionManager` routing to the current top-priority
  session) — it is **not** a valid way to simulate a hardware media button on Android, despite
  looking like it should be.
- `adb shell cmd media_session dispatch next`/`previous` is the actual dispatch path, confirmed via
  `adb shell cmd media_session list-sessions` (shows session priority order) and `dumpsys
  media_session`'s `updated` timestamp. Dispatch initially still did nothing, because Schmodcast's
  session was `PAUSED` and ranked *below* another installed app's session in priority — media-button
  dispatch always goes to the current top session, with no way to target a specific package.
  Starting real playback in the app (a tap on its own Play button, via `adb shell input tap` at
  coordinates read off an `adb exec-out screencap` of the actual phone screen — not DHU) brought
  Schmodcast to the top of `list-sessions`, after which dispatch worked immediately.
- With that fixed, `dispatch next` moved `position` forward by exactly 120000ms and `dispatch
  previous` moved it back by exactly 30000ms (both confirmed via tight before/after `dumpsys`
  snapshots), with `active item id` unchanged both times — confirming the interception works
  exactly as intended, without touching the natural end-of-episode auto-advance path (a separate,
  player-internal state transition — see `onMediaItemTransition` in `CLAUDE.md` — that
  `onPlayerCommandRequest` has no reason to interact with, since it only gates
  controller-*requested* commands).

### 5. Make episode artwork resolvable by the head unit — done

Confirmed `episode.podcastArtworkUrl` is always a plain, directly resolvable `http(s)` URI
(guarded by an `isNotBlank()` check before `PlaybackService.episodeMetadata()` calls
`setArtworkUri(...)`) — resolvability itself was never actually broken.

**What actually happened:** the real issue, caught by the app owner testing artwork on a
real head unit rather than by anything in this checklist, was resolution, not
resolvability. `ItunesSearchApi.kt`'s `ItunesPodcastDto` only captured the iTunes Search
API's `artworkUrl100` field — literally a 100x100px thumbnail URL — and copied it verbatim
into `Podcast.artworkUrl`, from which every subscribed episode's `podcastArtworkUrl`
(`EpisodeRepository.kt`'s `RssItem.toEntityOrNull`) and every UI surface (`AsyncImage` in
`QueueScreen`/`SearchScreen`/`LibraryScreen`) all draw the identical string — there is no
separate higher-res copy anywhere. Coil happily downscales that 100x100 source into the
app's own small (48-180dp) Compose thumbnails without it looking obviously bad, but Android
Auto's head-unit art tiles render much larger, upscaling the same source into a visibly
blurry result.

Fixed by adding `artworkUrl600` (the Search API returns this alongside `artworkUrl100` for
every podcast) to `ItunesPodcastDto` and preferring it in `toDomainOrNull()`, falling back to
`artworkUrl100` only if it's ever missing. This is a single point-of-truth fix — every
episode/UI surface downstream already reads the same `Podcast.artworkUrl`/
`Episode.podcastArtworkUrl` field, so nothing else needed to change.

**Known limitation, not fixed here:** this only improves artwork for *newly* subscribed
podcasts (or podcasts re-subscribed after this change) — `PodcastEntity.artworkUrl` is
written once at subscribe time and nothing re-fetches it from iTunes afterward, so podcasts
already subscribed before this fix keep their stored 100x100 URL until unsubscribed and
re-subscribed. Consistent with this being a pre-release app where local data is already
treated as disposable (see `CLAUDE.md`'s Room/`fallbackToDestructiveMigration` note) rather
than something requiring a backfill migration.

Separately, `RssFeedParser` still doesn't parse any `<itunes:image>`/channel `<image><url>`
tag from the feed itself (confirmed: no image handling exists in it at all), so there's no
fallback to a podcast's own feed-provided artwork (often larger than even iTunes's 600x600)
either — out of scope for this step, noted here in case artwork quality comes up again.

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

### 8. Testing — DHU set up and used throughout steps 1–4.5; full pass still pending

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
- `adb` isn't necessarily on `PATH` in whatever shell you're driving this from (it wasn't in
  either of Claude Code's Bash/PowerShell tool shells on this machine, despite a device
  being attached and authorized) — a bare `adb devices` failing with "command not found" is
  not evidence no device is connected. Fall back to the full path via `local.properties`'s
  `sdk.dir` + `/platform-tools/adb.exe` before concluding there's nothing to test against.
  See `CLAUDE.md`'s Commands section.
- `adb shell dumpsys media_session` is a useful non-visual substitute for a chunk of what
  the DHU would otherwise be needed for: the dump includes each app's real
  `PlaybackState` — custom actions (name + icon resource id), `queueTitle`/timeline size,
  play state/position/speed — straight from the live on-device session, without needing DHU
  running or a display to look at. It caught the step-4 custom actions (`Skip back 30
  seconds`/`Skip forward 2 minutes`/`1x speed`) registering correctly and re-confirmed
  step 3.5's full-timeline behavior (`queueTitle=null, size=92`) in the same pass. It can't
  confirm Auto's own UI actually renders/slots those actions correctly, though — that part
  still needs an actual DHU visual check.
- **An agent with no human at the wheel can still drive a real DHU visual check** — no need
  to hand this off to a person. Screen-grab the DHU window itself (in PowerShell:
  `Get-Process` for `desktop-head-unit` to get its `MainWindowHandle`, `GetWindowRect` +
  `CopyFromScreen` to capture it to a PNG, then read the PNG back as an image) rather than
  trying to screenshot the phone via `adb exec-out screencap` — DHU is its own desktop
  window on the host machine, not something rendered on the device. To interact with it,
  inject real clicks at the window's screen coordinates: plain `user32.dll` `mouse_event`
  calls were silently swallowed (clicks landed on-screen but nothing in the app reacted, and
  `dumpsys` confirmed no command fired), but `SendInput` worked reliably. This is exactly
  how step 4's three custom actions got confirmed end-to-end (tap in the real DHU window →
  `dumpsys` before/after showing the position/label actually changed) without a person
  needing to look at anything.
- **Neither DHU nor `adb shell input keyevent` can test hardware transport button behavior** —
  DHU has no steering wheel simulation at all, and `input keyevent` injects into whatever window
  currently has UI focus, which is a completely different path from real media-button dispatch
  and does nothing to a `MediaSession` (confirmed: `KEYCODE_MEDIA_NEXT` via `input keyevent`
  produced no position change and no `dumpsys` timestamp change). The actual dispatch path is
  `adb shell cmd media_session dispatch next`/`previous`/`play`/`pause`/etc. — but it always
  routes to whichever session currently ranks highest in `adb shell cmd media_session
  list-sessions`, with no way to target a specific package, so the app under test needs to
  actually be playing (not just launched) before dispatch will reach it. See step 4.5 for how
  this caught the hardware-button skip behavior working correctly.

Still pending: a full pass once steps 5–6 land (artwork, resumption
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
