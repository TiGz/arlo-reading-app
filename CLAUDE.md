# Arlo Reading App

## Overview

Arlo is an Android reading assistance app that helps users capture book pages with their camera, extract text using OCR, and read the content aloud with text-to-speech. The app allows users to organize scanned pages into books for later reading.

**Key Features:**
- Camera capture with CameraX (two-step flow: cover + pages)
- OCR text extraction via Claude Haiku API with sentence-level parsing
- Background OCR queue with retry logic and sentence continuation
- Sentence-by-sentence reading mode (28sp fonts)
- Kokoro TTS integration with word-level highlighting and pre-caching
- Collaborative reading mode (TTS reads, child speaks last word)
- Speech recognition with Google Speech Services
- Book/page organization with Room database
- Reading progress tracking and restoration (page + sentence index)
- Secure API key storage via EncryptedSharedPreferences
- Material Design 3 UI with literary-themed styling

## Tech Stack

- **Language:** Kotlin
- **Min SDK:** 26 | **Target SDK:** 34
- **Build:** Gradle 8.13.1, Kotlin 1.9.22
- **UI:** View Binding, Material Components 1.11.0
- **Camera:** CameraX 1.3.1
- **OCR:** Claude Haiku API (model: claude-3-5-haiku-20241022) via OkHttp 4.12.0
- **Database:** Room 2.6.1 (version 3 with migrations)
- **Security:** EncryptedSharedPreferences (security-crypto 1.1.0-alpha06)
- **JSON:** Gson 2.10.1
- **Image Loading:** Coil 2.5.0
- **Architecture:** MVVM with ViewModels, LiveData, StateFlow, and Flow
- **TTS:** Kokoro TTS server (Docker) with Android TTS fallback
- **Speech Recognition:** Google Speech Services (SpeechRecognizer)
- **Audio:** ExoPlayer for Kokoro audio, SoundPool for feedback sounds

## Project Structure

```
app/src/main/java/com/example/arlo/
├── data/
│   ├── AppDatabase.kt        # Room database v3 with migrations
│   ├── Book.kt               # Book entity with cover path + sentence index
│   ├── Page.kt               # Page entity with sentencesJson + continuation flag
│   ├── BookDao.kt            # 15+ query methods for CRUD
│   ├── BookRepository.kt     # Repository with sentence handling
│   ├── SentenceData.kt       # Data class for sentence parsing
│   └── SentenceListConverter.kt  # Room TypeConverter for JSON
├── ml/
│   └── ClaudeOCRService.kt   # Claude Haiku API OCR with retry logic
├── ocr/
│   └── OCRQueueManager.kt    # Background OCR queue with retry + continuation
├── speech/
│   ├── SpeechSetupActivity.kt   # First-launch setup for Fire tablets
│   └── SpeechSetupManager.kt    # Speech recognition diagnostics
├── tts/
│   ├── TTSService.kt         # Kokoro + Android TTS with word highlighting
│   ├── TTSPreferences.kt     # Speech rate, voice, collaborative mode prefs
│   └── TTSCacheManager.kt    # Pre-cache Kokoro audio for sentences
├── ApiKeyManager.kt          # EncryptedSharedPreferences for API key
├── MainActivity.kt           # Single Activity host with API key dialog
├── ArloApplication.kt        # App singleton with lazy init
├── LibraryFragment.kt        # 2-column book grid
├── LibraryViewModel.kt       # Books with page counts
├── BookWithInfo.kt           # Data class for enriched display
├── UnifiedReaderFragment.kt  # Sentence-by-sentence reader with collaborative mode
├── UnifiedReaderViewModel.kt # Reader state, TTS, speech recognition, audio feedback
├── CameraFragment.kt         # Camera with two capture modes
├── CameraViewModel.kt        # Claude OCR + book creation logic
├── AddPageFragment.kt        # Reusable add page button
└── BookAdapter.kt            # ListAdapter with DiffUtil
```

## Database Schema

**Database Version:** 3

**Books Table:**
- `id` (Long, PK, auto-generate)
- `title` (String)
- `createdAt` (Long, timestamp)
- `lastReadPageId` (Long?, nullable) - legacy
- `coverImagePath` (String?, nullable) - thumbnail path
- `lastReadPageNumber` (Int) - current page position
- `lastReadSentenceIndex` (Int) - current sentence within page

**Pages Table:**
- `id` (Long, PK, auto-generate)
- `bookId` (Long, FK -> Books.id, CASCADE delete)
- `text` (String) - full OCR text (fallback)
- `imagePath` (String) - URI to captured image
- `pageNumber` (Int)
- `sentencesJson` (String?, nullable) - JSON array of SentenceData
- `lastSentenceComplete` (Boolean) - for sentence continuation across pages
- `processingStatus` (String) - PENDING | PROCESSING | COMPLETED | FAILED
- `retryCount` (Int) - number of OCR retry attempts
- `lastError` (String?, nullable) - error message from last failure
- `detectedPageNumber` (Int?, nullable) - page number detected by OCR
- Index on `bookId` for query performance

**SentenceData (JSON):**
```json
{"text": "Sentence content.", "isComplete": true}
```

## Running the App

**IMPORTANT:** Always debug via USB-connected Fire Tablet. Do NOT use the emulator.

### Debug on Fire Tablet:

1. **Connect Fire Tablet via USB** (ensure ADB debugging enabled in settings)

2. **Build and install:**
   ```bash
   ./gradlew installDebug
   ```

3. **Launch:**
   ```bash
   ~/Library/Android/sdk/platform-tools/adb shell am start -n com.example.arlo/.MainActivity
   ```

4. **Check logs:**
   ```bash
   ~/Library/Android/sdk/platform-tools/adb logcat | grep -E "(ClaudeOCR|CameraFragment|TTSService)"
   ```

## Navigation Flow

1. **LibraryFragment** (Home)
   - 2-column grid of books with cover thumbnails
   - Shows page count and reading progress overlay
   - FAB → CameraFragment (MODE_NEW_BOOK)
   - Card tap → UnifiedReaderFragment

2. **CameraFragment** (Two modes)
   - `MODE_NEW_BOOK`: Cover capture → Claude title extraction → page capture
   - `MODE_ADD_PAGES`: Direct page capture for existing book
   - Creates thumbnails (400px width, 85% JPEG)
   - Shows loading overlay during OCR ("Extracting text with AI...")

3. **UnifiedReaderFragment** (Sentence reader)
   - One sentence at a time (28sp serif font)
   - **Collaborative reading**: TTS reads all but last word, child speaks it
   - Word-level highlighting during TTS playback
   - Controls: Prev/Next sentence, Play/Pause, Mic toggle
   - Shows pending OCR count indicator
   - Settings: Speech rate slider, voice selector, auto-advance toggle
   - Auto-restores last read position (page + sentence)

4. **SpeechSetupActivity** (First launch)
   - Fire tablet speech recognition setup wizard
   - Checks: Google app installed, permissions granted, speech test
   - Guides user through enabling speech recognition
   - Can be skipped but collaborative mode won't work

## Key Implementation Details

### Claude OCR Service
- **Model:** claude-3-5-haiku-20241022
- **Endpoint:** POST https://api.anthropic.com/v1/messages
- **Image handling:** Resize to max 1500px, compress to 85% JPEG, base64 encode
- **Retry logic:** 3 attempts with exponential backoff (1s, 2s, 3s)
- **Error handling:** InvalidApiKeyException on 401, rate limit wait on 429
- **Prompt:** Extracts sentences as JSON with `isComplete` flag for continuation

### API Key Management
- Stored via EncryptedSharedPreferences (AES-256-GCM)
- First launch shows API key dialog (cannot dismiss without key)
- Validates key starts with "sk-ant-"
- "Get Key" button opens https://console.anthropic.com/settings/keys
- Invalid key (401) triggers re-entry dialog

### TTS Service
- **Kokoro TTS (primary):** High-quality neural voices via Docker server
  - Voices: `bf_emma`, `bf_isabella`, `bm_lewis`, `bm_george` (British)
  - Word-level timestamps for precise highlighting
  - Audio playback via ExoPlayer
  - Pre-caching support for offline reading
- **Android TTS (fallback):** Google TTS → SVOX Pico → System default
- **Language fallback:** US English → UK English → Default locale
- `setOnRangeStartListener()` / `setOnSpeechDoneListener()` callbacks
- `speakWithKokoro()` for Kokoro-first with Android fallback

### Kokoro TTS Server
- **Location:** `server/` directory with docker-compose.yml
- **Port:** 8880 (configurable via `KOKORO_SERVER_URL` in local.properties)
- **Endpoints:** `/dev/captioned_speech` (synthesis), `/v1/audio/voices` (list voices)
- **Start:** `docker compose up -d`
- **Test:** `curl http://localhost:8880/health`

### Collaborative Reading Mode
- TTS speaks all but the last word of each sentence
- App listens for child to speak the final word
- **3-state machine:** IDLE → LISTENING → FEEDBACK
- Fuzzy matching: accepts word if spoken text contains it
- **3 retries** before TTS reads the word and continues
- Audio feedback: success ping, error buzz (via SoundPool)
- Purple highlight (#E0B0FF) for the word being listened for

### Speech Recognition
- Uses Google Speech Services (SpeechRecognizer)
- **Fire tablet setup:** Requires Google app with RECORD_AUDIO permission
- Pre-warmed recognizer to reduce startup latency (100ms delay after TTS)
- First-launch setup wizard validates all requirements
- Graceful degradation if unavailable (collaborative mode disabled)

### Data Flow
1. Camera captures image → saved to `context.filesDir`
2. Page queued with PENDING status via OCRQueueManager
3. OCRQueueManager processes queue (one at a time, with retries)
4. ClaudeOCRService extracts sentences as JSON from image
5. Sentence continuation handled (merges incomplete sentences across pages)
6. TTSCacheManager pre-caches Kokoro audio in background
7. Flow emits updates → StateFlow → UI refresh

### Sentence Continuation
- When a page ends mid-sentence, `lastSentenceComplete = false`
- On next page capture, first sentence merges into previous page's last
- `BookRepository.addPageWithSentences()` handles the merge logic
- Only the first sentence is removed from the new page after merging

### Image Storage
- Location: `context.filesDir` (app internal storage)
- Filename: `yyyy-MM-dd-HH-mm-ss-SSS.jpg`
- Thumbnails: 400px width, 85% quality JPEG

## Theme & Styling

- **Primary:** Burgundy (#8B3A3A)
- **Secondary:** Warm Gold (#C4A35A)
- **Background:** Cream (#FAF6F0)
- **Text:** Dark brown (#2D2A26)
- **Highlight:** Warm yellow (#FFE8A0)

Custom styles: `ArloFabStyle`, `ArloBookCard`, `ArloTextAppearance.*`, `ArloButton.*`, `ArloReaderControl`

### OCR Queue Manager
- Background processing with PENDING → PROCESSING → COMPLETED states
- **Retry logic:** 3 attempts with exponential backoff (2s, 4s, 8s)
- Detects missing pages via OCR-detected page numbers
- Handles sentence continuation across page boundaries
- Triggers TTS pre-caching on completion

## Permissions

- `android.permission.CAMERA` - Required for page capture
- `android.permission.INTERNET` - Required for Claude API and Kokoro TTS
- `android.permission.RECORD_AUDIO` - Required for collaborative reading mode

## Development Notes

- View Binding enabled in `build.gradle.kts`
- KAPT used for Room annotation processing
- Navigation via FragmentManager transactions (not Navigation component)
- Images stored in app's internal storage
- Database: "arlo_database" with export schema disabled
- TTS initialized eagerly in ArloApplication
- ExoPlayer used for Kokoro audio playback
- SoundPool used for success/error audio feedback
- BuildConfig.KOKORO_SERVER_URL set via local.properties

## Configuration

**local.properties** (not committed):
```properties
KOKORO_SERVER_URL=http://192.168.x.x:8880
```

## Sound Assets

- `res/raw/success_ping.mp3` - Correct answer feedback
- `res/raw/error_buzz.mp3` - Wrong answer feedback