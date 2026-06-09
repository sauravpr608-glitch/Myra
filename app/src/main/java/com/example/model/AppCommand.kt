package com.example.model

data class AppCommand(
    val type: String,
    val params: Map<String, String> = emptyMap()
) {
    companion object {
        const val GET_LOCATION = "GET_LOCATION"
        const val SET_MAP_ROOT = "SET_MAP_ROOT"
        const val BUILD_WEB_HOST = "BUILD_WEB_HOST"
        const val GENERATE_CODE = "GENERATE_CODE"
        const val PLAY_MUSIC = "PLAY_MUSIC"
        const val SOCIAL_POST = "SOCIAL_POST"
        const val OPEN_APP = "OPEN_APP"
        const val SYSTEM_TOGGLE = "SYSTEM_TOGGLE"
    }
}
