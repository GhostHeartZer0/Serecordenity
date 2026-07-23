# Serecordenity

Lightweight, secure, zero-dependency Android voice recorder, resilient 64kbps Phish.in stream fetcher, and offline formatted notes manager.

## Compatibility & OS Support
- **Supported Android Versions:** Android 8.0 (API 26) through **Android 16** (API 36 / Samsung One UI 8).
- **Security & Privacy:** 100% native Android framework APIs (`MediaCodec`, `AudioRecord`, `MediaStore`). Zero 3rd-party binary libraries or analytics.
- **APK Size:** ~3MB compiled.

---

## How to Use

### 1. Voice Note Recorder
1. Tap the **Voice Recorder** tab.
2. Select your desired output audio codec:
   - `AAC` (Standard compressed voice)
   - `FLAC` (Lossless audio)
   - `OPUS` (Ultra-low bitrate speech)
   - `AMR_WB` (Wideband voice)
   - `PCM_RAW` (Uncompressed WAV)
3. (Optional) Adjust **Mic Software Boost** slider (0dB to +24dB).
4. Tap **Start Recording**.
5. Tap **Stop & Auto-Save**. Files save automatically to `Music/SerenityAudio`.
6. Tap **Share Voice Note** to send your recording.

---

### 2. Phish.in Low-Bandwidth Streamer
1. Tap the **Phish.in Streamer** tab.
2. (Optional) Enter your Phish.in API key if required by the endpoint.
3. Choose your target low-bandwidth codec: **32 kbps Mono** or **64 kbps AAC**.
4. Type a song title and tap **Search Tracks**.
5. Tap any song result to begin downloading in **32KB HTTP Range packet slices**.
   - Handles network drops with infinite auto-retry and backoff.
   - Transcodes on-device so you can play music even over 2G connections.

---

### 3. Offline Notes Editor
1. Tap the **Offline Notes** tab.
2. Set your note title and select an export format from the dropdown right next to it:
   - `.txt` (Default plain text)
   - `.md` (Markdown)
   - `.html` (HTML rich text)
   - `.pdf` (Formatted PDF document)
3. Use the formatting toolbar:
   - **B** for Bold
   - ***I*** for Italic
   - Font Family selector (*SansSerif*, *Serif*, *Monospace*, *Cursive*)
   - Font Size selector (*12sp* to *28sp*)
4. Notes automatically save to `Documents/SerenityNotes`. You can change the subfolder name or toggle Auto-Save in the top settings card.
5. Tap **Save Note** for immediate manual saving.

---

## How to Build the APK
1. Open this repository directory in **Android Studio**.
2. Connect your Android device or start an emulator.
3. Click **Run > Run 'app'** or build the release binary via terminal:
   ```bash
   ./gradlew assembleRelease
   ```
4. Output APK location: `app/build/outputs/apk/release/app-release.apk`.
