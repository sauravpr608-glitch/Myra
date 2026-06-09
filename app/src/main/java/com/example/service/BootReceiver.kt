package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Device boot completed. Securing MYRA startup...")
            Toast.makeText(context, "MYRA: Core Systems Initialized on Boot.", Toast.LENGTH_LONG).show()
            
            // Start overlay service if permission is present
            try {
                val overlayIntent = Intent(context, MyraOverlayService::class.java)
                context.startService(overlayIntent)
            } catch (e: Exception) {
                Log.e("BootReceiver", "Could not automatically launch OverlayService", e)
            }
        }
    }
}
