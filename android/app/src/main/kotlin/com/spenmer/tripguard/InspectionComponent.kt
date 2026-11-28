package com.spenmer.tripguard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.spenmer.tripguard.domain.CheckItem
import com.spenmer.tripguard.domain.Observation

fun observationLabel(observation: Observation): String = when (observation) {
    Observation.UNKNOWN -> "Not checked / unsure"
    Observation.NO_VISIBLE_CONCERN -> "No visible concern"
    Observation.POTENTIAL_CONCERN -> "Potential concern"
    Observation.CRITICAL -> "Critical concern"
    Observation.NOT_APPLICABLE -> "Not applicable"
}

@Composable
fun InspectionComponent(check: CheckItem, initial: Observation, save: (Observation) -> Unit, cancel: () -> Unit) {
    var selected by rememberSaveable(check.id) { mutableStateOf(initial) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(check.title, style = MaterialTheme.typography.headlineMedium) }
        item { Text("Stationary visual observation", style = MaterialTheme.typography.labelLarge) }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("How to check", style = MaterialTheme.typography.titleMedium)
            Text(check.guidance)
            Text("Do not inspect hot, moving or high-voltage components. If uncertain, choose Not checked / unsure.")
        } } }
        item {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Observation.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                        selected = selected == option, onClick = { selected = option }, role = Role.RadioButton
                    ).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == option, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(observationLabel(option))
                    }
                }
            }
        }
        if (selected == Observation.CRITICAL) item {
            Text("Do not start travel. If already driving, stop safely and seek qualified assistance.", color = MaterialTheme.colorScheme.error)
        }
        item { Text("An observation is not a mechanical diagnosis or roadworthiness certificate.") }
        item { Button(onClick = { save(selected) }, modifier = Modifier.fillMaxWidth()) { Text("Save observation") } }
        item { OutlinedButton(onClick = cancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel observation") } }
    }
}
