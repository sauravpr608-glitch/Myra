package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.MainActivity

class PowerButtonReceiver : BroadcastReceiver() {

    companion object {
        private var lastClickTime = 0L
        private val CLICK_INTERVAL = 1500L // 1.5 seconds
        private var clickCount = 0
        private val handler = Handler(Looper.getMainLooper())
        private val resetRunnable = Runnable {
            clickCount = 0
            Log.d("PowerButtonReceiver", "Click count reset to 0.")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_SCREEN_OFF) {
            val currentTime = System.currentTimeMillis()
            Log.d("PowerButtonReceiver", "Power/Screen status changed: $action")

            handler.removeCallbacks(resetRunnable)

            if (currentTime - lastClickTime < CLICK_INTERVAL) {
                clickCount++
                if (clickCount >= 2) { // Double click detected
                    Log.d("PowerButtonReceiver", "Double power click gesture captured! Animating MYRA Overlay...")
                    Toast.makeText(context, "MYRA: Double Click Gesture Detected. Activating Companion...", Toast.LENGTH_SHORT).show()
                    launchMyraMain(context)
                    clickCount = 0
                }
            } else {
                clickCount = 1
            }

            lastClickTime = currentTime
            handler.postDelayed(resetRunnable, CLICK_INTERVAL)
        }
    }

    private fun launchMyraMain(context: Context) {
        try {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("GESTURE_LAUNCH", true)
            }
            context.startActivity(launchIntent)
        } catch (e: Exception) {
            Log.e("PowerButtonReceiver", "Failed to launch MainActivity", e)
        }
    }
}
