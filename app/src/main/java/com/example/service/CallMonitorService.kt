package com.example.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log

class CallMonitorService : BroadcastReceiver() {

    companion object {
        const val ACTION_INCOMING_CALL = "com.example.MYRA_INCOMING_CALL"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CALLER_NUMBER = "caller_number"

        @Volatile
        var isRinging = false
        var activeIncomingNumber: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            Log.d("CallMonitor", "Phone state changed: $state, incoming number: $incomingNumber")

            if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                isRinging = true
                activeIncomingNumber = incomingNumber ?: ""
                val contactName = if (!incomingNumber.isNullOrEmpty()) {
                    resolveContactName(context, incomingNumber)
                } else {
                    "Unknown Caller"
                }

                // Send broadcast or intent to MainActivity to handle Voice interruption
                val callIntent = Intent(ACTION_INCOMING_CALL).apply {
                    putExtra(EXTRA_CALLER_NAME, contactName)
                    putExtra(EXTRA_CALLER_NUMBER, incomingNumber ?: "")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.sendBroadcast(callIntent)
            } else if (state == TelephonyManager.EXTRA_STATE_IDLE || state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
                isRinging = false
                activeIncomingNumber = null
            }
        }
    }

    private fun resolveContactName(context: Context, phoneNumber: String): String {
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            val cursor: Cursor? = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIdx = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIdx != -1) {
                        return it.getString(nameIdx)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CallMonitor", "Error resolving contact", e)
        }
        return "Unknown (" + phoneNumber + ")"
    }

    @SuppressLint("MissingPermission")
    fun acceptCall(context: Context): Boolean {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telecomManager.acceptRingingCall()
                Log.d("CallMonitor", "Call accepted via TelecomManager.")
                true
            } else {
                // Fallback to media key simulation
                simulateHeadsetHook(context)
                true
            }
        } catch (e: Exception) {
            Log.e("CallMonitor", "Failed to accept call", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun declineCall(context: Context): Boolean {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager.endCall()
                Log.d("CallMonitor", "Call declined/ended via TelecomManager.")
                true
            } else {
                Log.w("CallMonitor", "Declining call not supported directly on this Android version.")
                false
            }
        } catch (e: Exception) {
            Log.e("CallMonitor", "Failed to decline call", e)
            false
        }
    }

    private fun simulateHeadsetHook(context: Context) {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_HEADSETHOOK))
        }
        context.sendOrderedBroadcast(intent, null)
        val intentUp = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_HEADSETHOOK))
        }
        context.sendOrderedBroadcast(intentUp, null)
    }
}
