package com.example.arlo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.arlo.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var apiKeyManager: ApiKeyManager
    private val handler = Handler(Looper.getMainLooper())

    // Track current navigation state
    private var currentNavItemId = R.id.nav_library

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiKeyManager = ApiKeyManager(this)

        // Setup bottom navigation
        setupBottomNavigation()

        // Check for API key first, then proceed
        checkApiKeyAndProceed(savedInstanceState)
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_library -> {
                    if (currentNavItemId != R.id.nav_library) {
                        navigateToFragment(LibraryFragment(), "library")
                        currentNavItemId = R.id.nav_library
                    }
                    true
                }
                R.id.nav_stats -> {
                    if (currentNavItemId != R.id.nav_stats) {
                        navigateToFragment(StatsDashboardFragment(), "stats")
                        currentNavItemId = R.id.nav_stats
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun navigateToFragment(fragment: Fragment, tag: String) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
            .replace(R.id.container, fragment, tag)
            .commit()
    }

    private fun checkApiKeyAndProceed(savedInstanceState: Bundle?) {
        if (!apiKeyManager.hasApiKey()) {
            showApiKeyDialog {
                // After API key is set, proceed with normal flow
                initializeApp(savedInstanceState)
            }
        } else {
            initializeApp(savedInstanceState)
        }
    }

    private fun initializeApp(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, LibraryFragment(), "library")
                .commit()
            currentNavItemId = R.id.nav_library
        }
    }

    /**
     * Show/hide bottom navigation bar.
     * Call this from fragments that need full-screen mode (e.g., reader).
     */
    fun setBottomNavVisible(visible: Boolean) {
        binding.bottomNavigation.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Reset navigation state when navigating to non-tab fragments (reader, camera, etc.)
     * This ensures the bottom nav will work correctly when returning to Library/Stats.
     */
    fun clearNavSelection() {
        currentNavItemId = -1
    }

    /**
     * Navigate to stats dashboard tab programmatically.
     */
    fun navigateToStats() {
        binding.bottomNavigation.selectedItemId = R.id.nav_stats
    }

    private fun showApiKeyDialog(onSuccess: () -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val input = EditText(this).apply {
            hint = "sk-ant-api03-..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
        }
        container.addView(input)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Anthropic API Key Required")
            .setMessage("Arlo uses Claude AI to extract text from book pages.\n\nVisit console.anthropic.com to get your API key.")
            .setView(container)
            .setPositiveButton("Save", null) // Set to null to prevent auto-dismiss
            .setNeutralButton("Get Key") { _, _ ->
                // Open Anthropic console
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://console.anthropic.com/settings/keys")))
                // Show dialog again after returning
                handler.postDelayed({ showApiKeyDialog(onSuccess) }, 500)
            }
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val key = input.text.toString().trim()
                if (key.startsWith("sk-ant-")) {
                    apiKeyManager.saveApiKey(key)
                    dialog.dismiss()
                    Toast.makeText(this, "API key saved!", Toast.LENGTH_SHORT).show()
                    onSuccess()
                } else if (key.isBlank()) {
                    Toast.makeText(this, "Please enter your API key", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Invalid key format. Should start with sk-ant-", Toast.LENGTH_LONG).show()
                }
            }
        }

        dialog.show()
    }

    /**
     * Show API key dialog from other parts of the app (e.g., when key becomes invalid)
     */
    fun showApiKeyDialogForReentry() {
        apiKeyManager.clearApiKey()
        showApiKeyDialog {
            // Refresh current fragment
            supportFragmentManager.findFragmentById(R.id.container)?.let { fragment ->
                supportFragmentManager.beginTransaction()
                    .detach(fragment)
                    .attach(fragment)
                    .commit()
            }
        }
    }

}
