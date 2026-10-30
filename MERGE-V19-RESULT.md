# ChordsInSync — Upstream v1.9 Merge Result

Executed 2026-11-03 (Vaughn approved the start at 12:49 PM EST). Local work only — **nothing was pushed anywhere.**

## Summary

- **Fetch: SUCCESS.** `git fetch origin tag v1.9` worked (the earlier egress block did not recur). v1.9 = `04f64c9f11bd2714d01bbc6da401f146f3d9399f` (2026-10-30, "bug fixes"), from origin `https://github.com/kushagrasinghx/BitChord`.
- **Branch created:** `craddock/v1.9-merge`. `craddock/v1.8.1-base` (877e3f3d) left untouched as the fallback.
- **Rebase: COMPLETE.** All 9 fork commits replayed onto v1.9, plus 3 transparent repair commits (below). Final HEAD: **`5d601929`**.
- **Build: SUCCESSFUL.** Full `assembleDevDebug` — EXIT 0, 26m 26s. APKs in `app/build/outputs/apk/dev/debug/` (arm64-v8a, armeabi-v7a, x86_64, universal).
- **Tests: 1,279 run — all functional tests pass.** Details and the one flaky benchmark under "Tests".

## Per-commit rebase outcome

| # | Fork commit (new HEAD) | Outcome |
|---|---|---|
| 1 | c9d91f62 Local folder view, folder favorites, branding | **Conflicts — judgment resolution, see "Folders"** |
| 2 | 4848d375 Sync engine core | Conflicts in BitChordApplication.kt, MainActivity.kt, SettingsSheet.kt, strings — resolved keep-both (see notes) |
| 3 | 1ba67f03 Local delete for songs and folders | Conflicts in MainActivity.kt, LocalGroups.kt, BrowseActionsMenu.kt, SongActionsMenu.kt — both features kept |
| 4 | 5a852872 Google Drive engine | Clean |
| 5 | cd6ecd34 Syncthing pivot | Conflict in AppSettings.kt — fork rule kept: `exportDownloads` defaults **true**; upstream's `syncDownloadedPlaylists` kept |
| 6 | db9cad42 Remove Dropbox | Clean |
| 7 | c7e6738e WebDAV engine | Clean |
| 8 | 8118d46a SMB engine | Clean |
| 9 | 6e7f1294 Engine-label onBackground fix | Clean |

Notes on commit 2: BitChordApplication now runs BOTH upstream's PlaylistSync.init and the fork's SyncStore.init + SyncScheduler.schedule. MainActivity keeps upstream's new SettingsSection/SettingsChoice/EqualizerPresets branches AND AppRoute.Sync. SettingsSheet's Downloads group carries BOTH upstream's sync-downloaded-playlists row and the fork's Sync engine row; the fork's superseded uncategorised Playback group was dropped in favor of upstream's categorised one.

## ⚠ Folders — the deferred judgment call (needs Vaughn's review)

Upstream v1.9 rebuilt the folders area (directoryPageId naming, folders grouped by directory with a readable parent-path subtitle, directories as BrowseType.PLAYLIST, a PLAYLISTS tab). The fork's favorites feature was built on the old structure. Per the task's fallback instruction, the resolution **keeps the fork's features working on top of upstream's new core**:

- Upstream's grouping/naming/tab structure adopted wholesale.
- Fork favorites layered back: `folderPageId` retained as an alias of `directoryPageId`; `orderFolders(groups, favorites)` (starred first, then upstream order) and `folderPathOf(group)` added to LocalGroups.kt; LocalFolder.kt collects `AppSettings.localFavoriteFolders` and orders the FOLDERS tab with it; the star (FolderFavoriteButton) UI is retained.
- Fork LocalGroupsTest expectations adapted to upstream semantics; the suite passes.

**This is the resolution to judge from — it is reversible, and the alternative (dropping fork favorites for pure upstream) was NOT taken.** Vaughn's theirs-vs-ours call is still owed.

## Repair commits (problems found in verification, fixed transparently)

1. **436772fe — Restore upstream v1.9 strings dropped during rebase.** Honest disclosure: my commit-2 strings union kept only the fork side of each conflict hunk, silently dropping **56 upstream v1.9 keys in each of 16 locale files + 4 keys in values/strings.xml**, and leaving a duplicate `folders` key in the base file. Caught by a key-set audit against the v1.9 tag before any build. Every missing element restored verbatim from v1.9; verified: zero v1.9 keys missing in any strings file, no duplicate names, all XML parses.
2. **6e5be632 — Adapt fork action rows to v1.9 menu structure (compile fix).** First compile failed (8 errors, 2 files): v1.9 split the action sheets into rows-only menus using SfIcons and deleted the `SongActionsPresentation` type the fork rows referenced. Fork rows adapted: presentation param and sheet-era spacer dropped; Delete-folder / Delete-from-device rows use `SfIcons.Trash` like every other delete row; six unused Material imports removed. Recompile: **BUILD SUCCESSFUL**.
3. **5d601929 — Fork strings for the new Korean locale (English placeholders).** Upstream v1.9 added values-ko; the fork never had a Korean catalogue, so all 74 fork-only translatable keys were missing and `LocaleStringsTest[ko]` failed. Keys inserted verbatim from values/strings.xml under a comment marking them **pending Korean translation** (same English fallback Android applies at runtime, made explicit). Real Korean translations remain an open item if Vaughn wants them.

## Tests

- Full suite (`:app:testDevDebugUnitTest`): **1,279 tests**.
  - Run 1: 1 failed (`LocaleStringsTest[ko]` — fixed by 5d601929), 1 skipped.
  - Run 2: ko passes. 1 failed: `QueueShuffleTierTest` **wall-clock benchmark** (asserts shuffle/restore of 1,000 items < 50 ms) — measured 53.3 ms while the VM was saturated by the parallel 26-minute native build; on immediate re-run the *other* direction flipped over (53.6 ms). On an idle re-run of the class: **all 27 tests pass** (shuffle 3.97 ms, restore 4.37 ms). Verdict: flaky upstream timing test on a shared VM, in code **no fork commit touches** — not a merge regression.
  - The 1 skipped test is a `GeniusTest` case gated by JUnit `assumeTrue` — pre-existing upstream behavior.
- Reference point: the v1.8.1 line had 1,172 tests; the merged tree's 1,279 includes upstream v1.9's growth, with all fork tests present and passing.

## Standing fork rules — verified present in the merged tree

- Home gate: uploads stop off-home (SyncEngine isHome checks); manual Sync now does not bypass it.
- Auto-sync only when charging AND on unmetered Wi-Fi (SyncScheduler); per-pair direction controls retained.
- `dav.craddock.club` read-only handling retained (READ_ONLY_REMOTE / DOWNLOAD_ONLY in SyncScreen).
- Downloads land in public Music/BitChord: `exportDownloads` default **true**.
- Engine registry: DEVICE_FOLDERS + WEBDAV + SMB, each with master toggle path via SyncScreen.
- 877e3f3d engine-label fix present (13 onBackground references in SyncScreen). A few multi-line `Text(` calls could not be visually confirmed headlessly — a quick on-device look at the Sync screen is still worthwhile.
- applicationId unchanged: base `com.music.bitchord`, dev flavor `com.dev.bitchord`.

## Build environment note

The stock toolchain scripts (`run-build.sh`) unset the proxy variables; the app's CMake step then cannot download the pinned Oboe 1.11.0 tarball and `assembleDevDebug` fails at `configureCMakeDebug` (unit tests and Kotlin compilation are unaffected, which is why compile-check passed first). The successful build used a proxy-preserving variant run from /tmp — **no toolchain files were modified**. Vaughn's call: update the toolchain scripts to preserve proxy env for CMake, or keep the workaround.

## Repo state at completion

- Branch: `craddock/v1.9-merge`, HEAD `5d601929`.
- Working tree clean except the pre-existing untracked `MERGE-V19-PREP-2026-11-02.md` (and this file).
- `craddock/v1.8.1-base` untouched at 877e3f3d.
- Nothing pushed; no production branch merged; no release APK built (debug APKs only, for verification).

## Decisions Vaughn must make

1. **Folders:** ratify the adopted resolution (upstream core + fork favorites on top) or ask for a different split.
2. **Korean:** leave the 74 fork strings as English placeholders, or commission real Korean translations.
3. **Toolchain:** whether to make the proxy-preserving build the standing script (Oboe fetch).
4. **Next step for the branch:** when/if to push `craddock/v1.9-merge` and promote it — not done, per scope.

## Delivery build (2026-11-05)

- **Tree verified before building:** branch `craddock/v1.9-merge` at `f61c42d6`, clean except the two untracked MERGE-V19-*.md files.
- **Tests (forced fresh run, not up-to-date):** `:app:cleanTestDevDebugUnitTest :app:testDevDebugUnitTest` — **1,279 tests, 0 failures, 0 errors, 1 skipped** (the pre-existing GeniusTest assumeTrue skip). QueueShuffleTierTest passed in the full run this time; no re-run needed.
- **Build:** `assembleDevDebug` via a proxy-preserving variant of run-build.sh run from /tmp (stock script untouched; proxy env kept so the CMake/Oboe fetch path works) — BUILD SUCCESSFUL.
- **aapt (arm64 APK):** package `com.dev.bitchord`, label **"Chords In Sync"**, versionName **1.9**, versionCode **35** (> 29, so it installs over the v1.8.1-based builds).
- **Delivered:** `ChordsInSync-v1.9-merge-arm64-debug.apk` to Google Drive "BitChord Fork" folder (id 12GUMMX8GroNe6ROtPUPsUhfBaWVvb0fD), Drive file id **1yrl6hGbZH-ftvYbkK8LgINHq-Ux7UC6G**, **141,553,129 B**, MD5 **1088b871ac62e18750f1b23b32dcf490** — Drive-reported size + MD5 match local byte-for-byte.
- **Nothing pushed anywhere;** branch and HEAD unchanged after delivery (still `craddock/v1.9-merge` @ `f61c42d6`); no toolchain scripts modified.
