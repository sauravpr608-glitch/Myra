package com.example.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

class SocialEngine(private val context: Context) {

    companion object {
        @Volatile
        var pendingPostContent: String? = null
        @Volatile
        var pendingPlatform: String? = null
    }

    fun copyToClipboardAndLaunchApp(platform: String, content: String): Boolean {
        return try {
            Log.d("SocialEngine", "Copying to clipboard: $content, Platform: $platform")
            // Send content to clipboard
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("MYRA Social Post", content)
            clipboard.setPrimaryClip(clip)

            // Setup cache for AccessibilityHelperService
            pendingPostContent = content
            pendingPlatform = platform.lowercase()

            val intent = when (platform.lowercase()) {
                "twitter", "x" -> {
                    // Try to open Twitter app directly or fallback to web
                    val intentX = context.packageManager.getLaunchIntentForPackage("com.twitter.android")
                    intentX ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://twitter.com/intent/tweet?text=${Uri.encode(content)}"))
                }
                "instagram" -> {
                    val intentInsta = context.packageManager.getLaunchIntentForPackage("com.instagram.android")
                    intentInsta ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/")).apply {
                        // Instagram has no simple web tweet prefill api, open web or share sheet
                    }
                }
                else -> {
                    // Generic share sheet fallback
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, content)
                    }
                }
            }

            intent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("SocialEngine", "Failed to copy and launch app for $platform", e)
            false
        }
    }
}
