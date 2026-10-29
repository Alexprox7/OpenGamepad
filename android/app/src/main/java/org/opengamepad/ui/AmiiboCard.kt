package org.opengamepad.ui

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
import androidx.compose.ui.graphics.Color
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
        "Amiibo per Switch",
        "Scegli un amiibo dalla ruota (tasto NFC sul controller o una combinazione) · serve emuiibo sulla Switch",
        Modifier.fillMaxWidth(),
    ) {
        Text(
            "OpenGamepad non include file amiibo: importa i dump .bin delle tue statuette. " +
                "La ruota mostra anche gli amiibo virtuali già presenti in emuiibo sulla Switch.",
            color = Faint,
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onImportFiles) { Text("Importa file") }
            OutlinedButton(onClick = onImportFolder) { Text("Importa cartella") }
            Text(
                when (entries.size) {
                    0 -> "Nessun amiibo sul telefono"
                    1 -> "1 amiibo sul telefono"
                    else -> "${entries.size} amiibo sul telefono"
                },
                color = Muted,
                fontSize = 13.sp,
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Nascondi" else "Gestisci") }
            }
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                entries.groupBy { it.group }.forEach { (group, list) ->
                    if (group.isNotEmpty()) Text("📁 $group", color = Muted, fontSize = 13.sp)
                    list.forEach { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.name, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(entry.identity.idHex, color = Faint, fontSize = 11.sp)
                            TextButton(onClick = { onDelete(entry) }) { Text("Elimina") }
                        }
                    }
                }
            }
        }
    }
}
