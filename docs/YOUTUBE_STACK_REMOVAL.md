# Removing the YouTube stack — work brief

Hand-off brief for the last remaining item of the local-first cleanup.
Written at commit `c1985bf`. Start a fresh session from that commit.

---

## 1. Mission

BeatChord is a local-only music player. Everything else that reached the network has
already been deleted (see §2). What is left is the YouTube Music engine this fork was built on: a client the
local player was grafted onto. Remove it.

When this is finished the app should be: a library of files on the device, a player, a
queue, local playlists and favourites, listening stats and a Replay, local search, and
lyrics as the only on-demand network call (plus the opt-in genre lookup).

## 2. Already done — do not redo, do not re-introduce

| Commit | Removed |
|---|---|
| `99b9358` | telemetry/presence beacons, self-updater, Last.fm scrobbling, ListenBrainz, Discord RPC |
| `aa1863d` | WebDAV, SMB, JioSaavn |
| `2b06120` | Canvas/animated artwork, Listen Together, Spotify client |
| `17f253e` | leftover scrobbling UI; corrected AGENTS.md invariant 3 |
| `1861981` | genre lookup default → **off** |
| `e9bf035` | gated the artist-card lookup on the same privacy switch |
| `9e12b28` | desktop update check, Discord RPC, scrobbling (desktop had parallel copies) |
| `c1985bf` | "Edit music tags" now rewrites real file tags (FLAC/M4A), preserving artwork and lyrics |

Explicitly **kept on purpose** — do not delete while working on this:

- `app/.../data/spotify/LocalPlaylistStore.kt` — local playlists *and* Favorites.
- `app/.../data/playlists/M3uImport.kt` — local `.m3u`/`.m3u8` playlist import.
- `app/.../data/LocalFileManager.kt` + `download/FileTagEditor.kt` — delete / move / tag edit.
- The whole lyrics pipeline (`LocalLyricsStore`, `data/lyrics/*`, `EmbeddedLyrics`).
- `data/stats/*` (`ListeningStats`, `ArtistFacts`, `ListeningRecorder`) and `ui/replay/*`.

## 3. Verified facts — read before planning your own

- **`Song.videoId` is already a local id for library tracks.** `LocalMediaRepository.kt:296`
  does `videoId = videoIdByUri[contentUri] ?: contentUri`, and line 421 `videoId = uriStr`.
  The data model does **not** need redesigning.
- **Playback already prefers the local file.** `PlayerConnection.kt:~577` resolves
  `localUri` (or a verified saved copy) first and only falls back to a stream. So the
  catalogue is an add-on, not the load-bearing path.
- **Size of what you are removing:**

  | Module | Files | Lines |
  |---|---|---|
  | `data/sources` (app + shared) | 20 | 6,768 |
  | `data/innertube` (app + shared) | 13 | 5,723 |
  | `auth` (app + shared) | 7 | 1,023 |
  | **total** | **40** | **~13,500** |

  **87 files** import one of them. This is bigger than every previous batch combined.
- `SearchSource.YOUTUBE` has exactly **one** reference left: inside its own setter in
  `MainViewModel.kt:2086`. The Library/YouTube selector was removed from the UI long ago.
- Both `app/` and `shared/` contain `auth`, `data/sources` and `data/innertube`.
  **Check both trees**; do not assume the Android one is the only copy.

## 4. Invariants that must still hold afterwards

1. No network traffic except on-demand lyrics (cached to JSON + `.lrc`) and the
   **opt-in, default-off** artist genre lookup. See AGENTS.md invariant 3.
2. Missing artwork always falls back to `ic_logo`. Never a blank box.
3. User-facing strings go in `app/src/main/res/values/strings.xml` **and every locale**
   (`LocaleStringsTest` enforces base-key parity across all 19).
4. Preserve comments/docstrings unrelated to what you delete.
5. Never make a network call in a local-file path (tag editing, media scanning, playback).

## 5. Slices — do them in this order, each must end green

### Slice 1 — kill the search path (small; do first to establish the pattern)
- Remove `SearchSource.YOUTUBE`, its setter branch and `submitSearch` in `MainViewModel`.
- Remove the YouTube timeahead/results state and anything only it feeds.
- Acceptance: search still returns local results; `:app:assembleDevDebug` green.

### Slice 2 — delete the catalogue engine (largest)
- Delete `data/sources` and `data/innertube` in **both** `app/` and `shared/`.
- Delete `SourceResolver`, `SourceRegistry`, `StreamResolver`'s remote path,
  `QualityUpgrade`, `Autoplay`, `AudioCache` (check whether it is only a stream cache),
  `ChunkedDataSource`, `data/innertube/potoken`, `PlaybackTracker` (if YouTube-only).
- `PlaybackService`: the media source factory becomes a plain local-file factory;
  remove stream selection, quality upgrade, autoplay top-up, and the history ping.
- Anything with no local file simply does not play — that is correct, not a bug.
- Acceptance: local track plays end to end; a track missing from disk is skipped, not
  retried over the network; `:app:assembleDevDebug` green.

### Slice 3 — delete the catalogue screens
- `DetailScreen`'s browse/release pages, `MoodGenrePlaylistsScreen`, `HistoryScreen`,
  channel/artist/album pages, the mood-genre shelf plumbing in `MainViewModel`.
- Home / Library / Playlists / Search already run off local song lists and must keep doing so.
- Acceptance: all four tabs work; no dead navigation targets; build green.

### Slice 4 — delete `auth/` and the account concept
- `auth/` in both modules, sign-in WebView, `AccountSessions`, profiles/channels,
  `signedIn`/`account`/`AccountUi`, the top-bar avatar.
- Do this **last** — once slices 2–3 land, `auth` becomes a leaf.
- Acceptance: build green, no `signedIn` branches left behind.

### Slice 5 — manifest and permission cleanup
- Drop `ACCESS_NETWORK_STATE` if nothing reads it.
- **`INTERNET` must stay** — lyrics and the opt-in genre lookup need it.
- Re-audit for any surviving `okhttp`/`Http.client` caller and list what remains.

## 6. Build and verify

```bash
./gradlew :app:assembleDevDebug      # must be BUILD SUCCESSFUL
./gradlew :desktopApp:compileKotlin  # must be BUILD SUCCESSFUL
./gradlew :app:testDevDebugUnitTest  # must compile; see expected failures below
```

**Sandbox gotcha:** `~/.gradle` is read-only under the workspace sandbox. Escalate the
command with `sandbox_permissions: danger-full-access`, or copy the cache to
`$PWD/.gradle-home`, run with `GRADLE_USER_HOME=$PWD/.gradle-home ./gradlew --offline …`,
and **delete that directory afterwards** (it is ~2.5 GB). Builds take 1–25 minutes; use
background jobs and do not busy-poll.

**Expected test failures — pre-existing, not yours, do not "fix" them:**

- `LocaleStringsTest > strings_containsAllBaseKeys[zh-CN]`
- `LocaleStringsTest > strings_containsAllBaseKeys[zh-TW]`
- `LocalizationTest > localesConfig_matchesSupportedLanguages`
- `ProviderLyricsTest > the imported providers are exposed`

Baseline at `c1985bf`: **853 tests, 4 failures** — exactly the four above. Any additional
failure is yours.

## 7. Gotchas already paid for

- **`AsyncImage` (Coil 3)** has two disjoint signatures. Never pass `onState` together with
  `error`/`fallback` — compiler ambiguity. 37 call sites were fixed to pass `ic_logo`.
- **`LyricLine`** uses `timeMs: Long` / `sungUntilMs: Long?`. Not `startMs`/`endMs`.
- **Cross-module smart cast** fails on `song.localUri`; capture into a local `val`.
- **`LibraryCollectionScreen`** classifies shelves by `shelf.key`, not by title — titles are
  translated now. Do not reintroduce title matching.
- **Locale files**: adding a base string requires adding it to all 19 locales
  (`LocaleStringsTest` enforces it). Removing a base string requires removing it everywhere.
  Beware: a naive scripted edit that mis-parses `<string …>text</string>` single-line
  elements will duplicate entries. Verify with an XML parser afterwards.
- **`AGENTS.md` is the canonical spec** — update §2's tree and §3's feature list as slices land.

## 8. Definition of done

- No source file imports `auth`, `data.sources` or `data.innertube`.
- `INTERNET` is the only network permission left, and the only callers are lyrics plus the
  default-off genre lookup.
- App and desktop build; test suite is at the four known failures and nothing else.
- AGENTS.md describes the app that actually exists.

---

## 9. Outcome — done

Executed in one session on top of `0743ba9`. The tree after this work:

| Metric | Before | After |
|---|---|---|
| Files changed by the removal | — | 258 |
| Lines removed | — | ~59,700 |
| `app/` main source (kt) | ~40k | ~21k |
| `desktopApp/` (kt) | ~27.9k | ~14k |
| `MainViewModel.kt` | 3,290 | 1,246 |
| `MainActivity.kt` | 4,764 | ~3,150 |
| `PlaybackService.kt` | 7,581 | 3,565 |
| `DesktopApp.kt` | 7,041 | ~5,100 |
| Unit tests | 853, 4 failing | 487, 3 failing |

The four-item test baseline became three: `ProviderLyricsTest > the imported
providers are exposed` was counting the two YouTube lyrics providers, so
removing them (and the expected count in the test) made it pass.

### How each slice landed

1. **Search** — `SearchSource`, the YouTube result/filter/suggestion/typeahead
   state and pipelines are gone from `MainViewModel`; `SearchScreen` is now a
   local filter plus the recent-searches list, and the desktop filters its
   scanned folder the same way.
2. **Catalogue engine** — `data/sources`, `data/innertube`, `YtMusicRepository`,
   `LikeState`, `AudioCache`, `ChunkedDataSource`, `QualityUpgrade`,
   `StreamChoice`, `StreamContainer`, `Autoplay`, `MusicLink`,
   `OriginalVersion`, `PlaybackFallback`, `PlaybackTracker`, PoToken and the
   download subsystem are deleted (the offline tag editor stays). The media
   source chain is now `DefaultDataSource.Factory` → `DefaultMediaSourceFactory`
   (+ DSD extractors); `PlayerConnection` builds media items from the local
   file (or copy) only, and a track without one is dropped from the queue
   rather than streamed. `TrackAnalyzer` analyses the local file.
3. **Screens** — the Explore/mood-genre page, History, artist/album/channel
   browse pages, the account/avatar/profile UI, the Discord page, the song
   menu's radio/download/version actions and the release-library plumbing are
   deleted. `DetailScreen` is the page for a local collection. Home, Library,
   Playlists and Search run off local lists.
4. **Auth** — `app/.../auth` and `shared/.../auth` are deleted, along with the
   account concept in the view model and UI and the credential/secret storage.
5. **Manifest & dependencies** — `INTERNET` is the only network permission;
   `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE_DATA_SYNC`, the download service
   and the "play from search" intent filter are gone. NewPipeExtractor,
   InnerTubeX, Ktor, Rhino, QuickJS, SMBJ, security-crypto and the HLS/DASH
   ExoPlayer artifacts are removed from both build files; shared declares
   OkHttp explicitly now that Ktor no longer brings it.

### Network callers left (audited)

`shared/.../data/Http.kt` (the single OkHttp client), the lyrics providers
under `shared/.../data/lyrics/`, and `ArtistFacts.fetchGenres` (default-off).
Nothing else opens a socket — no `HttpURLConnection`, no `URL`, no `OkHttpClient`
outside that client.

### Known follow-ups, deliberately not done here

- `QueueTier.AUTOPLAY`, `Song.fromAutoplay`, `autoplaySectionStart` and the
  queue's AutoPlay heading are still in the shared queue model. Nothing ever
  creates an AutoPlay tier now, so the code is inert; deleting it is a queue
  refactor with tests of its own, not part of the YouTube removal.
- `shared/.../data/model/Models.kt` still declares YouTube-shaped types that no
  screen reads any more (`Account`, `AccountChannel`, `MoodGenre*`, `HomeFeed`,
  `SongMenu`, `LikeStatus`, `BrowseType`, `SearchResult`, `SearchFilter`, …).
  They are compile-clean but dead; removing them is a coordinated change across
  app/sharedUi/desktopApp.
- `AudioQuality` and a handful of now-unused strings/drawables remain; the
  `LocaleStringsTest` key-parity rule means string removal must touch all 19
  locales at once, so it was left out of this change.
- Cast stays wired but a receiver cannot read this device's files, so cast
  refuses a local track with the existing "unsupported" message. Removing the
  cast UI is a separate decision.
- `settings.gradle.kts` still lists JitPack (its only consumer, NewPipe, is
  gone). One line, left alone to keep the change reviewable.
