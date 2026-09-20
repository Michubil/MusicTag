package dev.androidgui.core.designsystem.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import dev.androidgui.core.designsystem.tokens.AppDimensions
import dev.androidgui.core.designsystem.tokens.AppSpacing

@Composable
fun <T> AppChoiceGrid(
    options: List<AppChoiceOption<T>>,
    selectedValue: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        options.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                pair.forEach { option ->
                    Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(
                            Modifier.heightIn(min = AppDimensions.MinimumTouchTarget)
                                .selectable(selectedValue == option.value, enabled, Role.RadioButton) { onSelect(option.value) }
                                .padding(AppSpacing.Small),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
                        ) {
                            RadioButton(selectedValue == option.value, onClick = null, enabled = enabled)
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun AppTextField(value: String, label: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) },
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    )
}
