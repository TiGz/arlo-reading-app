package com.example.arlo.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.arlo.ArloApplication
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val tag = "SyncWorker"

        return try {
            val app = applicationContext as ArloApplication
            val syncManager = app.cloudSyncManager

            Log.d(tag, "Starting background sync")
            val result = syncManager.syncAll()

            if (result.success) {
                Log.d(tag, "Background sync completed successfully: ${result.synced}")
                Result.success()
            } else {
                Log.w(tag, "Sync failed: ${result.error}")
                // Retry on failure (with exponential backoff)
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(tag, "Sync worker error", e)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "arlo_cloud_sync"
        private const val TAG = "SyncWorker"

        /**
         * Schedule periodic background sync (every 15 minutes when connected).
         */
        fun schedulePeriodicSync(context: Context) {
            // Skip if Supabase not configured
            if (!SupabaseConfig.isConfigured()) {
                Log.d(TAG, "Supabase not configured, skipping periodic sync schedule")
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                15, TimeUnit.MINUTES,  // Repeat interval
                5, TimeUnit.MINUTES    // Flex interval (can run 5 min early)
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1, TimeUnit.MINUTES
                )
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,  // Don't replace if already scheduled
                syncRequest
            )

            Log.d(TAG, "Scheduled periodic sync (every 15 minutes)")
        }

        /**
         * Trigger an immediate one-time sync.
         */
        fun syncNow(context: Context) {
            if (!SupabaseConfig.isConfigured()) {
                Log.d(TAG, "Supabase not configured, skipping immediate sync")
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .addTag("${WORK_NAME}_immediate")
                .build()

            WorkManager.getInstance(context).enqueue(syncRequest)
            Log.d(TAG, "Queued immediate sync")
        }

        /**
         * Cancel all scheduled syncs.
         */
        fun cancelSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled periodic sync")
        }
    }
}
