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
            })
        }
    }

    SearchSettingsScreen(onClickBack = onClickBack, title = "Plugins", settings = emptyList()) {
        Column(Modifier.padding(16.dp)) {
            Text("Everything extra in the keyboard is a plugin. Switch off what you don't use and it " +
                    "disappears from the keyboard.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Plugins.builtIn.forEach { row(it.id, it.name, it.description) }
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
}
