package com.spenmer.tripguard

import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spenmer.tripguard.domain.*
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF075D59), onPrimary = Color.White,
                secondary = Color(0xFF43656B), background = Color(0xFFF5F8F7),
                surface = Color.White, error = Color(0xFFB3261E)
            )) { TripGuard() }
        }
    }
}

class PreviewRepository : VehicleRepository {
    private val records = mutableListOf<Vehicle>()
    override fun list() = records.toList()
    override fun save(vehicle: Vehicle) { records.removeAll { it.id == vehicle.id }; records.add(vehicle) }
    override fun remove(id: String) { records.removeAll { it.id == id } }
}
data class InspectionPreview(val vehicle: String, val finding: Finding, val completed: Int, val total: Int)
class TripGuardViewModel : ViewModel() {
    private val repository: VehicleRepository = PreviewRepository()
    var vehicles by mutableStateOf(repository.list()); private set
    var history by mutableStateOf(listOf<InspectionPreview>()); private set
    var journey by mutableStateOf<String?>(null); private set
    fun save(v: Vehicle) { repository.save(v); vehicles = repository.list() }
    fun remove(id: String) { repository.remove(id); vehicles = repository.list() }
    fun record(record: InspectionPreview) { history = history + record }
    fun startJourney(destination: String) { journey = destination }
    fun endJourney() { journey = null }
    fun clearPreview() { vehicles.forEach { repository.remove(it.id) }; vehicles = repository.list(); history = emptyList(); journey = null }
}
enum class Page(val title: String) { WELCOME("Welcome"), CAPABILITIES("Vehicle capabilities"), PRIVACY("Privacy and local data"), HOME("Home"), GARAGE("Garage"), ADD("Vehicle profile"),
    INSPECTION("Safety inspection"), HISTORY("Inspection history"), JOURNEY("Journey"),
    RESCUE("Emergency assistance"), SETTINGS("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripGuard(vm: TripGuardViewModel = viewModel()) {
    var page by rememberSaveable { mutableStateOf(Page.WELCOME) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val navigate: (Page) -> Unit = { page = it }
    BackHandler(page != Page.HOME) { page = if (page == Page.ADD) Page.GARAGE else Page.HOME }
    Scaffold(
        topBar = { TopAppBar(title = { Text(if (page == Page.HOME) "TripGuard" else page.title) },
            actions = { if (page != Page.RESCUE) TextButton(onClick = { page = Page.RESCUE }) { Text("SOS") } }) },
        bottomBar = {
            NavigationBar {
                listOf(Page.HOME, Page.GARAGE, Page.JOURNEY, Page.SETTINGS).forEach { target ->
                    NavigationBarItem(selected = page == target, onClick = { page = target },
                        icon = { Text(when(target) { Page.HOME -> "⌂"; Page.GARAGE -> "▣"; Page.JOURNEY -> "↗"; else -> "⚙" }) },
                        label = { Text(target.title) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Text("UI PREVIEW • Session-only data • No live monitoring", Modifier.fillMaxWidth().padding(12.dp),
                    style = MaterialTheme.typography.labelMedium)
            }
            Box(Modifier.weight(1f)) {
                when(page) {
                    Page.HOME -> Home(vm.vehicles.size, navigate)
                    Page.GARAGE -> Garage(vm.vehicles, { editingId = null; page = Page.ADD },
                        { editingId = it.id; page = Page.ADD }, { selectedId = it.id; page = Page.INSPECTION })
                    Page.ADD -> VehicleForm(vm.vehicles.firstOrNull { it.id == editingId }, {
                        vm.save(it); page = Page.GARAGE
                    }, { vm.remove(it); page = Page.GARAGE }, { page = Page.GARAGE })
                    Page.INSPECTION -> Inspection(vm.vehicles, selectedId, {
                        vm.record(it); page = Page.HISTORY
                    }, { editingId = null; page = Page.ADD })
                    Page.HISTORY -> History(vm.history, { page = Page.INSPECTION })
                    Page.JOURNEY -> Journey(vm.journey, vm::startJourney, vm::endJourney)
                    Page.RESCUE -> Rescue()
                    Page.SETTINGS -> Settings({ page = Page.PRIVACY }, { page = Page.CAPABILITIES })
                    Page.WELCOME -> Welcome { page = Page.HOME }
                    Page.CAPABILITIES -> Capabilities()
                    Page.PRIVACY -> Privacy { vm.clearPreview(); selectedId = null; editingId = null; page = Page.HOME }
                }
            }
        }
    }
}
@Composable
private fun Body(content: LazyListScopeBody) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) { content.invoke(this) }
}
private typealias LazyListScopeBody = androidx.compose.foundation.lazy.LazyListScope.() -> Unit

@Composable
private fun ActionCard(title: String, detail: String, button: String, action: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = action, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(button) }
        }
    }
}
@Composable
private fun Home(count: Int, navigate: (Page) -> Unit) = Body {
    item {
        Text("Every vehicle. Every journey.", style = MaterialTheme.typography.headlineMedium)
        Text("Prevent • Detect • Protect", style = MaterialTheme.typography.titleMedium)
    }
    item { ActionCard("My garage", "$count vehicles in this preview session", "Manage vehicles") { navigate(Page.GARAGE) } }
    item { ActionCard("Before you travel", "Guided stationary visual checks, not a certified inspection.", "Start safety check") { navigate(Page.INSPECTION) } }
    item { ActionCard("My journey", "Record a destination and prepare before departure.", "Plan journey") { navigate(Page.JOURNEY) } }
    item { ActionCard("Vehicle health", "Review observations recorded in this session.", "View history") { navigate(Page.HISTORY) } }
    item { ActionCard("Emergency assistance", "Access guidance without signing in.", "Open emergency assistance") { navigate(Page.RESCUE) } }
}
@Composable
private fun Garage(vehicles: List<Vehicle>, add: () -> Unit, edit: (Vehicle) -> Unit, inspect: (Vehicle) -> Unit) = Body {
    item { Button(onClick = add, modifier = Modifier.fillMaxWidth()) { Text("Add vehicle") } }
    if (vehicles.isEmpty()) item { Text("No vehicles yet. Add a motorcycle, car or commercial vehicle.") }
    items(vehicles, key = { it.id }) { v ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(v.name, style = MaterialTheme.typography.titleLarge)
                Text(v.kind.label + " • " + v.propulsion.name)
                OutlinedButton(onClick = { edit(v) }, modifier = Modifier.fillMaxWidth()) { Text("View / edit profile") }
                Button(onClick = { inspect(v) }, modifier = Modifier.fillMaxWidth()) { Text("Inspect vehicle") }
            }
        }
    }
}
@Composable
private fun VehicleForm(existing: Vehicle?, save: (Vehicle) -> Unit, remove: (String) -> Unit, cancel: () -> Unit) {
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var kind by rememberSaveable(existing?.id) { mutableStateOf(existing?.kind ?: VehicleClass.CAR) }
    var propulsion by rememberSaveable(existing?.id) { mutableStateOf(existing?.propulsion ?: Propulsion.PETROL) }
    var confirmDelete by remember { mutableStateOf(false) }
    Body {
        item { OutlinedTextField(name, { name = it }, label = { Text("Vehicle nickname") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { Text("Vehicle class", style = MaterialTheme.typography.titleMedium) }
        items(VehicleClass.entries) { k -> Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = kind == k, onClick = { kind = k }); Text(k.label)
        } }
        item { Text("Propulsion", style = MaterialTheme.typography.titleMedium) }
        items(Propulsion.entries) { p -> Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = propulsion == p, onClick = { propulsion = p }); Text(p.name)
        } }
        item { Button(onClick = { save(Vehicle(existing?.id ?: UUID.randomUUID().toString(), name.trim(), kind, propulsion)) },
            enabled = name.isNotBlank() && name.length <= 80, modifier = Modifier.fillMaxWidth()) { Text("Save vehicle") } }
        if (existing != null) item { OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete vehicle") } }
        item { TextButton(onClick = cancel) { Text("Cancel") } }
    }
    if (confirmDelete && existing != null) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this vehicle?") }, text = { Text("The profile is removed from this preview session.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; remove(existing.id) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}
@Composable
private fun Inspection(vehicles: List<Vehicle>, initialId: String?, save: (InspectionPreview) -> Unit, add: () -> Unit) {
    var chosen by rememberSaveable { mutableStateOf(initialId ?: vehicles.firstOrNull()?.id) }
    val vehicle = vehicles.firstOrNull { it.id == chosen }
    val observations = remember(chosen) { mutableStateMapOf<String, Observation>() }
    val checks = vehicle?.let(SafetyPolicy::checklist).orEmpty()
    val result = SafetyPolicy.assess(checks.map { observations[it.id] ?: Observation.UNKNOWN })
    Body {
        if (vehicles.isEmpty()) item { ActionCard("Add a vehicle", "Inspection guidance depends on vehicle type.", "Add vehicle", add) }
        else {
            item { Text("Select vehicle", style = MaterialTheme.typography.titleMedium) }
            items(vehicles, key = { it.id }) { v -> Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = chosen == v.id, onClick = { chosen = v.id }); Text(v.name)
            } }
            item { Text("Only inspect while safely parked. Follow the owner's manual. Unknown checks do not count as completed.") }
            items(checks, key = { it.id }) { check ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(check.title, style = MaterialTheme.typography.titleMedium)
                        Text(check.guidance)
                        Observation.entries.forEach { choice ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = (observations[check.id] ?: Observation.UNKNOWN) == choice,
                                    onClick = { observations[check.id] = choice })
                                Text(when(choice) {
                                    Observation.UNKNOWN -> "Not checked / unsure"
                                    Observation.NO_VISIBLE_CONCERN -> "No visible concern"
                                    Observation.POTENTIAL_CONCERN -> "Potential concern"
                                    Observation.CRITICAL -> "Critical concern"
                                    Observation.NOT_APPLICABLE -> "Not applicable"
                                })
                            }
                        }
                    }
                }
            }
            if (vehicle != null) item {
                Text(result.name.replace('_', ' '), style = MaterialTheme.typography.titleLarge)
                Text(SafetyPolicy.advice(result))
                Button(onClick = { save(InspectionPreview(vehicle.name, result, checks.count {
                    val o = observations[it.id] ?: Observation.UNKNOWN
                    o != Observation.UNKNOWN && o != Observation.NOT_APPLICABLE
                }, checks.size)) }, modifier = Modifier.fillMaxWidth()) { Text("Record observations") }
            }
        }
    }
}
@Composable
private fun History(history: List<InspectionPreview>, inspect: () -> Unit) = Body {
    if (history.isEmpty()) item { Text("No inspection records in this session.") }
    items(history.reversed()) { h ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(h.vehicle, style = MaterialTheme.typography.titleMedium)
                Text(h.finding.name.replace('_',' '))
                Text("${h.completed} observed / ${h.total} listed checks")
                Text(SafetyPolicy.advice(h.finding))
            }
        }
    }
    item { Button(onClick = inspect, modifier = Modifier.fillMaxWidth()) { Text("New inspection") } }
}
@Composable
private fun Journey(active: String?, start: (String) -> Unit, end: () -> Unit) {
    var destination by rememberSaveable { mutableStateOf("") }
    Body {
        item { Text("Journey preview", style = MaterialTheme.typography.headlineSmall) }
        item { Text("This records a destination only. No background tracking, location sharing or remote check-ins are active.") }
        if (active == null) {
            item { OutlinedTextField(destination, { destination = it }, label = { Text("Destination") }, modifier = Modifier.fillMaxWidth()) }
            item { Button(onClick = { start(destination.trim()) }, enabled = destination.isNotBlank() && destination.length <= 120,
                modifier = Modifier.fillMaxWidth()) { Text("Start local journey preview") } }
        } else {
            item { Text("Destination: $active", style = MaterialTheme.typography.titleLarge) }
            item { Text("At safe rest stops, review warning lights and visible signs of vehicle trouble. Never handle the phone while driving.") }
            item { Button(onClick = end, modifier = Modifier.fillMaxWidth()) { Text("End journey preview") } }
        }
    }
}
@Composable
private fun Rescue() {
    val context = LocalContext.current
    var confirmNumber by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    Body {
        item { Text("Emergency assistance", style = MaterialTheme.typography.headlineMedium) }
        item { Text("If driving, stop safely first. If there is fire, smoke, injury or immediate danger, move to safety when possible and contact emergency services.") }
        item { ActionCard("112 • India emergency", "Police, fire or medical emergency. Opens your phone dialer; no call is made automatically.", "Open 112 dialer") { confirmNumber = "112" } }
        item { ActionCard("1033 • National highways", "For applicable national highway assistance in India. Availability depends on location and network.", "Open 1033 dialer") { confirmNumber = "1033" } }
        item { Text("No network? Calls and messages may be unavailable. Do not assume help has been notified. Use safe alternatives or seek nearby assistance when possible.") }
        item { Text("GPS and automated dispatch are not implemented in this preview.") }
        if (error) item { Text("No dialer available. Use another phone if possible.", color = MaterialTheme.colorScheme.error) }
    }
    confirmNumber?.let { number ->
        AlertDialog(onDismissRequest = { confirmNumber = null }, title = { Text("Open phone dialer?") },
            text = { Text("You must choose to place the call. TripGuard cannot confirm delivery or dispatch.") },
            confirmButton = { TextButton(onClick = {
                confirmNumber = null
                try { context.startActivity(Intent(Intent.ACTION_DIAL, "tel:$number".toUri())); error = false }
                catch (_: android.content.ActivityNotFoundException) { error = true }
            }) { Text("Open dialer") } },
            dismissButton = { TextButton(onClick = { confirmNumber = null }) { Text("Cancel") } })
    }
}
@Composable
private fun Settings(privacy: () -> Unit, capabilities: () -> Unit) = Body {
    item { ActionCard("Privacy and local data", "Review or clear this session.", "Manage local data", privacy) }
    item { ActionCard("Vehicle capabilities", "Understand supported and unavailable checks.", "View capabilities", capabilities) }
    item { Text("Local preview", style = MaterialTheme.typography.headlineSmall) }
    item { Text("No account, internet permission or cloud synchronisation. Data is in memory and is lost when the process ends.") }
    item { Text("Device integrations, AI diagnosis and remote Guardian functionality are not available in this build.") }
    item { Text("Safety limitation: TripGuard is not a replacement for professional inspections, manufacturer warnings or emergency services.") }
}

@Composable
private fun Welcome(continueLocal: () -> Unit) = Body {
    item { Text("TripGuard", style = MaterialTheme.typography.headlineLarge) }
    item { Text("Prevent • Detect • Protect", style = MaterialTheme.typography.titleLarge) }
    item { Text("Prepare for journeys with guided visual checks and emergency guidance. No account is required.") }
    item { Text("This preview uses session-only data. It does not detect hidden mechanical faults, track your journey or dispatch help.") }
    item { Button(onClick = continueLocal, modifier = Modifier.fillMaxWidth()) { Text("Continue locally") } }
}
@Composable
private fun Capabilities() = Body {
    item { Text("Supported in this preview", style = MaterialTheme.typography.titleLarge) }
    item { Text("Manual vehicle profiles, stationary visual observations and local destination recording.") }
    item { Text("Not available", style = MaterialTheme.typography.titleLarge) }
    item { Text("Camera diagnosis, live sensor data, GPS sharing, automatic emergency dispatch and background monitoring.") }
    item { Text("Vehicle-specific checks depend on equipment fitted. Follow the manufacturer's manual; unknown checks cannot establish safety.") }
}
@Composable
private fun Privacy(clear: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Body {
        item { Text("Your preview data", style = MaterialTheme.typography.titleLarge) }
        item { Text("Vehicle profiles, inspection summaries and destination are held in memory. No cloud transmission or account.") }
        item { OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Clear all preview data") } }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Clear this session?") },
        text = { Text("All vehicle profiles, observations and the active destination will be removed. This cannot be undone.") },
        confirmButton = { TextButton(onClick = { confirm = false; clear() }) { Text("Clear data") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}
