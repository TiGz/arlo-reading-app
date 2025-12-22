# Arlo Reading App

A reading assistance app for children that makes learning to read fun and rewarding.

## Features

- **Camera-based OCR** - Capture book pages and extract text using Claude Haiku AI
- **Sentence-by-sentence reading** - Large, clear text with animated word highlighting
- **Collaborative reading mode** - TTS reads sentences, child speaks the last word
- **Kokoro TTS** - High-quality British neural voices for natural speech
- **Gamification** - Stars, streaks, achievements, and weekly goals
- **PixelWheels racing game** - Earn races as rewards for meeting reading goals

## PixelWheels Integration

Arlo includes [PixelWheels](https://agateau.com/projects/pixelwheels/), an open-source racing game, as a reward system for children who meet their daily reading goals.

### How It Works

1. Child completes their daily reading goal (configurable by parent)
2. Game reward unlocks with 1-3 races based on performance:
   - 1 race for meeting the goal
   - +1 bonus for exceeding goal by 50%
   - +1 bonus for 5+ correct answer streak
3. Child plays race-limited sessions (cannot access endless play)
4. After completing allowed races, returns to reading app

### Repository

PixelWheels is integrated as a git subtree from our fork:

- **Fork:** https://github.com/TiGz/pixelwheels
- **Branch:** `arlo-integration`
- **Upstream:** https://github.com/agateau/pixelwheels

The fork includes pre-built game assets (map tilesets, sprite atlases, UI skins) so you don't need to regenerate them from source.

### Key Integration Files

```
app/src/main/java/com/example/arlo/games/
├── GameRewardsManager.kt      # Calculates earned races from reading stats
├── GameSession.kt             # Session data (max races, session ID)
├── GameUnlockDialog.kt        # Dialog showing earned races
├── RaceCompleteDialog.kt      # Post-race celebration dialog
└── pixelwheels/
    ├── PixelWheelsActivity.kt # Android host activity for LibGDX
    ├── PixelWheelsProvider.kt # Factory for game sessions
    ├── RaceLimitedPwGame.kt   # Modified PwGame that skips main menu
    └── ArloMaestro.kt         # Race-limited game orchestrator

pixelwheels/                   # Git subtree from fork
├── android/assets/            # Pre-built game assets (committed)
│   ├── maps/*.png            # Map tileset textures
│   ├── sprites/              # Sprite atlas
│   └── ui/                   # UI skin
└── ...
```

### Parent Controls

Parents can configure game rewards in settings:
- Enable/disable game rewards entirely
- Set maximum races per day (default: 3)
- Configure daily reading point target

## Tech Stack

- **Language:** Kotlin
- **Platform:** Android (Min SDK 26, Target SDK 34)
- **OCR:** Claude Haiku API
- **TTS:** Kokoro TTS (remote) + Android TTS fallback
- **Database:** Room
- **Game Engine:** LibGDX (PixelWheels)

## Setup

1. Clone the repository
2. Copy `local.properties.example` to `local.properties`
3. Add your Kokoro TTS server URL
4. Build with `./gradlew installDebug`
5. Debug on Fire Tablet via USB (not emulator)

## License

- Arlo Reading App: Proprietary
- PixelWheels: GPL-3.0 (see pixelwheels/LICENSE)
