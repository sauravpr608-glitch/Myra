package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.ViewModelStore
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.MainActivity

class MyraOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        savedStateRegistryController.performRestore(null)
        
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            showFloatingOrb()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        return START_STICKY
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        if (overlayView != null) {
            windowManager.removeView(overlayView)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundServiceNotification() {
        val channelId = "myra_companion_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "MYRA Floating Core",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("MYRA Companion Overlay Active")
            .setContentText("The floating quick-launch system orb is running in overlay mode.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(102, notification)
    }

    private fun showFloatingOrb() {
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 500
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@MyraOverlayService)
            setViewTreeViewModelStoreOwner(object : androidx.lifecycle.ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore
                    get() = ViewModelStore()
            })
            setViewTreeSavedStateRegistryOwner(this@MyraOverlayService)

            setContent {
                val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")
                val pulseState by infiniteTransition.animateFloat(
                    initialValue = 0.95f,
                    targetValue = 1.08f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulse"
                )

                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .scale(pulseState)
                        .shadow(12.dp, CircleShape)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFFFF1744), // neon red
                                    Color(0xFFD500F9)  // neon purple
                                )
                            )
                        )
                        .clickable {
                            val intent = Intent(this@MyraOverlayService, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                putExtra("OVERLAY_LAUNCH", true)
                            }
                            startActivity(intent)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "MYRA",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        // Add drag & move listener natively so it behaves like standard assistive touch
        composeView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(composeView, layoutParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = event.rawX - initialTouchX
                        val diffY = event.rawY - initialTouchY
                        if (Math.abs(diffX) < 10 && Math.abs(diffY) < 10) {
                            v.performClick()
                        }
                        return true
                    }
                }
                return false
            }
        })

        overlayView = composeView
        windowManager.addView(composeView, layoutParams)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }
}
