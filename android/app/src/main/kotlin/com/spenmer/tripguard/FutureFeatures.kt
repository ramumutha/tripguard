package com.spenmer.tripguard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class Feature(val label: String) {
    GUARDIAN("Guardian"), CHECK_IN("Check-in states"), CONSENT("Sharing consent"), DELIVERY("Delivery states"),
    DEVICES("Connected devices"), COMPATIBILITY("Compatibility"), PAIRING("Pairing"), DASHBOARD("Device dashboard"),
    DIAGNOSTICS("Live diagnostics"), TYRES("Tyre pressure"), TRENDS("Coolant and battery"), CONNECTION("Connection status"),
    WORKSHOP("Workshop handover"), TRACKER("Independent tracker"), PREDICTION("Prediction research"),
    PREMIUM("Plans and features"), ACCOUNT("Account and cloud"), FLEET("Fleet overview"),
    ASSIGNMENT("Fleet assignment"), ALERTS("Fleet alerts"), RECEIVING("Workshop receiving")
}

private data class PreviewContent(val detail: String, val states: List<String>, val links: List<Feature> = emptyList())
private fun content(feature: Feature): PreviewContent = when (feature) {
    Feature.GUARDIAN -> PreviewContent("Future consent-based journey assistance. No enrollment, remote alerts or monitoring are active.", listOf("Not enrolled", "Enrollment unavailable offline"), listOf(Feature.CONSENT, Feature.CHECK_IN, Feature.DELIVERY))
    Feature.CHECK_IN -> PreviewContent("Example check-in lifecycle. A missed check-in alone does not establish an accident.", listOf("Example: scheduled", "Example: acknowledged", "Example: overdue — safety unconfirmed"))
    Feature.CONSENT -> PreviewContent("Sharing must be explicitly authorized and revocable. This preview cannot transmit or retain remote consent.", listOf("Not sharing", "Preview consent review", "Preview revoked"))
    Feature.DELIVERY -> PreviewContent("A message is not delivered merely because it was composed. No messages are sent here.", listOf("Not sent", "Example: queued offline", "Example: delivery unconfirmed", "Example: acknowledged"))
    Feature.DEVICES -> PreviewContent("Device categories under evaluation. No hardware is qualified or connected.", listOf("Read-only OBD", "Tyre pressure sensor", "Independent tracker"), listOf(Feature.COMPATIBILITY, Feature.PAIRING, Feature.DASHBOARD, Feature.TRACKER))
    Feature.COMPATIBILITY -> PreviewContent("Vehicle and device support must be verified before use. No compatibility claim is made.", listOf("Vehicle unspecified", "Device unverified", "Protocol support unknown"), listOf(Feature.PAIRING))
    Feature.PAIRING -> PreviewContent("Bluetooth pairing is not enabled in this preview. No permissions requested.", listOf("Not paired", "Example: permission denied", "Example: connection failed"), listOf(Feature.CONNECTION))
    Feature.DASHBOARD -> PreviewContent("No live readings are available. Missing data is not a normal reading.", listOf("No connected device", "No telemetry", "No safety conclusion"), listOf(Feature.DIAGNOSTICS, Feature.TYRES, Feature.TRENDS, Feature.CONNECTION))
    Feature.DIAGNOSTICS -> PreviewContent("Future read-only diagnostics. No ECU commands, code clearing or fault prediction.", listOf("No readings", "Example: unsupported metric", "Example: fault reported — professional assessment required"), listOf(Feature.PREDICTION, Feature.WORKSHOP))
    Feature.TYRES -> PreviewContent("Pressure readings require supported sensors and manufacturer specifications.", listOf("Front: unavailable", "Rear: unavailable", "No pressure assessment"))
    Feature.TRENDS -> PreviewContent("Coolant and battery trends require supported, validated readings.", listOf("Coolant: unavailable", "Battery: unavailable", "No trend assessment"))
    Feature.CONNECTION -> PreviewContent("Connection states are illustrative. No monitoring is active.", listOf("Disconnected", "Example: connecting", "Example: stale — do not rely on old readings"))
    Feature.WORKSHOP -> PreviewContent("Future pre-diagnosed service handover. No workshop booking or report transmission occurs.", listOf("No report selected", "Not sent", "No acknowledgement"), listOf(Feature.RECEIVING))
    Feature.TRACKER -> PreviewContent("Independent tracker support is research only. Network coverage and dispatch are not guaranteed.", listOf("No device registered", "No independent location", "No SOS channel verified"))
    Feature.PREDICTION -> PreviewContent("Research only. No validated prediction horizon or probability is available. Never delay action on manufacturer warnings.", listOf("No model qualified", "Insufficient evidence", "No prediction"))
    Feature.PREMIUM -> PreviewContent("Essential local safety remains free. Future connected features are not purchasable here.", listOf("Local essential features", "Future connected analytics", "Future fleet features"), listOf(Feature.ACCOUNT))
    Feature.ACCOUNT -> PreviewContent("No account is required for local safety. Cloud enrollment is unavailable.", listOf("Local mode", "Cloud disabled", "No credentials collected"))
    Feature.FLEET -> PreviewContent("Future fleet concept. No commercial account or remote fleet monitoring.", listOf("No fleet enrolled", "No vehicles assigned", "No live alerts"), listOf(Feature.ASSIGNMENT, Feature.ALERTS))
    Feature.ASSIGNMENT -> PreviewContent("Future vehicle and driver assignment. No employee or driver data collected.", listOf("No vehicle selected", "No driver assigned", "Assignment unavailable"))
    Feature.ALERTS -> PreviewContent("Future fleet alert review. No actual alerts or severity assessment.", listOf("No telemetry", "No alerts", "No report available"))
    Feature.RECEIVING -> PreviewContent("Future workshop receiving workflow. No actual report or customer data transferred.", listOf("No report received", "No acknowledgement", "No service booking"))
}

@Composable
fun FeaturePreview(feature: Feature, navigate: (Feature) -> Unit, back: () -> Unit) {
    val model = content(feature)
    var selected by rememberSaveable(feature.name) { mutableIntStateOf(0) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("FUTURE FEATURE • INTERACTIVE PREVIEW", style = MaterialTheme.typography.labelLarge) }
        item { Text(feature.label, style = MaterialTheme.typography.headlineMedium) }
        item { Text(model.detail) }
        item { Text("Explore example states", style = MaterialTheme.typography.titleMedium) }
        model.states.forEachIndexed { index, state ->
            item {
                OutlinedButton(onClick = { selected = index }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (selected == index) "Selected: " + state else state)
                }
            }
        }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text(model.states[selected.coerceIn(model.states.indices)], style = MaterialTheme.typography.titleMedium)
            Text("Illustration only. No real connection, delivery, enrollment or assessment.")
        } } }
        model.links.forEach { link ->
            item { Button(onClick = { navigate(link) }, modifier = Modifier.fillMaxWidth()) { Text(link.label) } }
        }
        item { TextButton(onClick = back) { Text("Back to feature catalogue") } }
    }
}

@Composable
fun FeatureCatalogue(open: (Feature) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Explore future features", style = MaterialTheme.typography.headlineMedium) }
        item { Text("These are mock workflows, not active safety services. Essential local features remain accessible from Home and SOS.") }
        listOf(Feature.GUARDIAN, Feature.DEVICES, Feature.WORKSHOP, Feature.PREDICTION, Feature.PREMIUM, Feature.FLEET).forEach { feature ->
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text(feature.label, style = MaterialTheme.typography.titleLarge)
                Text(content(feature).detail)
                OutlinedButton(onClick = { open(feature) }, modifier = Modifier.fillMaxWidth()) { Text("Explore " + feature.label) }
            } } }
        }
    }
}
