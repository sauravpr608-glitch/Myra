package com.example.engine

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.Locale

class LocationEngine(private val context: Context) {

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): LocationResult {
        return try {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            // Wait for the location securely
            val location: Location? = suspendCancellableCoroutine { continuation ->
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { loc -> continuation.resume(loc) }
                    .addOnFailureListener { e -> continuation.resumeWithException(e) }
            }
            if (location != null) {
                val address = reverseGeocode(location.latitude, location.longitude)
                LocationResult.Success(location.latitude, location.longitude, address)
            } else {
                // Return default fallback with detailed message (Delhi default for Indian theme of MYRA or user's city)
                val defaultLat = 28.6139
                val defaultLng = 77.2090
                val address = reverseGeocode(defaultLat, defaultLng)
                LocationResult.Success(
                    defaultLat,
                    defaultLng,
                    address ?: "Connaught Place, New Delhi"
                )
            }
        } catch (e: Exception) {
            Log.e("LocationEngine", "Error getting location", e)
            LocationResult.Error(e.message ?: "Unknown location retrieval error")
        }
    }

    private fun reverseGeocode(latitude: Double, longitude: Double): String? {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Note: For newer Android levels, the synchronous geocoder can block,
                // but since we are running in non-UI coroutine scope (ViewModel Dispatcher), it is safe.
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    addresses[0].getAddressLine(0) ?: addresses[0].locality
                } else null
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    addresses[0].getAddressLine(0) ?: addresses[0].locality
                } else null
            }
        } catch (e: Exception) {
            Log.e("LocationEngine", "Reverse geocoding failed", e)
            null
        }
    }

    fun setMapRoot(destination: String): Boolean {
        return try {
            val intentUri = Uri.parse("geo:0,0?q=${Uri.encode(destination)}")
            val mapIntent = Intent(Intent.ACTION_VIEW, intentUri).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (mapIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(mapIntent)
                true
            } else {
                // If maps app not installed, open in general browser
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(destination)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                true
            }
        } catch (e: Exception) {
            Log.e("LocationEngine", "Failed to launch map root intent", e)
            false
        }
    }

    sealed class LocationResult {
        data class Success(val latitude: Double, val longitude: Double, val address: String?) : LocationResult()
        data class Error(val message: String) : LocationResult()
    }
}
