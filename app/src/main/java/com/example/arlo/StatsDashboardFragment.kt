package com.example.arlo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.arlo.data.Achievement
import com.example.arlo.data.DifficultWord
import com.example.arlo.data.ReadingStatsRepository
import com.example.arlo.data.WeeklyGoal
import com.example.arlo.databinding.FragmentStatsDashboardBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dashboard fragment showing reading statistics, achievements, and progress.
 */
class StatsDashboardFragment : Fragment() {

    private var _binding: FragmentStatsDashboardBinding? = null
    private val binding get() = _binding!!

    private lateinit var statsRepository: ReadingStatsRepository
    private val achievementAdapter = AchievementAdapter()

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

        binding.achievementsRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = achievementAdapter
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

            // Load difficult words
            val difficultWords = withContext(Dispatchers.IO) {
                statsRepository.getPracticeWords(5)
            }
            updateDifficultWords(difficultWords)

            // Initialize achievements if needed
            withContext(Dispatchers.IO) {
                statsRepository.initializeAchievements()
            }
        }
    }

    private fun observeStats() {
        // Observe weekly goal
        viewLifecycleOwner.lifecycleScope.launch {
            statsRepository.observeCurrentWeekGoal().collectLatest { goal ->
                updateWeeklyGoal(goal)
            }
        }

        // Observe mastered/practice counts
        viewLifecycleOwner.lifecycleScope.launch {
            statsRepository.observeMasteredWordCount().collectLatest { count ->
                binding.tvMasteredCount.text = "$count mastered"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            statsRepository.observePracticeWordCount().collectLatest { count ->
                binding.tvPracticeCount.text = "$count practicing"
            }
        }

        // Observe achievements
        viewLifecycleOwner.lifecycleScope.launch {
            statsRepository.observeAllAchievements().collectLatest { achievements ->
                achievementAdapter.submitList(achievements)
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

    private fun updateDifficultWords(words: List<DifficultWord>) {
        if (words.isEmpty()) {
            binding.tvDifficultWords.text = "No difficult words yet! Keep reading!"
        } else {
            val wordList = words.joinToString("\n") { word ->
                val successRate = (word.successRate * 100).toInt()
                val masteryStars = "★".repeat(word.masteryLevel) + "☆".repeat(5 - word.masteryLevel)
                "${word.word} - $successRate% ($masteryStars)"
            }
            binding.tvDifficultWords.text = wordList
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

/**
 * Adapter for displaying achievements in a RecyclerView.
 */
class AchievementAdapter : RecyclerView.Adapter<AchievementAdapter.ViewHolder>() {

    private var achievements: List<Achievement> = emptyList()

    fun submitList(list: List<Achievement>) {
        achievements = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_achievement, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(achievements[position])
    }

    override fun getItemCount() = achievements.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvAchievementName)
        private val tvDescription: TextView = itemView.findViewById(R.id.tvAchievementDescription)
        private val tvProgress: TextView = itemView.findViewById(R.id.tvAchievementProgress)

        fun bind(achievement: Achievement) {
            tvName.text = achievement.name
            tvDescription.text = achievement.description

            if (achievement.isUnlocked) {
                tvProgress.text = "✓ Unlocked!"
                tvProgress.setTextColor(ContextCompat.getColor(itemView.context, R.color.success_green))
                itemView.alpha = 1f
            } else {
                tvProgress.text = "${achievement.progress}/${achievement.goal}"
                tvProgress.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_secondary))
                itemView.alpha = 0.6f
            }
        }
    }
}
