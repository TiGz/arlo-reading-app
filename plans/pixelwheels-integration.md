# Game Rewards Integration Plan

## Overview

Integrate PixelWheels (open-source racing game) as a reward feature for children who meet their daily reading targets. The game will be race-limited (e.g., 1-3 races) rather than time-limited.

## License Strategy

### Decision: GPL-3.0+ for Arlo

Since PixelWheels core game logic is GPL-3.0+, we'll license Arlo under GPL-3.0+ to enable direct embedding. This:
- Allows full integration of PixelWheels as a module
- Makes Arlo open source (benefits educational app community)
- Requires publishing source code (already on GitHub)
- Maintains compatibility with PixelWheels assets (CC BY-SA 4.0)

### License Files to Add

1. `LICENSE` - GPL-3.0 full text
2. `NOTICE` - Attribution for PixelWheels and other dependencies
3. Update `CLAUDE.md` with license info

---

## Architecture

### Module Structure

```
arlo-reading-app/
├── app/                          # Main app (GPL-3.0+)
│   └── ... existing code
│
├── games-api/                    # Games abstraction layer (GPL-3.0+)
│   ├── GameProvider.kt           # Interface for game launchers
│   ├── GameSession.kt            # Session config & limits
│   ├── GameRewardsManager.kt     # Unlocking logic
│   └── GameUnlockState.kt        # Persistence for unlocks
│
├── game-pixelwheels/             # PixelWheels wrapper (GPL-3.0+)
│   ├── core/                     # From PixelWheels repo (git subtree)
│   ├── android/                  # Custom launcher
│   ├── PixelWheelsProvider.kt    # Implements GameProvider
│   └── PixelWheelsActivity.kt    # Race-limited wrapper
│
└── settings.gradle.kts           # Include new modules
```

### Key Interfaces

```kotlin
// games-api/GameProvider.kt
interface GameProvider {
    val gameId: String
    val displayName: String
    val iconResId: Int
    val description: String

    fun createLauncher(context: Context): GameLauncher
    fun isAvailable(): Boolean
}

// games-api/GameLauncher.kt
interface GameLauncher {
    fun launch(session: GameSession)
    fun isRunning(): Boolean
}

// games-api/GameSession.kt
data class GameSession(
    val sessionId: String,
    val maxRaces: Int,           // 1, 2, or 3 races allowed
    val trackWhitelist: List<String>? = null,  // Optional track restrictions
    val difficulty: Difficulty = Difficulty.EASY,
    val earnedAt: Long = System.currentTimeMillis()
)

enum class Difficulty { EASY, MEDIUM, HARD }
```

---

## Reward Logic

### Unlock Conditions

Game sessions are earned when the daily reading goal is met:

```kotlin
// In ReadingStatsRepository or new GameRewardsManager

suspend fun checkGameRewardEligibility(): GameRewardState {
    val today = getTodayStats()
    val parentSettings = getParentSettings()

    // Check if goal met for first time today
    if (today.goalMet && !today.gameRewardClaimed) {
        return GameRewardState.NewRewardAvailable(
            racesEarned = calculateRacesEarned(today, parentSettings)
        )
    }

    // Check for unclaimed races from today
    val unclaimedRaces = today.racesEarned - today.racesUsed
    if (unclaimedRaces > 0) {
        return GameRewardState.RacesAvailable(unclaimedRaces)
    }

    return GameRewardState.NoRewardsAvailable
}

private fun calculateRacesEarned(stats: DailyStats, settings: ParentSettings): Int {
    // Base: 1 race for meeting goal
    var races = 1

    // Bonus race for exceeding goal by 50%
    if (stats.totalPoints >= settings.dailyPointsTarget * 1.5) {
        races++
    }

    // Bonus race for perfect streak (5+ gold stars in a row)
    if (stats.longestStreak >= 5) {
        races++
    }

    return races.coerceAtMost(3)  // Max 3 races per day
}
```

### Database Schema Addition

```kotlin
// Add to DailyStats entity
@Entity(tableName = "daily_stats")
data class DailyStats(
    // ... existing fields ...

    // Game rewards tracking
    val racesEarned: Int = 0,           // Races earned today
    val racesUsed: Int = 0,             // Races consumed today
    val gameRewardClaimed: Boolean = false,  // Has claimed today's reward
    val lastGamePlayedAt: Long? = null  // Timestamp of last game session
)

// New entity for game session history
@Entity(tableName = "game_sessions")
data class GameSessionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: String,                 // "pixelwheels"
    val date: String,                   // ISO date
    val racesPlayed: Int,
    val startedAt: Long,
    val endedAt: Long? = null,
    val raceResults: String? = null     // JSON of race positions
)
```

---

## PixelWheels Integration

### Git Subtree Approach

```bash
# Add PixelWheels as a subtree (preserves history, easy updates)
git remote add pixelwheels https://github.com/agateau/pixelwheels.git
git subtree add --prefix=game-pixelwheels/upstream pixelwheels master --squash

# Future updates
git subtree pull --prefix=game-pixelwheels/upstream pixelwheels master --squash
```

### Custom Launcher (Race-Limited)

```kotlin
// game-pixelwheels/PixelWheelsActivity.kt
class PixelWheelsActivity : AndroidApplication(), RaceListener {

    private var maxRaces: Int = 1
    private var racesCompleted: Int = 0
    private var sessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        maxRaces = intent.getIntExtra(EXTRA_MAX_RACES, 1)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)

        val config = AndroidApplicationConfiguration().apply {
            useImmersiveMode = true
        }

        // Create custom game adapter that hooks into race completion
        val game = RaceLimitedPixelWheels(
            maxRaces = maxRaces,
            onRaceComplete = { position -> handleRaceComplete(position) },
            onSessionEnd = { finishWithResult() }
        )

        initialize(game, config)
    }

    private fun handleRaceComplete(position: Int) {
        racesCompleted++

        if (racesCompleted >= maxRaces) {
            // Show "Great racing!" message then exit
            showSessionCompleteDialog()
        }
    }

    private fun finishWithResult() {
        val resultIntent = Intent().apply {
            putExtra(RESULT_RACES_COMPLETED, racesCompleted)
            putExtra(RESULT_SESSION_ID, sessionId)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    companion object {
        const val EXTRA_MAX_RACES = "max_races"
        const val EXTRA_SESSION_ID = "session_id"
        const val RESULT_RACES_COMPLETED = "races_completed"
        const val RESULT_SESSION_ID = "session_id"

        fun createIntent(context: Context, session: GameSession): Intent {
            return Intent(context, PixelWheelsActivity::class.java).apply {
                putExtra(EXTRA_MAX_RACES, session.maxRaces)
                putExtra(EXTRA_SESSION_ID, session.sessionId)
            }
        }
    }
}
```

### Hooking into PixelWheels Race Completion

PixelWheels uses a `Maestro` pattern for race flow. We'll need to:

1. Subclass or wrap `QuickRaceMaestro`
2. Override `onRaceFinished()` callback
3. Block access to menus (force quick race mode)
4. Inject race count limit

```kotlin
// game-pixelwheels/RaceLimitedPixelWheels.kt
class RaceLimitedPixelWheels(
    private val maxRaces: Int,
    private val onRaceComplete: (position: Int) -> Unit,
    private val onSessionEnd: () -> Unit
) : PwGame() {

    private var racesCompleted = 0

    override fun create() {
        super.create()

        // Skip main menu, go straight to quick race
        showQuickRace(1)  // Single player
    }

    override fun onRaceFinished(gameInfo: GameInfo, position: Int) {
        racesCompleted++
        onRaceComplete(position)

        if (racesCompleted >= maxRaces) {
            // Session complete - return to Arlo
            onSessionEnd()
        } else {
            // Allow next race
            showQuickRace(1)
        }
    }

    // Override to prevent menu access
    override fun showMainMenu() {
        // No-op: keep user in quick race mode
    }
}
```

---

## UI Integration

### Secret Feature Access

The game reward is a "secret" feature - unlocked after meeting daily goal:

```kotlin
// In UnifiedReaderFragment or StatsDashboardFragment

private fun checkForGameReward() {
    viewLifecycleOwner.lifecycleScope.launch {
        val rewardState = gameRewardsManager.checkGameRewardEligibility()

        when (rewardState) {
            is GameRewardState.NewRewardAvailable -> {
                // Show celebration + game unlock animation
                showGameUnlockCelebration(rewardState.racesEarned)
            }
            is GameRewardState.RacesAvailable -> {
                // Show subtle game icon in UI
                binding.btnPlayGame.isVisible = true
                binding.tvRacesRemaining.text = "${rewardState.races} races"
            }
            GameRewardState.NoRewardsAvailable -> {
                binding.btnPlayGame.isVisible = false
            }
        }
    }
}

private fun showGameUnlockCelebration(racesEarned: Int) {
    // Fun animation: racing car zooms across screen
    // Text: "You earned X race(s)! 🏎️"
    // Button: "Play Now" / "Save for Later"

    GameUnlockDialog.newInstance(racesEarned)
        .show(childFragmentManager, "game_unlock")
}
```

### Parent Settings Integration

Add game controls to ParentSettingsDialogFragment:

```kotlin
// New section in parent settings
// - Enable/disable game rewards
// - Max races per day (1-5)
// - Difficulty setting (Easy/Medium/Hard)

data class GameRewardSettings(
    val enabled: Boolean = true,
    val maxRacesPerDay: Int = 3,
    val defaultDifficulty: Difficulty = Difficulty.EASY,
    val requireGoalMet: Boolean = true  // false = games always available
)
```

---

## Implementation Phases

### Phase 1: Foundation (2-3 hours)
1. Add GPL-3.0 LICENSE file
2. Create `games-api` module with interfaces
3. Add game reward fields to DailyStats
4. Create database migration

### Phase 2: PixelWheels Integration (4-6 hours)
5. Add PixelWheels as git subtree
6. Configure Gradle for multi-module build
7. Create PixelWheelsActivity wrapper
8. Implement race-limiting logic
9. Test standalone game launch

### Phase 3: Reward System (2-3 hours)
10. Implement GameRewardsManager
11. Hook into goal completion flow
12. Add UI for game unlock celebration
13. Add game button to reader/dashboard

### Phase 4: Parent Controls (1-2 hours)
14. Add game settings to ParentSettings
15. Update ParentSettingsDialogFragment
16. Test parental controls

### Phase 5: Polish (2-3 hours)
17. Celebration animations
18. Race completion feedback
19. Edge cases (app backgrounding, etc.)
20. Testing on Fire tablet

---

## Technical Considerations

### APK Size Impact
- PixelWheels adds ~40MB (native libs for 5 CPU architectures)
- Consider using App Bundle to deliver only needed architectures
- Assets add ~20MB

### LibGDX Version
- PixelWheels uses LibGDX 1.9.14 (pinned for tablet stability)
- No conflicts expected with Arlo (no existing LibGDX usage)

### Memory Management
- LibGDX games run in separate Activity
- Memory released when game Activity finishes
- No shared state with main Arlo app

### Lifecycle Handling
- Game Activity handles its own lifecycle
- Result returned via startActivityForResult pattern
- Arlo resumes cleanly after game ends

---

## Future Extensibility

This architecture supports adding more games:

```kotlin
// Example: Adding a simple puzzle game later
class PuzzleGameProvider : GameProvider {
    override val gameId = "word-puzzle"
    override val displayName = "Word Puzzle"
    // ...
}

// Register in GameRegistry
gameRegistry.register(PixelWheelsProvider())
gameRegistry.register(PuzzleGameProvider())  // Future

// UI shows available games as grid
// User picks which game to play with their earned session
```

---

## Files to Create/Modify

### New Files
- `LICENSE` (GPL-3.0 text)
- `NOTICE` (attributions)
- `games-api/build.gradle.kts`
- `games-api/src/.../GameProvider.kt`
- `games-api/src/.../GameSession.kt`
- `games-api/src/.../GameRewardsManager.kt`
- `game-pixelwheels/build.gradle.kts`
- `game-pixelwheels/src/.../PixelWheelsProvider.kt`
- `game-pixelwheels/src/.../PixelWheelsActivity.kt`
- `game-pixelwheels/src/.../RaceLimitedPixelWheels.kt`
- `app/src/.../ui/GameUnlockDialog.kt`

### Modified Files
- `settings.gradle.kts` (include new modules)
- `app/build.gradle.kts` (depend on games-api)
- `ReadingStats.kt` (add game reward fields)
- `AppDatabase.kt` (migration + new DAO)
- `ParentSettingsDialogFragment.kt` (game settings section)
- `UnifiedReaderFragment.kt` or `StatsDashboardFragment.kt` (game button)
- `CLAUDE.md` (update with game module info)
