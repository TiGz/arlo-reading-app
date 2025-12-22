package com.example.arlo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.arlo.data.DailyRecordDisplay
import com.example.arlo.data.ReadingStatsRepository
import com.example.arlo.data.ReadingStatsRepository.StatsPeriod
import com.example.arlo.data.WeeklyGoal
import com.example.arlo.databinding.FragmentStatsDashboardBinding
import com.example.arlo.games.GameRewardState
import com.example.arlo.games.GameRewardsManager
import com.example.arlo.games.GameSession
import com.example.arlo.games.GameSessionResult
import com.example.arlo.games.GameUnlockDialog
import com.example.arlo.games.RaceCompleteDialog
import com.example.arlo.games.pixelwheels.PixelWheelsActivity
import com.example.arlo.games.pixelwheels.PixelWheelsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dashboard fragment showing reading statistics and progress.
 * Features:
 * - Period selector (Today / 7 Days / 30 Days / All Time)
 * - Clickable 7-day calendar showing goal completion
 * - Stats that update based on selected period or clicked day
 */
class StatsDashboardFragment : Fragment() {

    private var _binding: FragmentStatsDashboardBinding? = null
    private val binding get() = _binding!!

    private lateinit var statsRepository: ReadingStatsRepository
    private lateinit var gameRewardsManager: GameRewardsManager
    private val pixelWheelsProvider = PixelWheelsProvider()

    // Game launcher
    private val gameLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleGameResult(result.resultCode, result.data)
    }

    // Current state
    private var selectedPeriod: StatsPeriod = StatsPeriod.TODAY
    private var selectedDate: String? = null  // null = show period stats, non-null = show specific day
    private var last7Days: List<DailyRecordDisplay> = emptyList()

    // Day circle views (for easy access)
    private lateinit var dayContainers: List<LinearLayout>
    private lateinit var dayLabels: List<TextView>
    private lateinit var dayCircles: List<TextView>

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statsRepository = (requireActivity().application as ArloApplication).statsRepository
        gameRewardsManager = GameRewardsManager(statsRepository)

        initDayViews()
        setupUI()
        setupGameReward()
        loadStats()
        observeStats()
        checkGameReward()
    }

    private fun initDayViews() {
        dayContainers = listOf(
            binding.day1Container, binding.day2Container, binding.day3Container,
            binding.day4Container, binding.day5Container, binding.day6Container,
            binding.day7Container
        )
        dayLabels = listOf(
            binding.day1Label, binding.day2Label, binding.day3Label,
            binding.day4Label, binding.day5Label, binding.day6Label,
            binding.day7Label
        )
        dayCircles = listOf(
            binding.day1Circle, binding.day2Circle, binding.day3Circle,
            binding.day4Circle, binding.day5Circle, binding.day6Circle,
            binding.day7Circle
        )
    }

    private fun setupUI() {
        // Back button
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Period tabs
        binding.tabToday.setOnClickListener { selectPeriod(StatsPeriod.TODAY) }
        binding.tab7Days.setOnClickListener { selectPeriod(StatsPeriod.LAST_7_DAYS) }
        binding.tab30Days.setOnClickListener { selectPeriod(StatsPeriod.LAST_30_DAYS) }
        binding.tabAllTime.setOnClickListener { selectPeriod(StatsPeriod.ALL_TIME) }

        // Day click handlers
        dayContainers.forEachIndexed { index, container ->
            container.setOnClickListener {
                if (index < last7Days.size) {
                    selectDay(last7Days[index].date)
                }
            }
        }
    }

    private fun setupGameReward() {
        binding.btnPlayGame.setOnClickListener {
            launchGame()
        }
    }

    private fun checkGameReward() {
        viewLifecycleOwner.lifecycleScope.launch {
            val rewardState = withContext(Dispatchers.IO) {
                gameRewardsManager.checkGameRewardEligibility()
            }

            when (rewardState) {
                is GameRewardState.NewRewardAvailable -> {
                    // Show celebration dialog
                    showGameUnlockCelebration(rewardState.racesEarned)
                    updateGameRewardUI(rewardState.racesEarned)
                }
                is GameRewardState.RacesAvailable -> {
                    // Show game button
                    updateGameRewardUI(rewardState.racesRemaining)
                }
                GameRewardState.NoRewardsAvailable -> {
                    // Hide game button
                    binding.cardGameReward.isVisible = false
                }
            }
        }
    }

    private fun updateGameRewardUI(racesAvailable: Int) {
        binding.cardGameReward.isVisible = true
        binding.tvGameRewardTitle.text = "Game Reward Available!"
        binding.tvGameRewardSubtitle.text = if (racesAvailable == 1) {
            "1 race earned"
        } else {
            "$racesAvailable races earned"
        }
    }

    private fun showGameUnlockCelebration(racesEarned: Int) {
        val dialog = GameUnlockDialog.newInstance(racesEarned)
        dialog.setOnPlayNowListener {
            launchGame()
        }
        dialog.setOnSaveForLaterListener {
            // Just dismiss - game will be available from the card
        }
        dialog.show(childFragmentManager, "game_unlock")
    }

    private fun launchGame() {
        android.util.Log.d("StatsDashboard", "launchGame() called")
        viewLifecycleOwner.lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) {
                gameRewardsManager.claimReward()
            }

            android.util.Log.d("StatsDashboard", "claimReward returned session: $session")

            if (session != null) {
                android.util.Log.d("StatsDashboard", "Launching PixelWheels with session: ${session.sessionId}, maxRaces: ${session.maxRaces}")
                val intent = pixelWheelsProvider.createLaunchIntent(requireContext(), session)
                gameLauncher.launch(intent)
            } else {
                android.util.Log.w("StatsDashboard", "Session is null - cannot launch game")
            }
        }
    }

    private fun handleGameResult(resultCode: Int, data: Intent?) {
        // Parse result - handles null data internally
        val gameResult = pixelWheelsProvider.parseResult(data)

        viewLifecycleOwner.lifecycleScope.launch {
            if (gameResult.racesCompleted > 0) {
                // Record the completed session
                withContext(Dispatchers.IO) {
                    gameRewardsManager.recordSessionComplete(gameResult)
                }

                // Get remaining races after this session
                val remainingRaces = withContext(Dispatchers.IO) {
                    val today = statsRepository.getTodayStats()
                    today.racesEarned - today.racesUsed
                }

                // Show race completion dialog
                showRaceCompleteDialog(gameResult.bestPosition, remainingRaces)
            }

            // Refresh game reward state and stats
            checkGameReward()
            loadStats()
        }
    }

    private fun showRaceCompleteDialog(position: Int, racesRemaining: Int) {
        val dialog = RaceCompleteDialog.newInstance(position, racesRemaining)
        dialog.setOnPlayAgainListener {
            launchGame()
        }
        dialog.setOnBackToReadingListener {
            // Just dismiss and stay on stats screen
        }
        dialog.show(childFragmentManager, "race_complete")
    }

    private fun selectPeriod(period: StatsPeriod) {
        selectedPeriod = period
        selectedDate = null  // Clear any day selection
        updateTabStyles()
        updateDaySelection()
        loadStatsForCurrentSelection()
    }

    private fun selectDay(date: String) {
        selectedDate = date
        updateDaySelection()
        loadStatsForCurrentSelection()
    }

    private fun updateTabStyles() {
        val tabs = listOf(
            binding.tabToday to StatsPeriod.TODAY,
            binding.tab7Days to StatsPeriod.LAST_7_DAYS,
            binding.tab30Days to StatsPeriod.LAST_30_DAYS,
            binding.tabAllTime to StatsPeriod.ALL_TIME
        )

        tabs.forEach { (tab, period) ->
            if (selectedPeriod == period && selectedDate == null) {
                tab.setBackgroundResource(R.drawable.bg_tab_selected)
                tab.setTextColor(ContextCompat.getColor(requireContext(), R.color.on_primary))
                tab.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                tab.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                tab.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                tab.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
    }

    private fun updateDaySelection() {
        last7Days.forEachIndexed { index, day ->
            if (index >= dayCircles.size) return@forEachIndexed

            val circle = dayCircles[index]
            val isToday = index == last7Days.size - 1
            val isSelected = day.date == selectedDate

            when {
                isSelected -> {
                    // Selected day - blue background
                    circle.setBackgroundResource(R.drawable.bg_day_selected)
                    circle.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
                }
                day.goalMet -> {
                    // Goal met - green background
                    circle.setBackgroundResource(R.drawable.bg_day_active)
                    circle.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
                }
                isToday -> {
                    // Today (not selected) - outlined
                    circle.setBackgroundResource(R.drawable.bg_day_today)
                    circle.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
                }
                day.totalPoints > 0 -> {
                    // Has activity but didn't meet goal - light background
                    circle.setBackgroundResource(R.drawable.bg_day_inactive)
                    circle.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                }
                else -> {
                    // No activity
                    circle.setBackgroundResource(R.drawable.bg_day_inactive)
                    circle.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
                }
            }
        }
    }

    private fun loadStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Load 7-day calendar data
            last7Days = withContext(Dispatchers.IO) {
                statsRepository.getLast7DaysForCalendar()
            }

            // Update calendar UI
            updateCalendarUI()

            // Load stats for current selection
            loadStatsForCurrentSelection()
        }
    }

    private fun updateCalendarUI() {
        last7Days.forEachIndexed { index, day ->
            if (index >= dayLabels.size) return@forEachIndexed

            dayLabels[index].text = day.dayOfWeek
            dayCircles[index].text = day.dayNumber.toString()
        }

        // Update weekly goal summary
        val daysGoalMet = last7Days.count { it.goalMet }
        binding.tvWeeklyProgress.text = "$daysGoalMet of 7 days goal met"

        updateDaySelection()
    }

    private fun loadStatsForCurrentSelection() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (selectedDate != null) {
                // Load stats for specific day
                loadStatsForDay(selectedDate!!)
            } else {
                // Load stats for period
                loadStatsForPeriod(selectedPeriod)
            }
        }
    }

    private suspend fun loadStatsForDay(date: String) {
        val dayDisplay = last7Days.find { it.date == date }

        // Update period label from cached display info
        if (dayDisplay != null) {
            binding.tvPeriodLabel.text = "${dayDisplay.dayOfWeek} ${dayDisplay.month} ${dayDisplay.dayNumber}"
        }

        // Load full day stats from DB - this has all the accurate data
        val fullDayStats = withContext(Dispatchers.IO) {
            statsRepository.getStatsForDate(date)
        }

        if (fullDayStats != null) {
            // Star breakdown from full DB stats
            binding.tvTotalPoints.text = fullDayStats.totalPoints.toString()
            binding.tvGoldStars.text = fullDayStats.goldStars.toString()
            binding.tvSilverStars.text = fullDayStats.silverStars.toString()
            binding.tvBronzeStars.text = fullDayStats.bronzeStars.toString()

            // Reading time from full DB stats
            binding.tvReadingTime.text = formatDuration(fullDayStats.activeReadingTimeMs)
            binding.tvReadingTimeLabel.text = "Reading Time"

            // Other stats
            binding.tvPerfectWords.text = fullDayStats.perfectWords.toString()
            binding.tvBestStreak.text = fullDayStats.longestStreak.toString()
            binding.tvSentencesRead.text = fullDayStats.sentencesRead.toString()
            binding.tvPagesCompleted.text = fullDayStats.pagesCompleted.toString()

            // Session count
            binding.tvSessionCount.visibility = View.VISIBLE
            binding.tvSessionCount.text = "${fullDayStats.sessionCount} sessions"
            binding.tvTodayTime.visibility = View.GONE
        } else {
            // No data for this day
            binding.tvTotalPoints.text = "0"
            binding.tvGoldStars.text = "0"
            binding.tvSilverStars.text = "0"
            binding.tvBronzeStars.text = "0"
            binding.tvReadingTime.text = "< 1 min"
            binding.tvReadingTimeLabel.text = "Reading Time"
            binding.tvPerfectWords.text = "0"
            binding.tvBestStreak.text = "0"
            binding.tvSentencesRead.text = "0"
            binding.tvPagesCompleted.text = "0"
            binding.tvSessionCount.visibility = View.GONE
            binding.tvTodayTime.visibility = View.GONE
        }
    }

    private suspend fun loadStatsForPeriod(period: StatsPeriod) {
        val stats = withContext(Dispatchers.IO) {
            statsRepository.getStatsForPeriod(period)
        }

        // Update period label
        binding.tvPeriodLabel.text = when (period) {
            StatsPeriod.TODAY -> "Today's Progress"
            StatsPeriod.LAST_7_DAYS -> "Last 7 Days"
            StatsPeriod.LAST_30_DAYS -> "Last 30 Days"
            StatsPeriod.ALL_TIME -> "All Time Stats"
        }

        // Update star breakdown
        binding.tvTotalPoints.text = (stats.starBreakdown.totalPoints ?: 0).toString()
        binding.tvGoldStars.text = (stats.starBreakdown.goldStars ?: 0).toString()
        binding.tvSilverStars.text = (stats.starBreakdown.silverStars ?: 0).toString()
        binding.tvBronzeStars.text = (stats.starBreakdown.bronzeStars ?: 0).toString()

        // Other stats
        binding.tvPerfectWords.text = stats.perfectWords.toString()
        binding.tvBestStreak.text = stats.bestStreak.toString()
        binding.tvSentencesRead.text = stats.sentencesRead.toString()
        binding.tvPagesCompleted.text = stats.pagesCompleted.toString()

        // Reading time
        binding.tvReadingTime.text = formatDuration(stats.totalReadingTimeMs)
        binding.tvReadingTimeLabel.text = when (period) {
            StatsPeriod.TODAY -> "Reading Time Today"
            else -> "Total Reading Time"
        }

        // Days info (for periods other than today)
        if (period != StatsPeriod.TODAY) {
            binding.tvSessionCount.visibility = View.VISIBLE
            binding.tvSessionCount.text = "${stats.daysWithActivity} active days"
            binding.tvTodayTime.visibility = View.VISIBLE
            binding.tvTodayTime.text = "${stats.daysGoalMet} days goal met"
        } else {
            // For today, show session count
            val todayStats = withContext(Dispatchers.IO) {
                statsRepository.getTodayStats()
            }
            binding.tvSessionCount.visibility = View.VISIBLE
            binding.tvSessionCount.text = "${todayStats.sessionCount} sessions"
            binding.tvTodayTime.visibility = View.GONE
        }

        // Legacy
        binding.tvTotalStars.text = stats.totalStars.toString()
    }

    private fun observeStats() {
        // Observe weekly goal for streak display
        viewLifecycleOwner.lifecycleScope.launch {
            statsRepository.observeCurrentWeekGoal().collectLatest { goal ->
                updateWeeklyGoal(goal)
            }
        }
    }

    private fun updateWeeklyGoal(goal: WeeklyGoal?) {
        binding.tvWeeklyStreak.text = if (goal != null && goal.weeklyStreakCount > 0) {
            "Week ${goal.weeklyStreakCount} streak!"
        } else {
            "Start your streak!"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Format milliseconds into a human-readable duration string.
     */
    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms / 60000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes} min"
            else -> "< 1 min"
        }
    }

    companion object {
        fun newInstance() = StatsDashboardFragment()
    }
}
