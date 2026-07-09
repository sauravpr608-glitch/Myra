package com.example

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ai.GeminiLiveClient
import com.example.ai.ChatMessage
import com.example.service.CallMonitorService
import com.example.service.MyraOverlayService
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private var incomingCallReceiver: BroadcastReceiver? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val viewModel: MainViewModel = viewModel()
                var showSettingsSheet by remember { mutableStateOf(false) }
                val context = LocalContext.current

                // Permissions Launcher
                val permissionsToRequest = arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_CONTACTS
                )

                val selectPermissions = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
                    if (recordAudioGranted) {
                        Toast.makeText(context, "Voice permissions online.", Toast.LENGTH_SHORT).show()
                    }
                }

                LaunchedEffect(Unit) {
                    val missing = permissionsToRequest.filter {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isNotEmpty()) {
                        selectPermissions.launch(missing.toTypedArray())
                    }
                    // Start overlay service safely if permission is ready
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(context)) {
                        context.startService(Intent(context, MyraOverlayService::class.java))
                    }
                }

                // Register receiver for incoming phone state events
                DisposableEffect(Unit) {
                    incomingCallReceiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            if (intent.action == CallMonitorService.ACTION_INCOMING_CALL) {
                                val name = intent.getStringExtra(CallMonitorService.EXTRA_CALLER_NAME) ?: "Caller"
                                Toast.makeText(context, "Incoming from $name", Toast.LENGTH_LONG).show()
                                
                                // Direct voice interaction simulation on incoming ring:
                                viewModel.submitTextCommand("Sir, $name ka call aa raha hai. Uthau ya reject karu?")
                            }
                        }
                    }
                    val filter = IntentFilter(CallMonitorService.ACTION_INCOMING_CALL)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        registerReceiver(incomingCallReceiver, filter, RECEIVER_EXPORTED)
                    } else {
                        registerReceiver(incomingCallReceiver, filter)
                    }

                    onDispose {
                        incomingCallReceiver?.let { unregisterReceiver(it) }
                    }
                }

                MyraMainScreen(
                    viewModel = viewModel,
                    onOpenSettings = { showSettingsSheet = true },
                    onRequestOverlayPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        } else {
                            Toast.makeText(context, "Overlay not required below Android M", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                // Bottom sheet for Advanced Settings Panel
                if (showSettingsSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showSettingsSheet = false },
                        containerColor = Color(0xFF0C0714),
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                    ) {
                        MyraSettingsLayout(
                            viewModel = viewModel,
                            onClose = { showSettingsSheet = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MyraMainScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
    onRequestOverlayPermission: () -> Unit
) {
    val context = LocalContext.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()

    val clientState by viewModel.clientState.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isUserConnecting by viewModel.isUserConnecting.collectAsState()
    val transcript by viewModel.transcript.collectAsState()
    val waveformAmplitudes by viewModel.waveformAmplitudes.collectAsState()
    val netlifyToken by viewModel.netlifyToken.collectAsState()

    val isSessionLocked by viewModel.isSessionLocked.collectAsState()
    val sessionTimeRemaining by viewModel.sessionTimeRemaining.collectAsState()
    var showAdVideoPlayer by remember { mutableStateOf(false) }

    var textInput by remember { mutableStateOf("") }
    val lazyListState = rememberLazyListState()

    // Scroll chat automatically to bottom when messages arrive
    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) {
            lazyListState.animateScrollToItem(transcript.size - 1)
        }
    }

    // Gradient Mesh Background drawing
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(Color(0xFF030108))
                // Draw Sci-Fi corner blobs
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFFF1744).copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(0f, 0f),
                        radius = size.width * 0.85f
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFD500F9).copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(size.width, size.height),
                        radius = size.width * 0.85f
                    )
                )
            }
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Monospace Telemetry Counters Header
            TelemetryHeaderSection(
                isSessionLocked = isSessionLocked,
                sessionTimeRemaining = sessionTimeRemaining,
                onRequestOverlayPermission = onRequestOverlayPermission
            )

            Spacer(modifier = Modifier.height(16.dp))

            // AI Neural Orb Section (with pulse relative to ClientState)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CompanionNeuralOrb(
                        clientState = clientState,
                        isListening = isListening,
                        modifier = Modifier.testTag("core_neural_orb")
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // 20-Bar Reactive Soundwave Visualizer Row
                    AudioWaveformRow(amplitudes = waveformAmplitudes)

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = when (clientState) {
                            GeminiLiveClient.ClientState.CONNECTED -> "NEURAL LINK GREEN / SPECS STABLE"
                            GeminiLiveClient.ClientState.CONNECTING -> "SYNCING QUANTUM CHANNELS..."
                            GeminiLiveClient.ClientState.ERROR -> "QUANTUM BREAK DETECTED / SIM RUNNING"
                            else -> "ZOYA SYSTEM OFFLINE / STANDBY"
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = when (clientState) {
                            GeminiLiveClient.ClientState.CONNECTED -> Color(0xFF00FFCC)
                            GeminiLiveClient.ClientState.CONNECTING -> Color(0xFFFFFF33)
                            else -> Color(0xFFFF1744).copy(alpha = 0.8f)
                        },
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Embedded Console Chat Bubbles RecyclerView equivalent
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.0f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF090610).copy(alpha = 0.7f))
                    .border(1.dp, Color(0xFFFF1744).copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                if (isSessionLocked) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Locked Session",
                                tint = Color(0xFFFF1744),
                                modifier = Modifier.size(48.dp)
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Text(
                                text = "ZOYA APP AD-GATE SYSTEM",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF1744),
                                letterSpacing = 2.sp
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Text(
                                text = "Saurav bhai ke Zoya App me aapka swagat hai! Mujhse 15 minute live baat karne ke liye, kirpa karke screen par diye gaye 'Watch Video' button par click karke ek chhota ad dekhein.",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.8f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            
                            Spacer(modifier = Modifier.height(20.dp))
                            
                            Button(
                                onClick = { showAdVideoPlayer = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFD500F9)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("watch_video_button"),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Watch Icon",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Watch Video",
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                } else if (transcript.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Standby Console",
                                tint = Color(0xFFD500F9).copy(alpha = 0.4f),
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "CONSOLE INITIALIZED. WAITING FOR TRANSCRIPT COMMAND...",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.4f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(transcript) { item ->
                            ConsoleBubbleCard(item)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom Console row input + action items + mic listener
            ConsoleBottomRow(
                textInput = textInput,
                isListening = isListening,
                isConnecting = isUserConnecting,
                clientState = clientState,
                onValueChange = { textInput = it },
                onSend = {
                    if (textInput.isNotEmpty()) {
                        viewModel.submitTextCommand(textInput)
                        textInput = ""
                        keyboardController?.hide()
                    }
                },
                onMicToggle = {
                    viewModel.toggleListening()
                },
                onOpenSettings = onOpenSettings,
                onConnectToggle = {
                    if (clientState == GeminiLiveClient.ClientState.CONNECTED || clientState == GeminiLiveClient.ClientState.CONNECTING) {
                        viewModel.disconnectMYRA()
                    } else {
                        viewModel.connectMYRA()
                    }
                }
            )

            Spacer(modifier = Modifier.navigationBarsPadding().height(16.dp))
        }

        // Animated Ad Video Player simulation overlay Box blocking underlying interaction
        if (showAdVideoPlayer) {
            var adCountdown by remember { mutableStateOf(5) }
            LaunchedEffect(Unit) {
                while (adCountdown > 0) {
                    delay(1000)
                    adCountdown -= 1
                }
                viewModel.triggerUnityAdAndUnlock()
                showAdVideoPlayer = false
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFA05020B))
                    .clickable(enabled = false) {}, // consume clicks to block background interaction
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp)
                ) {
                    // Unity Ads Logo Header
                    Text(
                        text = "unity ads",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.SansSerif
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "REWARDED VIDEO AD RUNNING",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF00FFCC),
                        letterSpacing = 1.5.sp
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // High tech simulated neural visualizer frame
                    Box(
                        modifier = Modifier
                            .size(180.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF0C0714))
                            .border(2.dp, Color(0xFFD500F9), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = adCountdown / 5f,
                            color = Color(0xFFFF1744),
                            strokeWidth = 6.dp,
                            modifier = Modifier.size(100.dp)
                        )
                        Text(
                            text = "$adCountdown",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // Metadata specs
                    Text(
                        text = "Game ID: 800083344",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "Placement: Rewarded_Android",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White.copy(alpha = 0.6f)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Please do not close this ad screen. Your reward will be granted automatically in a few seconds.",
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        color = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.widthIn(max = 280.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun TelemetryHeaderSection(
    isSessionLocked: Boolean,
    sessionTimeRemaining: Int,
    onRequestOverlayPermission: () -> Unit
) {
    val context = LocalContext.current
    var memoryInUse by remember { mutableStateOf(0L) }
    var batteryLevel by remember { mutableStateOf(0) }
    var utcTime by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val format = SimpleDateFormat("HH:mm:ss 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        while (true) {
            // Live UTC Clock
            utcTime = format.format(Date())

            // Memory Status
            val runtime = Runtime.getRuntime()
            memoryInUse = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

            // Battery Status query
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

            delay(1000)
        }
    }

    val minutes = sessionTimeRemaining / 60
    val seconds = sessionTimeRemaining % 60
    val timeFormatted = String.format("%02d:%02d", minutes, seconds)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .background(Color(0xFF0F091A).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .border(0.5.dp, Color(0xFFD500F9).copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TelemetryIndicator(label = "RAM", value = "${memoryInUse}MB")
            TelemetryIndicator(label = "BATT", value = "$batteryLevel%")
            TelemetryIndicator(
                label = "ZOYA",
                value = if (isSessionLocked) "LOCKED" else timeFormatted,
                valueColor = if (isSessionLocked) Color(0xFFFF1744) else Color(0xFF00FFCC)
            )
        }

        IconButton(
            onClick = onRequestOverlayPermission,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "Authorize SYSTEM_ALERT_WINDOW",
                tint = Color(0xFF00FFCC),
                modifier = Modifier.size(16.dp)
            )
        }

        Text(
            text = utcTime,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFFFF1744),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("telemetry_clock")
        )
    }
}

@Composable
fun TelemetryIndicator(label: String, value: String, valueColor: Color = Color(0xFF00FFCC)) {
    Row {
        Text(
            text = "$label:",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.4f)
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = valueColor,
            modifier = Modifier.padding(start = 2.dp)
        )
    }
}

@Composable
fun CompanionNeuralOrb(
    clientState: GeminiLiveClient.ClientState,
    isListening: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition("orb_pulsation")

    // Animations of sizes/glows
    val scaleFactor by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(5000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Glowing colors mapping the exact MYRA engine state:
    // Listening (Crimson/Magenta), Thinking (Cyan/Blue), Speaking (Purple), Idle/Standby (Neon Red)
    val orbColors = when {
        isListening -> listOf(Color(0xFFFF1744), Color(0xFFE040FB))
        clientState == GeminiLiveClient.ClientState.CONNECTING -> listOf(Color(0xFFFFEA00), Color(0xFFFF9100))
        clientState == GeminiLiveClient.ClientState.CONNECTED -> listOf(Color(0xFF8E24AA), Color(0xFFD500F9)) // ready / active voice link purple
        else -> listOf(Color(0xFFFF1744), Color(0xFF800020)) // idle red mesh
    }

    val glowColor = when {
        isListening -> Color(0xFFFF1744)
        clientState == GeminiLiveClient.ClientState.CONNECTING -> Color(0xFFFFEA00)
        clientState == GeminiLiveClient.ClientState.CONNECTED -> Color(0xFFD500F9)
        else -> Color(0xFFFF1744).copy(alpha = 0.6f)
    }

    Box(
        modifier = modifier
            .size(260.dp),
        contentAlignment = Alignment.Center
    ) {
        // Futuristic Glowing Halo Backplate background
        Box(
            modifier = Modifier
                .size(190.dp)
                .scale(scaleFactor)
                .blur(36.dp)
                .background(glowColor.copy(alpha = 0.35f), CircleShape)
        )

        // Custom canvas visualizer elements
        Canvas(
            modifier = Modifier
                .size(200.dp)
                .scale(scaleFactor)
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.width / 2f - 10.dp.toPx()

            // Orbit Outer Tech Rings
            drawCircle(
                color = glowColor.copy(alpha = 0.4f),
                radius = radius + 8.dp.toPx(),
                style = Stroke(width = 1.0.dp.toPx())
            )

            // Inner Orb mesh
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(orbColors[0], orbColors[1], Color.Transparent),
                    center = center,
                    radius = radius
                )
            )

            // Intersecting cybernetic lines showing active computations
            drawArc(
                color = Color.White.copy(alpha = 0.3f),
                startAngle = rotationAngle,
                sweepAngle = 120f,
                useCenter = false,
                style = Stroke(width = 2.0.dp.toPx())
            )

            drawArc(
                color = Color(0xFF00FFCC).copy(alpha = 0.4f),
                startAngle = rotationAngle + 180f,
                sweepAngle = 90f,
                useCenter = false,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        // Concentric Core Node
        Box(
            modifier = Modifier
                .size(45.dp)
                .background(Color(0xFF050308).copy(alpha = 0.9f), CircleShape)
                .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // Pulse node dots
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(glowColor, CircleShape)
                    .shadow(10.dp, CircleShape)
            )
        }
    }
}

@Composable
fun AudioWaveformRow(amplitudes: List<Float>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        amplitudes.forEachIndexed { index, amp ->
            val animatedHeight by animateFloatAsState(
                targetValue = Math.max(0.12f, amp) * 44f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium),
                label = "bar_height"
            )

            val barColor = when {
                index < 5 -> Color(0xFFFF1744)
                index < 15 -> Color(0xFFD500F9)
                else -> Color(0xFF00FFCC)
            }

            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(animatedHeight.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor)
            )
        }
    }
}

@Composable
fun ConsoleBubbleCard(message: ChatMessage) {
    val isMyra = message.sender == "MYRA" || message.sender == "Zoya"
    val isSystem = message.isSystem

    val cardBg = when {
        isSystem -> Color(0xFF130E20).copy(alpha = 0.4f)
        isMyra -> Color(0xFF0F0314).copy(alpha = 0.7f)
        else -> Color(0xFFFF1744).copy(alpha = 0.05f)
    }

    val cardBorderColor = when {
        isSystem -> Color.White.copy(alpha = 0.15f)
        isMyra -> Color(0xFFD500F9).copy(alpha = 0.5f)
        else -> Color(0xFFFF1744).copy(alpha = 0.5f)
    }

    val nameColor = when {
        isSystem -> Color.White.copy(alpha = 0.5f)
        isMyra -> Color(0xFFD500F9)
        else -> Color(0xFFFF1744)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = if (isMyra || isSystem) Alignment.Start else Alignment.End
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(cardBg)
                .border(1.dp, cardBorderColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isSystem) "◆ SYSTEM CORE" else "◆ ${message.sender.uppercase()}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = nameColor,
                        fontWeight = FontWeight.Bold
                    )
                    
                    val timeString = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
                    Text(
                        text = timeString,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        color = Color.White.copy(alpha = 0.3f)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = message.text,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontFamily = if (isSystem) FontFamily.Monospace else FontFamily.SansSerif,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
fun ConsoleBottomRow(
    textInput: String,
    isListening: Boolean,
    isConnecting: Boolean,
    clientState: GeminiLiveClient.ClientState,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicToggle: () -> Unit,
    onOpenSettings: () -> Unit,
    onConnectToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Toggle Settings Option
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .size(46.dp)
                .background(Color(0xFF0F0A1A), CircleShape)
                .border(1.dp, Color(0xFFD500F9).copy(alpha = 0.4f), CircleShape)
                .testTag("settings_button")
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Myra Prefs Settings",
                tint = Color(0xFFD500F9)
            )
        }

        // Voice Socket Connection Activator
        IconButton(
            onClick = onConnectToggle,
            modifier = Modifier
                .size(46.dp)
                .background(Color(0xFF0F0A1A), CircleShape)
                .border(
                    width = 1.dp,
                    color = if (clientState == GeminiLiveClient.ClientState.CONNECTED) Color(0xFF00FFCC) else Color(0xFFFF1744).copy(alpha = 0.4f),
                    shape = CircleShape
                )
                .testTag("connect_socket_button")
        ) {
            Icon(
                imageVector = if (clientState == GeminiLiveClient.ClientState.CONNECTED) Icons.Default.Check else Icons.Default.Close,
                contentDescription = "Neural Socket Hook",
                tint = if (clientState == GeminiLiveClient.ClientState.CONNECTED) Color(0xFF00FFCC) else Color(0xFFFF1744)
            )
        }

        // Console text entry field
        TextField(
            value = textInput,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    "Send system command...",
                    color = Color.White.copy(alpha = 0.35f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            },
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .border(0.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(23.dp))
                .testTag("text_command_input"),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF100A1D),
                unfocusedContainerColor = Color(0xFF08040F),
                disabledContainerColor = Color(0xFF08040F),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            singleLine = true,
            trailingIcon = {
                if (textInput.isNotEmpty()) {
                    IconButton(onClick = onSend, modifier = Modifier.testTag("submit_text_command")) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send Intent",
                            tint = Color(0xFF00FFCC)
                        )
                    }
                }
            }
        )

        // Low Level MIC trigger button
        IconButton(
            onClick = onMicToggle,
            modifier = Modifier
                .size(46.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = if (isListening) {
                            listOf(Color(0xFFFF1744), Color(0xFFD500F9))
                        } else {
                            listOf(Color(0xFF1E1433), Color(0xFF0C0714))
                        }
                    ),
                    shape = CircleShape
                )
                .border(
                    width = 1.dp,
                    color = if (isListening) Color.White else Color(0xFFFF1744).copy(alpha = 0.4f),
                    shape = CircleShape
                )
                .testTag("mic_toggle_button")
        ) {
            Icon(
                imageVector = if (isListening) Icons.Default.Favorite else Icons.Default.PlayArrow,
                contentDescription = "Ask ZOYA via Voice",
                tint = if (isListening) Color.White else Color(0xFFFF1744)
            )
        }
    }
}

@Composable
fun MyraSettingsLayout(
    viewModel: MainViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val netlifyToken by viewModel.netlifyToken.collectAsState()
    var tokenInput by remember(netlifyToken) { mutableStateOf(netlifyToken) }
    var selectedModel by remember { mutableStateOf("Native Audio (Human Voice)") }
    var selectedVoice by remember { mutableStateOf("Aoede (Default Female)") }
    var selectedMode by remember { mutableStateOf("GF Mode 💖") }

    val modelsList = listOf("Native Audio (Human Voice)", "Flash Live (Fast)", "Pro Audio Dialog")
    val voicesList = listOf("Aoede (Default Female)", "Charon (Male)", "Kore (Female)", "Fenrir (Male)", "Puck (Male)", "Leda (Female)", "Orus (Male)", "Zephyr (Female)")
    val modesList = listOf("GF Mode 💖", "Professional Mode 💼", "Assistant Mode 🤖")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 36.dp)
    ) {
        // Upper Title Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color(0xFFD500F9).copy(alpha = 0.15f), CircleShape)
                        .border(1.dp, Color(0xFFD500F9).copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color(0xFFD500F9),
                        modifier = Modifier.size(16.dp)
                    )
                }
                Column {
                    Text(
                        "SYSTEM PREFERENCES",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        letterSpacing = 1.sp
                    )
                    Text(
                        "Configure high-performance automation & AI engine layers",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp
                    )
                }
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(28.dp)
                    .background(Color.White.copy(alpha = 0.05f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Panel",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // SECTION 1: AI COGNITIVE MODEL CONFIG
        SettingsSectionHeader(title = "NEURAL COGNITIVE ENGINE", subtitle = "Manage target LLM behaviors and sound generation models")
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0818).copy(alpha = 0.6f)),
            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SettingsPickerRow(
                    label = "GEMINI MAIN REASONING MODEL",
                    description = "Choose the core multi-modal architecture driving responses",
                    icon = Icons.Default.Build,
                    currentSelection = selectedModel,
                    options = modelsList,
                    onSelect = {
                        selectedModel = it
                        updateConfig(viewModel, selectedModel, selectedVoice, selectedMode)
                    }
                )

                SettingsPickerRow(
                    label = "SYNTHETIC SPEECH VOICE SIGNATURE",
                    description = "Change character voice signatures for physical wave audio synthesis",
                    icon = Icons.Default.Face,
                    currentSelection = selectedVoice,
                    options = voicesList,
                    onSelect = {
                        selectedVoice = it
                        updateConfig(viewModel, selectedModel, selectedVoice, selectedMode)
                    }
                )
            }
        }

        // SECTION 2: COMPANION PERSONALITY CUSTOMIZATION
        SettingsSectionHeader(title = "COGNITIVE STYLES & INTERACTION", subtitle = "Calibrate MYRA's language pattern guidelines")
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0818).copy(alpha = 0.6f)),
            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                SettingsPickerRow(
                    label = "BEHAVIOR PROFILE RULES",
                    description = "Adjust vocabulary rules between emotional companion and structured corporate agent",
                    icon = Icons.Default.Favorite,
                    currentSelection = selectedMode,
                    options = modesList,
                    onSelect = {
                        selectedMode = it
                        updateConfig(viewModel, selectedModel, selectedVoice, selectedMode)
                    }
                )
            }
        }

        // SECTION 3: CLOUD HOSTING & NETLIFY DEPLOYMENT
        SettingsSectionHeader(title = "CLOUD COMPILE & SANDBOX INTERFACE", subtitle = "Secure credentials for remote hosting and continuous integration")

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0818).copy(alpha = 0.6f)),
            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Secure Token",
                        tint = Color(0xFF00FFCC),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        "NETLIFY SECURITY AUTHORIZATION TOKEN",
                        color = Color.White.copy(alpha = 0.9f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Text(
                    "Token is locally encrypted on your device and enables immediate direct deployment from the app's dev sandbox environment.",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )

                Spacer(modifier = Modifier.height(4.dp))

                TextField(
                    value = tokenInput,
                    onValueChange = {
                        tokenInput = it
                        viewModel.setNetlifyToken(it)
                    },
                    placeholder = {
                        Text(
                            "Paste net_xxxxx credentials...",
                            color = Color.White.copy(alpha = 0.35f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp)),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF140F22),
                        unfocusedContainerColor = Color(0xFF0A0614),
                        focusedIndicatorColor = Color(0xFFD500F9).copy(alpha = 0.6f),
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )
            }
        }

        // SECTION 4: TELEMETRY QUICK INSIGHT CARD
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF030108)),
            border = BorderStroke(1.dp, Color(0xFF00FFCC).copy(alpha = 0.15f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.size(6.dp).background(Color(0xFF00FFCC), CircleShape))
                    Text(
                        "ZOYA TERMINAL TELEMETRY",
                        color = Color(0xFF00FFCC),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                
                TelemetryRow(label = "AUTHORIZED LINK", value = if (tokenInput.isNotEmpty()) "ACTIVE SECRET SHA-256" else "CREDENTIALS WAITING")
                TelemetryRow(label = "COMPILER SANDBOX", value = "INTEGRAL / JETPACK COMPOSE V2")
                TelemetryRow(label = "LLM HOST CHANNELS", value = "DIRECT CRYPTO-SOCKET GREEN")
            }
        }

        // Save Button
        Button(
            onClick = {
                Toast.makeText(context, "System configurations locked.", Toast.LENGTH_SHORT).show()
                onClose()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFD500F9)
            ),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text(
                "LOCK CORE SYSTEM VARIABLES",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
        }
    }
}

@Composable
fun SettingsSectionHeader(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(
            text = "◆ $title",
            color = Color(0xFFD500F9),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(
            text = subtitle,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp)
        )
    }
}

@Composable
fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$label:",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.4f)
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = Color.White
        )
    }
}

private fun updateConfig(viewModel: MainViewModel, model: String, voice: String, mode: String) {
    val cleanModel = when (model) {
        "Flash Live (Fast)" -> "models/gemini-2.0-flash-live-001"
        "Pro Audio Dialog" -> "models/gemini-2.5-flash-preview-native-audio-dialog"
        else -> "models/gemini-2.5-flash-native-audio-preview-12-2025"
    }
    val cleanVoice = voice.substringBefore(" ") // Extract "Aoede", "Charon", etc.
    viewModel.setModelConfig(cleanModel, cleanVoice, mode)
}

@Composable
fun SettingsPickerRow(
    label: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    currentSelection: String,
    options: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFFD500F9).copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.82f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = description,
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 9.sp,
            lineHeight = 12.sp,
            modifier = Modifier.padding(start = 20.dp, bottom = 6.dp)
        )
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF140F22), RoundedCornerShape(8.dp))
                .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentSelection,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Expand options",
                    tint = Color.White.copy(alpha = 0.6f)
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .background(Color(0xFF0F0B1E))
                    .border(1.dp, Color(0xFFD500F9).copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            ) {
                options.forEach { item ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = item,
                                color = if (item == currentSelection) Color(0xFF00FFCC) else Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = if (item == currentSelection) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(item)
                        }
                    )
                }
            }
        }
    }
}
