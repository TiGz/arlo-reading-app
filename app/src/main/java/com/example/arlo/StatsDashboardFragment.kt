package com.example.arlo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.arlo.data.ReadingStatsRepository
import com.example.arlo.data.WeeklyGoal
import com.example.arlo.databinding.FragmentStatsDashboardBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dashboard fragment showing reading statistics and progress.
 */
class StatsDashboardFragment : Fragment() {

    private var _binding: FragmentStatsDashboardBinding? = null
    private val binding get() = _binding!!

    private lateinit var statsRepository: ReadingStatsRepository

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

        setupUI()
        loadStats()
        observeStats()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    private fun loadStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Load lifetime stats
            val lifetimeStats = withContext(Dispatchers.IO) {
                statsRepository.getLifetimeStats()
            }

            // Load star breakdown
            val starBreakdown = withContext(Dispatchers.IO) {
                statsRepository.getLifetimeStarBreakdown()
            }

            // Display star breakdown
            binding.tvTotalPoints.text = starBreakdown.totalPoints.toString()
            binding.tvGoldStars.text = starBreakdown.goldStars.toString()
            binding.tvSilverStars.text = starBreakdown.silverStars.toString()
            binding.tvBronzeStars.text = starBreakdown.bronzeStars.toString()

            // Legacy total stars (hidden but kept for compatibility)
            binding.tvTotalStars.text = (lifetimeStats.totalStars ?: 0).toString()
            binding.tvPerfectWords.text = (lifetimeStats.totalPerfect ?: 0).toString()
            binding.tvBestStreak.text = (lifetimeStats.bestStreak ?: 0).toString()
            binding.tvSentencesRead.text = (lifetimeStats.totalSentences ?: 0).toString()
            binding.tvPagesCompleted.text = (lifetimeStats.totalPages ?: 0).toString()

            // Load reading time stats
            val totalTimeMs = withContext(Dispatchers.IO) {
                statsRepository.getTotalReadingTimeMs()
            }
            val todayTimeMs = withContext(Dispatchers.IO) {
                statsRepository.getTodayReadingTimeMs()
            }
            val sessionCount = withContext(Dispatchers.IO) {
                statsRepository.getTodaySessionCount()
            }

            // Format and display reading time
            binding.tvReadingTime.text = formatDuration(totalTimeMs)
            binding.tvTodayTime.text = "Today: ${formatDuration(todayTimeMs)}"
            binding.tvSessionCount.text = "$sessionCount sessions today"
        }
    }

    private fun observeStats() {
        // Observe weekly goal
        viewLifecycleOwner.lifecycleScope.launch {
            statsRepository.observeCurrentWeekGoal().collectLatest { goal ->
                updateWeeklyGoal(goal)
            }
        }
    }

    private fun updateWeeklyGoal(goal: WeeklyGoal?) {
        if (goal == null) {
            binding.tvWeeklyProgress.text = "0 of 5 days"
            binding.tvWeeklyStreak.text = "Week 1"
            resetDayIndicators()
            return
        }

        binding.tvWeeklyProgress.text = "${goal.completedDays} of ${goal.targetDays} days"
        binding.tvWeeklyStreak.text = if (goal.weeklyStreakCount > 0) {
            "Week ${goal.weeklyStreakCount} streak!"
        } else {
            "Start your streak!"
        }

        // Update day indicators
        val activeDays = goal.daysWithActivity.split(",").map { it.trim() }
        updateDayIndicator(binding.dayMon, "Mon" in activeDays)
        updateDayIndicator(binding.dayTue, "Tue" in activeDays)
        updateDayIndicator(binding.dayWed, "Wed" in activeDays)
        updateDayIndicator(binding.dayThu, "Thu" in activeDays)
        updateDayIndicator(binding.dayFri, "Fri" in activeDays)
        updateDayIndicator(binding.daySat, "Sat" in activeDays)
        updateDayIndicator(binding.daySun, "Sun" in activeDays)
    }

    private fun updateDayIndicator(view: TextView, isActive: Boolean) {
        if (isActive) {
            view.setBackgroundResource(R.drawable.bg_day_active)
            view.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
        } else {
            view.setBackgroundResource(R.drawable.bg_day_inactive)
            view.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        }
    }

    private fun resetDayIndicators() {
        listOf(binding.dayMon, binding.dayTue, binding.dayWed, binding.dayThu,
               binding.dayFri, binding.daySat, binding.daySun).forEach {
            updateDayIndicator(it, false)
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
