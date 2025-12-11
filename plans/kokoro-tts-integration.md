# feat: Integrate Kokoro TTS with Word Timestamps

## Overview

Add high-quality text-to-speech using a self-hosted Kokoro-FastAPI server. Keep Android TTS as fallback. No abstractions, no settings UI, just make it work.

## Approach

Modify the existing `TTSService.kt` to try Kokoro first, fall back to Android TTS on failure. Add ExoPlayer for audio playback with word-level highlighting from Kokoro's timestamps.

```
User taps Play
    ↓
TTSService.speak(text)
    ↓
Try Kokoro HTTP request (5s timeout)
    ├─ Success → ExoPlayer plays audio, highlights words from timestamps
    └─ Failure → Android TTS speaks (existing behavior)
```

## Implementation

### 1. Add Kokoro server URL to local.properties

```properties
# local.properties (already has ANTHROPIC_API_KEY)
KOKORO_SERVER_URL=http://192.168.1.100:8880
```

### 2. Add BuildConfig field in build.gradle.kts

```kotlin
// app/build.gradle.kts - in defaultConfig block, after ANTHROPIC_API_KEY

// Kokoro TTS server URL from local.properties
val kokoroUrl = localProperties.getProperty("KOKORO_SERVER_URL")
    ?: System.getenv("KOKORO_SERVER_URL")
    ?: ""
buildConfigField("String", "KOKORO_SERVER_URL", "\"$kokoroUrl\"")
```

### 3. Add ExoPlayer dependency

```kotlin
// app/build.gradle.kts - in dependencies block
implementation("androidx.media3:media3-exoplayer:1.5.0")
```

### 4. Add Kokoro to TTSService.kt

```kotlin
// app/src/main/java/com/example/arlo/tts/TTSService.kt

class TTSService(private val context: Context) : TextToSpeech.OnInitListener {

    // NEW: Kokoro configuration from BuildConfig
    private val kokoroServerUrl: String? = BuildConfig.KOKORO_SERVER_URL.ifEmpty { null }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private var exoPlayer: ExoPlayer? = null
    private var highlightJob: Job? = null

    // Existing fields unchanged...
    private var tts: TextToSpeech? = null
    private var onRangeStart: ((Int, Int) -> Unit)? = null
    // etc.

    /**
     * NEW: Speak with Kokoro (preferred) or Android TTS (fallback)
     */
    suspend fun speakWithKokoro(text: String, onComplete: () -> Unit) {
        // Try Kokoro first
        if (kokoroServerUrl != null) {
            try {
                val result = synthesizeKokoro(text)
                playWithTimestamps(result.audioBytes, result.timestamps, onComplete)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Kokoro failed: ${e.message}, falling back to Android TTS")
            }
        }

        // Fallback to existing Android TTS
        speak(text, onComplete)
    }

    private suspend fun synthesizeKokoro(text: String): KokoroResult = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("input", text)
            put("voice", "af_bella")  // British female voice
            put("stream", false)
        }

        val request = Request.Builder()
            .url("$kokoroServerUrl/dev/captioned_speech")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Kokoro error: ${response.code}")
        }

        val responseJson = JSONObject(response.body?.string() ?: throw IOException("Empty response"))
        val audioBase64 = responseJson.getString("audio")
        val timestampsArray = responseJson.getJSONArray("timestamps")

        KokoroResult(
            audioBytes = Base64.decode(audioBase64, Base64.DEFAULT),
            timestamps = (0 until timestampsArray.length()).map { i ->
                val obj = timestampsArray.getJSONObject(i)
                WordTimestamp(
                    word = obj.getString("word"),
                    startMs = (obj.getDouble("start_time") * 1000).toLong(),
                    endMs = (obj.getDouble("end_time") * 1000).toLong()
                )
            }
        )
    }

    private fun playWithTimestamps(
        audioBytes: ByteArray,
        timestamps: List<WordTimestamp>,
        onComplete: () -> Unit
    ) {
        // Save to temp file (ExoPlayer needs a URI)
        val tempFile = File(context.cacheDir, "kokoro_${System.currentTimeMillis()}.mp3")
        tempFile.writeBytes(audioBytes)

        // Setup ExoPlayer
        exoPlayer?.release()
        exoPlayer = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(tempFile)))

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        cleanup(tempFile)
                        onComplete()
                    }
                }
            })

            prepare()
            play()
        }

        // Schedule word highlights using Handler (not polling)
        val handler = Handler(Looper.getMainLooper())
        var charPosition = 0

        timestamps.forEach { word ->
            handler.postDelayed({
                val endPos = charPosition + word.word.length
                onRangeStart?.invoke(charPosition, endPos)
                charPosition = endPos + 1  // +1 for space
            }, word.startMs)
        }
    }

    private fun cleanup(tempFile: File) {
        highlightJob?.cancel()
        tempFile.delete()
    }

    override fun stop() {
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        tts?.stop()
    }

    // Data classes
    private data class KokoroResult(
        val audioBytes: ByteArray,
        val timestamps: List<WordTimestamp>
    )

    private data class WordTimestamp(
        val word: String,
        val startMs: Long,
        val endMs: Long
    )

    // ... existing TTSService code unchanged ...
}
```

### 5. Update UnifiedReaderViewModel

```kotlin
// In speakCurrentSentence() or wherever TTS is triggered:

// BEFORE:
ttsService.speak(sentence, onComplete)

// AFTER:
viewModelScope.launch {
    ttsService.speakWithKokoro(sentence, onComplete)
}
```

### 6. Docker server setup

```yaml
# server/docker-compose.yml
services:
  kokoro:
    image: ghcr.io/remsky/kokoro-fastapi-cpu:v0.3.0
    ports:
      - "8880:8880"
    restart: unless-stopped
```

```markdown
# server/README.md
## Kokoro TTS Server

1. Install Docker
2. Run: `docker compose up -d`
3. Test: `curl http://localhost:8880/health`

Server runs on port 8880. Update the IP in TTSService.kt to match your server's IP address.
```

## Files Changed

| File | Change |
|------|--------|
| `local.properties` | Add `KOKORO_SERVER_URL` |
| `app/build.gradle.kts` | Add BuildConfig field + ExoPlayer dependency |
| `app/.../tts/TTSService.kt` | Add Kokoro HTTP + ExoPlayer playback |
| `app/.../UnifiedReaderViewModel.kt` | Call `speakWithKokoro()` instead of `speak()` |
| `server/docker-compose.yml` | New file (5 lines) |
| `server/README.md` | New file (6 lines) |

## What's NOT Included (Build Later If Needed)

- Settings UI for server URL (hardcoded for now)
- TTSProvider interface/abstraction
- Voice selection UI
- mDNS auto-discovery
- Audio caching
- Background playback
- Connection testing UI

## Acceptance Criteria

- [ ] `docker compose up` starts Kokoro server
- [ ] App plays audio from Kokoro with word highlighting
- [ ] App falls back to Android TTS when Kokoro unavailable
- [ ] No crashes on network errors
- [ ] Temp audio files cleaned up after playback

## Configuration

To change the Kokoro server URL, edit `local.properties`:

```properties
KOKORO_SERVER_URL=http://YOUR_SERVER_IP:8880
```

Leave empty or remove the line to disable Kokoro and use Android TTS only.
