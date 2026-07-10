# De-risk Naming: "Zoom Room" → "Meetings Remote"

Rename the product everywhere so nothing we ship or publish reads as a clone of **Zoom
Rooms** (Zoom's paid product) or violates Zoom's branding rules (no "Zoom" in third-party
app names, ToU §7.2). Today the app is literally labeled **"Zoom Room"** — launcher icon,
in-app title, the display name other meeting participants see, the applicationId
(`com.bilal.zoomroom`, which becomes the public Play Store URL), the GitHub repo, README,
and the backend. This plan enumerates every surface and swaps in the terminology already
decided in `plans/2026-07-08-playstore-and-oauth.md` §5 A1: app = **Meetings Remote**,
applicationId = **`com.bilal.meetingsremote`**. Factual references to the Zoom platform
(SDK imports, zoom.us APIs, "Sign in with Zoom") stay — nominative use is fine and required.

---

## 1. How To Use This Template

**Repo layout:** `~/code` holds all repos, one directory per repo (this template lives in `~/code/misc`). Plans reference paths relative to their own repo root.

Fork into `plans/YYYY-MM-DD-<slug>.md`, one per task. All section headers stay in every fork. **Copy §1–§3 verbatim — they are project-independent working agreements; rewrite the title, intro paragraph, and §4 onward for the specific project**, replacing each italic meta-description with real content.

1. How To Use This Template
2. Maintain This Plan
3. Preferences
4. Context & Problem Statement
5. Execution Steps
6. Out of Scope / Non-Goals
7. Architecture
8. Database Schema — *optional*
9. Implementation Details
10. Data Snippets — *if relevant*
11. Open Questions / Decisions Needed
12. Test Plan / Acceptance Criteria
13. References / Links
14. File List
15. Long Jobs / Backfill — *optional*
16. Rollback Plan — *optional*
17. Postmortems — *default `not applicable`*
18. Project History — **last**

**500 lines max.** Cut prose first when tight.

---

## 2. Maintain This Plan

- **This is a living document — maintain it constantly.** The moment anything changes, update this file: new info, a decision, a course change, an experiment result, a postmortem, a new constraint, a status change. It is a living human↔agent contract — curate context here so we can clear the conversation and resume cold from this file alone.
- **Own the plan.** Update + commit + push *in the same turn* at checkpoints.
- Keep: decisions + *why*, paths, commands, thresholds, acceptance, rollback, next steps.
- Drop: diary text, dead alternatives, "we tried X" narration, excessive reasoning.
- One status table (Execution Steps). Move rows `not started` -> `started (status)` -> `completed`.
- Project History is append-only. Keep the File List current.

---

## 3. Preferences / Best Practices

- **Be autonomous.** Decide and execute; ask when blocked, genuinely ambiguous, or before destructive/irreversible actions.
- **Think before coding.** State assumptions explicitly. Push back against the human when warranted. If multiple interpretations exist, present them — don't pick silently. Surface simpler approaches and tradeoffs.
- **Simplicity first.** Minimum code that solves the problem, nothing speculative — no unrequested features, no abstractions for single-use code, no configurability or error handling for impossible scenarios. If 200 lines could be 50, rewrite. (Boundaries live in §6.)
- **Surgical changes.** Every changed line traces to the request. Refactor when it unblocks the task (duplicated/convoluted code in your path, or to isolate code for a test) — never speculative cleanup of code you're just passing through.
- **Goal-driven execution.** Turn each task into a verifiable goal ("add validation" → "write tests for invalid inputs, then make them pass"). State a brief plan with a *verify* check per step and loop until verified.
- **Read before write.** Verify with data before mutating shared state.
- **Evidence first:** problem, observations, decision, implementation.
- **TDD by default:** cheapest failing test, minimum fix, refactor.
- Plain words. Small steps. Reversible beats clever.
- **Be succinct always.** Both in this doc and in conversation, prefer bullets, numbered lists, and diagrams over paragraphs. Avoid jargon/vocab words.
- **Use git cleverly, especially for debugging.**
  - Commit often, by filename (never `git add .`); keep commits atomic — one logical change each — with grep-searchable titles and descriptions.
  - *Develop with history:* `git log`/`blame` to recover intent, `git diff/show` to ground edits, `bisect` to find the breaking commit, `reflog` to recover lost state.
  - *As useful:* branch/tag a known-good state before risky work; worktrees to explore approaches in parallel.
- **Guard your context.** It degrades as it fills, so spend it deliberately. Reach for `grep -C`/`find`/`sed/tail/head` to pull only the lines you need instead of reading large files or docs whole; delegate big searches to subagents.
- Write python scripts to do tasks we may want to repeat rather than running strings of adhoc commands.
- Delegate all long-running tasks to subagents so as to keep main chat unblocked.

---

> **▲ Copy §1–§3 above verbatim on fork. ▼ Write everything below fresh for this project.**

## 4. Context & Problem Statement

**Why.** Two concrete review risks once we publish (Play Store + Zoom Marketplace):

1. **Branding rule:** Zoom's marketplace ToU (§7.2) forbids "Zoom" in a third-party app's
   name/logo. Our app name *is* "Zoom Room".
2. **Non-compete optics:** Zoom's API/SDK terms forbid building products competitive with
   Zoom's own. "Zoom Room" is the *name of the Zoom product we most resemble* (Zoom Rooms,
   ~$499/room/yr). The functionality is defensible (SDK-embedded meetings on our own
   hardware); the naming hands a reviewer the rejection for free. De-risk = neutral naming
   + positioning as a meeting appliance/remote, never "a Zoom Rooms alternative".

**Naming decisions** (made in playstore plan §5 A1, 2026-07-08 — reuse, don't re-decide):

| Surface | Old | New |
| --- | --- | --- |
| Launcher / in-app title / Play listing | Zoom Room | **Meetings Remote** |
| applicationId + namespace + Kotlin package | `com.bilal.zoomroom` | **`com.bilal.meetingsremote`** |
| Zoom Marketplace app name (Zoom dev console) | — (not created yet) | **Mobile Remote** |
| Default participant display name (seen in-meeting by others) | Zoom Room | **Meeting Room** |
| Gradle `rootProject.name` | zoom-room | **meetings-remote** |
| GitHub repo | `bilalib1/zoom-room-controller` | **`bilalib1/meetings-remote`** |
| adb debug broadcast action | `com.bilal.zoomroom.DEBUG_CMD` | `com.bilal.meetingsremote.DEBUG_CMD` |
| AirPlay sender name (shows on TV) | ZoomRoom | **Meetings Remote** |

**Complete surface inventory** (grep sweep 2026-07-10, `build/` + `.cxx/` excluded):

*User-visible (highest risk):*
- `room/src/main/AndroidManifest.xml:18` — `android:label="Zoom Room"` (launcher name).
- `room/src/main/java/.../MainActivity.kt:171` — title text `"Zoom Room"`; `:373,425,503` —
  default display name `"Zoom Room"` **shown to every other participant in a meeting** and
  in the settings field label.
- `room/src/main/java/.../sdk/RoomBackend.kt:26` — fallback host name `"Zoom Room"`.
- `backend/token_server.py:90` — default `name = "Zoom Room"`; `:193` — OAuth success page
  "Return to the Zoom Room app"; `:2` docstring.
- `tools/airplay_proto/airplay_mirror.py:196` — mDNS sender `"name": "ZoomRoom"` (appears
  on the Apple TV screen while pairing).
- applicationId `com.bilal.zoomroom` — becomes the permanent public Play Store URL.
  **Immutable after first upload — must change before any Play activity.**
- GitHub repo name `zoom-room-controller` (public URL; also cited in listings' support links later).
- Notifications already neutral ("Casting to TV", "Screen mirroring") — no change.

*In-code identifiers (invisible to reviewers but tied to the above):*
- `room/build.gradle.kts:15,19` — `namespace`/`applicationId`; `:97-98` —
  `endMeetingBeforeInstall` task broadcasts to `com.bilal.zoomroom/.TestHooksReceiver`.
- Kotlin package `com.bilal.zoomroom` — 12 files under `room/src/main/java/com/bilal/zoomroom/`
  (+ `.sdk`, `.source` subpackages), plus FQN references inside `MainActivity.kt:87`,
  `RoomSdk.kt:64,138`, `TestHooksReceiver.kt` doc comment.
- **JNI symbols** `room/src/main/cpp/rtsp_decoder.c:90,157,254,258,263,268` —
  `Java_com_bilal_zoomroom_source_FfmpegVideoSource_*`. Must be renamed in lockstep with the
  Kotlin package or the RTSP source dies at runtime (`UnsatisfiedLinkError`) — the build
  will NOT catch it.
- `AirPlayService.kt:139-140` — `ACTION_START/STOP` intent strings.
- Manifest `:50` — `DEBUG_CMD` action; `TestHooksReceiver.kt:14-15` doc comment.
- `settings.gradle.kts:15` — `rootProject.name = "zoom-room"`.

*Docs / prose:*
- `README.md` — title, intro ("complete Zoom room"), settings instructions (`:1,3,73`).
- `docs/DESIGN.md:1,9` — v1 design doc; describes acting "like a Zoom Rooms controller"
  (worst phrase in the repo if ever quoted in a review).
- `plans/2026-07-06-tablet-only-zoom-room.md`, `plans/2026-07-08-airplay-cast-to-tv.md`,
  `plans/2026-07-08-playstore-and-oauth.md` — internal, low priority, but README links there
  and the repo is public: sweep product-noun uses.
- `MainActivity.kt:45` comment ("Zoom Room console").

**Keep unchanged — nominative-use whitelist** (factual references to the Zoom platform):
`us.zoom.*` SDK imports + the SDK .aar, `zoom.us`/`api.zoom.us` URLs, "Sign in with Zoom"
(official OAuth wording), "Zoom meeting" / "Zoom Meeting SDK" / "Zoom Marketplace" in prose,
error-code discussions, `legacy/` tree (archived, not in the build), local checkout dir name.

**Consequences to plan around:**
- Changing applicationId = a **new app identity** on the tablet. The old
  `com.bilal.zoomroom` install stays behind → uninstall it (end any live meeting first —
  same hazard the `endMeetingBeforeInstall` guard exists for). SharedPreferences (backend
  host, room name) don't migrate → re-enter once. No published users yet, so this is free —
  which is exactly why it must happen **now**, before Play upload.
- SDK JWT / marketplace credentials are keyed to the Marketplace app, not the package name →
  existing dev JWT keeps working mid-rename.
- GitHub renames auto-redirect old clones/remotes; still update `origin` locally.

**Done looks like.** `grep -ri "zoomroom\|zoom room\|zoom-room" --exclude-dir={build,.cxx,.git,legacy}`
over the repo returns zero product-noun hits (only whitelisted platform references remain);
app builds, installs as `com.bilal.meetingsremote`, full smoke test passes (host, join,
RTSP video, cast, adb hooks); repo renamed; other participants see "Meeting Room".

---

## 5. Execution Steps

Order matters: package/JNI rename (2–4) is one atomic commit so the tree never half-builds.

| #  | Task | Status |
| -- | --- | --- |
| 1  | Confirm naming table (§4) — inherited from playstore plan A1; only new decision is default display name "Meeting Room" (§11 Q1) | completed (this plan) |
| 2  | Gradle identity: `room/build.gradle.kts` `namespace` + `applicationId` → `com.bilal.meetingsremote`; `settings.gradle.kts` `rootProject.name = "meetings-remote"`; fix `endMeetingBeforeInstall` broadcast component + action | not started |
| 3  | Kotlin package move: `git mv room/src/main/java/com/bilal/zoomroom → .../meetingsremote`; update `package`/`import`/FQN lines in all 12 files (incl. `MainActivity.kt:87`, `RoomSdk.kt:64,138`) | not started |
| 4  | JNI: rename all 6 `Java_com_bilal_zoomroom_source_...` symbols in `rtsp_decoder.c` to `Java_com_bilal_meetingsremote_source_...` — same commit as step 3 | not started |
| 5  | Manifest: `android:label="Meetings Remote"`; `DEBUG_CMD` action → new package; update `TestHooksReceiver.kt` doc comment | not started |
| 6  | User-visible strings: `MainActivity.kt` title "Meetings Remote", display-name defaults → "Meeting Room" (`:373,425,503`), comment `:45`; `RoomBackend.kt:26` default → "Meeting Room" | not started |
| 7  | Backend: `token_server.py` docstring, `:90` default name → "Meeting Room", `:193` → "Return to the Meetings Remote app" | not started |
| 8  | Tools: `airplay_mirror.py:196` sender name → "Meetings Remote" | not started |
| 9  | Docs sweep: README title/intro/instructions; `docs/DESIGN.md` header + drop the "Zoom Rooms controller" simile (describe as "meeting room controller"); product-noun uses in `plans/*.md` | not started |
| 10 | Device migration: `adb shell am broadcast` leave (old action) if in meeting → uninstall `com.bilal.zoomroom` → `./gradlew :room:installDebug` → re-enter backend host + room name in settings | not started |
| 11 | Smoke test (§12): build, RTSP video (proves JNI), adb hooks with **new** action, host+join cycle vs Mac web participant, cast to TV, backend OAuth page text | not started |
| 12 | GitHub: rename repo → `meetings-remote`; `git remote set-url origin git@github.com:bilalib1/meetings-remote.git`; verify push | not started |
| 13 | Final grep gate (§12 acceptance) + update memory files / playstore plan cross-references | not started |

---

## 6. Out of Scope / Non-Goals

- **No erasing "Zoom" as a platform reference** — SDK imports, API URLs, "Sign in with
  Zoom", prose about Zoom meetings all stay. The risk is product naming, not the word.
- `legacy/` tree (archived, excluded from build) — including `com.bilal.zoomremote`.
- Local checkout directory name `~/code/zoom_custom_android` (harmless; rename later if desired).
- Listing copy, icon, screenshots, privacy policy — playstore plan territory.
- Any functional change. This is a pure rename; every diff line is naming.

---

## 7. Architecture

No architectural change. One identity note: three name spaces move independently —

```
Play Store identity   applicationId  com.bilal.meetingsremote   (immutable post-upload)
Zoom identity         Marketplace app "Mobile Remote" + its SDK key/JWT (not package-tied)
In-meeting identity   display name, default "Meeting Room"       (user-editable in settings)
```

The token backend and the Zoom SDK never see the applicationId, so the rename can land
entirely before any Marketplace work starts.

---

## 8. Database Schema

not applicable

---

## 9. Implementation Details

- **Package move mechanics:** `git mv` the directory tree (preserves history), then a
  scripted sed over `room/src` for `com.bilal.zoomroom` → `com.bilal.meetingsremote` (per §3,
  write it as a small script, e.g. `tools/rename_package.sh`, since we may re-run after
  review feedback). Manual review of the diff before commit — no `git add .`.
- **JNI symbol rename is the only silent-failure risk.** `System.loadLibrary` succeeds; the
  first `nativeOpen` call throws `UnsatisfiedLinkError` at runtime. Acceptance therefore
  requires *seeing RTSP frames*, not just a green build.
- **`endMeetingBeforeInstall` chicken-and-egg:** after step 2, the gradle task broadcasts to
  the *new* component, but the tablet still runs the *old* app. Step 10 handles it manually:
  send the leave broadcast with the old action before uninstalling
  (`adb shell am broadcast -n com.bilal.zoomroom/.TestHooksReceiver -a com.bilal.zoomroom.DEBUG_CMD --es cmd leave`).
- **Grep gate command** (also the reviewer's-eye check):
  `grep -rin "zoomroom\|zoom room\|zoom-room\|zoom_room" --exclude-dir=build --exclude-dir=.cxx --exclude-dir=.git --exclude-dir=legacy .`
  → expected zero hits after step 13 (whitelisted platform references don't match these patterns).
- **Positioning language for docs** (reused later in listings): "a meeting room appliance /
  remote for your Zoom account", "works with Zoom meetings". Never: "Zoom Rooms
  alternative/replacement/clone", no price comparisons with Zoom Rooms.

---

## 10. Data Snippets

not applicable

---

## 11. Open Questions / Decisions Needed

- **Q1 — default in-meeting display name.** Proposed "Meeting Room" (what participants see
  when the owner hasn't set a room name; "Meetings Remote" reads like an app, not a room).
  Decide at step 6; one string in 4 places.
- **Q2 — rename GitHub repo now or at publish time?** Proposed now (step 12): redirects make
  it free today; later the URL is baked into listings/support links.
- **Q3 — keep `:room` Gradle module name?** Proposed keep: "room" is generic (a physical
  room), not a Zoom term, and renaming the module churns paths for zero review benefit.

---

## 12. Test Plan / Acceptance Criteria

All on-device per [[tablet-only-no-mac-zoom]] (Mac = build + fake participant via web client only).

1. `./gradlew :room:assembleDebug` green.
2. Old app removed; `adb shell pm list packages | grep bilal` shows only `com.bilal.meetingsremote`.
3. Launcher shows **Meetings Remote**; main screen title matches; settings show "Meeting Room" default.
4. **RTSP source renders live video** in self-preview (proves JNI symbol rename) with
   `room/scripts` test stream / mediamtx on the Mac.
5. Host meeting from tablet; join from Mac **web client**; participant list shows "Meeting Room".
6. adb hooks work with new action: `adb shell am broadcast -n com.bilal.meetingsremote/.TestHooksReceiver -a com.bilal.meetingsremote.DEBUG_CMD --es cmd leave` ends the meeting; `endMeetingBeforeInstall` gradle guard fires correctly.
7. Cast to TV starts/stops; Apple TV pairing screen shows "Meetings Remote" (if prototype path used).
8. Backend: `/host-zak` fallback name and OAuth landing page show new wording.
9. Grep gate (§9) returns zero product-noun hits outside `legacy/`.
10. `git push` to renamed remote succeeds.

---

## 13. References / Links

- `plans/2026-07-08-playstore-and-oauth.md` §5 A1 (naming decision), A5 (Marketplace review), §9.5–9.6 (policy facts).
- Zoom Marketplace ToU §7.2 (no "Zoom" in third-party app names); Zoom API/SDK non-compete clause — re-verify exact current wording at submission time.
- GitHub repo: `git@github.com:bilalib1/zoom-room-controller.git` → to be renamed.

---

## 14. File List

Changing:
- `room/build.gradle.kts` — namespace, applicationId, endMeetingBeforeInstall
- `settings.gradle.kts` — rootProject.name
- `room/src/main/AndroidManifest.xml` — label, DEBUG_CMD action
- `room/src/main/java/com/bilal/zoomroom/**` → `.../meetingsremote/**` (12 .kt files; package/import/FQN lines; strings in `MainActivity.kt`, `RoomBackend.kt`; comments in `MainActivity.kt`, `TestHooksReceiver.kt`; ACTION strings in `AirPlayService.kt`)
- `room/src/main/cpp/rtsp_decoder.c` — 6 JNI symbols
- `backend/token_server.py` — docstring, default name, OAuth page
- `tools/airplay_proto/airplay_mirror.py` — mDNS sender name
- `README.md`, `docs/DESIGN.md`, `plans/2026-07-06-tablet-only-zoom-room.md`, `plans/2026-07-08-airplay-cast-to-tv.md`, `plans/2026-07-08-playstore-and-oauth.md` — prose sweep
- New: `tools/rename_package.sh` (scripted, re-runnable rename)

Not changing: `legacy/**`, `room/libs/**` (Zoom SDK aar), `room/src/main/res/**` (icons are
neutral; no strings.xml exists — all strings are inline Kotlin).

---

## 15. Long Jobs / Backfill

not applicable

---

## 16. Rollback Plan

Pure-rename commits, atomic per step → `git revert` restores any layer independently. Device
rollback = uninstall new package, reinstall old APK (keep one `com.bilal.zoomroom` debug APK
in `~/code/zoom_custom_android/room/build/outputs/apk/debug/` or re-checkout + build the
pre-rename tag). Tag `pre-rename` on main before step 2. GitHub repo rename is reversible
and auto-redirects both ways.

---

## 17. Postmortems

not applicable

---

## 18. Project History

- 2026-07-10 — Plan created: full grep inventory of "Zoom Room" surfaces (user-visible,
  in-code, docs); adopted naming from playstore plan A1; sequenced atomic rename with JNI
  lockstep and device migration.
