package com.example.engine

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

class MusicEngine(private val context: Context) {

    fun playMusic(songTitle: String, platform: String): Boolean {
        return try {
            val query = songTitle.ifEmpty { "lofi hip hop radio" }
            Log.d("MusicEngine", "Triggering music playback for: $query on platform: $platform")

            val intent = when (platform.lowercase()) {
                "spotify" -> {
                    // Start spotify search or query
                    val spotifyUri = Uri.parse("spotify:search:${Uri.encode(query)}")
                    Intent(Intent.ACTION_VIEW, spotifyUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                "youtube" -> {
                    val youtubeUri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
                    Intent(Intent.ACTION_VIEW, youtubeUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                else -> {
                    // Standard media playback from search intent
                    Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                        putExtra(SearchManager.QUERY, query)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            }

            // Verify if intent resolves, otherwise fallback to system media search intent or web link
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                true
            } else {
                // Universal web link fallback
                val webUri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
                val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                true
            }
        } catch (e: Exception) {
            Log.e("MusicEngine", "Failed to start music search intent", e)
            false
        }
    }
}
