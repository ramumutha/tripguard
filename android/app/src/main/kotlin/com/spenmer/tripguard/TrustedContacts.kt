package com.spenmer.tripguard

import android.content.Intent
import android.content.ActivityNotFoundException
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.spenmer.tripguard.domain.ContactPhone
import java.util.UUID

@Composable
fun TrustedContacts(contacts: List<TrustedContactRow>, busy: Boolean,
    save: (TrustedContactRow, () -> Unit) -> Unit, remove: (String, () -> Unit) -> Unit) {
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<TrustedContactRow?>(null) }
    var calling by remember { mutableStateOf<TrustedContactRow?>(null) }
    var error by remember { mutableStateOf(false) }
    val normalized = ContactPhone.normalize(phone)
    fun reset() { editing = null; name = ""; phone = "" }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Trusted emergency contacts", style = MaterialTheme.typography.headlineSmall) }
        item { Text("Enter contacts manually with their permission. Records stay on this phone. No automatic alerts, remote monitoring or contact-book access.") }
        item { OutlinedTextField(name, { name = it }, label = { Text("Contact name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy) }
        item { OutlinedTextField(phone, { phone = it }, label = { Text("Phone number with country code if needed") }, singleLine = true,
            isError = phone.isNotBlank() && normalized == null, modifier = Modifier.fillMaxWidth(), enabled = !busy) }
        item { Button(onClick = {
            normalized?.let { save(TrustedContactRow(editing ?: UUID.randomUUID().toString(), name.trim(), it), ::reset) }
        }, enabled = !busy && name.isNotBlank() && name.trim().length <= 80 && normalized != null,
            modifier = Modifier.fillMaxWidth()) { Text(if (editing == null) "Add trusted contact" else "Save contact") } }
        if (editing != null) item { TextButton(onClick = { reset() }, enabled = !busy) { Text("Cancel edit") } }
        if (contacts.isEmpty()) item { Text("No trusted contacts saved.") }
        items(contacts, key = { it.id }) { contact ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(contact.name, style = MaterialTheme.typography.titleMedium)
                    Text(contact.phone)
                    Button(onClick = { calling = contact }, modifier = Modifier.fillMaxWidth()) { Text("Open contact dialer") }
                    OutlinedButton(onClick = { editing = contact.id; name = contact.name; phone = contact.phone }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Edit contact") }
                    TextButton(onClick = { deleting = contact }, enabled = !busy) { Text("Remove contact") }
                }
            }
        }
        if (error) item { Text("Unable to open the phone dialer. No call was placed.", color = MaterialTheme.colorScheme.error) }
    }
    deleting?.let { contact ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Remove trusted contact?") },
            text = { Text("Remove ${contact.name} from this phone's TripGuard records?") },
            confirmButton = { TextButton(onClick = { remove(contact.id) { if (editing == contact.id) reset(); deleting = null } }, enabled = !busy) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
    calling?.let { contact ->
        AlertDialog(onDismissRequest = { calling = null }, title = { Text("Open phone dialer?") },
            text = { Text("Call ${contact.name} yourself from the phone dialer. TripGuard will not place a call automatically.") },
            confirmButton = { TextButton(onClick = {
                calling = null
                try { context.startActivity(Intent(Intent.ACTION_DIAL, "tel:${contact.phone}".toUri())); error = false }
                catch (_: ActivityNotFoundException) { error = true }
            }) { Text("Open dialer") } },
            dismissButton = { TextButton(onClick = { calling = null }) { Text("Cancel") } })
    }
}
