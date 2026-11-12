// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.TextShortcuts
import helium314.keyboard.settings.SearchSettingsScreen

@Composable
fun TextShortcutsScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(TextShortcuts.load(ctx)) }
    var editing by remember { mutableStateOf<Int?>(null) } // index, or -1 for a new one

    fun store(newList: List<TextShortcuts.Shortcut>) { list = newList; TextShortcuts.save(ctx, newList) }

    SearchSettingsScreen(onClickBack = onClickBack, title = "Text shortcuts", settings = emptyList()) {
        Column(Modifier.padding(16.dp)) {
            Text("Type a shortcut and press space, and it turns into the full text. " +
                    "Press delete straight away to get the shortcut back.",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { editing = -1 }) { Text("Add a shortcut") }
            Spacer(Modifier.height(12.dp))
            if (list.isEmpty()) Text("No shortcuts yet. Try @@ for your email address.",
                style = MaterialTheme.typography.bodyMedium)
            list.forEachIndexed { i, s ->
                Row(Modifier.fillMaxWidth().clickable { editing = i }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(s.trigger, fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp))
                    Text(s.text.replace("\n", " ⏎ "), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { store(list.filterIndexed { j, _ -> j != i }) }) { Text("✕") }
                }
            }
        }
    }

    val index = editing ?: return
    val old = list.getOrNull(index)
    var trigger by remember(index) { mutableStateOf(old?.trigger ?: "") }
    var text by remember(index) { mutableStateOf(old?.text ?: "") }
    val clash = list.withIndex().any { (j, s) -> j != index && s.trigger.equals(trigger.trim(), true) }
    val valid = trigger.isNotBlank() && !trigger.trim().contains(' ') && text.isNotBlank() && !clash
    AlertDialog(
        onDismissRequest = { editing = null },
        title = { Text(if (old == null) "New shortcut" else "Edit shortcut") },
        text = {
            Column {
                OutlinedTextField(value = trigger, onValueChange = { trigger = it.replace(" ", "") },
                    label = { Text("Shortcut, e.g. @@") }, singleLine = true)
                if (clash) Text("You already have this shortcut", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = text, onValueChange = { text = it },
                    label = { Text("Turns into, e.g. your email") }, minLines = 2)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val s = TextShortcuts.Shortcut(trigger.trim(), text)
                store(if (old == null) list + s else list.mapIndexed { j, o -> if (j == index) s else o })
                editing = null
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
    )
}
