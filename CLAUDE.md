# Arlo Reading App

## Overview

Arlo is an Android reading assistance app that helps users capture book pages with their camera, extract text using OCR, and read the content aloud with text-to-speech. The app allows users to organize scanned pages into books for later reading.

**Key Features:**
- Camera capture with CameraX (two-step flow: cover + pages)
- OCR text extraction via Claude Haiku API with sentence-level parsing
- Sentence-by-sentence reading mode with large fonts (28sp)
- Text-to-speech with auto-advance between sentences
- Touch-to-read from any position
- Book/page organization with Room database
- Reading progress tracking and restoration (page + sentence index)
- Secure API key storage via EncryptedSharedPreferences
- Material Design 3 UI with literary-themed styling

## Tech Stack

- **Language:** Kotlin
- **Min SDK:** 26 | **Target SDK:** 34
- **Build:** Gradle 8.13.1, Kotlin 1.9.22
- **UI:** View Binding, Material Components 1.11.0, ViewPager2
- **Camera:** CameraX 1.3.1
- **OCR:** Claude Haiku API (model: claude-3-5-haiku-20241022) via OkHttp 4.12.0
- **Database:** Room 2.6.1 (version 3 with migrations)
- **Security:** EncryptedSharedPreferences (security-crypto 1.1.0-alpha06)
- **JSON:** Gson 2.10.1
- **Image Loading:** Coil 2.5.0
- **Architecture:** MVVM with ViewModels, LiveData, StateFlow, and Flow

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
├── tts/
│   └── TTSService.kt         # TTS with multi-engine fallback
├── ApiKeyManager.kt          # EncryptedSharedPreferences for API key
├── MainActivity.kt           # Single Activity host with API key dialog
├── ArloApplication.kt        # App singleton with lazy init
├── LibraryFragment.kt        # 2-column book grid
├── LibraryViewModel.kt       # Books with page counts
├── BookWithInfo.kt           # Data class for enriched display
├── ReaderFragment.kt         # ViewPager2 for pages + sentence mode entry
├── ReaderViewModel.kt        # Pages & reading position
├── PageFragment.kt           # Single page with touch-to-read
├── SentenceReaderFragment.kt # Sentence-by-sentence reading with large fonts
├── SentenceReaderViewModel.kt # Sentence navigation + TTS control
├── CameraFragment.kt         # Camera with two capture modes
├── CameraViewModel.kt        # Claude OCR + book creation logic
├── AddPageFragment.kt        # Reusable add page button
├── BookAdapter.kt            # ListAdapter with DiffUtil
└── PageAdapter.kt            # FragmentStateAdapter for ViewPager2
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
   - Card tap → ReaderFragment

2. **CameraFragment** (Two modes)
   - `MODE_NEW_BOOK`: Cover capture → Claude title extraction → page capture
   - `MODE_ADD_PAGES`: Direct page capture for existing book
   - Creates thumbnails (400px width, 85% JPEG)
   - Shows loading overlay during OCR ("Extracting text with AI...")

3. **ReaderFragment** (Page browser)
   - ViewPager2 horizontal page navigation
   - Controls: Back, Add Page, Prev/Next, Play/Pause/Stop/Restart, Recapture
   - **Sentence Mode** button → SentenceReaderFragment
   - Auto-restores last read position

4. **SentenceReaderFragment** (Large font reading)
   - Displays one sentence at a time (28sp serif font)
   - Controls: Prev/Next sentence, Play/Pause TTS
   - Auto-advances TTS with sentence-level callbacks
   - Shows "Scan more pages" banner at end of content
   - Orange tint for incomplete sentences (cut off mid-thought)

5. **PageFragment** (Single page)
   - Displays OCR text with touch-to-read
   - Word highlighting during TTS (#FFE8A0)

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
- **Multi-engine fallback:** Google TTS → SVOX Pico → System default
- **Language fallback:** US English → UK English → Default locale
- `UtteranceProgressListener.onRangeStart()` for word highlighting
- `setOnRangeStartListener()` / `setOnSpeechDoneListener()` callbacks
- `speak(text, onComplete)` overload for sentence-level auto-advance

### Data Flow
1. Camera captures image → saved to `context.filesDir`
2. ClaudeOCRService extracts sentences as JSON from image
3. CameraViewModel creates Book + Page entities with sentencesJson
4. Room DAO persists via Repository (handles sentence continuation)
5. Flow emits updates → LiveData/StateFlow → UI refresh

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

## Permissions

- `android.permission.CAMERA` - Required for page capture
- `android.permission.INTERNET` - Required for Claude API calls

## Development Notes

- View Binding enabled in `build.gradle.kts`
- KAPT used for Room annotation processing
- Navigation via FragmentManager transactions (not Navigation component)
- Images stored in app's internal storage
- Database: "arlo_database" with export schema disabled
- TTS initialized eagerly in ArloApplication