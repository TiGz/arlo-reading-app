package com.example.arlo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.arlo.data.Book
import com.example.arlo.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var apiKeyManager: ApiKeyManager
    private val handler = Handler(Looper.getMainLooper())
    private val repository by lazy { (application as ArloApplication).repository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiKeyManager = ApiKeyManager(this)

        // Check for API key first, then proceed
        checkApiKeyAndProceed(savedInstanceState)
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
                .replace(R.id.container, LibraryFragment())
                .commit()
        }

        // Check for legacy books that need migration
        checkForLegacyBooks()

    }

    private fun checkForLegacyBooks() {
        lifecycleScope.launch {
            val legacyBooks = repository.getBooksWithoutSentences()
            if (legacyBooks.isNotEmpty()) {
                showLegacyMigrationDialog(legacyBooks)
            }
        }
    }

    private fun showLegacyMigrationDialog(legacyBooks: List<Book>) {
        val bookCount = legacyBooks.size
        val bookWord = if (bookCount == 1) "book" else "books"
        val bookNames = legacyBooks.take(3).joinToString(", ") { "\"${it.title}\"" }
        val moreText = if (bookCount > 3) " and ${bookCount - 3} more" else ""

        AlertDialog.Builder(this)
            .setTitle("Update Required")
            .setMessage(
                "Found $bookCount old $bookWord that use a legacy format:\n\n" +
                "$bookNames$moreText\n\n" +
                "These books were created before the new sentence-based reading system. " +
                "Would you like to remove them?"
            )
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch {
                    repository.deleteLegacyBooks(legacyBooks.map { it.id })
                    Toast.makeText(
                        this@MainActivity,
                        "$bookCount legacy $bookWord removed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Keep for Now", null)
            .show()
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
