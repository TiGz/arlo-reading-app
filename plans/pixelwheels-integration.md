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

### Phase 1: Foundation - COMPLETED (Dec 21, 2025)

| Task | Status | Notes |
|------|--------|-------|
| Add GPL-3.0 LICENSE file | ✅ | `LICENSE.GPL-3` added manually |
| Add NOTICE file | ✅ | Attribution for PixelWheels + LibGDX |
| Create games package with interfaces | ✅ | `app/.../games/` package created |
| Add game reward fields to DailyStats | ✅ | `racesEarned`, `racesUsed`, `gameRewardClaimed`, `lastGamePlayedAt` |
| Create database migration | ✅ | Migration 11→12, version bumped to 12 |

**Files Created:**
- `LICENSE.GPL-3` - GPL-3.0 license text
- `NOTICE` - Third-party attributions
- `app/src/main/java/com/example/arlo/games/GameSession.kt` - Session config
- `app/src/main/java/com/example/arlo/games/GameProvider.kt` - Game provider interface
- `app/src/main/java/com/example/arlo/games/GameRewardsManager.kt` - Reward logic

**Files Modified:**
- `ReadingStats.kt` - Added game fields to DailyStats + GameSessionRecord entity
- `AppDatabase.kt` - Added migration 11→12, added GameSessionRecord entity
- `ReadingStatsDao.kt` - Added game session DAO methods
- `ReadingStatsRepository.kt` - Added `claimGameReward()`, `recordGameSessionUsed()`, `recordGameSession()`

**Build Status:** ✅ Verified compiling

---

### Phase 2: PixelWheels Integration - COMPLETED (Dec 21, 2025)

| Task | Status | Notes |
|------|--------|-------|
| Add PixelWheels as git subtree | ✅ | `pixelwheels/` directory with full source |
| Configure Gradle for multi-module build | ✅ | `:pixelwheels:core` included, LibGDX 1.9.14 deps added |
| Create custom Maestro subclass | ✅ | `ArloMaestro.kt` with race limiting |
| Create PixelWheelsActivity wrapper | ✅ | Immersive mode, race callbacks |
| Create RaceLimitedPwGame | ✅ | Extends PwGame, skips main menu |
| Create PixelWheelsProvider | ✅ | Implements GameProvider interface |
| Register in AndroidManifest | ✅ | Landscape orientation, fullscreen theme |
| Include PixelWheels assets | ✅ | 24MB assets bundled |

**Files Created:**
- `app/src/main/java/com/example/arlo/games/pixelwheels/PixelWheelsActivity.kt`
- `app/src/main/java/com/example/arlo/games/pixelwheels/RaceLimitedPwGame.kt`
- `app/src/main/java/com/example/arlo/games/pixelwheels/ArloMaestro.kt`
- `app/src/main/java/com/example/arlo/games/pixelwheels/PixelWheelsProvider.kt`
- `app/src/main/res/drawable/ic_game_racing.xml`

**Files Modified:**
- `settings.gradle.kts` - Added `:pixelwheels:core` module
- `app/build.gradle.kts` - LibGDX deps, NDK filters, assets sourceSets
- `AndroidManifest.xml` - Registered PixelWheelsActivity

**Build Status:** ✅ 35MB APK (14MB app + 21MB PixelWheels)

---

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

**Assets:** 24MB (maps, sprites, UI, sounds)

**Native Libraries (per architecture):**
- LibGDX core: ~160 KB (arm64-v8a), ~156 KB (armeabi-v7a)
- Box2D physics: Similar size per arch
- FreeType fonts: Similar size per arch
- Total per arch: ~500 KB estimated
- All 5 architectures: ~2.5 MB

**Total estimated impact:** ~26.5 MB

**Optimization strategies:**
- Use Android App Bundle (AAB) to deliver only target architecture (~24.5 MB per install)
- Consider removing unused tracks/vehicles to reduce assets
- Enable ABI splits for APK builds

**2025 requirement:** Apps targeting Android 15+ must support 16 KB page sizes on 64-bit devices (PixelWheels may need LibGDX update from 1.9.14 to handle this)

### LibGDX Version

**Current PixelWheels setup:**
- LibGDX: 1.9.14 (deliberately pinned - comment says "Do not update to 1.10.0, causes random freeze on test tablet")
- LibGDX Controllers: 2.2.2
- Android Gradle Plugin: 8.7.1
- Compile SDK: 35, Target SDK: 35, Min SDK: 19
- Uses AndroidX and Jetifier

**No conflicts expected:** Arlo has no existing LibGDX usage

**Integration method:** Can be embedded as:
1. **Separate Activity** (recommended) - Full isolation, cleaner lifecycle
2. **AndroidFragment** - Partial screen, requires FragmentActivity + AndroidFragmentApplication.Callbacks
3. **Library module** - Apply `com.android.library` instead of `com.android.application`

### Race Completion Hooks

**PixelWheels architecture uses the "Maestro" pattern:**

**Key files:**
- `core/src/com/agateau/pixelwheels/gamesetup/Maestro.java` - Abstract orchestrator for game flow
- `core/src/com/agateau/pixelwheels/gamesetup/QuickRaceMaestro.java` - Quick race implementation
- `core/src/com/agateau/pixelwheels/racescreen/RaceScreen.java` - Main race screen
- `core/src/com/agateau/pixelwheels/racescreen/FinishedOverlay.java` - Post-race results

**Race state management:**
```java
// GameWorld.java interface
enum State {
    COUNTDOWN,   // Race is counting down
    RUNNING,     // Race in progress
    FINISHED     // Race complete
}
```

**RaceScreen.Listener callbacks:**
```java
public interface Listener {
    void onRestartPressed();    // User wants to restart
    void onQuitPressed();       // User wants to quit
    void onNextTrackPressed();  // Proceed to next (we'll use this to exit)
}
```

**Integration approach:**
1. Subclass `QuickRaceMaestro` to create `RaceLimitedMaestro`
2. Override `createRaceScreen()` to inject custom `RaceScreen.Listener`
3. Track race count in listener's `onNextTrackPressed()` callback
4. After max races, call `finish()` on Activity instead of showing track selector
5. Optionally skip vehicle selection by pre-configuring `GameInfo.Builder`

**Main entry point:**
```java
// PwGame.java - main game class
public void showMainMenu()              // Skip this
public void startQuickRace(int players) // Use this directly
public Maestro getMaestro()             // Access for controlling flow
```

### Memory Management
- LibGDX games run in separate Activity
- Memory released when game Activity finishes
- Box2D physics world properly disposed via GameWorld.dispose()
- No shared state with main Arlo app

### Lifecycle Handling
- Game Activity handles its own lifecycle
- Result returned via startActivityForResult pattern
- Arlo resumes cleanly after game ends
- PixelWheels handles orientation changes and backgrounding

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

---

## Research Findings (Dec 21, 2025)

### PixelWheels Project Analysis

**Repository:** https://github.com/agateau/pixelwheels
**Latest Release:** 1.0.1 (Dec 20, 2025) - actively maintained
**Contributors:** 24+
**License:** Multi-license approach
- Game logic: GPL-3.0+
- Utility code: Apache 2.0
- Assets: CC BY-SA 4.0

**Tech Stack:**
- Java 94.3% (core game)
- LibGDX 1.9.14 framework
- Box2D physics engine
- Build: Gradle 8.13.1, AGP 8.7.1

**Project structure:**
```
pixelwheels/
├── core/            # Platform-agnostic game logic (GPL-3.0+)
├── android/         # Android launcher (Apache 2.0)
├── desktop/         # Desktop launcher
├── android/assets/  # 24MB (maps, sprites, sounds) [CC BY-SA 4.0]
├── build.gradle     # Root config (LibGDX 1.9.14)
└── version.properties
```

### Build System Details

**Root build.gradle:**
```gradle
ext {
    appName = 'Pixel Wheels'
    gdxVersion = '1.9.14'  // Pinned - do not update to 1.10.0
    gdxControllersVersion = '2.2.2'
}
```

**android/build.gradle:**
- Application ID: `com.agateau.tinywheels.android`
- Min SDK: 19, Target SDK: 35, Compile SDK: 35
- Native libs for: armeabi, armeabi-v7a, x86, arm64-v8a, x86_64
- Uses AndroidX and Jetifier
- Product flavors: `itchio`, `gplay`

**Native library extraction:**
- Gradle task `copyAndroidNatives()` extracts .so files from JARs
- Libraries placed in `android/libs/{arch}/` directories
- Packaged with APK automatically

### Game Architecture

**Main entry point:** `PwGame.java` (extends libGDX `Game` class)
- Initializes assets, audio, config, stats
- Uses `ScreenStack` for screen navigation
- Manages `Maestro` for game flow orchestration

**Screen flow (normal):**
1. MainMenuScreen
2. SelectGameModeScreen (Quick Race / Championship)
3. SelectTrackScreen
4. SelectVehicleScreen
5. RaceScreen (actual gameplay)
6. FinishedOverlay (results)

**Maestro pattern:**
- Abstract `Maestro` class orchestrates screen transitions
- `QuickRaceMaestro` handles single-race flow
- `ChampionshipMaestro` handles multi-race tournaments

**Key classes for integration:**

`QuickRaceMaestro.java`:
- Line 42: `start()` - shows track selection
- Line 108: `createRaceScreen()` - builds RaceScreen with listener
- Lines 110-141: `RaceScreen.Listener` implementation
  - `onRestartPressed()` - recreates race
  - `onQuitPressed()` - returns to main menu
  - `onNextTrackPressed()` - shows reward screen then menu

`RaceScreen.java`:
- Line 88: Constructor takes `GameInfo` + `Listener`
- Line 224: `mGameWorld.getState()` transitions tracked
- Line 226-228: When state → FINISHED, calls `onFinished()`
- Line 333: `onFinished()` creates `FinishedOverlay`
- Line 356: `onRestartPressed()` delegates to listener
- Line 359: `onQuitPressed()` delegates to listener

`GameWorld.java` (interface):
- State enum: COUNTDOWN → RUNNING → FINISHED
- `setState(State)` to manually control state

`FinishedOverlay.java`:
- Displays race results table
- "Continue" button triggers `onNextTrackPressed()`
- Supports both quick race and championship modes

### Embedding LibGDX Games

**Method 1: Separate Activity (recommended)**
```java
public class MyActivity extends AndroidApplication {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        initialize(new MyGame(), config);
    }
}
```

**Method 2: AndroidFragment**
```java
// Activity extends FragmentActivity + implements AndroidFragmentApplication.Callbacks
public class MyFragment extends AndroidFragmentApplication {
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        return initializeForView(new MyGame());
    }
}
```
*Requires:* androidx.fragment dependency + FragmentActivity host

**Method 3: Library Module**
- Change `apply plugin: "com.android.application"` → `"com.android.library"`
- Remove `applicationId` from android block
- Host app depends on library module
- Assets must be in library module's assets folder

### APK Size Analysis

**Assets breakdown (android/assets/):**
- Maps (.tmx, .tsx): ~2 MB
- Sprites/textures: ~15 MB
- UI elements: ~2 MB
- Sounds/music: ~5 MB
- Total: 24 MB

**Native libraries (estimated from LibGDX 1.13.5+):**
- Per architecture: ~500 KB (gdx + box2d + freetype)
- 5 architectures total: ~2.5 MB
- Modern devices only need 1 arch (via App Bundle)

**APK size impact:**
- Full APK (all archs): ~26.5 MB
- AAB install (single arch): ~24.5 MB

**Optimization strategies:**
1. Use Android App Bundle (recommended)
2. Enable ABI splits for direct APK distribution
3. Remove unused assets (tracks, vehicles, languages)
4. Compress assets further (already at 85% JPEG)

### LibGDX Fragment Integration Examples

**Official docs:** [Starter classes and configuration](https://libgdx.com/wiki/app/starter-classes-and-configuration)

**Key steps:**
1. Add AndroidX Fragment library to android module
2. Activity extends `FragmentActivity` (not `AndroidApplication`)
3. Activity implements `AndroidFragmentApplication.Callbacks`
4. Create fragment extending `AndroidFragmentApplication`
5. Fragment's `onCreateView()` calls `initializeForView(game)`
6. Use `FragmentTransaction` to add fragment to activity

**Example from libgdx-in-fragment project:**
```java
// In Activity
FragmentTransaction trans = getSupportFragmentManager().beginTransaction();
trans.replace(android.R.id.content, new GameFragment());
trans.commit();

// Fragment class
public static class GameFragment extends AndroidFragmentApplication {
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        return initializeForView(new MyGdxGame());
    }
}
```

### 2025 Android Requirements

**16KB Page Size Requirement:**
- Deadline: November 1, 2025 (already passed)
- Affects: Apps targeting Android 15+ on 64-bit devices
- Issue: LibGDX 1.13.5 shows "4KB LOAD section" in APK analyzer
- Impact: Google Play may reject apps without 16KB support
- Status: PixelWheels uses LibGDX 1.9.14 (older), may have same issue

**Resolution:**
- Monitor for LibGDX updates addressing 16KB pages
- May need to update LibGDX version before Google Play submission
- Test on Android 15+ devices before release

### Integration Best Practices

**From research:**

1. **Separate Activity preferred** for full-screen games
   - Cleaner lifecycle management
   - No fragment complexity
   - Better memory isolation
   - Easier to finish() and return to host app

2. **Use Fragment** only for partial-screen embedding
   - More complex setup
   - Shared lifecycle with host activity
   - Good for mini-games in existing UI

3. **Race limiting implementation:**
   - Subclass `QuickRaceMaestro`
   - Override `createRaceScreen()` to inject counting listener
   - Track calls to `onNextTrackPressed()`
   - Call activity `finish()` after N races

4. **Skip menu flow:**
   - Override `PwGame.create()` to skip `showMainMenu()`
   - Directly create `QuickRaceMaestro` with pre-configured `GameInfo`
   - Pre-select track and vehicle to bypass selection screens
   - Start race immediately

### Source Citations

**LibGDX Documentation:**
- [Creating a Project](https://libgdx.com/wiki/start/project-generation)
- [Starter classes and configuration](https://libgdx.com/wiki/app/starter-classes-and-configuration)
- [Building the libGDX Natives](https://libgdx.com/dev/natives/)

**Integration Examples:**
- [Add libGDX to existing Android (Kotlin) project](https://rafalgolarz.com/blog/2018/05/17/add_libgdx_to_existing_android_kotlin_project/)
- [libgdx-in-fragment (GitHub)](https://github.com/kbigdelysh/libgdx-in-fragment)
- [Integrating libgdx into an android project](https://ivanludvig.dev/tech/integrating-libgdx-into-android-project)

**APK Size Research:**
- [Controlling APK Size When Using Native Libraries (Medium)](https://medium.com/android-news/controlling-apk-size-when-using-native-libraries-45c6c0e5b70a)
- [GitHub Issue #7704: All modules with native libraries must support same ABIs](https://github.com/libgdx/libgdx/issues/7704)
- [GitHub Issue #5958: Question about removing armeabi and x86 natives](https://github.com/libgdx/libgdx/issues/5958)

**16KB Page Size Issues:**
- [GitHub Issue #7695: arm64-v8a 4KB LOAD section not 16KB](https://github.com/libgdx/libgdx/issues/7695)

**PixelWheels:**
- [PixelWheels GitHub Repository](https://github.com/agateau/pixelwheels)
