# Enhanced Game Rewards System - Implementation Plan

## Overview

Transform the current single-trigger reward system (daily goal = 1-3 races) into a granular, parent-configurable milestone system with 7 distinct reward types, completion tracking, and a "missed stars" retry feature.

## Requirements Summary

| # | Milestone | Description | Parent Config |
|---|-----------|-------------|---------------|
| 1 | Daily Target | Reaching daily points goal | 0-5 races |
| 2 | Score Multiples | Each 2x, 3x, etc. of daily target | 0-5 races per multiple |
| 3 | Streak of N | Every N consecutive correct answers | 0-5 races |
| 4 | Page Completion | Finishing a page with X% stars | 0-5 races |
| 5 | Chapter Completion | Finishing a chapter with X% stars | 0-5 races |
| 6 | Book Completion | Finishing a book with X% stars | 0-5 races |
| 7 | Completion Threshold | Minimum % for pages/chapters/books | 50-100% slider |
| 8 | Missed Stars | Navigate back to failed sentences | Retry functionality |

---

## Database Changes (Migration 13 → 14)

### New Columns in ParentSettings

```kotlin
// Milestone 1-3: Instant rewards
racesForDailyTarget: Int = 1         // 0-5 races when target hit
racesPerMultiple: Int = 1            // 0-5 races per 100% multiple (2x, 3x...)
streakThreshold: Int = 5             // N value for streak milestones
racesPerStreakAchievement: Int = 1   // 0-5 races per N-streak

// Milestone 4-6: Completion rewards
racesPerPageCompletion: Int = 0      // 0-5 races per page
racesPerChapterCompletion: Int = 1   // 0-5 races per chapter
racesPerBookCompletion: Int = 2      // 0-5 races per book

// Milestone 7: Shared threshold
completionThreshold: Float = 0.8f    // 80% minimum for completion rewards
```

### New Entity: MilestoneClaimRecord

Prevents double-claiming of milestones per day:

```kotlin
@Entity(tableName = "milestone_claims", primaryKeys = ["date", "milestoneType", "milestoneId"])
data class MilestoneClaimRecord(
    val date: String,                 // "2025-12-22"
    val milestoneType: String,        // DAILY_TARGET, MULTIPLE, STREAK, PAGE, CHAPTER, BOOK
    val milestoneId: String,          // Unique identifier (e.g., "2X", "10", pageId, chapterTitle)
    val racesAwarded: Int,
    val claimedAt: Long
)
```

### New Entity: SentenceCompletionState

Tracks possible vs achieved stars for completion percentage:

```kotlin
@Entity(tableName = "sentence_completion_state", primaryKeys = ["bookId", "pageId", "sentenceIndex"])
data class SentenceCompletionState(
    val bookId: Long,
    val pageId: Long,
    val sentenceIndex: Int,
    val resolvedChapter: String?,          // Inferred chapter (see Chapter Inference System)
    val hasCollaborativeOpportunity: Boolean = true,
    val wasAttempted: Boolean = false,
    val wasCompletedSuccessfully: Boolean = false,
    val wasSkippedByTTS: Boolean = false,  // Missed star - can retry
    val starType: String? = null,          // GOLD, SILVER, BRONZE, null
    val completedAt: Long? = null
)
```

### New Columns in DailyStats

Track race sources for analytics:

```kotlin
racesFromDailyTarget: Int = 0
racesFromMultiples: Int = 0
racesFromStreaks: Int = 0
racesFromPages: Int = 0
racesFromChapters: Int = 0
racesFromBooks: Int = 0
highestMultipleReached: Int = 0
highestStreakMilestone: Int = 0
```

---

## Core Business Logic

### New Service: MilestoneRewardsService

Central coordinator for all milestone rewards:

```kotlin
class MilestoneRewardsService(
    private val statsRepository: ReadingStatsRepository,
    private val milestoneDao: MilestoneDao
) {
    // Called after each collaborative success
    suspend fun checkAndAwardMilestones(
        bookId: Long, pageId: Long,
        currentPoints: Int, currentStreak: Int
    ): MilestoneCheckResult

    // Called on page navigation
    suspend fun checkPageCompletion(bookId: Long, pageId: Long): EarnedMilestone?

    // Called when chapter changes
    suspend fun checkChapterCompletion(bookId: Long, chapterTitle: String): EarnedMilestone?

    // Called on book completion
    suspend fun checkBookCompletion(bookId: Long): EarnedMilestone?
}
```

### Completion Rate Calculation

```sql
-- Page completion rate
SELECT
    SUM(CASE WHEN wasCompletedSuccessfully = 1 THEN 1 ELSE 0 END) /
    SUM(CASE WHEN hasCollaborativeOpportunity = 1 THEN 1 ELSE 0 END)
FROM sentence_completion_state
WHERE bookId = :bookId AND pageId = :pageId
```

### Milestone Claim Prevention

Before awarding any milestone:
1. Check if already claimed today: `isMilestoneClaimed(date, type, id)`
2. Check daily cap: `racesEarned + newRaces <= maxRacesPerDay`
3. Only then insert claim record and increment racesEarned

---

## UI Changes

### Parent Settings: New Milestone Section

Expandable section within Game Rewards card:

| Control | Type | Range | Default |
|---------|------|-------|---------|
| Daily Goal Races | Slider | 0-5 | 1 |
| Extra Points Bonus | Slider | 0-5 | 1 |
| Streak Threshold | Slider | 5-20 | 5 |
| Streak Races | Slider | 0-5 | 1 |
| Page Completion Races | Slider | 0-5 | 0 |
| Chapter Completion Races | Slider | 0-5 | 1 |
| Book Completion Races | Slider | 0-5 | 2 |
| Completion Threshold | Slider | 50-100% | 80% |

### Reader: Missed Stars Button

Top bar button (visible when missed stars > 0):
- Shows count badge
- Opens dialog listing sentences with missed stars
- Tap navigates to that sentence for retry
- Successfully completing it fills in the star

---

## Files to Modify

### Data Layer
- `app/src/main/java/com/example/arlo/data/ReadingStats.kt` - Add entities & fields
- `app/src/main/java/com/example/arlo/data/ReadingStatsDao.kt` - Add queries
- `app/src/main/java/com/example/arlo/data/ReadingStatsRepository.kt` - Add methods
- `app/src/main/java/com/example/arlo/data/AppDatabase.kt` - Migration 13→14
- `app/src/main/java/com/example/arlo/data/Page.kt` - Add `resolvedChapter` field
- `app/src/main/java/com/example/arlo/data/BookDao.kt` - Add `getPagesBeforePage()`, update `updatePageWithOCRResult()`

### OCR Processing
- `app/src/main/java/com/example/arlo/ocr/OCRQueueManager.kt` - Add chapter inference at OCR time

### Game Logic
- `app/src/main/java/com/example/arlo/games/GameRewardsManager.kt` - Refactor for milestones

### Reader
- `app/src/main/java/com/example/arlo/UnifiedReaderViewModel.kt` - Track completion state
- `app/src/main/java/com/example/arlo/UnifiedReaderFragment.kt` - Missed stars UI

### Settings
- `app/src/main/java/com/example/arlo/ParentSettingsDialogFragment.kt` - Milestone controls
- `app/src/main/res/layout/dialog_parent_settings.xml` - Layout updates

### Application
- `app/src/main/java/com/example/arlo/ArloApplication.kt` - Initialize service

## Files to Create

- `app/src/main/java/com/example/arlo/games/MilestoneRewardsService.kt`
- `app/src/main/java/com/example/arlo/games/MissedStarsDialogFragment.kt`
- `app/src/main/res/layout/item_milestone_slider.xml`
- `app/src/main/res/layout/dialog_missed_stars.xml`

---

## Implementation Phases

### Phase 1: Database Foundation
1. Add `resolvedChapter` field to Page.kt
2. Add new entities to ReadingStats.kt (MilestoneClaimRecord, SentenceCompletionState)
3. Create migration in AppDatabase.kt (includes chapter backfill)
4. Add DAO methods for milestone queries and chapter resolution
5. Add repository methods

### Phase 2: Chapter Inference System
6. Add chapter resolution logic to OCRQueueManager
7. Implement `isValidChapterTitle()` heuristics (filter book titles, short strings)
8. Update `updatePageWithOCRResult()` to include resolvedChapter
9. Test with various book page scenarios

### Phase 3: Sentence Tracking
10. Track collaborative opportunity when entering sentence
11. Mark sentence complete on success (with resolvedChapter)
12. Mark sentence as skipped when TTS reads after max retries

### Phase 4: Milestone Service
13. Create MilestoneRewardsService
14. Implement claim prevention logic
15. Integrate with UnifiedReaderViewModel

### Phase 5: Completion Checks
16. Add page completion check on page navigation
17. Add chapter completion check on chapter change (using resolvedChapter)
18. Add book completion check when reaching last page

### Phase 6: Parent Settings UI
19. Create reusable milestone slider component
20. Add expandable milestone section to settings
21. Wire up save/load logic

### Phase 7: Missed Stars Feature
22. Create missed stars dialog
23. Add missed stars button to reader top bar
24. Implement navigation to missed sentences
25. Handle successful retry (clear skipped flag, award star)

---

## Key Design Decisions

1. **Daily Cap Enforcement**: All milestones contribute to a shared daily race cap (`maxRacesPerDay`)

2. **Completion Tracking**: Every sentence with a collaborative opportunity creates a `SentenceCompletionState` record, enabling accurate percentage calculation

3. **Chapter Detection**: See "Chapter Inference System" section below for detailed approach. **Books without chapters are treated as a single chapter** (chapter completion = book completion)

4. **Milestone Deduplication**: Composite key `(date, type, id)` prevents claiming the same milestone twice per day

5. **Missed Stars**: Sentences where `wasSkippedByTTS = true AND wasCompletedSuccessfully = false` are retry candidates

6. **Backward Compatibility**: All new ParentSettings fields have sensible defaults that approximate current behavior

7. **Streak Milestones Reset Daily**: Each day starts with a fresh streak counter. A child can earn streak rewards (5, 10, 15...) each day. Streaks don't persist across days.

8. **Reward Notifications**: Use **immediate celebratory toast** when milestones are earned. Races accumulate ("banked") and can be played later. Avoids interrupting reading flow with dialogs.

---

## Chapter Inference System

### The Problem

The `Page.chapterTitle` field is only populated when OCR detects a chapter heading **on that specific page**. But:
- Chapter titles typically appear only on the first page of a chapter
- Some pages show the book title instead of chapter title in the header
- Running headers may alternate (chapter on even pages, book title on odd)
- Most pages within a chapter have `chapterTitle = null`

### The Solution: Resolved Chapter at OCR Time

Add a new field `resolvedChapter` to `Page` that gets computed during OCR processing in `OCRQueueManager`:

```kotlin
// In Page.kt - add new field
val resolvedChapter: String? = null  // Inferred chapter from this or previous pages
```

### Chapter Inference Algorithm

When OCR completes for a page (in `OCRQueueManager.processPage()`):

```kotlin
private suspend fun resolveChapterForPage(bookId: Long, pageId: Long, detectedChapterTitle: String?): String? {
    // 1. If this page has a chapter title and it looks like a real chapter (not book title), use it
    if (detectedChapterTitle != null && isValidChapterTitle(detectedChapterTitle, bookId)) {
        return detectedChapterTitle
    }

    // 2. Look backward through previous pages to find the most recent chapter
    val previousPages = bookDao.getPagesBeforePage(bookId, pageId)
        .sortedByDescending { it.pageNumber }

    for (page in previousPages) {
        // Check if this page has a detected chapter title
        if (page.chapterTitle != null && isValidChapterTitle(page.chapterTitle, bookId)) {
            return page.chapterTitle
        }
        // Also check if it has a resolved chapter (from earlier inference)
        if (page.resolvedChapter != null) {
            return page.resolvedChapter
        }
    }

    // 3. No chapter found - return null (will be treated as "default chapter")
    return null
}

private suspend fun isValidChapterTitle(title: String, bookId: Long): Boolean {
    val book = bookDao.getBookById(bookId) ?: return true

    // Don't use book title as chapter title
    if (title.equals(book.title, ignoreCase = true)) return false

    // Don't use author name (if we had it stored)
    // Additional heuristics:
    // - Very short strings (< 3 chars) unlikely to be chapters
    // - Strings that are just numbers might be page numbers, not chapters
    if (title.length < 3) return false
    if (title.all { it.isDigit() }) return false

    return true
}
```

### Implementation in OCRQueueManager

Modify the page processing to resolve chapter:

```kotlin
// In processPage() after OCR result is parsed:
val resolvedChapter = resolveChapterForPage(
    bookId = page.bookId,
    pageId = page.id,
    detectedChapterTitle = pageResult.chapterTitle
)

// Update page with both detected and resolved
bookDao.updatePageWithOCRResult(
    pageId = page.id,
    text = fullText,
    json = sentencesJson,
    complete = lastComplete,
    pageLabel = pageResult.pageLabel,
    chapterTitle = pageResult.chapterTitle,  // Keep original detection
    resolvedChapter = resolvedChapter,       // Add inferred chapter
    confidence = pageResult.confidence
)
```

### Migration for Existing Pages

When the migration runs, backfill `resolvedChapter` for existing pages:

```sql
-- Step 1: Set resolvedChapter = chapterTitle where chapterTitle exists
UPDATE pages SET resolvedChapter = chapterTitle WHERE chapterTitle IS NOT NULL;

-- Step 2: Backfill from previous pages (done in Kotlin migration code)
```

```kotlin
// In migration code:
private fun backfillResolvedChapters(db: SupportSQLiteDatabase) {
    // Get all books
    val booksCursor = db.query("SELECT DISTINCT bookId FROM pages")
    val bookIds = mutableListOf<Long>()
    while (booksCursor.moveToNext()) {
        bookIds.add(booksCursor.getLong(0))
    }
    booksCursor.close()

    for (bookId in bookIds) {
        // Get pages in order
        val pagesCursor = db.query(
            "SELECT id, pageNumber, chapterTitle, resolvedChapter FROM pages WHERE bookId = ? ORDER BY pageNumber ASC",
            arrayOf(bookId.toString())
        )

        var lastKnownChapter: String? = null
        while (pagesCursor.moveToNext()) {
            val pageId = pagesCursor.getLong(0)
            val chapterTitle = pagesCursor.getString(2)
            val resolvedChapter = pagesCursor.getString(3)

            // If this page has a chapter title, update lastKnownChapter
            if (chapterTitle != null) {
                lastKnownChapter = chapterTitle
            }

            // If resolvedChapter is null but we have a lastKnownChapter, backfill
            if (resolvedChapter == null && lastKnownChapter != null) {
                db.execSQL(
                    "UPDATE pages SET resolvedChapter = ? WHERE id = ?",
                    arrayOf(lastKnownChapter, pageId)
                )
            }
        }
        pagesCursor.close()
    }
}
```

### Using Resolved Chapter for Completion Tracking

In `SentenceCompletionState`, use `resolvedChapter` instead of `chapterTitle`:

```kotlin
data class SentenceCompletionState(
    val bookId: Long,
    val pageId: Long,
    val sentenceIndex: Int,
    val resolvedChapter: String?,  // Use resolved, not detected
    // ... rest of fields
)
```

Chapter completion queries then group by `resolvedChapter`:

```sql
SELECT resolvedChapter,
       COUNT(CASE WHEN wasCompletedSuccessfully = 1 THEN 1 END) as completed,
       COUNT(CASE WHEN hasCollaborativeOpportunity = 1 THEN 1 END) as total
FROM sentence_completion_state
WHERE bookId = :bookId
GROUP BY resolvedChapter
```

### Benefits of This Approach

1. **Computed at OCR time** - No runtime overhead during reading
2. **Backward-looking inference** - Handles the common case of chapter title only on first page
3. **Preserves original detection** - `chapterTitle` still has raw OCR output for debugging
4. **Handles edge cases** - Book title in headers filtered out
5. **Migration-friendly** - Existing pages can be backfilled
