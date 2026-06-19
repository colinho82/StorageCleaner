# StorageCleaner v6.0-lite

A native Android app, built and compiled entirely from a phone via Termux + GitHub Actions
(no Android Studio / PC required). Finds duplicate and near-duplicate photos, documents,
and videos; recommends which copy to keep; and safely archives the rest with full
restore support.

This is the **v6-lite** build — see [`SCOPE.md`](#scope-v6-lite-vs-full-v60-prs) below for
exactly what's included vs. deferred from the full v6.0 PRS.

---

## 🚀 Build (GitHub Actions)

Every push to any branch triggers `.github/workflows/build.yml`, which:
1. Sets up Java 17 + Gradle 8.2 (manually installed — avoids the Gradle 9.x /
   Kotlin 1.9 `HasConvention` incompatibility seen with `gradle/actions/setup-gradle`)
2. Generates the Gradle wrapper
3. Installs Android SDK platform 34 + build-tools 34.0.0 (using the runner's
   pre-installed SDK at `$ANDROID_HOME` — do **not** override `ANDROID_HOME`/`ANDROID_SDK_ROOT`)
4. Runs `./gradlew assembleDebug --stacktrace --no-daemon`
5. Uploads the resulting APK as a workflow artifact (`StorageCleaner-debug-apk`)

Download the APK from the **Actions** tab → latest run → **Artifacts**.

---

## 📱 Replacing repo contents from Termux

```bash
cd ~/StorageCleaner          # your existing clone
git pull

# remove everything except .git
find . -mindepth 1 -maxdepth 1 ! -name '.git' -exec rm -rf {} +

# copy the new project files in (adjust source path as needed)
cp -r /path/to/SC/. .

git add -A
git commit -m "Rebuild: v6.0-lite"
git push
```

Then check the **Actions** tab for the new build.

---

## 🏗️ Architecture

- **Language**: Kotlin
- **UI**: Views + XML layouts, ViewBinding, Navigation Component (Safe Args)
- **DI**: Hilt
- **Persistence**: Room (cached scan results, archive history, protected files,
  ignore rules, scan sessions)
- **Concurrency**: Kotlin Coroutines + Flow / StateFlow
- **Images**: Glide
- **Detection**: pure-Kotlin perceptual hashing (pHash + DCT) for images,
  MD5 + Jaccard shingles for documents, MD5 for videos — **no TensorFlow Lite,
  no native libraries**

### Module map
```
data/
  model/Models.kt          — enums, ScannedFile, DuplicateGroup, Room entities
  db/Database.kt           — DAOs + StorageCleanerDatabase (Room, v3)
  repository/StorageRepository.kt — single entry point used by all ViewModels
di/AppModule.kt             — Hilt providers (DB + DAOs)
util/
  StorageScanner.kt          — scan engine: pHash grouping, doc similarity, confidence scoring
  RecommendationEngine.kt    — Smart "KEEP" recommendation (§6)
  FileActionManager.kt        — copy→verify→trash archive workflow + restore (§10-11)
  DuplicateCache.kt           — persists last scan results across restarts
  FolderBrowser.kt            — folder discovery + classification (§3.2)
  StorageStatsHelper.kt       — Storage Dashboard + Analytics queries (§3.1, §18)
  NotificationHelper.kt       — scan/archive/restore notifications (§16)
ui/
  home/        — Dashboard, Scan History, Storage Analytics
  folders/     — Folder picker (Full / Quick scan)
  duplicates/  — Duplicates Found, Side-by-side Comparison
  preview/     — File Preview
  archive/     — Archive (sessions, restore, remove record)
  settings/    — Detection thresholds, Ignore Rules, Notifications
  protected_files/ — Protected Files management
```

---

## 📋 Scope: v6-lite vs. full v6.0 PRS

The full v6.0 PRS calls for TensorFlow Lite visual embeddings, a Jetpack Compose
rewrite, video keyframe pHash extraction, Storage Access Framework, and
WorkManager-scheduled background scans. Attempting all of that in one pass —
on top of a build pipeline that's only just been stabilized, and debugged
entirely via CI logs and screenshots — would multiply the failure surface
(native library linking, full UI migration, etc.) far beyond what's practical
to fix blind.

**v6-lite keeps the proven Kotlin/Views/Hilt/Room architecture and implements
everything else from the PRS that doesn't require those heavy dependencies:**

| Section | Status |
|---|---|
| §3.1 Storage Dashboard | ✅ Implemented (StatFs + MediaStore aggregates) |
| §3.2 Folder Selection | ✅ MediaStore-based (SAF deferred) |
| §3.3 Scan Modes (Quick/Full/Incremental) | ✅ Quick & Full implemented; Incremental partial |
| §4.1 Image Detection | ✅ pHash + metadata-derived match categories (no TFLite embeddings) |
| §4.2 Document Detection | ✅ MD5 + Jaccard shingle similarity |
| §4.3 Video Detection | ✅ Exact MD5 only (keyframe similarity deferred) |
| §5 Confidence Scoring | ✅ Implemented |
| §6 Smart Recommendation Engine | ✅ Implemented |
| §7 Duplicate Results Screen | ✅ Filters, sort, badges |
| §8 Side-by-side Comparison | ✅ Static comparison (pinch-zoom/swipe deferred) |
| §9 Cleanup Simulator | ✅ Implemented |
| §10-11 Archive + Restore | ✅ Implemented |
| §12 Protected Files | ✅ Implemented |
| §13 Ignore Rules | ✅ Implemented (with presets) |
| §14 Smart Select | ✅ Implemented |
| §15 Scan History | ✅ Implemented |
| §16 Notifications | ✅ Implemented |
| §17 Settings | ✅ Detection thresholds, Ignore Rules, Notifications, Protected Files. Performance/Battery settings deferred |
| §18 Storage Analytics | ✅ Largest Files/Folders (MediaStore-based, no filesystem walk) |
| TensorFlow Lite embeddings | ⏭️ Phase 2 |
| Jetpack Compose | ⏭️ Phase 2 |
| Video keyframe similarity | ⏭️ Phase 2 |
| Storage Access Framework | ⏭️ Phase 2 |
| WorkManager scheduled scans | ⏭️ Phase 2 |
| Battery saver / CPU limits | ⏭️ Phase 2 |

---

## ⚠️ Runtime notes

- **All Files Access (Android 11+)**: the Archive feature copies files into
  `Documents/StorageCleaner/`, a public folder outside the app's sandbox. On
  Android 11+ this requires the "All files access" special permission. The
  **Settings** screen detects this and shows a button to grant it
  (`Settings → Storage Cleaner → Allow access to manage all files`). Without
  it, "Move to Archive" will report files as failed.
- **Notifications (Android 13+)**: requires `POST_NOTIFICATIONS` runtime
  permission — prompted from the Settings screen.
- All processing is on-device and offline; no analytics, no network calls.
