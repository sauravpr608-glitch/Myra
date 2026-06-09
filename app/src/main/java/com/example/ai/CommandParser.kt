package com.example.ai

import com.example.model.AppCommand
import java.util.Locale

object CommandParser {
    fun parse(text: String): AppCommand? {
        val clean = text.trim().lowercase(Locale.ROOT)
        if (clean.isEmpty()) return null

        // 1. GET_LOCATION
        if (clean.contains("kahan hoon") || clean.contains("kahaan hoon") ||
            clean.contains("get location") || clean.contains("my location") ||
            clean.contains("location batao") || clean.contains("coordinates")
        ) {
            return AppCommand(AppCommand.GET_LOCATION)
        }

        // 2. SET_MAP_ROOT / NAVIGATION
        // Pattern: [Place] ka map set karo, Navigate to [Place], [Place] ka root set karo
        val mapRegexes = listOf(
            Regex("(.*)\\s+(?:ka\\s+)?(?:map|root|route)\\s+set\\s+karo"),
            Regex("navigate\\s+to\\s+(.*)"),
            Regex("rasta\\s+batao\\s+(?:for\\s+)?(.*)"),
            Regex("go\\s+to\\s+(.*)")
        )
        for (regex in mapRegexes) {
            val match = regex.find(clean)
            if (match != null) {
                var dest = match.groupValues[1].trim()
                // Clean up destination text a bit
                dest = dest.replace(Regex("^(karo|set|locate|find|search)\\s+"), "")
                if (dest.isNotEmpty()) {
                    return AppCommand(AppCommand.SET_MAP_ROOT, mapOf("destination" to dest))
                }
            }
        }

        // 3. BUILD_WEB_HOST
        // Match: [Website Name] ki website banao aur host karo
        if (clean.contains("website banao") && clean.contains("host")) {
            val idx = clean.indexOf("website")
            val name = if (idx > 0) clean.substring(0, idx).trim() else "my_website"
            return AppCommand(AppCommand.BUILD_WEB_HOST, mapOf(
                "project_type" to "website",
                "description" to text,
                "name" to name
            ))
        }

        // 4. GENERATE_CODE
        // Match: [Language] me [X] ka code likho / write code for X in Y
        val codeRegexes = listOf(
            Regex("(.*)\\s+me\\s+(.*)\\s+ka\\s+code\\s+likho"),
            Regex("write\\s+(.*)\\s+code\\s+for\\s+(.*)"),
            Regex("code\\s+for\\s+(.*)\\s+in\\s+(.*)")
        )
        for (regex in codeRegexes) {
            val match = regex.find(clean)
            if (match != null) {
                val g1 = match.groupValues[1].trim()
                val g2 = match.groupValues[2].trim()
                return AppCommand(AppCommand.GENERATE_CODE, mapOf(
                    "language" to g1,
                    "prompt" to g2
                ))
            }
        }
        if (clean.contains("code likho") || clean.contains("write code")) {
            return AppCommand(AppCommand.GENERATE_CODE, mapOf(
                "language" to "kotlin",
                "prompt" to text
            ))
        }

        // 5. PLAY_MUSIC
        // Match: [Song Name] gaana bajao / Play [Song Name] / Play music
        val musicRegexes = listOf(
            Regex("(.*)\\s+gaana\\s+bajao"),
            Regex("(.*)\\s+song\\s+bajao"),
            Regex("play\\s+(.*)"),
            Regex("music\\s+play\\s+(.*)")
        )
        for (regex in musicRegexes) {
            val match = regex.find(clean)
            if (match != null) {
                val song = match.groupValues[1].trim()
                if (song != "music" && song.isNotEmpty()) {
                    val platform = if (clean.contains("youtube")) "youtube" else "spotify"
                    return AppCommand(AppCommand.PLAY_MUSIC, mapOf("song_title" to song, "platform" to platform))
                }
            }
        }
        if (clean.contains("play music") || clean.contains("gaana bajao") || clean.contains("song bajao")) {
            return AppCommand(AppCommand.PLAY_MUSIC, mapOf("song_title" to "lofi chill beats", "platform" to "spotify"))
        }

        // 6. SOCIAL_POST
        // Match: Twitter/Instagram pe post karo [Text]
        if (clean.contains("post karo") || clean.contains("tweet karo") || clean.contains("pe post")) {
            val platform = if (clean.contains("twitter") || clean.contains("tweet")) "twitter" else "instagram"
            var content = text
            // Strip triggers
            listOf("twitter pe post karo", "instagram pe post karo", "post karo", "tweet karo").forEach {
                content = content.replace(Regex("(?i)" + Regex.escape(it)), "")
            }
            return AppCommand(AppCommand.SOCIAL_POST, mapOf("platform" to platform, "content" to content.trim()))
        }

        // 7. OPEN_APP
        // Match: YouTube kholo / Open YouTube
        val appRegexes = listOf(
            Regex("(.*)\\s+kholo"),
            Regex("open\\s+(.*)"),
            Regex("chalao\\s+(.*)")
        )
        for (regex in appRegexes) {
            val match = regex.find(clean)
            if (match != null) {
                val app = match.groupValues[1].trim()
                if (app.isNotEmpty() && app != "apps" && app != "website") {
                    return AppCommand(AppCommand.OPEN_APP, mapOf("app_name" to app))
                }
            }
        }

        // 8. SYSTEM_TOGGLE
        // Match: Volume badhao / Flashlight on karo
        if (clean.contains("flashlight") || clean.contains("torch") || clean.contains("light")) {
            val action = if (clean.contains("off") || clean.contains("band")) "off" else "on"
            return AppCommand(AppCommand.SYSTEM_TOGGLE, mapOf("action" to "flashlight", "value" to action))
        }
        if (clean.contains("volume")) {
            val action = if (clean.contains("kam") || clean.contains("decrease") || clean.contains("slow")) "down" else "up"
            return AppCommand(AppCommand.SYSTEM_TOGGLE, mapOf("action" to "volume", "value" to action))
        }

        return null
    }
}
