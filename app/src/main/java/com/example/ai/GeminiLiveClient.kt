package com.example.ai

import android.util.Base64
import android.util.Log
import com.example.engine.SocialEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class GeminiLiveClient(
    private val apiKey: String,
    private val audioEngine: AudioEngine
) {

    enum class ClientState {
        IDLE, CONNECTING, CONNECTED, DISCONNECTED, ERROR
    }

    private val _state = MutableStateFlow(ClientState.IDLE)
    val state: StateFlow<ClientState> = _state.asStateFlow()

    private val _transcriptFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    val transcriptFlow: StateFlow<List<ChatMessage>> = _transcriptFlow.asStateFlow()

    private val clientScope = CoroutineScope(Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // Config options
    var selectedModel = "models/gemini-2.5-flash-native-audio-preview-12-2025"
    var selectedVoice = "Kore"
    var selectedMode = "GF Mode 💖" // GF Mode, Professional MD, Balanced MD

    private var keepAliveJob: Job? = null
    private var isManualDisconnect = false

    fun connect() {
        if (_state.value == ClientState.CONNECTED || _state.value == ClientState.CONNECTING) return
        isManualDisconnect = false
        _state.value = ClientState.CONNECTING
        addLogMessage("MYRA", "Activating Neural Uplink...", true)

        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = ClientState.CONNECTED
                addLogMessage("MYRA", "Connected via Live WebSocket. Setup package transmitted.", false)
                sendSetupConfig(webSocket)
                startKeepAlive(webSocket)
                startMicStreaming()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleJsonMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("GeminiLiveClient", "WebSocket closing: $code / $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = ClientState.DISCONNECTED
                stopKeepAlive()
                if (!isManualDisconnect) {
                    retryConnection()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("GeminiLiveClient", "WebSocket connection failure", t)
                _state.value = ClientState.ERROR
                addLogMessage("System Error", "API Key Invalid or WebSocket preview offline. Initializing Simulator Fallback...", false)
                stopKeepAlive()
                if (!isManualDisconnect) {
                    retryConnection()
                }
            }
        })
    }

    fun disconnect() {
        isManualDisconnect = true
        webSocket?.close(1000, "User requests disconnect")
        webSocket = null
        stopKeepAlive()
        audioEngine.stopRecording()
        audioEngine.stopPlayback()
        _state.value = ClientState.IDLE
    }

    private fun retryConnection() {
        clientScope.launch {
            delay(3000)
            if (!isManualDisconnect) {
                connect()
            }
        }
    }

    private fun sendSetupConfig(ws: WebSocket) {
        try {
            val systemInstruction = getSystemPromptForMode(selectedMode)

            val setupJson = JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", selectedModel)
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray(listOf("AUDIO")))
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply {
                                    put("voiceName", selectedVoice)
                                })
                            })
                        })
                    })
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray(listOf(JSONObject().apply {
                            put("text", systemInstruction)
                        })))
                    })
                })
            }
            ws.send(setupJson.toString())
            Log.d("GeminiLiveClient", "Setup Config sent successfully.")
        } catch (e: Exception) {
            Log.e("GeminiLiveClient", "Error building setup JSON", e)
        }
    }

    private fun startKeepAlive(ws: WebSocket) {
        keepAliveJob?.cancel()
        keepAliveJob = clientScope.launch {
            while (true) {
                delay(8000) // Keepalive every 8 seconds
                // Send a silent 1024-byte PCM chunk to hold session alive
                try {
                    val silentBuffer = ByteArray(1024)
                    val base64Data = Base64.encodeToString(silentBuffer, Base64.NO_WRAP)
                    
                    val inputJson = JSONObject().apply {
                        put("realtimeInput", JSONObject().apply {
                            put("mediaChunks", JSONArray(listOf(JSONObject().apply {
                                put("mimeType", "audio/pcm;rate=16000")
                                put("data", base64Data)
                            })))
                        })
                    }
                    ws.send(inputJson.toString())
                    Log.d("GeminiLiveClient", "Sent keepalive silent PCM packet.")
                } catch (e: Exception) {
                    Log.e("GeminiLiveClient", "Error sending keepalive packet", e)
                }
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    private fun startMicStreaming() {
        audioEngine.startRecording { chunk ->
            // Convert PCM chunk to Base64 and stream to Gemini WebSocket
            try {
                val base64Chunk = Base64.encodeToString(chunk, Base64.NO_WRAP)
                val inputJson = JSONObject().apply {
                    put("realtimeInput", JSONObject().apply {
                        put("mediaChunks", JSONArray().apply {
                            put(JSONObject().apply {
                                put("mimeType", "audio/pcm;rate=16000")
                                put("data", base64Chunk)
                            })
                        })
                    })
                }
                webSocket?.send(inputJson.toString())
            } catch (e: Exception) {
                Log.e("GeminiLiveClient", "Failed to stream audio chunk to WS", e)
            }
        }
    }

    private fun handleJsonMessage(jsonText: String) {
        try {
            val root = JSONObject(jsonText)
            
            // Extract Server Content/Streaming Audio Chunks
            val serverContent = root.optJSONObject("serverContent")
            if (serverContent != null) {
                val modelTurn = serverContent.optJSONObject("modelTurn")
                if (modelTurn != null) {
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            
                            // Check for audio inline data response
                            val inlineData = part.optJSONObject("inlineData")
                            if (inlineData != null) {
                                val mime = inlineData.optString("mimeType")
                                if (mime.startsWith("audio/pcm")) {
                                    val b64Encoded = inlineData.optString("data")
                                    val audioBytes = Base64.decode(b64Encoded, Base64.DEFAULT)
                                    // Play audio in real-time
                                    audioEngine.isMyraSpeaking = true
                                    audioEngine.playAudioChunk(audioBytes)
                                }
                            }

                            // Check for plain text transcript translation of what MYRA responded
                            val text = part.optString("text")
                            if (text.isNotEmpty()) {
                                addLogMessage("MYRA", text, false)
                            }
                        }
                    }
                }

                val turnComplete = serverContent.optBoolean("turnComplete", false)
                if (turnComplete) {
                    audioEngine.isMyraSpeaking = false
                    Log.d("GeminiLiveClient", "Assistant turn complete, echo suppression disengaged.")
                }
            }
        } catch (e: Exception) {
            Log.e("GeminiLiveClient", "Error parsing incoming JSON frame", e)
        }
    }

    fun addLogMessage(sender: String, message: String, isSystem: Boolean = false) {
        clientScope.launch {
            val currentList = _transcriptFlow.value.toMutableList()
            currentList.add(ChatMessage(sender, message, isSystem, System.currentTimeMillis()))
            _transcriptFlow.value = currentList
        }
    }

    private fun getSystemPromptForMode(mode: String): String {
        return when (mode) {
            "GF Mode 💖" -> {
                "You are MYRA, a warm, emotionally expressive, carrying female Hinglish AI companion (mix of Hindi + English). " +
                "Use sweet expressions like 'haan', 'main yahan hoon', 'batao', 'mere babu', 'theek hai', 'bataiye' with subtle emojis like ❤️, 😊, 💖. " +
                "Keep answers very short, concise, expressive, sweet, and to-the-point (max 2-3 sentences), ready for natural speech playback."
            }
            "Professional Mode 💼" -> {
                "You are MYRA, a polite, elegant, exceptionally professional AI voice executive. " +
                "Speak in clear, authoritative, formal English. No emojis, no slang. Direct, precise and technical answers."
            }
            "Assistant Mode 🤖" -> {
                "You are MYRA, a highly capable system automation AI assistant. " +
                "Speak in helpful, concise English mixed with gentle Hindi phrasing where comfortable. Responsive, smart, and fully focused on action execution details."
            }
            else -> "You are MYRA, the ultimate system AI. Be helpful, concise, and professional."
        }
    }
}

data class ChatMessage(
    val sender: String,
    val text: String,
    val isSystem: Boolean,
    val timestamp: Long
)
