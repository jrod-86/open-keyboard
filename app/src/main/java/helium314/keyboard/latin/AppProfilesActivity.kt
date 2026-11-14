// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.utils.Theme

/** Settings › Per-app settings: every app with its own keyboard settings, with a reset for each. */
class AppProfilesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Theme { Screen() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun Screen() {
        var tick by remember { mutableStateOf(0) }
        val apps = remember(tick) { AppProfiles.apps(this) }
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = { TopAppBar(title = { Text("Per-app settings") }) },
        ) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Give an app its own keyboard height, language, autocorrect, suggestions and sounds. " +
                        "Open the app, tap the grid button, then the \"This app\" tile. " +
                        "The keyboard switches to those settings by itself whenever you type in that app.")
                if (apps.isEmpty()) Text("No apps have their own settings yet.", style = MaterialTheme.typography.bodySmall)
                apps.forEach { pkg ->
                    val name = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.titleMedium)
                                Text(AppProfiles.summary(this@AppProfilesActivity, pkg).ifEmpty { "—" }, style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(onClick = { AppProfiles.clear(this@AppProfilesActivity, pkg); tick++ }) { Text("Reset") }
                        }
                    }
                }
            }
        }
    }
}
