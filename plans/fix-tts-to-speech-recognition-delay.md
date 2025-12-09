# fix: Reduce TTS-to-Speech Recognition Delay in Collaborative Reading

## Overview

When collaborative reading mode finishes speaking a sentence (minus the last word), there's a ~400ms delay before speech recognition is ready to hear the user. Users who speak immediately when TTS finishes miss the recognition window, causing frustrating failed attempts.

**Current Timing Breakdown (from logs):**
- 300ms intentional delay (line 664 in [UnifiedReaderViewModel.kt:658-668](app/src/main/java/com/example/arlo/UnifiedReaderViewModel.kt#L658-L668))
- ~90ms SpeechRecognizer initialization
- **Total: ~390-400ms**

**Target:** Reduce to ~100-150ms total

## Problem Statement

### User Experience Issue

In collaborative reading mode, children are supposed to speak the target word(s) after TTS finishes. However:

1. Young readers naturally want to speak immediately when they hear the silence after TTS
2. The ~400ms delay means their first syllable is often missed
3. This counts as a failed attempt, leading to frustration
4. The child may think they said it wrong when they actually said it at the right time

### Root Cause Analysis

```
TTS finishes → 300ms delay (hardcoded) → Create/configure recognizer (~90ms) → Ready
                    ^                              ^
                    |                              |
            "audio buffer clear"           New instance each time
```

**Location of 300ms delay:** [UnifiedReaderViewModel.kt:664](app/src/main/java/com/example/arlo/UnifiedReaderViewModel.kt#L664)

```kotlin
private fun onPartialTTSComplete(targetWords: String) {
    _state.value = _state.value.copy(targetWord = targetWords)
    viewModelScope.launch {
        delay(300)  // <-- THIS DELAY
        startSpeechRecognition()
    }
}
```

## Proposed Solution

### Phase 1: Pre-warm SpeechRecognizer (Primary Optimization)

Instead of creating a new SpeechRecognizer each time recognition is needed, maintain a "warm" instance that's ready to start listening faster.

**Current flow:**
```
TTS done → delay(300) → create recognizer → set listener → create intent → startListening → onReadyForSpeech
```

**Optimized flow:**
```
TTS playing → pre-create recognizer (background) → TTS done → delay(100) → startListening → onReadyForSpeech
```

### Phase 2: Reduce Intentional Delay

Reduce the 300ms delay to 100-150ms. The original delay was for "audio buffer to clear," but:
- Modern Android handles this quickly (~50ms)
- Fire tablets don't have complex audio routing
- Testing will determine the safe minimum

### Phase 3: Enable Offline Recognition

Add `EXTRA_PREFER_OFFLINE` to recognition intent for devices that support it:
- Eliminates network round-trip
- More consistent timing
- Works without internet

## Technical Approach

### Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                    UnifiedReaderViewModel                           │
├─────────────────────────────────────────────────────────────────────┤
│  ┌─────────────────────┐    ┌─────────────────────────────────┐    │
│  │  SpeechRecognizer   │    │  RecognitionListener            │    │
│  │  (kept warm)        │    │  (single instance)              │    │
│  │                     │    │                                 │    │
│  │  States:            │    │  onReadyForSpeech → LISTENING   │    │
│  │  - COLD (init)      │    │  onResults → process + FEEDBACK │    │
│  │  - WARM (ready)     │    │  onError → handle + retry       │    │
│  │  - LISTENING        │    │                                 │    │
│  └─────────────────────┘    └─────────────────────────────────┘    │
│                                                                     │
│  Pre-warm trigger: When TTS starts partial sentence                 │
│  Reduced delay: 100-150ms (configurable for testing)               │
└─────────────────────────────────────────────────────────────────────┘
```

### Implementation Phases

#### Phase 1: Pre-warm SpeechRecognizer

**File:** [UnifiedReaderViewModel.kt](app/src/main/java/com/example/arlo/UnifiedReaderViewModel.kt)

```kotlin
// Add at class level - keep recognizer warm
private var speechRecognizer: SpeechRecognizer? = null
private var isRecognizerWarm = false

// Pre-warm when entering collaborative mode or when TTS starts
private fun ensureRecognizerWarm() {
    if (speechRecognizer == null || !isRecognizerWarm) {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(
            application,
            ComponentName("com.google.android.googlequicksearchbox",
                "com.google.android.voicesearch.serviceapi.GoogleRecognitionService")
        )
        speechRecognizer?.setRecognitionListener(createRecognitionListener())
        isRecognizerWarm = true
        Log.d("SpeechRecognition", "Recognizer pre-warmed")
    }
}

// Call this when TTS starts the partial sentence
private fun speakCurrentSentence() {
    // ... existing TTS code ...

    // Pre-warm recognizer while TTS is playing
    if (_state.value.collaborativeModeEnabled) {
        ensureRecognizerWarm()
    }
}
```

#### Phase 2: Reduce Delay

```kotlin
// Change from 300ms to configurable value
companion object {
    private const val RECOGNITION_START_DELAY_MS = 100L  // Reduced from 300
}

private fun onPartialTTSComplete(targetWords: String) {
    _state.value = _state.value.copy(targetWord = targetWords)
    viewModelScope.launch {
        delay(RECOGNITION_START_DELAY_MS)  // Use constant
        startSpeechRecognitionQuick()  // Use warm recognizer
    }
}
```

#### Phase 3: Simplified startListening

```kotlin
private fun startSpeechRecognitionQuick() {
    val recognizer = speechRecognizer
    if (recognizer == null) {
        Log.w("SpeechRecognition", "Recognizer not warm, falling back to full init")
        startSpeechRecognition()  // Fallback to current behavior
        return
    }

    _state.value = _state.value.copy(collaborativeState = CollaborativeState.LISTENING)

    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)  // NEW: Prefer offline
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 100L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
    }

    try {
        recognizer.startListening(intent)
        Log.d("SpeechRecognition", "Quick startListening called")
    } catch (e: Exception) {
        Log.e("SpeechRecognition", "Quick startListening failed", e)
        handleSpeechError(SpeechRecognizer.ERROR_CLIENT)
    }
}
```

#### Phase 4: Lifecycle Management

```kotlin
// In cancelSpeechRecognition() - don't destroy, just cancel
fun cancelSpeechRecognition() {
    speechRecognizer?.cancel()  // Cancel but keep warm
    _state.value = _state.value.copy(
        collaborativeState = CollaborativeState.IDLE,
        targetWord = null
    )
}

// Only destroy on cleanup
override fun onCleared() {
    super.onCleared()
    speechRecognizer?.destroy()
    speechRecognizer = null
    isRecognizerWarm = false
}
```

## Acceptance Criteria

### Functional Requirements

- [ ] Speech recognition ready within 150ms of TTS completion (down from ~400ms)
- [ ] User can speak target word immediately when TTS finishes and be recognized
- [ ] Pre-warmed recognizer is reused across sentences (no recreation each time)
- [ ] Fallback to full initialization if pre-warmed recognizer fails
- [ ] No audio artifacts or interference between TTS and recognition

### Non-Functional Requirements

- [ ] No increase in memory usage (single recognizer instance vs. creating/destroying)
- [ ] No increase in battery drain
- [ ] Works on Fire HD 8 and Fire HD 10 tablets
- [ ] Handles app backgrounding/foregrounding without crashes

### Quality Gates

- [ ] Test with 20+ sentences in a row - no degradation
- [ ] Test "speak immediately" scenario - should succeed 90%+ of the time
- [ ] Test pause/resume mid-session - recognition still works
- [ ] Test airplane mode with EXTRA_PREFER_OFFLINE - should work if offline model available

## Success Metrics

| Metric | Current | Target |
|--------|---------|--------|
| Time to recognition ready | ~400ms | <150ms |
| "Spoke too early" failures | ~50% | <10% |
| User satisfaction | Frustrated | Delighted |

## Test Plan

### Manual Testing

1. **Timing Test**
   - Enable collaborative mode
   - Read 10 sentences
   - Note time between TTS ending and "Ready" indicator
   - Verify <150ms consistently

2. **Immediate Speech Test**
   - Enable collaborative mode
   - Read sentences and speak target word IMMEDIATELY when TTS finishes
   - Success rate should be >90%

3. **Sustained Use Test**
   - Read entire book (50+ pages) in collaborative mode
   - Verify no memory leaks or degradation
   - Recognition should stay responsive

4. **Lifecycle Test**
   - Start reading, press home button, return
   - Recognition should still work
   - Press power button (screen off), wait 30s, return
   - Recognition should still work

5. **Offline Test**
   - Enable airplane mode
   - Start collaborative reading
   - Verify recognition works (if offline model available)
   - OR verify graceful fallback with clear error message

### Edge Cases

- [ ] Very short sentences (single word remaining)
- [ ] Very long sentences (many seconds of TTS)
- [ ] User speaks during TTS (should ignore)
- [ ] User speaks very quietly (should handle timeout gracefully)
- [ ] User speaks wrong word then correct word quickly
- [ ] Noisy environment
- [ ] Low battery (<15%)

## Dependencies & Prerequisites

- Google app with Speech Services installed on Fire tablet
- RECORD_AUDIO permission already granted
- TTS service already functional

## Risk Analysis & Mitigation

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Reduced delay causes audio artifacts | Medium | High | Keep 150ms initially, reduce further only if testing shows it's safe |
| Pre-warmed recognizer becomes stale | Low | Medium | Re-warm if error occurs, or re-warm periodically |
| Fire tablet audio HAL needs more time | Medium | High | Make delay configurable, test on multiple Fire models |
| EXTRA_PREFER_OFFLINE not available | Medium | Low | Fallback works fine, just with network latency |

## Future Considerations

### Potential Further Optimizations

1. **Audio cue when ready**: Play subtle sound/vibration when recognition becomes active
2. **Visual countdown**: Show "3... 2... 1..." during delay so user knows exactly when to speak
3. **Adaptive delay**: Measure actual time-to-ready on device and adjust dynamically
4. **Continuous listening**: Instead of start/stop per sentence, maintain continuous listening and segment based on audio

### Out of Scope

- Alternative speech recognition engines (keeping Google for now)
- Custom TTS engines
- UI changes to collaborative indicator (separate issue)

## References

### Internal References

- Current implementation: [UnifiedReaderViewModel.kt:658-802](app/src/main/java/com/example/arlo/UnifiedReaderViewModel.kt#L658-L802)
- TTS callbacks: [TTSService.kt:110-124](app/src/main/java/com/example/arlo/tts/TTSService.kt#L110-L124)
- Collaborative state management: [UnifiedReaderViewModel.kt:73-77](app/src/main/java/com/example/arlo/UnifiedReaderViewModel.kt#L73-L77)

### External References

- [Android SpeechRecognizer API](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [RecognizerIntent options](https://developer.android.com/reference/android/speech/RecognizerIntent)
- [UtteranceProgressListener](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener)
- [Understanding and Overcoming Latency in Speech Recognition](https://picovoice.ai/blog/latency-in-speech-recognition/)
- [Real-Time Speech Transcription on Android](https://webrtc.ventures/2025/03/real-time-speech-transcription-on-android-with-speechrecognizer/)

### Research Findings

**Best practice: Pre-initialize SpeechRecognizer**
> Creating new instances for each recognition consumes significant resources. Reusing instances reduces CPU and memory usage.

**Target latency for natural conversation:**
> Industry standard is 500-800 milliseconds total response time. On-device recognition achieves 200-300ms.

**Android 13+ note:**
> RecognitionService moved to Android System Intelligence. Don't hard-code specific recognition service packages.

---

*Plan created: 2025-12-09*
