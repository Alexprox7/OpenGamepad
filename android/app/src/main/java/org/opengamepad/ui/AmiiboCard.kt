package org.opengamepad.ui

import org.opengamepad.str
import org.opengamepad.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opengamepad.amiibo.AmiiboLibrary

/** Amiibo dumps stored in the app, for the Switch sysmodule (through emuiibo). */
@Composable
fun AmiiboCard(
    library: AmiiboLibrary,
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
    onDelete: (AmiiboLibrary.Entry) -> Unit,
) {
    val entries by library.entries.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    Card(
        "🎴",
        str(R.string.amiibo_title),
        str(R.string.amiibo_subtitle),
        Modifier.fillMaxWidth(),
    ) {
        Text(
            str(R.string.amiibo_note),
            color = Faint,
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onImportFiles) { Text(str(R.string.amiibo_import_files)) }
            OutlinedButton(onClick = onImportFolder) { Text(str(R.string.amiibo_import_folder)) }
            Text(
                when (entries.size) {
                    0 -> str(R.string.amiibo_none)
                    else -> str(R.string.amiibo_count, entries.size)
                },
                color = Muted,
                fontSize = 13.sp,
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) str(R.string.hide) else str(R.string.manage)) }
            }
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                entries.groupBy { it.group }.forEach { (group, list) ->
                    if (group.isNotEmpty()) Text("📁 $group", color = Muted, fontSize = 13.sp)
                    list.forEach { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.name, color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(entry.identity.idHex, color = Faint, fontSize = 11.sp)
                            TextButton(onClick = { onDelete(entry) }) { Text(str(R.string.delete)) }
                        }
                    }
                }
            }
        }
    }
}
