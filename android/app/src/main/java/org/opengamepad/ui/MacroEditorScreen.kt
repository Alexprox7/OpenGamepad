package org.opengamepad.ui

import org.opengamepad.str
import org.opengamepad.R
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
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
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxSize()
            .background(p.background)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenHeader(str(R.string.advanced_title), onBack = onClose) {
            Button(onClick = { message = onSave(text) ?: str(R.string.saved) }) { Text(str(R.string.save)) }
            OutlinedButton(onClick = { text = MacroConfig.EXAMPLE }) { Text(str(R.string.restore_example)) }
            message?.let { Text(it, color = if (it == str(R.string.saved)) Success else Danger) }
        }
        Text(
            str(R.string.json_help),
            color = p.muted,
            fontSize = 12.sp,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = p.text),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = p.surface,
                unfocusedContainerColor = p.surface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}
