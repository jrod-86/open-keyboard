// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import helium314.keyboard.latin.Plugins
import helium314.keyboard.settings.SearchSettingsScreen

@Composable
fun PluginsScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val apps = remember { Plugins.appActions(ctx) }
    var version by remember { mutableStateOf(0) }
    var commandApps by remember { mutableStateOf(helium314.keyboard.latin.CommandPlugins.providers) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        helium314.keyboard.latin.CommandPlugins.refresh(ctx, true) { commandApps = helium314.keyboard.latin.CommandPlugins.providers }
    }

    var mcpServers by remember { mutableStateOf(helium314.keyboard.latin.McpServers.load(ctx)) }
    var adding by remember { mutableStateOf(false) }
    fun reloadMcp() { helium314.keyboard.latin.McpServers.refresh(ctx, true) { version++ } }
    androidx.compose.runtime.LaunchedEffect(Unit) { reloadMcp() }

    @Composable
    fun row(id: String, name: String, description: String?, icon: android.graphics.drawable.Drawable? = null, enabled: Boolean = true) {
        val on = remember(version) { Plugins.isOn(ctx, id) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                val bmp = remember(icon) { runCatching { icon.toBitmap(96, 96).asImageBitmap() }.getOrNull() }
                if (bmp != null) Image(bmp, null, Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold)
                if (description != null) Text(description, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = on, enabled = enabled, onCheckedChange = {
                Plugins.set(ctx, id, it); version++
                helium314.keyboard.latin.CommandPlugins.refresh(ctx, true)
                if (id.startsWith("mcp")) reloadMcp()
            })
        }
    }

    SearchSettingsScreen(onClickBack = onClickBack, title = "Plugins", settings = emptyList()) {
        Column(Modifier.padding(16.dp)) {
            Text("Everything extra in the keyboard is a plugin. Switch off what you don't use and it " +
                    "disappears from the keyboard.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Plugins.builtIn.forEach { row(it.id, it.name, it.description) }
            Spacer(Modifier.height(16.dp))
            Text("MCP servers", style = MaterialTheme.typography.titleMedium)
            Text("Add an MCP server and its tools become / commands. Nothing is sent to it until you " +
                    "tap Ask on a command, and then only what you typed after the command.",
                style = MaterialTheme.typography.bodySmall)
            val mcpOn = remember(version) { Plugins.isOn(ctx, "mcp") }
            mcpServers.forEach { s ->
                val problem = helium314.keyboard.latin.McpServers.problems[s.url]
                val toolNames = helium314.keyboard.latin.McpServers.tools.filter { it.server.url == s.url }
                    .joinToString("  ") { "/" + helium314.keyboard.latin.McpServers.commandName(it.name) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        row(s.id, s.name, problem?.let { "Couldn't connect: $it" } ?: toolNames.ifEmpty { s.url }, enabled = mcpOn)
                    }
                    androidx.compose.material3.TextButton(onClick = {
                        helium314.keyboard.latin.McpServers.save(ctx, mcpServers - s)
                        mcpServers = helium314.keyboard.latin.McpServers.load(ctx); reloadMcp()
                    }) { Text("✕") }
                }
            }
            androidx.compose.material3.Button(onClick = { adding = true }, enabled = mcpOn) { Text("Add an MCP server") }
            if (commandApps.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Commands from your apps", style = MaterialTheme.typography.titleMedium)
                val cmdOn = remember(version) { Plugins.isOn(ctx, "app_commands") }
                commandApps.forEach { p ->
                    val icon = remember(p.packageName) { runCatching { ctx.packageManager.getApplicationIcon(p.packageName) }.getOrNull() }
                    row(p.id, p.label, p.names.joinToString("  ") { "/$it" }, icon, enabled = cmdOn)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("From your apps", style = MaterialTheme.typography.titleMedium)
            Text("These apps can work on your text. Each one shows up in the keyboard menu: tap it to " +
                    "use it on the selected text, or on everything you've typed.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            val appsOn = remember(version) { Plugins.isOn(ctx, "app_actions") }
            if (apps.isEmpty()) Text("None of your apps offer text actions yet.", style = MaterialTheme.typography.bodyMedium)
            apps.forEach { row(it.id, it.label, null, it.icon, enabled = appsOn) }
        }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("https://") }
        var token by remember { mutableStateOf("") }
        val valid = name.isNotBlank() && url.trim().startsWith("https://") && url.trim().length > 10
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add an MCP server") },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text("Name, e.g. Weather") }, singleLine = true)
                    androidx.compose.material3.OutlinedTextField(value = url, onValueChange = { url = it.trim() },
                        label = { Text("Address (https://…/mcp)") }, singleLine = true)
                    androidx.compose.material3.OutlinedTextField(value = token, onValueChange = { token = it.trim() },
                        label = { Text("Key, if it needs one") }, singleLine = true)
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(enabled = valid, onClick = {
                    val list = mcpServers.filter { it.url != url.trim() } + helium314.keyboard.latin.McpServers.Server(name.trim(), url.trim(), token)
                    helium314.keyboard.latin.McpServers.save(ctx, list)
                    mcpServers = list; adding = false; reloadMcp()
                }) { Text("Add") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}
