# feat: Collaborative Reading Mode

## Overview

Add an interactive "collaborative reading" mode to the sentence-by-sentence reader where TTS reads all but the last word of each sentence, then the user must speak the final word aloud. The app listens via speech recognition, provides audio/visual feedback, tracks a session score, and handles retries (up to 3 attempts before auto-correction).

**Target Users:** Children learning to read, adults practicing pronunciation
**Scope:** UnifiedReaderFragment sentence mode only

## Problem Statement

Currently, Arlo's sentence reading mode is passive—TTS reads entire sentences while users listen. There's no active participation that would help reinforce reading skills. Collaborative reading transforms passive listening into active practice by requiring users to read the final word of each sentence themselves.

## Proposed Solution

### Core Flow
1. User activates collaborative mode via new toggle button (microphone icon)
2. TTS reads sentence **except the last word**, then stops
3. Last word is highlighted in a special color (purple/blue to distinguish from TTS yellow)
4. Visual indicator shows "Your turn!" with pulsing microphone
5. App listens for user's speech via Android SpeechRecognizer
6. **Success:** Play "ping" sound, flash word green, increment score, auto-advance
7. **Failure:** Play "buzz" sound, flash word red, show "Try again" (up to 3 attempts)
8. **3 failures:** TTS reads the correct word, then auto-advances

### Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Speech Recognition | Android `SpeechRecognizer` with offline fallback | Built-in, no external deps, privacy-friendly |
| Word Matching | Case-insensitive, strip punctuation, 80% Levenshtein threshold | Forgiving for children learning to read |
| Score Persistence | Session-only (ephemeral) | Keep it simple, avoid DB migration |
| Mode Toggle | New microphone button in sentence mode top bar | Discoverable, consistent with existing controls |
| Audio Feedback | SoundPool with short .ogg files | Low latency, ideal for instant feedback |

## Technical Approach

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    UnifiedReaderFragment                     │
│  ┌─────────────────────────────────────────────────────┐    │
│  │              UnifiedReaderViewModel                  │    │
│  │  ┌─────────────────────────────────────────────┐    │    │
│  │  │            ReaderState                       │    │    │
│  │  │  + collaborativeMode: Boolean                │    │    │
│  │  │  + sessionScore: Int                         │    │    │
│  │  │  + currentAttempt: Int (1-3)                │    │    │
│  │  │  + collaborativeState: CollaborativeState   │    │    │
│  │  │  + lastWord: String?                         │    │    │
│  │  │  + lastWordRange: Pair<Int, Int>?           │    │    │
│  │  └─────────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
           │                    │                    │
           ▼                    ▼                    ▼
┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
│   TTSService     │ │ SpeechRecognition│ │ AudioFeedback    │
│   (existing)     │ │ Service (new)    │ │ Service (new)    │
│                  │ │                  │ │                  │
│ + speakPartial() │ │ + startListening │ │ + playSuccess()  │
│                  │ │ + stopListening  │ │ + playError()    │
│                  │ │ + onResult       │ │                  │
└──────────────────┘ └──────────────────┘ └──────────────────┘
```

### New Files to Create

```
app/src/main/java/com/example/arlo/
├── speech/
│   └── SpeechRecognitionService.kt   # Wraps Android SpeechRecognizer
├── audio/
│   └── AudioFeedbackService.kt       # SoundPool for success/error sounds
└── (existing files to modify below)

app/src/main/res/
├── raw/
│   ├── success_ping.ogg              # ~0.3s pleasant chime
│   └── error_buzz.ogg                # ~0.3s gentle buzz
└── drawable/
    ├── ic_mic.xml                    # Microphone icon for toggle
    └── ic_mic_active.xml             # Pulsing mic indicator
```

### State Machine

```
enum class CollaborativeState {
    IDLE,                    // Collaborative mode on, waiting for play
    TTS_SPEAKING,            // Reading sentence minus last word
    AWAITING_SPEECH,         // Last word highlighted, mic ready
    LISTENING,               // Actively recording user speech
    PROCESSING,              // Evaluating speech recognition result
    SUCCESS_FEEDBACK,        // Showing success animation
    FAILURE_FEEDBACK,        // Showing failure animation
    READING_CORRECTION       // TTS reading correct word after 3 failures
}
```

### Implementation Phases

#### Phase 1: Infrastructure (Foundation)

**Tasks:**
- [ ] Add `RECORD_AUDIO` permission to AndroidManifest.xml
- [ ] Create `SpeechRecognitionService.kt` wrapping Android SpeechRecognizer
- [ ] Create `AudioFeedbackService.kt` with SoundPool
- [ ] Add sound effect files to `res/raw/`
- [ ] Add microphone icons to `res/drawable/`
- [ ] Implement runtime permission request flow in UnifiedReaderFragment

**Files:**
- `app/src/main/AndroidManifest.xml` (modify)
- `app/src/main/java/com/example/arlo/speech/SpeechRecognitionService.kt` (new)
- `app/src/main/java/com/example/arlo/audio/AudioFeedbackService.kt` (new)
- `app/src/main/res/raw/success_ping.ogg` (new)
- `app/src/main/res/raw/error_buzz.ogg` (new)
- `app/src/main/res/drawable/ic_mic.xml` (new)

**Success Criteria:**
- SpeechRecognizer can be initialized and destroyed without crashes
- Sound effects play with < 100ms latency
- Permission request shows rationale and handles denial gracefully

#### Phase 2: State Management

**Tasks:**
- [ ] Extend `ReaderState` with collaborative mode properties
- [ ] Add `CollaborativeState` enum
- [ ] Implement word extraction logic (last word parsing)
- [ ] Add word matching algorithm (Levenshtein distance)
- [ ] Wire up state transitions in ViewModel

**Files:**
- `app/src/main/java/com/example/arlo/UnifiedReaderViewModel.kt` (modify)

**Key Code - Word Extraction:**
```kotlin
// UnifiedReaderViewModel.kt
private fun extractLastWord(sentence: String): Pair<String, IntRange> {
    val trimmed = sentence.trim()
    val lastSpaceIndex = trimmed.lastIndexOf(' ')

    return if (lastSpaceIndex == -1) {
        // Single word sentence
        Pair(trimmed, 0..trimmed.length)
    } else {
        val lastWord = trimmed.substring(lastSpaceIndex + 1)
        Pair(lastWord, (lastSpaceIndex + 1)..trimmed.length)
    }
}

private fun normalizeForMatching(word: String): String {
    return word.lowercase()
        .replace(Regex("[^a-z']"), "") // Keep letters and apostrophes
}

private fun isWordMatch(spoken: String, target: String): Boolean {
    val normalizedSpoken = normalizeForMatching(spoken)
    val normalizedTarget = normalizeForMatching(target)

    // Exact match
    if (normalizedSpoken == normalizedTarget) return true

    // Fuzzy match (80% similarity threshold)
    val distance = levenshteinDistance(normalizedSpoken, normalizedTarget)
    val maxLen = maxOf(normalizedSpoken.length, normalizedTarget.length)
    val similarity = 1.0 - (distance.toDouble() / maxLen)

    return similarity >= 0.8
}
```

**Success Criteria:**
- State transitions work correctly through all scenarios
- Word extraction handles punctuation, contractions, hyphenation
- Matching algorithm correctly identifies similar pronunciations

#### Phase 3: UI Implementation

**Tasks:**
- [ ] Add collaborative mode toggle button to sentence mode controls
- [ ] Add "Your turn!" indicator with pulsing microphone animation
- [ ] Add last word highlight style (different from TTS highlight)
- [ ] Add score display in top bar
- [ ] Add attempt counter display (1/3, 2/3, 3/3)
- [ ] Add success/failure visual feedback (color flash, animation)

**Files:**
- `app/src/main/res/layout/fragment_unified_reader.xml` (modify)
- `app/src/main/java/com/example/arlo/UnifiedReaderFragment.kt` (modify)
- `app/src/main/res/values/colors.xml` (modify)

**New Colors:**
```xml
<!-- colors.xml additions -->
<color name="collaborative_highlight">#9C6ADE</color>  <!-- Purple for "your turn" -->
<color name="collaborative_success">#4A7C59</color>   <!-- Green flash -->
<color name="collaborative_failure">#C44536</color>   <!-- Red flash -->
```

**UI Layout Changes:**
```
Sentence Mode Top Bar (existing):
[Back] [Add Page] [◀] Page X of Y [▶] [Play/Pause] [Stop] [Speed] [Voice] [Mode]

Sentence Mode Top Bar (with collaborative):
[Back] [Add Page] [◀] Page X of Y [▶] [Play/Pause] [Stop] [Speed] [Voice] [🎤] [Mode]
                                                                    ▲
                                                          Collaborative toggle

Score Display (when collaborative mode active):
┌─────────────────────────────────────────┐
│  Score: 42                              │  ← Top right corner
└─────────────────────────────────────────┘

Sentence Display (during collaborative reading):
┌─────────────────────────────────────────┐
│                                         │
│  "The cat sat on the mat."              │
│                        ^^^^             │
│                    (purple highlight)   │
│                                         │
│         🎤 Your turn!                   │
│         Attempt 2 of 3                  │
│                                         │
└─────────────────────────────────────────┘
```

**Success Criteria:**
- Toggle button clearly indicates collaborative mode state
- Last word highlight is visually distinct from TTS highlight
- Score updates immediately on success
- Feedback animations are smooth and not jarring

#### Phase 4: Core Feature Integration

**Tasks:**
- [ ] Implement TTS partial sentence reading (all but last word)
- [ ] Integrate SpeechRecognitionService with ViewModel
- [ ] Wire up audio feedback on success/failure
- [ ] Implement 3-attempt retry logic
- [ ] Implement auto-correction (TTS reads word after 3 failures)
- [ ] Implement auto-advance after success or correction
- [ ] Handle incomplete sentences (skip collaborative for these)

**Files:**
- `app/src/main/java/com/example/arlo/UnifiedReaderViewModel.kt` (modify)
- `app/src/main/java/com/example/arlo/UnifiedReaderFragment.kt` (modify)
- `app/src/main/java/com/example/arlo/tts/TTSService.kt` (modify)

**Key Code - Partial TTS:**
```kotlin
// UnifiedReaderViewModel.kt
fun speakCurrentSentenceCollaborative() {
    val sentence = currentSentence ?: return
    val (lastWord, range) = extractLastWord(sentence.text)

    // Store for later matching
    _state.value = _state.value.copy(
        lastWord = lastWord,
        lastWordRange = range,
        collaborativeState = CollaborativeState.TTS_SPEAKING
    )

    // Speak all but last word
    val textWithoutLastWord = sentence.text.substring(0, range.first).trim()

    if (textWithoutLastWord.isEmpty()) {
        // Single word sentence - go straight to listening
        onPartialTTSComplete()
    } else {
        ttsService.speak(textWithoutLastWord) {
            onPartialTTSComplete()
        }
    }
}

private fun onPartialTTSComplete() {
    _state.value = _state.value.copy(
        collaborativeState = CollaborativeState.AWAITING_SPEECH
    )
    // Small delay for audio buffer, then start listening
    viewModelScope.launch {
        delay(300)
        startSpeechRecognition()
    }
}
```

**Success Criteria:**
- TTS stops precisely before the last word
- Speech recognition activates smoothly after TTS
- Sound effects play at correct moments
- Auto-advance works after success and after 3 failures

#### Phase 5: Error Handling & Edge Cases

**Tasks:**
- [ ] Handle speech recognition service unavailable
- [ ] Handle no speech detected (timeout after 5 seconds)
- [ ] Handle multiple words spoken (extract last word from result)
- [ ] Handle background noise / unintelligible speech
- [ ] Handle permission denial with graceful fallback
- [ ] Handle app backgrounding during recognition
- [ ] Handle single-word sentences (skip TTS, go straight to listening)
- [ ] Handle navigation during active recognition (cancel and reset)
- [ ] Disable collaborative mode for incomplete sentences

**Files:**
- `app/src/main/java/com/example/arlo/UnifiedReaderViewModel.kt` (modify)
- `app/src/main/java/com/example/arlo/UnifiedReaderFragment.kt` (modify)
- `app/src/main/java/com/example/arlo/speech/SpeechRecognitionService.kt` (modify)

**Error Handling Strategy:**
```kotlin
// SpeechRecognitionService.kt
when (errorCode) {
    SpeechRecognizer.ERROR_NO_MATCH -> {
        // Treat as failed attempt (user didn't say recognizable word)
        onError(SpeechError.NO_MATCH)
    }
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
        // User didn't speak in time
        onError(SpeechError.TIMEOUT)
    }
    SpeechRecognizer.ERROR_AUDIO,
    SpeechRecognizer.ERROR_CLIENT,
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
        // Technical failure - show error, fallback to standard mode
        onError(SpeechError.SERVICE_UNAVAILABLE)
    }
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
    SpeechRecognizer.ERROR_SERVER -> {
        // Network issue - try offline or fallback
        onError(SpeechError.NETWORK_ERROR)
    }
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
        // Permission revoked - fallback to standard mode
        onError(SpeechError.PERMISSION_DENIED)
    }
}
```

**Success Criteria:**
- App never crashes due to speech recognition errors
- Clear user feedback for all error conditions
- Graceful fallback to standard reading mode when needed
- Single-word sentences work correctly

## Acceptance Criteria

### Functional Requirements

- [ ] User can toggle collaborative reading mode on/off in sentence mode
- [ ] TTS reads sentence up to (but not including) the last word
- [ ] Last word is highlighted in a distinct color (purple)
- [ ] Visual indicator shows user it's their turn to speak
- [ ] App listens for user speech via microphone
- [ ] Correct word spoken: plays success sound, increments score, auto-advances
- [ ] Incorrect word spoken: plays error sound, shows "try again", up to 3 attempts
- [ ] After 3 failures: TTS reads correct word, then auto-advances
- [ ] Session score displayed and updated in real-time
- [ ] Score resets when leaving reader or turning off collaborative mode
- [ ] Incomplete sentences skip collaborative mode (normal TTS)
- [ ] Single-word sentences go straight to listening (no partial TTS)

### Non-Functional Requirements

- [ ] Speech recognition latency < 500ms from user finishing speech
- [ ] Sound effect latency < 100ms from trigger
- [ ] No memory leaks from SpeechRecognizer lifecycle
- [ ] Graceful handling of all SpeechRecognizer error codes
- [ ] Works offline if device has offline speech recognition
- [ ] Permission request includes clear rationale

### Quality Gates

- [ ] Manual testing with various sentence types and punctuation
- [ ] Test with background noise
- [ ] Test permission denial and recovery
- [ ] Test app backgrounding during recognition
- [ ] Verify no audio feedback issues (TTS + sound effects timing)

## Dependencies & Prerequisites

- **Android SDK 26+** (already met - min SDK)
- **SpeechRecognizer availability** - check with `SpeechRecognizer.isRecognitionAvailable()`
- **RECORD_AUDIO permission** - must be added to manifest
- **Sound effect files** - need to source or create .ogg files
- **Microphone icon assets** - need to create vector drawables

## Risk Analysis & Mitigation

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Speech recognition unavailable on device | Low | High | Check availability, fallback to standard mode with toast |
| Poor recognition accuracy for children | Medium | Medium | Use fuzzy matching (80% threshold), allow more attempts |
| Audio conflict (TTS + mic) | Medium | Medium | Sequential operation with 300ms buffer between TTS and mic |
| User speaks before indicator | Low | Low | Buffer captures speech, or show "Too early" feedback |
| Background noise interference | Medium | Low | Show "Too noisy" message, count as attempt |

## Future Considerations

- **Adaptive difficulty:** Adjust matching threshold based on success rate
- **Word difficulty scoring:** Harder words worth more points
- **Per-book score persistence:** Track progress over time
- **Achievements/badges:** Gamification elements
- **Parent dashboard:** View child's reading progress
- **Multi-language support:** Different speech recognition languages

## References

### Internal References
- [UnifiedReaderViewModel.kt](app/src/main/java/com/example/arlo/UnifiedReaderViewModel.kt) - State management patterns
- [TTSService.kt](app/src/main/java/com/example/arlo/tts/TTSService.kt) - TTS callback patterns
- [SentenceData.kt](app/src/main/java/com/example/arlo/data/SentenceData.kt) - Sentence model
- [colors.xml](app/src/main/res/values/colors.xml) - Existing color palette

### External References
- [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [Android RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent)
- [SoundPool for audio feedback](https://developer.android.com/reference/android/media/SoundPool)
- [Runtime permissions](https://developer.android.com/training/permissions/requesting)

### Audio Resources
- [Freesound.org](https://freesound.org) - Free sound effects (CC0/CC-BY)
- Success ping: Search "chime notification short"
- Error buzz: Search "error gentle buzz"
