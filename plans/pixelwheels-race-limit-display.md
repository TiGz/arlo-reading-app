# feat: PixelWheels Race Limit Display and Enforcement (Simplified)

## Overview

Display race limits in the PixelWheels game and enforce them properly. The goal is to show children "Race X of Y" and guide them back to reading when races run out.

## Problem Statement

Currently:
- Race limits are tracked but not displayed to users
- When races run out, the game exits without explanation
- No positive messaging about earning more races through reading

## Solution: Minimal Changes

The review revealed the original plan was over-engineered (200+ LOC). This simplified approach achieves the same goals with ~30 lines across 4 files.

### Key Insight

PixelWheels already has conditional button logic via the `quickRace` variable in GDXUI templates. We can leverage this pattern instead of creating new overlay classes.

## Implementation

### File 1: RaceLimitedPwGame.kt (Add 2 methods)

Add helper methods for UI queries:

```kotlin
// Add after getRacesCompleted()

fun getRacesRemaining(): Int = (maxRaces - racesCompleted).coerceAtLeast(0)

fun hasRacesRemaining(): Boolean = racesCompleted < maxRaces
```

**Location:** [RaceLimitedPwGame.kt:92](app/src/main/java/com/example/arlo/games/pixelwheels/RaceLimitedPwGame.kt#L92)

### File 2: Hud.java (Add 6 lines)

Add race counter label to the HUD:

```java
// ARLO MODIFICATION - Add after mLapLabel declaration (~line 55)
private Label mRaceLabel;
private int mRaceCount = 0;
private int mMaxRaces = 0;

// ARLO MODIFICATION - Add in Hud constructor after mLapLabel setup (~line 75)
mRaceLabel = new Label("", mAssets.ui.skin, "hud");
mRoot.addActor(mRaceLabel);
mRoot.addPositionRule(mRaceLabel, Anchor.TOP_CENTER, mRoot, Anchor.TOP_CENTER, 0, -10);

// ARLO MODIFICATION - Add new public method
public void setRaceLimits(int current, int max) {
    mRaceCount = current;
    mMaxRaces = max;
    if (max > 0) {
        mRaceLabel.setText("Race " + current + " of " + max);
    }
}
```

**Location:** [Hud.java:55](pixelwheels/core/src/com/agateau/pixelwheels/racescreen/Hud.java#L55)

### File 3: ArloMaestro.kt (Add 12 lines)

Add defensive check in track selection and update HUD:

```kotlin
// ARLO MODIFICATION - Add at start of createSelectTrackScreen()
// Defensive: If somehow we get here with 0 races, exit immediately
if (currentRaceCount() >= maxRaces) {
    android.util.Log.w("ArloMaestro", "createSelectTrackScreen called with no races remaining - exiting")
    stopEnoughInputChecker()
    game.showMainMenu()
    // Return a dummy screen that will be immediately replaced
    return object : Screen {
        override fun show() {}
        override fun render(delta: Float) {}
        override fun resize(width: Int, height: Int) {}
        override fun pause() {}
        override fun resume() {}
        override fun hide() {}
        override fun dispose() {}
    }
}

// Alternative: Add after RaceScreen creation in onPlayerSelected callback
// Update HUD race counter
(game.screen as? RaceScreen)?.hud?.setRaceLimits(
    currentRaceCount() + 1,  // 1-indexed for display
    maxRaces
)
```

**Location:** [ArloMaestro.kt:60](app/src/main/java/com/example/arlo/games/pixelwheels/ArloMaestro.kt#L60)

### File 4: FinishedOverlay.java (Add 8 lines)

Modify button text based on race status:

```java
// ARLO MODIFICATION - Add in fillMenu() after button creation (~line 285)
if (mGame instanceof RaceLimitedPwGame) {
    RaceLimitedPwGame arloGame = (RaceLimitedPwGame) mGame;
    if (!arloGame.hasRacesRemaining()) {
        // Change "BACK TO MENU" to "BACK TO READING"
        continueButton.setText("BACK TO READING");

        // Add motivational message
        Label earnMore = new Label("Great racing! Read more to earn more!",
            mGame.getAssets().ui.skin);
        mMainTable.row();
        mMainTable.add(earnMore).padTop(20);
    } else {
        // Show remaining races
        continueButton.setText("NEXT RACE (" + arloGame.getRacesRemaining() + " left)");
    }
}
```

**Location:** [FinishedOverlay.java:285](pixelwheels/core/src/com/agateau/pixelwheels/racescreen/FinishedOverlay.java#L285)

## Security Fix

### AndroidManifest.xml

Prevent ADB command injection of race counts:

```xml
<!-- Change from: -->
<activity android:name=".games.pixelwheels.PixelWheelsActivity" />

<!-- Change to: -->
<activity
    android:name=".games.pixelwheels.PixelWheelsActivity"
    android:exported="false" />
```

**Location:** [AndroidManifest.xml](app/src/main/AndroidManifest.xml)

## State Persistence Fix

### PixelWheelsActivity.kt

Handle Android process death:

```kotlin
// Add to onCreate after super.onCreate()
savedInstanceState?.let { bundle ->
    racesCompletedInSession = bundle.getInt("racesCompleted", 0)
}

// Add onSaveInstanceState override
override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putInt("racesCompleted", racesCompletedInSession)
}
```

**Location:** [PixelWheelsActivity.kt:45](app/src/main/java/com/example/arlo/games/pixelwheels/PixelWheelsActivity.kt#L45)

## File Summary

| File | Lines Changed | Purpose |
|------|---------------|---------|
| `RaceLimitedPwGame.kt` | +2 | Add helper methods |
| `Hud.java` | +6 | Add race counter label |
| `ArloMaestro.kt` | +12 | Defensive check + HUD update |
| `FinishedOverlay.java` | +9 | Modify button text + hide RESTART |
| `PauseOverlay.java` | +4 | Conditional RESTART (first lap only) |
| `RaceScreen.java` | +5 | Add `isOnFirstLap()` helper |
| `AndroidManifest.xml` | +1 | Security fix |
| `PixelWheelsActivity.kt` | +8 | State persistence |

**Total: ~47 lines** (vs. 200+ in original plan)

## What We're NOT Doing

These were in the original plan but are unnecessary:

- ❌ New `ArloRaceCounter.java` class (use existing Label)
- ❌ New `ArloFinishedOverlay.java` class (modify existing)
- ❌ New `arlofinishedoverlay.gdxui` template (use existing)
- ❌ Bounce animations on counter (scope creep)
- ❌ Counter on SelectTrackScreen/SelectVehicleScreen (HUD is sufficient)
- ❌ Custom styles in uiskin.json (use existing "hud" style)

## Acceptance Criteria

- [ ] Race counter visible in HUD during race ("Race X of Y")
- [ ] FinishedOverlay shows "NEXT RACE (X left)" or "BACK TO READING"
- [ ] Motivational message when races exhausted
- [ ] `android:exported="false"` set on PixelWheelsActivity
- [ ] Race count survives process death (savedInstanceState)
- [ ] RESTART button available during first lap only (for bad starts)
- [ ] RESTART button hidden after completing first lap

### Note on RESTART Button

Both `FinishedOverlay` and `PauseOverlay` use the `quickRace` variable to conditionally show RESTART:

```xml
<!-- finishedoverlay.gdxui:35-36 and pauseoverlay.gdxui:8-10 -->
<Ifdef var="quickRace">
    <ButtonMenuItem id="restartButton" text="RESTART"/>
</Ifdef>
```

Since Arlo uses QuickRace mode, the RESTART button appears in both overlays. To hide it:

### File 5: FinishedOverlay.java (Hide RESTART - 1 line change)

```java
// Change line 277 from:
if (!isChampionship()) {
    builder.defineVariable("quickRace");
}

// To:
if (!isChampionship() && !(mGame instanceof RaceLimitedPwGame)) {
    builder.defineVariable("quickRace");
}
```

**Location:** [FinishedOverlay.java:277](pixelwheels/core/src/com/agateau/pixelwheels/racescreen/FinishedOverlay.java#L277)

### File 6: PauseOverlay.java (Conditional RESTART - 4 lines)

Allow RESTART only during the first lap (to handle bad starts), but hide it after:

```java
// Change line 49-51 from:
boolean isQuickRace = mRaceScreen.getGameType() == GameInfo.GameType.QUICK_RACE;
if (isQuickRace) {
    builder.defineVariable("quickRace");
}

// To:
boolean isQuickRace = mRaceScreen.getGameType() == GameInfo.GameType.QUICK_RACE;
boolean isRaceLimited = mGame instanceof RaceLimitedPwGame;
// Allow restart during first lap only (for bad starts) in race-limited mode
boolean allowRestart = isQuickRace && (!isRaceLimited || mRaceScreen.isOnFirstLap());
if (allowRestart) {
    builder.defineVariable("quickRace");
}
```

**Location:** [PauseOverlay.java:49](pixelwheels/core/src/com/agateau/pixelwheels/racescreen/PauseOverlay.java#L49)

### File 7: RaceScreen.java (Add helper method - 5 lines)

Add method to check if player is still on first lap:

```java
// ARLO MODIFICATION - Add public method for restart logic
/**
 * Returns true if the player hasn't completed their first lap yet.
 * Used by PauseOverlay to allow restarts during first lap only in race-limited mode.
 */
public boolean isOnFirstLap() {
    Racer playerRacer = mGameWorld.getPlayerRacer(0);
    return playerRacer.getLapPositionComponent().getLapCount() <= 1;
}
```

**Location:** [RaceScreen.java:360](pixelwheels/core/src/com/agateau/pixelwheels/racescreen/RaceScreen.java#L360)

This allows restarts only during the first lap, preventing frustration from bad starts while still enforcing race limits.

## Flow Analysis: Edge Cases

### Device BACK Button Behavior

When FinishedOverlay is showing after a race:
- `RaceScreen.pauseRace()` checks `if (mGameWorld.getState() == GameWorld.State.FINISHED) return;`
- **Result:** Device BACK button does nothing when race is finished
- User MUST press an on-screen button

### Zero Races Edge Case

The user cannot normally reach track selection with 0 races because:
1. After last race → FinishedOverlay shows "BACK TO READING"
2. Pressing it → `onNextTrackPressed()` → ArloMaestro sees `completedRaces >= maxRaces`
3. ArloMaestro calls `game.showMainMenu()` → Activity finishes

**However**, we add a defensive check in `createSelectTrackScreen()` to handle any edge case:
- If somehow called with 0 races, immediately exit
- Logs a warning for debugging
- Returns dummy screen (immediately replaced by exit)

### RESTART Button Already Blocked

During race (via PauseOverlay), RESTART is hidden when we don't define `quickRace` variable.
After race (via FinishedOverlay), we modify to hide it for race-limited sessions.

## Testing

1. Start reading session, earn 2 races
2. Launch game, verify HUD shows "Race 1 of 2"
3. **First lap restart test:**
   - Start race, immediately pause
   - Verify RESTART button IS visible
   - Restart race, verify it works
4. **Second lap restart test:**
   - Race normally, complete first lap
   - Pause during second lap
   - Verify RESTART button is NOT visible
5. Complete race, verify "NEXT RACE (1 left)" button
6. Complete second race, verify "BACK TO READING" button
7. Verify motivational message appears
8. Force-stop app mid-race, relaunch, verify race count preserved

## References

- [FinishedOverlay.java:275-285](pixelwheels/core/src/com/agateau/pixelwheels/racescreen/FinishedOverlay.java#L275-L285) - Button creation
- [finishedoverlay.gdxui:35-36](pixelwheels/android/assets/screens/finishedoverlay.gdxui#L35-L36) - Conditional RESTART
- [Hud.java](pixelwheels/core/src/com/agateau/pixelwheels/racescreen/Hud.java) - HUD container
