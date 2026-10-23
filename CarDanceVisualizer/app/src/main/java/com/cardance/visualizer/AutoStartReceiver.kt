package com.cardance.visualizer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Best-effort auto-start. Filters the usual playback-state broadcasts so the
 * visualiser comes up when music starts.
 *
 * Head units are inconsistent here: some emit the AOSP `com.android.music.*`
 * actions, some only send `MEDIA_BUTTON`, and some send nothing at all to
 * third-party receivers. If it never fires on your unit, either add that
 * firmware's broadcast action to the manifest filter, or use the launcher icon
 * (the activity also re-arms the tap on resume).
 */
class AutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "playback broadcast: ${intent.action}")
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { context.startActivity(launch) }
            .onFailure { Log.w(TAG, "could not start MainActivity", it) }
    }

    private companion object {
        const val TAG = "AutoStartReceiver"
    }
}
