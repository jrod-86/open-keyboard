// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Test 295: the "Sort by popularity" preview. Looks the songs up, shows the new order with play counts and chart spots
 * as they come in, and only changes the playlist when you press Apply.
 */
@Composable
internal fun PopularitySort(songs: List<Song>, onApply: (List<Song>) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var pops by remember { mutableStateOf<Map<String, Pop>>(emptyMap()) }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(1) }
    var finished by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val ok = Popularity.lookUp(ctx, songs) { d, t, r -> done = d; total = maxOf(t, 1); pops = r }
        offline = !ok
        finished = true
    }
    val ordered = if (finished) Popularity.order(songs, pops) else songs
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val notFound = if (finished) songs.count { pops[Popularity.key(it)]?.found != true } else 0

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding()) {
            Text("Sort by popularity", Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                when {
                    !finished -> "Looking up songs… ${minOf(done, total)} of $total" + if (total > 1) " (about ${maxOf(1, (total - done))} s left)" else ""
                    offline -> "Couldn't reach the music sites. Check your internet and try again."
                    notFound > 0 -> "Most played first, from ListenBrainz. $notFound not found, kept at the bottom."
                    else -> "Most played first, from ListenBrainz. Chart spots from Wikipedia."
                },
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 13.sp, color = if (offline) Accent else dim,
            )
            if (!finished) LinearProgressIndicator(progress = { done.toFloat() / total }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = Accent)
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(ordered, key = { i, s -> "${s.id}/$i" }) { i, s ->
                    val p = pops[Popularity.key(s)]
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (finished && p?.found == true) "${i + 1}" else "", Modifier.width(30.dp), fontSize = 14.sp, color = Accent, fontWeight = FontWeight.Bold)
                        Cover(s, 120, Modifier.size(44.dp), corner = 6)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = dim)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            when {
                                p == null -> Text(if (finished) "not found" else "…", fontSize = 12.sp, color = dim)
                                !p.found -> Text("not found", fontSize = 12.sp, color = dim)
                                else -> {
                                    if (p.plays > 0) Text(Popularity.plays(p.plays), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    p.peaks().takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 11.sp, color = Accent) }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClose, Modifier.weight(1f)) { Text("Cancel", color = Accent) }
                Button({ onApply(ordered) }, Modifier.weight(1f), enabled = finished && !offline && notFound < songs.size,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Apply") }
            }
        }
    }
}
