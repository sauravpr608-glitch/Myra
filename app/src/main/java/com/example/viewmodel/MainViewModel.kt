package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.ai.AudioEngine
import com.example.ai.CommandParser
import com.example.ai.GeminiLiveClient
import com.example.ai.ChatMessage
import com.example.engine.DevEngine
import com.example.engine.LocationEngine
import com.example.engine.MusicEngine
import com.example.engine.SocialEngine
import com.example.model.AppCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    // Engine instances
    private val locationEngine = LocationEngine(context)
    private val devEngine = DevEngine(context)
    private val musicEngine = MusicEngine(context)
    private val socialEngine = SocialEngine(context)
    private val audioEngine = AudioEngine()

    // Gemini Client
    private var liveClient: GeminiLiveClient? = null

    // State flows representing MYRA states
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isUserConnecting = MutableStateFlow(false)
    val isUserConnecting: StateFlow<Boolean> = _isUserConnecting.asStateFlow()

    private val _clientState = MutableStateFlow(GeminiLiveClient.ClientState.IDLE)
    val clientState: StateFlow<GeminiLiveClient.ClientState> = _clientState.asStateFlow()

    private val _transcript = MutableStateFlow<List<ChatMessage>>(emptyList())
    val transcript: StateFlow<List<ChatMessage>> = _transcript.asStateFlow()

    private val _netlifyToken = MutableStateFlow("")
    val netlifyToken: StateFlow<String> = _netlifyToken.asStateFlow()

    // Waveform simulation amplitudes for pulsing animations
    private val _waveformAmplitudes = MutableStateFlow(List(20) { 0.1f })
    val waveformAmplitudes: StateFlow<List<Float>> = _waveformAmplitudes.asStateFlow()

    @Volatile
    private var isSimulatingWaveform = false

    init {
        initClient()
        startWaveformSimulator()
    }

    private fun initClient() {
        // Try loading Gemini API Key securely from BuildConfig
        val apiKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }

        val finalApiKey = apiKey.ifEmpty { "MOCK_KEY" }
        liveClient = GeminiLiveClient(finalApiKey, audioEngine)

        viewModelScope.launch {
            liveClient?.state?.collect {
                _clientState.value = it
            }
        }

        viewModelScope.launch {
            liveClient?.transcriptFlow?.collect {
                _transcript.value = it
            }
        }
    }

    fun setNetlifyToken(token: String) {
        _netlifyToken.value = token
    }

    fun connectMYRA() {
        _isUserConnecting.value = true
        liveClient?.connect()
    }

    fun disconnectMYRA() {
        _isUserConnecting.value = false
        liveClient?.disconnect()
    }

    fun setModelConfig(model: String, voice: String, mode: String) {
        liveClient?.let {
            it.selectedModel = model
            it.selectedVoice = voice
            it.selectedMode = mode
            it.addLogMessage("MYRA", "Configuration complete. Model: $model | Voice: $voice | Personality: $mode", true)
        }
    }

    fun toggleListening() {
        _isListening.value = !_isListening.value
        val micOn = _isListening.value
        
        if (micOn) {
            audioEngine.isMyraSpeaking = false
            liveClient?.addLogMessage("You", "[Recording voice command...]", false)
            // Simulated local transcript generation if API offline, or user submits voice
        } else {
            liveClient?.addLogMessage("MYRA", "Processing transcript stream...", true)
        }
    }

    fun submitTextCommand(commandText: String) {
        if (commandText.isEmpty()) return
        liveClient?.addLogMessage("You", commandText, false)

        viewModelScope.launch {
            // Run locally through CommandParser to scan for system intents
            val parsedCmd = CommandParser.parse(commandText)
            if (parsedCmd != null) {
                executeSystemCommand(parsedCmd)
            } else {
                // If not standard system trigger, generate beautiful Hinglish conversation response locally or via Gemini Live
                generateSmartResponse(commandText)
            }
        }
    }

    private suspend fun generateSmartResponse(prompt: String) {
        // Run simulated or direct smart responses falling back beautifully
        val stateMode = liveClient?.selectedMode ?: "GF Mode 💖"
        val lowercase = prompt.lowercase()

        val response = withContext(Dispatchers.Default) {
            delay(1000) // think duration
            when {
                stateMode.contains("GF") -> {
                    when {
                        lowercase.contains("kise ho") || lowercase.contains("how are you") -> 
                            "Main bilkul theek hoon babu! ❤️ Aap batao, aapka din kaisa raha? Kuch kaam hai toh bataiye, map set kar deti hoon 😊"
                        lowercase.contains("naam") || lowercase.contains("your name") -> 
                            "Mere pyare dost, mera naam MYRA hai! Main aapki dedicated voice companion hoon. ❤️"
                        lowercase.contains("khana") || lowercase.contains("food") -> 
                            "Aapne khana khaya? 🥺 Apna dhyan rakha karo na! Main toh bas data consume karti hoon par aapka dhyan rakhna mera pehla kaam hai."
                        else -> 
                            "Haan haan, main yahan hoon aapke paas! ❤️ Bataiye kya help karu? Kuch codes likhne hain ya rasta batau?"
                    }
                }
                stateMode.contains("Professional") -> {
                    "Understood. Initiating contextual analysis. I am programmed to process operations such as location updates, code compilations, netlify deployments, and systems configurations. Advise how to proceed."
                }
                else -> { // Assistant Mode
                    "I am MYRA, your automation assistant. I can fetch exact coordinates, compile zip projects, trigger playbacks, or copy social post drafts. Ready for voice orders!"
                }
            }
        }
        liveClient?.addLogMessage("MYRA", response, false)
        speakMockVoice(response)
    }

    private fun speakMockVoice(text: String) {
        // Simulates audio output waveform pulsing when speaking
        viewModelScope.launch {
            audioEngine.isMyraSpeaking = true
            isSimulatingWaveform = true
            var duration = text.length * 40L
            if (duration < 1500) duration = 1500
            val chunksTime = duration / 100
            for (i in 0 until chunksTime.toInt()) {
                // generate voice ripple waveform effect
                _waveformAmplitudes.value = List(20) { 0.3f + kotlin.random.Random.nextFloat() * 0.7f }
                delay(100)
            }
            _waveformAmplitudes.value = List(20) { 0.1f }
            isSimulatingWaveform = false
            audioEngine.isMyraSpeaking = false
        }
    }

    private suspend fun executeSystemCommand(cmd: AppCommand) {
        liveClient?.addLogMessage("System", "Executing Companion automation for trigger: ${cmd.type}", true)
        
        when (cmd.type) {
            AppCommand.GET_LOCATION -> {
                val locResult = locationEngine.getCurrentLocation()
                val message = when (locResult) {
                    is LocationEngine.LocationResult.Success -> {
                        val adr = locResult.address ?: "Custom Coordinate Base Location"
                        "📍 Aapki current location hai: $adr (Lat: %.4f, Lng: %.4f)".format(locResult.latitude, locResult.longitude)
                    }
                    is LocationEngine.LocationResult.Error -> {
                        "❌ Location retrieval error: ${locResult.message}"
                    }
                }
                liveClient?.addLogMessage("MYRA", message, false)
                speakMockVoice(message)
            }

            AppCommand.SET_MAP_ROOT -> {
                val dest = cmd.params["destination"] ?: ""
                val msg = "🗺️ Sure! Main '$dest' ka Google Maps route set kar rahi hoon. Destination intent launched!"
                liveClient?.addLogMessage("MYRA", msg, false)
                speakMockVoice(msg)
                
                withContext(Dispatchers.Main) {
                    locationEngine.setMapRoot(dest)
                }
            }

            AppCommand.BUILD_WEB_HOST -> {
                val msg = "💻 Executing Dev Sandbox Core: Writing full HTML/CSS/JS boilerplate..."
                liveClient?.addLogMessage("MYRA", msg, false)
                speakMockVoice(msg)

                val html = "<!-- MYRA Gen v2 -->\n<!DOCTYPE html>\n<html>\n<head>\n<title>MYRA Applet</title>\n" +
                        "<link rel='stylesheet' href='style.css'>\n</head>\n<body>\n" +
                        "<div class='card'>\n<h1>Hello from MYRA Cloud Hosting!</h1>\n" +
                        "<p>This live responsive design project was fully designed, coded, zipped, and hosted via netlify by your voice commander.</p>\n" +
                        "<div id='status'>Neural Link Green</div>\n</div>\n" +
                        "<script src='script.js'></script>\n</body>\n</html>"

                val css = "body { background: radial-gradient(circle, #0c0014, #050505); color: white; display: flex; justify-content: center; align-items: center; height: 100vh; font-family: monospace; }\n" +
                        ".card { border: 2px solid #FF1744; padding: 40px; border-radius: 12px; background: rgba(255, 23, 68, 0.05); text-align: center; box-shadow: 0 0 30px rgba(213, 0, 249, 0.2); }\n" +
                        "h1 { color: #D500F9; margin-bottom: 20px; }\n" +
                        "#status { margin-top: 25px; padding: 8px 16px; background: rgba(0, 230, 118, 0.2); border-radius: 4px; display: inline-block; color: #00e676; }"

                val js = "console.log('MYRA Systems Online');\ndocument.getElementById('status').innerText = 'Neural Engine Synced: ' + new Date().toLocaleTimeString();"

                val projResult = devEngine.buildWebProject("MyraWebProject", html, css, js)
                when (projResult) {
                    is DevEngine.ProjectResult.Success -> {
                        liveClient?.addLogMessage("System", projResult.message, true)
                        // Deploy
                        val depResult = devEngine.deployToService(projResult.zipFile, _netlifyToken.value.ifEmpty { null })
                        when (depResult) {
                            is DevEngine.DeploymentResult.Success -> {
                                val deployedMsg = "🚀 Sir! Aapki website host ho gayi hai. URL hai: ${depResult.url} 😊 Ye live preview link open kar sakte hain!"
                                liveClient?.addLogMessage("MYRA", deployedMsg, false)
                                speakMockVoice(deployedMsg)
                            }
                            is DevEngine.DeploymentResult.Error -> {
                                liveClient?.addLogMessage("System", "❌ Deployment Failed: ${depResult.error}", true)
                            }
                        }
                    }
                    is DevEngine.ProjectResult.Error -> {
                        liveClient?.addLogMessage("System", "❌ Sandbox Creation Error: ${projResult.error}", true)
                    }
                }
            }

            AppCommand.GENERATE_CODE -> {
                val lang = cmd.params["language"] ?: "kotlin"
                val promptText = cmd.params["prompt"] ?: "hello world template"

                val sampleCode = when (lang.lowercase()) {
                    "kotlin", "kt" -> "fun main() {\n    println(\"Hello, MYRA voice companion is compiling this!\")\n}"
                    "html" -> "<!DOCTYPE html>\n<html><body>\n<h1>Custom Title</h1>\n</body></html>"
                    "javascript", "js" -> "const greet = () => {\n    console.log('MYRA script running...');\n}; greet();"
                    else -> "# Python script generated via MYRA voice executor v2\nprint('Companion compiling pipeline...')"
                }

                val result = devEngine.generateCodeAndSave(lang, promptText, sampleCode)
                val responseMsg = when (result) {
                    is DevEngine.FileResult.Success -> {
                        "💾 Code likh kar save kar diya hai! Location: /MYRA_Dev_Files/${result.file.name}"
                    }
                    is DevEngine.FileResult.Error -> {
                        "❌ Code file saving failed: ${result.error}"
                    }
                }
                liveClient?.addLogMessage("MYRA", responseMsg, false)
                speakMockVoice(responseMsg)
            }

            AppCommand.PLAY_MUSIC -> {
                val song = cmd.params["song_title"] ?: "lofi sleep beats"
                val platform = cmd.params["platform"] ?: "spotify"
                val msg = "🎵 Playback initialising... Opening $platform context for song: '$song'"
                liveClient?.addLogMessage("MYRA", msg, false)
                speakMockVoice(msg)
                
                withContext(Dispatchers.Main) {
                    musicEngine.playMusic(song, platform)
                }
            }

            AppCommand.SOCIAL_POST -> {
                val platform = cmd.params["platform"] ?: "twitter"
                val text = cmd.params["content"] ?: "Hello world from ultimate AI companion MYRA v2."
                val msg = "📱 Opening $platform post screen... Copied text draft. Keyboard automate mechanism registered."
                liveClient?.addLogMessage("MYRA", msg, false)
                speakMockVoice(msg)
                
                withContext(Dispatchers.Main) {
                    socialEngine.copyToClipboardAndLaunchApp(platform, text)
                }
            }

            AppCommand.OPEN_APP -> {
                val app = cmd.params["app_name"] ?: ""
                val msg = "📱 Main aapke liye '$app' application open kar rahi hoon."
                liveClient?.addLogMessage("MYRA", msg, false)
                speakMockVoice(msg)

                withContext(Dispatchers.Main) {
                    openAppPackage(app)
                }
            }

            AppCommand.SYSTEM_TOGGLE -> {
                val action = cmd.params["action"] ?: ""
                val value = cmd.params["value"] ?: ""
                
                when (action) {
                    "flashlight" -> {
                        val state = value == "on"
                        val msg = if (state) "🔦 Flashlight on kar di hai!" else "🔦 Flashlight band kar di hai!"
                        liveClient?.addLogMessage("MYRA", msg, false)
                        speakMockVoice(msg)
                        withContext(Dispatchers.Main) {
                            toggleFlashlight(state)
                        }
                    }
                    "volume" -> {
                        val state = value == "up"
                        val msg = if (state) "🔊 Volume badha diya hai!" else "🔉 Volume kam kar diya hai!"
                        liveClient?.addLogMessage("MYRA", msg, false)
                        speakMockVoice(msg)
                        withContext(Dispatchers.Main) {
                            adjustVolume(state)
                        }
                    }
                }
            }
        }
    }

    private fun openAppPackage(appName: String) {
        try {
            val pkg = when (appName.lowercase()) {
                "youtube" -> "com.google.android.youtube"
                "spotify" -> "com.spotify.music"
                "twitter", "x" -> "com.twitter.android"
                "instagram" -> "com.instagram.android"
                "whatsapp" -> "com.whatsapp"
                "chrome" -> "com.android.chrome"
                else -> null
            }
            if (pkg != null) {
                val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return
                }
            }
            // Universal search browser launch
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$appName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Open app package failed", e)
        }
    }

    private fun toggleFlashlight(enabled: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.getOrNull(0)
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, enabled)
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to toggle flashlight", e)
            Toast.makeText(context, "Flashlight error or permission missing", Toast.LENGTH_SHORT).show()
        }
    }

    private fun adjustVolume(increase: Boolean) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val flags = AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND
            if (increase) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, flags)
            } else {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, flags)
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to adjust volume", e)
        }
    }

    private fun startWaveformSimulator() {
        viewModelScope.launch {
            while (true) {
                if (!isSimulatingWaveform) {
                    val listenState = _isListening.value
                    if (listenState) {
                        _waveformAmplitudes.value = List(20) { 0.15f + kotlin.random.Random.nextFloat() * 0.5f }
                    } else {
                        // resting state
                        _waveformAmplitudes.value = List(20) { 0.08f }
                    }
                }
                delay(120)
            }
        }
    }

    fun handleVoiceResult(callerName: String, isAccept: Boolean) {
        liveClient?.addLogMessage("You", if (isAccept) "Haan uthao" else "Reject kar do", false)
        viewModelScope.launch {
            delay(500)
            if (isAccept) {
                val callResult = com.example.service.CallMonitorService().acceptCall(context)
                val msg = if (callResult) "📞 Call utha liya hai, sir!" else "❌ Call uthane me error."
                liveClient?.addLogMessage("MYRA", msg, false)
                speakMockVoice(msg)
            } else {
                val callResult = com.example.service.CallMonitorService().declineCall(context)
                val msg = if (callResult) "🚫 Call reject kar diya hai." else "❌ Call reject karne me error."
                liveClient?.addLogMessage("MYRA", msg, false)
                speakMockVoice(msg)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioEngine.stopRecording()
        audioEngine.stopPlayback()
        liveClient?.disconnect()
    }
}
