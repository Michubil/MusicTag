package dev.androidgui.core.designsystem.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.androidgui.core.designsystem.tokens.AppSpacing

enum class AppTextInputKind { SingleLine, ShortMultiline, LongMultiline }

@Composable
fun AppEditableField(
    label: String, value: String, selected: Boolean, enabled: Boolean,
    hint: String?, kind: AppTextInputKind,
    onSelectedChange: (Boolean) -> Unit, onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, enabled = enabled,
        label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        leadingIcon = {
            Checkbox(selected, onSelectedChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = "修改$label" })
        },
        supportingText = hint?.let { { Text(it) } },
        singleLine = kind == AppTextInputKind.SingleLine,
        minLines = if (kind == AppTextInputKind.LongMultiline) 3 else 1,
        maxLines = if (kind == AppTextInputKind.SingleLine) 1 else 8,
        shape = MaterialTheme.shapes.medium,
    )
}

@Composable
fun AppTextInputDialog(
    visible: Boolean, title: String, value: String, label: String, description: String,
    error: String?, enabled: Boolean,
    onValueChange: (String) -> Unit, onConfirm: () -> Unit, onDismissRequest: () -> Unit,
) {
    AppModalSurface(visible, title, onDismissRequest) {
        Text(title, Modifier.padding(AppSpacing.ExtraLarge), style = MaterialTheme.typography.headlineSmall)
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.ExtraLarge),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        ) {
            Text(description, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value, onValueChange, modifier = Modifier.fillMaxWidth(), enabled = enabled && visible,
                label = { Text(label) }, minLines = 3, maxLines = 8, isError = error != null,
                supportingText = error?.let { { Text(it) } }, shape = MaterialTheme.shapes.medium,
            )
        }
        Row(Modifier.fillMaxWidth().padding(AppSpacing.Large), horizontalArrangement = Arrangement.End) {
            TextButton(onDismissRequest, enabled = visible) { Text("取消") }
            TextButton(onConfirm, enabled = visible && enabled && error == null) { Text("保存") }
        }
    }
}
