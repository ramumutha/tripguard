package com.spenmer.tripguard

import android.Manifest
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import android.os.CancellationSignal
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale

@Composable
fun EmergencyLocation() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    var fix by remember { mutableStateOf<Location?>(null) }
    var status by remember { mutableStateOf("Location not requested.") }
    var pending by remember { mutableStateOf(false) }
    var request by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(1000)
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) request++ else status = "Permission denied. You can still call for help."
    }
    DisposableEffect(request, lifecycle) {
        val signal = CancellationSignal()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && pending) {
                signal.cancel()
                pending = false
                status = "Location request stopped. Request again when the app is visible."
            }
        }
        lifecycle.addObserver(observer)
        if (request > 0) {
            fix = null
            try {
                val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val provider = if (fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
                if (!manager.isProviderEnabled(provider)) {
                    pending = false
                    status = "Location provider unavailable. Check phone location settings."
                } else {
                    pending = true
                    status = "Requesting location. No message has been sent."
                    LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                        pending = false
                        fix = location
                        now = SystemClock.elapsedRealtime()
                        status = if (location == null) "No location fix. Try again outdoors if safe." else "Location obtained locally. No message has been sent."
                    }
                }
            } catch (_: Exception) {
                pending = false
                status = "Location unavailable. You can still use emergency assistance."
            }
        }
        onDispose { lifecycle.removeObserver(observer); signal.cancel() }
    }
    val location = fix
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Emergency location", style = MaterialTheme.typography.titleLarge)
        Text("Optional foreground location only. GPS does not transmit your position. Sharing requires a compatible app and may require network access.")
        Text(status)
        Button(enabled = !pending, onClick = {
            if (listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }) request++ else permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }, modifier = Modifier.fillMaxWidth()) { Text("Get my location") }
        if (location != null) {
            val age = ((now - location.elapsedRealtimeNanos / 1000000L) / 1000L).coerceAtLeast(0)
            val coords = String.format(Locale.US, "%.6f, %.6f", location.latitude, location.longitude)
            val accuracy = if (location.hasAccuracy()) "${location.accuracy.toInt()} m estimated accuracy" else "Accuracy unknown"
            val freshness = if (age > 60) "STALE location" else "Location fix"
            Text("$freshness: $coords")
            Text("$accuracy • $age seconds old")
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("TripGuard location", "$freshness: $coords; $accuracy; fix age $age seconds."))
                status = "Location copied. Nothing has been sent."
            }, modifier = Modifier.fillMaxWidth()) { Text("Copy location details") }
            OutlinedButton(onClick = {
                val message = "TripGuard: I may need assistance. $freshness: $coords. $accuracy; fix age $age seconds. Please verify my location. No automatic dispatch."
                try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, message)
                }, "Share emergency location")) }
                catch (_: android.content.ActivityNotFoundException) { status = "No sharing app available." }
            }, modifier = Modifier.fillMaxWidth()) { Text("Choose app to share location") }
        }
    }
}
