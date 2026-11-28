// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
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
    val apps = remember { Plugins.appActions(ctx, fresh = true) }
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

    SearchSettingsScreen(onClickBack = onClickBack, title = "Open Hub", settings = emptyList()) {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp)) {
            Text("Everything extra in the keyboard is a plugin. Switch off what you don't use and it " +
                    "disappears from the keyboard.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            SpeedMeterCard()
            Spacer(Modifier.height(12.dp))
            ModelManagerCard()
            Spacer(Modifier.height(12.dp))
            PluginStoreCard(onBack = {
                // back from installing or removing a plugin: look for plugin apps again
                helium314.keyboard.latin.CommandPlugins.refresh(ctx, true) { commandApps = helium314.keyboard.latin.CommandPlugins.providers }
            })
            Spacer(Modifier.height(16.dp))
            Text("Apps in the app drawer", style = MaterialTheme.typography.titleMedium)
            Text("Switch an app off and it leaves the app drawer and the top bar. Switch it back on any time.",
                style = MaterialTheme.typography.bodySmall)
            Plugins.apps.forEach { row(it.id, it.name, it.description) }
            Spacer(Modifier.height(16.dp))
            Text("Keyboard features", style = MaterialTheme.typography.titleMedium)
            Plugins.builtIn.forEach {
                row(it.id, it.name, it.description)
                // Open Keyboard (test 220, Jarrod): a switch for each voice command, so you keep only the ones you like
                if (it.id == "voice_commands" && remember(version) { Plugins.isOn(ctx, "voice_commands") }) {
                    Column(Modifier.padding(start = 20.dp)) {
                        helium314.keyboard.voice.VoiceCommands.commands.forEach { c -> row(c.id, c.name, c.example) }
                    }
                }
            }
            if (remember(version) { Plugins.isOn(ctx, "fingers") }) {
                val taps = remember(version) { helium314.keyboard.keyboard.FingerModel.learnedTaps(ctx) }
                androidx.compose.material3.TextButton(onClick = {
                    helium314.keyboard.keyboard.FingerModel.reset(ctx); version++
                }) { Text(if (taps == 0) "Learns your fingers: still watching how you type" else "Forget how I tap (learned from $taps taps)") }
            }
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
            Spacer(Modifier.height(12.dp))
            KeyboardMcpCard()
            if (commandApps.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Commands from your apps", style = MaterialTheme.typography.titleMedium)
                val cmdOn = remember(version) { Plugins.isOn(ctx, "app_commands") }
                commandApps.forEach { p ->
                    val icon = remember(p.packageName) { runCatching { ctx.packageManager.getApplicationIcon(p.packageName) }.getOrNull() }
                    val badge = remember(p.packageName) { helium314.keyboard.latin.ModelManager.trust(ctx, p.packageName, "only what you type after its command") }
                    val fresh = remember(version, p.id) { Plugins.isNew(ctx, p.id) }
                    row(p.id, (if (fresh) "🆕 " else "") + p.label, p.names.joinToString("  ") { "/$it" } + "\n" + badge +
                        (if (fresh) "\nNew: off until you switch it on" else ""), icon, enabled = cmdOn)
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
            apps.forEach {
                val badge = remember(it.id) { helium314.keyboard.latin.ModelManager.trust(ctx, it.component.packageName, "only the text you send it from the menu") }
                val fresh = remember(version, it.id) { Plugins.isNew(ctx, it.id) }
                row(it.id, (if (fresh) "🆕 " else "") + it.label, badge + (if (fresh) "\nNew: off until you switch it on" else ""), it.icon, enabled = appsOn)
            }
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

/**
 * Open Keyboard (test 219): the speed meter. How fast the keyboard opens, any freezes, and how much memory each part of
 * the app uses right now, so a plugin that slows things down is easy to spot.
 */
@Composable
private fun SpeedMeterCard() {
    val ctx = LocalContext.current
    var memory by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        memory = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { helium314.keyboard.latin.SpeedMeter.memoryByPart(ctx) }
    }
    val meter = helium314.keyboard.latin.SpeedMeter
    val opens = remember { meter.openTimes() }
    val freezes = remember { meter.freezes() }
    val low = remember { meter.lowMemoryEvents() }
    val loaded = remember { meter.loadedNow() }
    androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("⚡ Keyboard speed", style = MaterialTheme.typography.titleMedium)
            Text("Since the keyboard last started.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            if (opens.isEmpty()) Text("Opening: open the keyboard a few times to see this.")
            else {
                val avg = opens.average().toInt()
                val verdict = when { avg < 150 -> "fast"; avg < 400 -> "OK"; else -> "slow" }
                Text("Opening: ${avg} ms on average ($verdict), slowest ${opens.max()} ms, over ${opens.size} openings")
            }
            if (freezes.isEmpty()) Text("Freezes: none 👍")
            else {
                val longest = freezes.maxBy { it.ms }
                Text("Freezes: ${freezes.size}, longest ${longest.ms} ms (in ${longest.what})")
                val fmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                freezes.takeLast(3).reversed().forEach {
                    Text("  ${fmt.format(java.util.Date(it.atWallMs))}: ${it.ms} ms in ${it.what}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (low.isNotEmpty()) Text("Phone short of memory ${low.size} time(s); AI models were given back.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text("Memory now", fontWeight = FontWeight.Bold)
            if (memory.isEmpty()) Text("Working it out…", style = MaterialTheme.typography.bodySmall)
            memory.forEach { (name, mb) -> Text("$name: $mb MB", style = MaterialTheme.typography.bodySmall) }
            Text("Loaded in the keyboard: " + (loaded.joinToString(", ").ifEmpty { "no AI models (lightest)" }) + ". The writing model always runs outside the keyboard.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Open Keyboard (test 219, idea 5): the shared Model Manager. Every AI model downloaded, how big it is, which apps share
 * it, and Delete. Model packs at the top get everything for one job in one tap.
 */
@Composable
private fun ModelManagerCard() {
    val ctx = LocalContext.current
    val mm = helium314.keyboard.latin.ModelManager
    val wt = helium314.keyboard.latin.WritingTools
    var tick by remember { mutableStateOf(0) }
    var models by remember { mutableStateOf<List<helium314.keyboard.latin.ModelManager.Entry>?>(null) }
    var asking by remember { mutableStateOf<helium314.keyboard.latin.ModelManager.Entry?>(null) }
    androidx.compose.runtime.LaunchedEffect(tick) {
        models = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { mm.list(ctx) }
    }
    val voiceModels = helium314.keyboard.voice.VoiceModel.entries
    val voiceDone = remember(tick) { voiceModels.all { it.isInstalled(ctx) } }
    val writingDone = remember(tick) { wt.isReady(ctx, wt.QUICK) || wt.isReady(ctx, wt.BETTER) }
    val voiceSize = "%.1f GB".format(voiceModels.sumOf { it.approxMb } / 1000.0)
    fun getVoice() = ctx.startActivity(android.content.Intent(ctx, helium314.keyboard.voice.VoiceSetupActivity::class.java))
    fun getWriting() { wt.startDownload(ctx, wt.QUICK); wt.choose(ctx, wt.QUICK) }
    androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("🧠 AI models", style = MaterialTheme.typography.titleMedium)
            Text("Each model is downloaded once and shared by every app that needs it.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text("Model packs", fontWeight = FontWeight.Bold)
            @Composable
            fun pack(name: String, what: String, done: Boolean, get: () -> Unit) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name)
                        Text(what, style = MaterialTheme.typography.bodySmall)
                    }
                    // test 222: same look as the Delete buttons below (Jarrod: the packs looked different)
                    androidx.compose.material3.TextButton(onClick = { get(); tick++ }, enabled = !done) { Text(if (done) "✓ Got it" else "Get") }
                }
            }
            pack("🎙️ Voice pack", "Voice typing, voice commands, Chat Talk mode. About $voiceSize.", voiceDone) { getVoice() }
            pack("✍️ Writing pack", "Smart compose, Writing help, AI writer, Chat. About ${wt.QUICK.sizeText}.", writingDone) { getWriting() }
            pack("📴 Everything offline", "Both packs, so the keyboard's AI works with no internet. About " +
                    "%.1f GB. Use Wi-Fi.".format(voiceModels.sumOf { it.approxMb } / 1000.0 + wt.QUICK.size / 1e9), voiceDone && writingDone) {
                if (!writingDone) getWriting()
                if (!voiceDone) getVoice()
            }
            Spacer(Modifier.height(8.dp))
            Text("Downloaded", fontWeight = FontWeight.Bold)
            val list = models
            if (list == null) Text("Looking…", style = MaterialTheme.typography.bodySmall)
            else if (list.isEmpty()) Text("No AI models downloaded yet.", style = MaterialTheme.typography.bodySmall)
            else {
                Text("Total: " + mm.sizeText(list.sumOf { it.bytes }), style = MaterialTheme.typography.bodySmall)
                list.forEach { e ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${e.name} · ${mm.sizeText(e.bytes)}")
                            Text("Used by: ${e.usedBy}", style = MaterialTheme.typography.bodySmall)
                        }
                        androidx.compose.material3.TextButton(onClick = { asking = e }) { Text("Delete") }
                    }
                }
            }
        }
    }
    asking?.let { e ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("Delete ${e.name}?") },
            text = { Text(if (e.userMade) "These are things you made. They can't be downloaded again." else
                "Frees ${mm.sizeText(e.bytes)}. ${e.usedBy} won't work until you download it again.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    asking = null
                    val problem = mm.delete(ctx, e)
                    android.widget.Toast.makeText(ctx, problem ?: "Deleted ${e.name}", android.widget.Toast.LENGTH_LONG).show()
                    tick++
                }) { Text("Delete") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Open Keyboard (test 225): the plugin store. Free, open source plugin apps from a list on GitHub. Get opens F-Droid or
 * the download page; once installed, the plugin shows up below (switched off until you switch it on).
 */
@Composable
private fun PluginStoreCard(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = helium314.keyboard.latin.PluginStore
    var open by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<helium314.keyboard.latin.PluginStore.Item>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    if (open) androidx.compose.runtime.LaunchedEffect(Unit) {
        val (list, problem) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.load(ctx) }
        items = list; note = problem
    }
    // coming back from the installer: check again what's installed
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { tick++; if (tick > 1) onBack() } }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("🛍️ Open Hub Store", style = MaterialTheme.typography.titleMedium)
            Text("Free, open source mini-apps & plugins for O-Board. New ones start switched off.", style = MaterialTheme.typography.bodySmall)
            if (!open) androidx.compose.material3.TextButton(onClick = { open = true }) { Text("Browse Open Hub") }
            else {
                val list = items
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (list == null) Text("Loading…", style = MaterialTheme.typography.bodySmall)
                else if (list.isEmpty() && note == null) Text("No apps in Open Hub yet.", style = MaterialTheme.typography.bodySmall)
                list?.forEach { item ->
                    val installed = remember(tick, item.packageName) { store.isInstalled(ctx, item) }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${item.emoji} ${item.name}", fontWeight = FontWeight.Bold)
                            Text(item.description, style = MaterialTheme.typography.bodySmall)
                            val facts = listOfNotNull(
                                if (item.source != null) "🔓 Open source" else null,
                                if (item.offline) "📴 Works offline" else "🌐 Uses the internet",
                                item.usesModels?.let { "🧠 Uses your $it" },
                                item.size,
                                item.author.ifBlank { null }?.let { "by $it" },
                            )
                            Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (installed) {
                                androidx.compose.material3.TextButton(onClick = {}, enabled = false) { Text("✓ Installed") }
                                androidx.compose.material3.TextButton(onClick = { store.uninstall(ctx, item) }) { Text("Remove") }
                            } else androidx.compose.material3.TextButton(onClick = { store.get(ctx, item) }) { Text("Get") }
                            if (item.source != null) androidx.compose.material3.TextButton(onClick = { store.openSource(ctx, item) }) { Text("Code") }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Made a plugin? Add it to plugin-store.json on GitHub (see PLUGINS.md).", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Open Keyboard (test 226): the keyboard as an MCP server, so AI apps on this phone can use its on-phone AI.
 * Off until switched on; only reachable from this phone, and only with the key.
 */
@Composable
private fun KeyboardMcpCard() {
    val ctx = LocalContext.current
    val server = helium314.keyboard.latin.KeyboardMcpServer
    var on by remember { mutableStateOf(server.isEnabled(ctx)) }
    var key by remember { mutableStateOf(if (on) server.key(ctx) else "") }
    fun copy(label: String, text: String) {
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        android.widget.Toast.makeText(ctx, "$label copied", android.widget.Toast.LENGTH_SHORT).show()
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Let AI apps use the keyboard (MCP)", fontWeight = FontWeight.Bold)
            Text("AI apps on this phone can use the keyboard's on-phone AI: write, rewrite in a style, list models. " +
                    "Only apps on this phone, and only with the key below. Nothing leaves the phone.",
                style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = on, onCheckedChange = {
            server.setEnabled(ctx, it); on = it
            if (it) key = server.key(ctx)
        })
    }
    if (on) {
        Text("Address: ${server.ADDRESS}", style = MaterialTheme.typography.bodySmall)
        Text("Key: $key", style = MaterialTheme.typography.bodySmall)
        if (!server.running) Text("Couldn't start (port ${server.PORT} is in use). Switch it off and on again.", style = MaterialTheme.typography.bodySmall)
        Row {
            androidx.compose.material3.TextButton(onClick = { copy("Address", server.ADDRESS) }) { Text("Copy address") }
            androidx.compose.material3.TextButton(onClick = { copy("Key", key) }) { Text("Copy key") }
            androidx.compose.material3.TextButton(onClick = { key = server.newKey(ctx) }) { Text("New key") }
        }
    }
}
