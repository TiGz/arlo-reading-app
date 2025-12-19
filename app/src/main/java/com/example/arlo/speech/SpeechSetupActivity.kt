package com.example.arlo.speech

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.arlo.MainActivity
import com.example.arlo.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Setup screen for speech recognition on Fire tablets.
 * Shows a checklist of requirements and guides the user through setup.
 */
class SpeechSetupActivity : AppCompatActivity() {

    private lateinit var setupManager: SpeechSetupManager

    // Views
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var checklistContainer: LinearLayout
    private lateinit var testButton: Button
    private lateinit var skipButton: Button
    private lateinit var continueButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        setupManager.runDiagnostics()
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupManager = SpeechSetupManager(this)

        // Check if we can skip setup entirely
        if (setupManager.isSetupComplete() || setupManager.isSetupSkipped()) {
            proceedToMain()
            return
        }

        setContentView(R.layout.activity_speech_setup)

        initViews()
        setupClickListeners()
        observeState()

        // Run initial diagnostics
        setupManager.runDiagnostics()
    }

    private fun initViews() {
        titleText = findViewById(R.id.titleText)
        subtitleText = findViewById(R.id.subtitleText)
        checklistContainer = findViewById(R.id.checklistContainer)
        testButton = findViewById(R.id.testButton)
        skipButton = findViewById(R.id.skipButton)
        continueButton = findViewById(R.id.continueButton)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)

        // Build checklist items
        buildChecklist()
    }

    private fun buildChecklist() {
        checklistContainer.removeAllViews()

        val instructions = setupManager.getSetupInstructions()
        for (instruction in instructions) {
            val itemView = layoutInflater.inflate(R.layout.item_setup_checklist, checklistContainer, false)

            val checkIcon = itemView.findViewById<ImageView>(R.id.checkIcon)
            val titleText = itemView.findViewById<TextView>(R.id.itemTitle)
            val descText = itemView.findViewById<TextView>(R.id.itemDescription)
            val helpButton = itemView.findViewById<Button>(R.id.helpButton)
            val adbText = itemView.findViewById<TextView>(R.id.adbCommand)

            titleText.text = instruction.title
            descText.text = instruction.description

            // Help button
            if (instruction.helpUrl != null) {
                helpButton.visibility = View.VISIBLE
                helpButton.setOnClickListener {
                    openUrl(instruction.helpUrl)
                }
            } else {
                helpButton.visibility = View.GONE
            }

            // ADB command
            if (instruction.adbCommand != null) {
                adbText.visibility = View.VISIBLE
                adbText.text = "ADB: ${instruction.adbCommand}"
            } else {
                adbText.visibility = View.GONE
            }

            // Store instruction ID for later updates
            itemView.tag = instruction.id

            checklistContainer.addView(itemView)
        }
    }

    private fun updateChecklist() {
        val instructions = setupManager.getSetupInstructions()

        for (i in 0 until checklistContainer.childCount) {
            val itemView = checklistContainer.getChildAt(i)
            val instructionId = itemView.tag as? String ?: continue
            val instruction = instructions.find { it.id == instructionId } ?: continue

            val checkIcon = itemView.findViewById<ImageView>(R.id.checkIcon)
            val isPassed = instruction.checkState()

            if (isPassed) {
                checkIcon.setImageResource(R.drawable.ic_check_circle)
                checkIcon.setColorFilter(ContextCompat.getColor(this, R.color.success_green))
            } else {
                checkIcon.setImageResource(R.drawable.ic_circle_outline)
                checkIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }
    }

    private fun setupClickListeners() {
        testButton.setOnClickListener {
            runTest()
        }

        skipButton.setOnClickListener {
            setupManager.markSetupSkipped()
            proceedToMain()
        }

        continueButton.setOnClickListener {
            if (setupManager.state.value.allChecksPassed) {
                setupManager.markSetupComplete()
            }
            proceedToMain()
        }

        // Request permission when clicking on the app permission item
        checklistContainer.post {
            for (i in 0 until checklistContainer.childCount) {
                val itemView = checklistContainer.getChildAt(i)
                if (itemView.tag == "app_permission") {
                    itemView.setOnClickListener {
                        requestAudioPermission()
                    }
                }
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            setupManager.state.collectLatest { state ->
                updateUI()
            }
        }
    }

    private fun updateUI() {
        val state = setupManager.state.value

        // Update checklist
        updateChecklist()

        // Update buttons
        if (state.isTestingInProgress) {
            testButton.isEnabled = false
            testButton.text = "Testing..."
            progressBar.visibility = View.VISIBLE
        } else {
            testButton.isEnabled = true
            testButton.text = "Run Test"
            progressBar.visibility = View.GONE
        }

        // Update subtitle with progress
        subtitleText.text = setupManager.getPlayStoreProgress()

        // Update status based on what's missing
        val nextComponent = state.getNextPlayStoreComponent()
        when {
            state.allChecksPassed -> {
                statusText.text = "All checks passed! Speech recognition is ready."
                statusText.setTextColor(ContextCompat.getColor(this, R.color.success_green))
                continueButton.text = "Continue"
                continueButton.isEnabled = true
            }
            state.testError != null -> {
                statusText.text = "Test failed: ${state.testError}"
                statusText.setTextColor(ContextCompat.getColor(this, R.color.error_red))
                continueButton.text = "Continue Anyway"
                continueButton.isEnabled = true
            }
            nextComponent != null -> {
                // Still installing Play Store components
                statusText.text = "Next: Install ${nextComponent.displayName} (step ${nextComponent.installOrder} of 4)"
                statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                continueButton.text = "Continue Without Speech"
                continueButton.isEnabled = true
            }
            !state.isGoogleAppInstalled -> {
                statusText.text = "Play Store ready! Now install the Google app from Play Store."
                statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                continueButton.text = "Continue Without Speech"
                continueButton.isEnabled = true
            }
            !state.isAppHasRecordPermission -> {
                statusText.text = "Tap 'Grant Microphone to Arlo' to enable the permission."
                statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                continueButton.text = "Continue Without Speech"
                continueButton.isEnabled = true
            }
            else -> {
                statusText.text = "Run the test to verify speech recognition works."
                statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                continueButton.text = "Continue Without Speech"
                continueButton.isEnabled = true
            }
        }
    }

    private fun runTest() {
        // First ensure we have permission
        if (!setupManager.state.value.isAppHasRecordPermission) {
            requestAudioPermission()
            return
        }

        setupManager.runSpeechTest { success, error ->
            runOnUiThread {
                updateUI()
            }
        }
    }

    private fun requestAudioPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                setupManager.runDiagnostics()
                updateUI()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                // Show explanation then request
                statusText.text = "Microphone permission is needed for speech recognition."
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    private fun proceedToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onResume() {
        super.onResume()
        // Re-run diagnostics when returning (user might have installed Google app)
        setupManager.runDiagnostics()
    }

    override fun onDestroy() {
        super.onDestroy()
        setupManager.destroy()
    }
}
