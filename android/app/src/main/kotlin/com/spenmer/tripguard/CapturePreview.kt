package com.spenmer.tripguard

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp

@Composable
fun CapturePreview(registration: Boolean, back: () -> Unit) {
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("No image captured.") }
    var reference by rememberSaveable { mutableStateOf("") }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        photo = bitmap
        status = if (bitmap == null) "Capture cancelled or unavailable." else "Preview captured. Review visually; no automated analysis performed."
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(if (registration) "RC capture and review" else "Visual inspection capture", style = MaterialTheme.typography.headlineSmall) }
        item { Text(if (registration) "Optional registration document preview. Avoid capturing unrelated personal information. OCR is not enabled; verify and enter details manually."
            else "Capture only while safely parked. Do not access hot, moving or high-voltage components. A photograph cannot establish mechanical safety.") }
        item { Button(onClick = {
            try { capture.launch(null) }
            catch (_: android.content.ActivityNotFoundException) { status = "Camera unavailable. Continue manually." }
            catch (_: SecurityException) { status = "Camera access unavailable. Continue manually." }
        }, modifier = Modifier.fillMaxWidth()) { Text("Open camera") } }
        item { Text(status) }
        photo?.let { bitmap ->
            item { Image(bitmap.asImageBitmap(), contentDescription = "Captured preview", modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) }
            item { OutlinedButton(onClick = { photo = null; status = "Preview discarded." }, modifier = Modifier.fillMaxWidth()) { Text("Discard preview") } }
        }
        if (registration) {
            item { OutlinedTextField(reference, { reference = it.take(40) }, label = { Text("Registration reference (optional draft)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { Text("Draft only. This reference is not saved or verified. Use the vehicle profile for persistent records.") }
        } else {
            item { Text("Analysis status: unable to assess", style = MaterialTheme.typography.titleMedium) }
            item { Text("No qualified AI model is installed. Continue the manual checklist or seek professional assistance. No safety conclusion is available from this image.") }
        }
        item { Text("Preview image is held in memory only and discarded when leaving this screen. No upload or evidence archive.") }
        item { Button(onClick = back, modifier = Modifier.fillMaxWidth()) { Text("Continue manually") } }
    }
}
