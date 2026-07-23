# Changelog - Serecordenity

## [1.2.0] - 2026-07-23

### Added
- **Voice Record Pause & Resume**: Added `pauseRecording()` and `resumeRecording()` engine functions and updated UI with interactive `Pause` / `Resume` / `Stop & Save` button controls.
- **Dynamic Recording Badges**: Real-time status badges (`REC 🔴`, `PAUSED`, `IDLE`) reflecting active recording state.
- **Hardware Microphone Failure Repair**:
  - Multi-source fallback algorithm (`MIC` -> `DEFAULT` -> `VOICE_RECOGNITION` -> `VOICE_COMMUNICATION` -> `CAMCORDER`) and sample rate fallbacks (`44100`, `48000`, `16000`).
  - Strict `AudioRecord.STATE_INITIALIZED` verification before starting capture stream.
  - Fixed 16-bit PCM little-endian byte decoding in software gain calculations (`(high shl 8) or low`).
- **Hamburger Settings Menu (☰)**:
  - Top app bar menu for configuring file storage paths (`Music/` subfolder, `Documents/` subfolder).
  - Hardware recording default preferences (preferred mic source, sample rate).
  - App behavior toggles (Offline Notes auto-save default, Phish.in API key, clear cache).
- **Persistent Preferences**: Standardized all storage paths and recorder settings across `SharedPreferences` (`serenity_app_prefs`).

### Fixed
- **Compose `ACTION_HOVER_EXIT` Crash**: Upgraded Compose BOM to `2024.06.00` and `kotlinCompilerExtensionVersion` to `1.5.14` to resolve upstream AndroidComposeView hover exit event exception.
- **Pointer Event Leaks**: Replaced `combinedClickable` long-press gesture detectors with explicit `🗑️` delete buttons in document and voice file lists.
- **Windows Build File Lock Fix**: Added `org.gradle.vfs.watch=false` to [gradle.properties](file:///c:/Users/ccrg6/Desktop/Desktop/Hub/Serecordenity/gradle.properties) to prevent Windows File Watcher daemon lock crashes (`AccessDeniedException` on `merged_res_blame_folder`).
- **Kotlin Compiler Upgrade**: Updated Kotlin Gradle plugin version to `1.9.24` in [build.gradle.kts](file:///c:/Users/ccrg6/Desktop/Desktop/Hub/Serecordenity/build.gradle.kts).
- **Audio Engine AudioRecord & MediaCodec Repair**:
  - Implemented monotonic microsecond presentation timestamps (`presentationTimeUs`) for MediaCodec.
  - Matched MediaCodec input sample rate to hardware-initialized `actualSampleRate`.
  - Added native direct WAV (PCM RAW) file writer with 44-byte RIFF header generation.
- **Unified Engine Signatures**: Standardized `recordWavFile` signature with `recordMediaCodec` (`outputFile, config, bufferSize, onDbPeak`), resolving Kotlin type mismatch build failure.
- **Instant File List Auto-Refresh**:
  - Dispatched `onSaved` file refresh callbacks onto `Dispatchers.Main` UI thread so Compose updates instantly after recording.
  - Added auto-refresh when opening/expanding the recordings or documents lookup menus.
  - Added explicit `🔄 Refresh` action buttons to lookup headers for instant manual scanning.

## [1.1.0] - 2026-07-23

### Added
- Dedicated **Offline Notes Editor** tab in `MainActivity.kt`.
- UI layout with `titlehere.formatdropdown` title and extension selection (`.txt`, `.md`, `.html`, `.pdf`).
- Configurable save folder setting (defaults to `Documents/SerenityNotes`, editable via `SharedPreferences`).
- Dual save behavior: manual "Save Note" button + toggleable auto-save.
- Rich text formatting controls: Bold toggle (B), Italic toggle (*I*), Font Family picker (SansSerif, Serif, Monospace, Cursive), and Font Size picker (12sp-28sp).
- Native PDF rendering support using `android.graphics.pdf.PdfDocument`.
- Updated `targetSdk` to 36 for explicit **Android 16 / Samsung One UI 8** compatibility.
- Created [gradle.properties](file:///c:/Users/ccrg6/Desktop/Desktop/Hub/Serecordenity/gradle.properties) with `android.useAndroidX=true`, `android.enableJetifier=false`, and `android.sync.suppressAgpWarnings=UNSUPPORTED_PROJECT_OPTION_USE,DEPRECATED_DSL` to suppress AGP build warnings.
- Resolved 6 user requested items in [MainActivity.kt](file:///c:/Users/ccrg6/Desktop/Desktop/Hub/Serecordenity/app/src/main/java/com/ghostheart/serenity/recorder/MainActivity.kt):
  1. **Fixed margin spacing**: switched to `WindowInsets.systemBars` in `MainTabScreen` and added 32dp bottom Spacers to prevent navigation bar cutoffs.
  2. **Fixed Mic failed error**: added automatic AAC hardware encoder fallback when target codec (such as MP3 or PCM) lacks native hardware encoder support.
  3. **Replaced delete button with preview**: replaced `🗑️` button with `▶ Preview` (audio player) and `👁️ Preview` (text dialog) buttons in lookup lists. Kept deletion on long-click hold.
  4. **Fixed Phish.in lookup**: added `URLEncoder.encode` parameter handling and proper `User-Agent` headers.
  5. **Fixed Note lookup**: expanded `refreshNotesList` to scan public Documents, app external files Documents, and internal app files.
  6. **Added Fonts**: added `ComicSans`, `Arial`, and `TimesNewRoman` font family options and font mapping.
- Removed unused `val mp = ` variable declaration in `AudioStudioScreen` preview button onClick handler to resolve compiler warning.
- Resolved top-level class scoping and explicit lambda parameter type inferencing in [MainActivity.kt](file:///c:/Users/ccrg6/Desktop/Desktop/Hub/Serecordenity/app/src/main/java/com/ghostheart/serenity/recorder/MainActivity.kt):
  - Fixed missing closing brace in `PhishEngineScreen`.
  - Restored `OfflineNotesScreen`, `saveNoteToFile`, `ResumablePhishDownloader`, and `NativeAudioRecorderEngine` to top-level definitions, resolving all 12 unresolved reference compiler errors.
  - Added explicit lambda parameter types (`BluetoothCodecType`, `Float`, `Uri`, `String`, `ResumableDownloadState`) to resolve type inference compiler errors.
- Fixed Mic `0xfffffffe (NAME_NOT_FOUND)` encoder initialization crash in `recordMediaCodec` by wrapping `createEncoderByType` in try-catch with safe AAC hardware encoder fallback.
- Fixed Kotlin errors in [MainActivity.kt](file:///c:/Users/ccrg6/Desktop/Desktop/Hub/Serecordenity/app/src/main/java/com/ghostheart/serenity/recorder/MainActivity.kt): replaced non-composable `.let` block with null-check `if (savedAudioUri != null)` and added `as android.os.Parcelable` cast for `Intent.EXTRA_STREAM` overload resolution.
- Enhanced **Filename & Extension selection boxes** with high-visibility cyan outlines (`0xFF38BDF8`) and explicit `Ext: .$format ▾` picker buttons.
- Added comprehensive [README.md](file:///c:/Users/ccrg6/Desktop/Desktop/Hub/Serecordenity/README.md) usage documentation.

## [1.0.0] - 2026-07-23

### Added
- Initial standard Android Studio Gradle project structure (`settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`).
- `MainActivity.kt` containing voice recorder with multi-codec encoding (AAC, FLAC, OPUS, AMR-WB, PCM/WAV) and mic software gain (+0dB to +24dB).
- Bluetooth headset codec auto-detection (SBC, LDAC, aptX, AAC).
- Phish.in ultra-resilient low-bandwidth packet fetcher using 32KB HTTP Range slices with automatic retry/backoff on network drop.
- Local on-device audio transcoder (MediaExtractor + MediaCodec + MediaMuxer) compressing downloaded streams down to 32kbps / 64kbps AAC.
- Phish.in API key configuration saved in `SharedPreferences`.
- Direct file export to `Music/SerenityAudio` via `MediaStore` and `FileProvider` share intent.
