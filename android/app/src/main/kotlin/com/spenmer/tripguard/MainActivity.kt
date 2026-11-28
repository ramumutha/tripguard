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
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
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

private data class LocalSnapshot(val vehicles: List<Vehicle>, val history: List<InspectionPreview>, val journey: String?, val contacts: List<TrustedContactRow>, val completed: List<CompletedJourneyRow>)
data class InspectionPreview(val vehicle: String, val finding: Finding, val completed: Int, val total: Int)
class TripGuardViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = LocalDatabase.get(application).dao()
    var vehicles by mutableStateOf(emptyList<Vehicle>()); private set
    var history by mutableStateOf(emptyList<InspectionPreview>()); private set
    var journey by mutableStateOf<String?>(null); private set
    var contacts by mutableStateOf(emptyList<TrustedContactRow>()); private set
    var completedJourneys by mutableStateOf(emptyList<CompletedJourneyRow>()); private set
    var ready by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var failure by mutableStateOf<String?>(null); private set
    init {
        viewModelScope.launch {
            try {
                combine(dao.vehicles(), dao.inspections(), dao.journey(), dao.contacts(), dao.completedJourneys()) { v, h, j, contacts, completed ->
                    LocalSnapshot(v.map { it.domain() }, h.map { InspectionPreview(it.vehicle, Finding.valueOf(it.finding), it.completed, it.total) }, j?.destination, contacts, completed)
                }.collect { snapshot ->
                    vehicles = snapshot.vehicles; history = snapshot.history; journey = snapshot.journey; contacts = snapshot.contacts; completedJourneys = snapshot.completed; ready = true
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { ready = false; failure = "Local records unavailable. Restart the app to retry." }
        }
    }
    fun dismissFailure() { failure = null }
    private fun change(after: () -> Unit = {}, operation: suspend () -> Unit) {
        if (!ready || busy) return
        busy = true
        viewModelScope.launch {
            try { operation(); after() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failure = "Local change could not be saved. Please retry." }
            finally { busy = false }
        }
    }
    fun save(v: Vehicle, after: () -> Unit) = change(after) {
        dao.saveVehicle(VehicleRow(v.id, v.name, v.kind.name, v.propulsion.name))
    }
    fun remove(id: String, after: () -> Unit) = change(after) { dao.deleteVehicle(id) }
    fun record(record: InspectionPreview, after: () -> Unit) = change(after) {
        dao.record(InspectionRow(vehicle = record.vehicle, finding = record.finding.name,
            completed = record.completed, total = record.total, recordedAt = System.currentTimeMillis()))
    }
    fun startJourney(destination: String) = change {
        check(dao.start(JourneyRow(destination = destination, startedAt = System.currentTimeMillis())) != -1L)
    }
    fun endJourney() = change { dao.end(System.currentTimeMillis()) }
    fun saveContact(row: TrustedContactRow, after: () -> Unit) = change(after) { dao.saveContact(row) }
    fun removeContact(id: String, after: () -> Unit) = change(after) { dao.deleteContact(id) }
    fun clearPreview(after: () -> Unit) = change(after) { dao.clearAll() }
}
enum class Page(val title: String) { FEATURES("Feature catalogue"), FEATURE("Feature preview"), RC_CAPTURE("RC capture"), VISUAL_CAPTURE("Visual capture"), CONTACTS("Trusted contacts"), PREPARE("Journey preparedness"), REST_STOP("Rest-stop check"), WELCOME("Welcome"), CAPABILITIES("Vehicle capabilities"), PRIVACY("Privacy and local data"), HOME("Home"), GARAGE("Garage"), ADD("Vehicle profile"),
    INSPECTION("Safety inspection"), HISTORY("Inspection history"), JOURNEY("Journey"),
    RESCUE("Emergency assistance"), SETTINGS("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripGuard(vm: TripGuardViewModel = viewModel()) {
    var page by rememberSaveable { mutableStateOf(Page.WELCOME) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var feature by rememberSaveable { mutableStateOf(Feature.GUARDIAN) }
    val openFeature: (Feature) -> Unit = { feature = it; page = Page.FEATURE }
    val navigate: (Page) -> Unit = { page = it }
    BackHandler(page != Page.HOME) { page = when (page) { Page.FEATURE -> Page.FEATURES; Page.FEATURES -> Page.SETTINGS; Page.ADD -> Page.GARAGE; Page.RC_CAPTURE -> Page.GARAGE; Page.VISUAL_CAPTURE -> Page.INSPECTION; Page.PREPARE, Page.REST_STOP -> Page.JOURNEY; Page.CONTACTS -> Page.RESCUE; else -> Page.HOME } }
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
                Text("LOCAL PREVIEW • No live monitoring", Modifier.fillMaxWidth().padding(12.dp),
                    style = MaterialTheme.typography.labelMedium)
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.failure?.let { message -> Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { Text(message, Modifier.weight(1f)); TextButton(onClick = vm::dismissFailure) { Text("Dismiss") } } }
            Box(Modifier.weight(1f)) {
                if (page != Page.RESCUE && !vm.ready) {
                    Column(Modifier.padding(16.dp)) { Text("Local storage is loading or unavailable."); if (vm.failure != null) Text("Restart the app to retry.") }
                } else when(page) {
                    Page.HOME -> Home(vm.vehicles.size, navigate)
                    Page.FEATURES -> FeatureCatalogue(openFeature)
                    Page.FEATURE -> FeaturePreview(feature, openFeature) { page = Page.FEATURES }
                    Page.GARAGE -> Garage(vm.vehicles, { editingId = null; page = Page.ADD }, { page = Page.RC_CAPTURE },
                        { editingId = it.id; page = Page.ADD }, { selectedId = it.id; page = Page.INSPECTION })
                    Page.ADD -> VehicleForm(vm.vehicles.firstOrNull { it.id == editingId }, {
                        vm.save(it) { page = Page.GARAGE }
                    }, { vm.remove(it) { page = Page.GARAGE } }, { page = Page.GARAGE })
                    Page.INSPECTION -> Inspection(vm.vehicles, selectedId, {
                        vm.record(it) { page = Page.HISTORY }
                    }, { editingId = null; page = Page.ADD }, { page = Page.VISUAL_CAPTURE })
                    Page.HISTORY -> History(vm.history, { page = Page.INSPECTION })
                    Page.RC_CAPTURE -> CapturePreview(true) { editingId = null; page = Page.ADD }
                    Page.VISUAL_CAPTURE -> CapturePreview(false) { page = Page.INSPECTION }
                    Page.JOURNEY -> Journey(vm.journey, vm.completedJourneys, vm::startJourney, vm::endJourney, { page = Page.PREPARE }, { page = Page.REST_STOP })
                    Page.PREPARE -> JourneyPreparation { page = Page.JOURNEY }
                    Page.REST_STOP -> RestStop { selectedId = null; page = Page.INSPECTION }
                    Page.CONTACTS -> TrustedContacts(vm.contacts, vm.busy, vm::saveContact, vm::removeContact)
                    Page.RESCUE -> Rescue { page = Page.CONTACTS }
                    Page.SETTINGS -> Settings({ page = Page.PRIVACY }, { page = Page.CAPABILITIES }, { page = Page.CONTACTS }, { page = Page.FEATURES })
                    Page.WELCOME -> Welcome { page = Page.HOME }
                    Page.CAPABILITIES -> Capabilities()
                    Page.PRIVACY -> Privacy { vm.clearPreview { selectedId = null; editingId = null; page = Page.HOME } }
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
    item { ActionCard("My garage", "$count vehicles stored on this device", "Manage vehicles") { navigate(Page.GARAGE) } }
    item { ActionCard("Before you travel", "Guided stationary visual checks, not a certified inspection.", "Start safety check") { navigate(Page.INSPECTION) } }
    item { ActionCard("My journey", "Record a destination and prepare before departure.", "Plan journey") { navigate(Page.JOURNEY) } }
    item { ActionCard("Vehicle health", "Review observations stored on this device.", "View history") { navigate(Page.HISTORY) } }
    item { ActionCard("Emergency assistance", "Access guidance without signing in.", "Open emergency assistance") { navigate(Page.RESCUE) } }
    item { ActionCard("Future features", "Explore clearly labelled previews of connected services.", "Explore future features") { navigate(Page.FEATURES) } }
}
@Composable
private fun Garage(vehicles: List<Vehicle>, add: () -> Unit, capture: () -> Unit, edit: (Vehicle) -> Unit, inspect: (Vehicle) -> Unit) = Body {
    item { Button(onClick = add, modifier = Modifier.fillMaxWidth()) { Text("Add vehicle") } }
    item { OutlinedButton(onClick = capture, modifier = Modifier.fillMaxWidth()) { Text("Optional RC capture") } }
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
        title = { Text("Delete this vehicle?") }, text = { Text("The profile is removed from this device. Historical inspection summaries remain until local data is cleared.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; remove(existing.id) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}
@Composable
private fun Inspection(vehicles: List<Vehicle>, initialId: String?, save: (InspectionPreview) -> Unit, add: () -> Unit, capture: () -> Unit) {
    var chosen by rememberSaveable { mutableStateOf(initialId ?: vehicles.firstOrNull()?.id) }
    val vehicle = vehicles.firstOrNull { it.id == chosen }
    val observations = remember(chosen) { mutableStateMapOf<String, Observation>() }
    var detailId by rememberSaveable(chosen) { mutableStateOf<String?>(null) }
    var pendingVehicle by remember { mutableStateOf<String?>(null) }
    val checks = vehicle?.let(SafetyPolicy::checklist).orEmpty()
    val result = SafetyPolicy.assess(checks.map { observations[it.id] ?: Observation.UNKNOWN })
    val detail = checks.firstOrNull { it.id == detailId }
    if (detail != null) {
        BackHandler { detailId = null }
        InspectionComponent(detail, observations[detail.id] ?: Observation.UNKNOWN, { choice -> observations[detail.id] = choice; detailId = null }, { detailId = null })
        return
    }
    Body {
        if (vehicles.isEmpty()) item { ActionCard("Add a vehicle", "Inspection guidance depends on vehicle type.", "Add vehicle", add) }
        else {
            item { Text("Select vehicle", style = MaterialTheme.typography.titleMedium) }
            items(vehicles, key = { it.id }) { v -> Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = chosen == v.id, onClick = { if (chosen != v.id && observations.isNotEmpty()) pendingVehicle = v.id else chosen = v.id }); Text(v.name)
            } }
            item { OutlinedButton(onClick = capture, enabled = observations.isEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Preview visual capture") }; if (observations.isNotEmpty()) Text("Finish or leave this checklist before opening the separate camera preview.") }
            item { Text("Only inspect while safely parked. Follow the owner's manual. Unknown checks do not count as completed.") }
            if (vehicle != null) item {
                val observed = checks.count { observations[it.id] in listOf(Observation.NO_VISIBLE_CONCERN, Observation.POTENTIAL_CONCERN, Observation.CRITICAL) }
                Text("$observed of ${checks.size} components observed", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(progress = { if (checks.isEmpty()) 0f else observed.toFloat() / checks.size }, modifier = Modifier.fillMaxWidth())
            }
            items(checks, key = { it.id }) { check ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(check.title, style = MaterialTheme.typography.titleMedium)
                        Text(observationLabel(observations[check.id] ?: Observation.UNKNOWN))
                        OutlinedButton(onClick = { detailId = check.id }, modifier = Modifier.fillMaxWidth()) { Text("Check " + check.title) }
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
    pendingVehicle?.let { target ->
        AlertDialog(onDismissRequest = { pendingVehicle = null }, title = { Text("Change inspection vehicle?") },
            text = { Text("Unsaved observations for the current vehicle will be discarded.") },
            confirmButton = { TextButton(onClick = { chosen = target; pendingVehicle = null }) { Text("Change vehicle") } },
            dismissButton = { TextButton(onClick = { pendingVehicle = null }) { Text("Cancel") } })
    }
}
@Composable
private fun History(history: List<InspectionPreview>, inspect: () -> Unit) = Body {
    if (history.isEmpty()) item { Text("No inspection records on this device.") }
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
private fun Journey(active: String?, completed: List<CompletedJourneyRow>, start: (String) -> Unit, end: () -> Unit, prepare: () -> Unit, restStop: () -> Unit) {
    var confirmEnd by remember { mutableStateOf(false) }
    var destination by rememberSaveable { mutableStateOf("") }
    Body {
        item { Text("Journey preview", style = MaterialTheme.typography.headlineSmall) }
        item { Text("Local destination and journey times only. No background tracking, location sharing or remote check-ins are active.") }
        if (active == null) {
            item { ActionCard("Prepare before travel", "Review offline travel preparedness.", "Review preparedness", prepare) }
            item { OutlinedTextField(destination, { destination = it }, label = { Text("Destination") }, modifier = Modifier.fillMaxWidth()) }
            item { Button(onClick = { start(destination.trim()) }, enabled = destination.isNotBlank() && destination.length <= 120,
                modifier = Modifier.fillMaxWidth()) { Text("Start local journey preview") } }
        } else {
            item { Text("Destination: $active", style = MaterialTheme.typography.titleLarge) }
            item { Text("At safe rest stops, review warning lights and visible signs of vehicle trouble. Never handle the phone while driving.") }
            item { OutlinedButton(onClick = restStop, modifier = Modifier.fillMaxWidth()) { Text("Review rest-stop checks") } }
            item { Button(onClick = { confirmEnd = true }, modifier = Modifier.fillMaxWidth()) { Text("End journey preview") } }
        }
        item { Text("Completed journeys", style = MaterialTheme.typography.titleLarge) }
        if (completed.isEmpty()) item { Text("No completed journeys yet.") }
        items(completed, key = { it.id }) { record ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(record.destination, style = MaterialTheme.typography.titleMedium)
                    Text("Started: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(record.startedAt)))
                    Text("Ended: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(record.endedAt)))
                    Text("Recorded locally; arrival not independently verified.")
                }
            }
        }
    }
    if (confirmEnd) AlertDialog(onDismissRequest = { confirmEnd = false }, title = { Text("End this journey?") },
        text = { Text("Record the end of your local journey. This does not verify arrival or notify anyone.") },
        confirmButton = { TextButton(onClick = { confirmEnd = false; end() }) { Text("End journey") } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Cancel") } })
}
@Composable
private fun JourneyPreparation(back: () -> Unit) = Body {
    item { Text("Prepare before departure", style = MaterialTheme.typography.headlineSmall) }
    item { Text("Review your owner's manual, fuel or charge, tyre condition, warning lights and required professional maintenance.") }
    item { Text("Plan suitable rest stops and assistance options. Save essential contact numbers and offline directions before departure.") }
    item { Text("This is a manual checklist, not live route, weather, traffic or roadworthiness assessment.") }
    item { Button(onClick = back, modifier = Modifier.fillMaxWidth()) { Text("Back to journey") } }
}
@Composable
private fun RestStop(inspect: () -> Unit) = Body {
    item { Text("Check only when safely parked", style = MaterialTheme.typography.headlineSmall) }
    item { Text("Review dashboard warnings and unusual smells, leaks or visible damage. Never open a hot coolant cap. Stop travel and seek qualified assistance for critical concerns.") }
    item { Button(onClick = inspect, modifier = Modifier.fillMaxWidth()) { Text("Open safety inspection") } }
}
@Composable
private fun Rescue(contacts: () -> Unit) {
    val context = LocalContext.current
    var confirmNumber by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    Body {
        item { Text("Emergency assistance", style = MaterialTheme.typography.headlineMedium) }
        item { Text("If driving, stop safely first. If there is fire, smoke, injury or immediate danger, move to safety when possible and contact emergency services.") }
        item { ActionCard("112 • India emergency", "Police, fire or medical emergency. Opens your phone dialer; no call is made automatically.", "Open 112 dialer") { confirmNumber = "112" } }
        item { ActionCard("1033 • National highways", "For applicable national highway assistance in India. Availability depends on location and network.", "Open 1033 dialer") { confirmNumber = "1033" } }
        item { Text("No network? Calls and messages may be unavailable. Do not assume help has been notified. Use safe alternatives or seek nearby assistance when possible.") }
        item { EmergencyLocation() }
        item { ActionCard("Trusted contacts", "Open a saved contact dialer manually. No automated alerts.", "Manage trusted contacts", contacts) }
        item { Text("Automated dispatch is not implemented. Sharing is user-controlled.") }
        item { Text("Overheating or steam: stop safely, switch off the engine, follow the manufacturer manual and seek assistance. Never open a hot coolant cap.") }
        item { Text("Tyre or brake concern: stop safely and arrange professional assistance; do not rely on a visual check to continue.") }
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
private fun Settings(privacy: () -> Unit, capabilities: () -> Unit, contacts: () -> Unit, features: () -> Unit) = Body {
    item { ActionCard("Future features", "Explore non-operational feature previews.", "Explore future features", features) }
    item { ActionCard("Trusted contacts", "Manage local emergency contacts.", "Manage trusted contacts", contacts) }
    item { ActionCard("Privacy and local data", "Review or clear local records.", "Manage local data", privacy) }
    item { ActionCard("Vehicle capabilities", "Understand supported and unavailable checks.", "View capabilities", capabilities) }
    item { Text("Local preview", style = MaterialTheme.typography.headlineSmall) }
    item { Text("No account, internet permission or cloud synchronisation. Records are stored locally on this device.") }
    item { Text("Device integrations, AI diagnosis and remote Guardian functionality are not available in this build.") }
    item { Text("Safety limitation: TripGuard is not a replacement for professional inspections, manufacturer warnings or emergency services.") }
}

@Composable
private fun Welcome(continueLocal: () -> Unit) = Body {
    item { Text("TripGuard", style = MaterialTheme.typography.headlineLarge) }
    item { Text("Prevent • Detect • Protect", style = MaterialTheme.typography.titleLarge) }
    item { Text("Prepare for journeys with guided visual checks and emergency guidance. No account is required.") }
    item { Text("This preview stores records locally. It does not detect hidden mechanical faults, track your journey or dispatch help. Emergency location is optional.") }
    item { Button(onClick = continueLocal, modifier = Modifier.fillMaxWidth()) { Text("Continue locally") } }
}
@Composable
private fun Capabilities() = Body {
    item { Text("Supported in this preview", style = MaterialTheme.typography.titleLarge) }
    item { Text("Manual vehicle profiles, stationary visual observations and local destination recording.") }
    item { Text("Not available", style = MaterialTheme.typography.titleLarge) }
    item { Text("Automated camera diagnosis, live sensor data, automatic emergency dispatch and background monitoring. Foreground location sharing is available only when you request it.") }
    item { Text("Vehicle-specific checks depend on equipment fitted. Follow the manufacturer's manual; unknown checks cannot establish safety.") }
}
@Composable
private fun Privacy(clear: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Body {
        item { Text("Your preview data", style = MaterialTheme.typography.titleLarge) }
        item { Text("Vehicle profiles, inspection summaries, journeys and trusted contacts are stored locally. No cloud transmission or account.") }
        item { OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Clear all local data") } }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Clear local records?") },
        text = { Text("All vehicle profiles, observations, journeys and trusted contacts will be removed. This cannot be undone.") },
        confirmButton = { TextButton(onClick = { confirm = false; clear() }) { Text("Clear data") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}
