package org.opengamepad.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opengamepad.macro.MacroConfig

/**
 * Text editor for the macro configuration (JSON). [onSave] returns an error message,
 * or null when the configuration was accepted.
 */
@Composable
fun MacroEditorScreen(
    initial: String,
    onSave: (String) -> String?,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var text by remember { mutableStateOf(initial) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Macro e ruote", color = Color.White, fontSize = 20.sp)
            Button(onClick = { message = onSave(text) ?: "Salvato" }) { Text("Salva") }
            OutlinedButton(onClick = { text = MacroConfig.EXAMPLE }) { Text("Ripristina esempio") }
            OutlinedButton(onClick = onClose) { Text("Chiudi") }
            message?.let { Text(it, color = if (it == "Salvato") Color(0xFF6FCF97) else Color(0xFFEB5757)) }
        }
        Text(
            "Pulsanti: A B X Y LB RB LT RT BACK START GUIDE L3 R3 UP DOWN LEFT RIGHT · " +
                "Tasti PC: A-Z 0-9 F1-F24 ENTER ESC TAB SPACE CTRL SHIFT ALT WIN ARROWUP… · " +
                "Azioni: press, keys, stick, wait · I tasti PC richiedono il companion OpenGamepad o il Bluetooth.",
            color = Color(0xFF8A91A0),
            fontSize = 12.sp,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFFDCE0E8)),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.Black,
                unfocusedContainerColor = Color.Black,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}
