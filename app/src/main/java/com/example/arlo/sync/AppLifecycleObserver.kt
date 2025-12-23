package com.example.arlo.sync

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Observes app lifecycle to trigger sync when app goes to background.
 */
class AppLifecycleObserver(
    private val cloudSyncManager: CloudSyncManager,
    private val scope: CoroutineScope
) : DefaultLifecycleObserver {

    private val tag = "AppLifecycle"

    override fun onStop(owner: LifecycleOwner) {
        // App going to background - sync to ensure data is saved
        Log.d(tag, "App going to background, triggering sync")
        scope.launch {
            cloudSyncManager.syncAll()
        }
    }

    companion object {
        /**
         * Register the lifecycle observer with the process lifecycle.
         */
        fun register(manager: CloudSyncManager, scope: CoroutineScope) {
            if (!SupabaseConfig.isConfigured()) return

            ProcessLifecycleOwner.get().lifecycle.addObserver(
                AppLifecycleObserver(manager, scope)
            )
            Log.d("AppLifecycle", "Registered lifecycle observer for background sync")
        }
    }
}
