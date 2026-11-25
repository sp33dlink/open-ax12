package org.openax12.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.openax12.core.data.ImportKind
import org.openax12.core.data.ImportValidation
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxDivider
import org.openax12.core.designsystem.AxEmptyState
import org.openax12.core.designsystem.AxErrorState
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxListGroup
import org.openax12.core.designsystem.AxLoadingState
import org.openax12.core.designsystem.AxSettingRow
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.AxValueBox
import org.openax12.core.designsystem.TextEntryDialog

/** Everything the import screen can do; defaults are no-ops for previews. */
internal class ImportActions(
    val onBack: () -> Unit = {},
    val onChoose: () -> Unit = {},
    val onName: (String) -> Unit = {},
    val onImport: () -> Unit = {},
)

/**
 * The import screen (SPEC section 4): shows what the chosen file contains, lets the user pick
 * the name (never overwriting an existing model) and imports. The model is added to the library
 * but not selected.
 */
@Composable
internal fun ModelImportScreen(state: ImportUiState, actions: ImportActions, modifier: Modifier = Modifier) {
    var nameDialog by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.models_import_title), onBack = actions.onBack) {
            if (state.phase is ImportPhase.Ready) {
                AxButton(
                    text = stringResource(R.string.models_import_action),
                    onClick = actions.onImport,
                    variant = AxButtonVariant.Primary,
                    icon = AxIcons.Import,
                    enabled = state.canImport,
                )
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            when (val phase = state.phase) {
                ImportPhase.Idle -> AxEmptyState(
                    title = stringResource(R.string.models_import_idle_title),
                    message = stringResource(R.string.models_import_idle_message),
                    icon = AxIcons.Import,
                    actionLabel = stringResource(R.string.models_import_choose),
                    onAction = actions.onChoose,
                    modifier = Modifier.fillMaxSize(),
                )
                ImportPhase.Inspecting -> AxLoadingState(
                    text = stringResource(R.string.models_import_inspecting),
                    modifier = Modifier.fillMaxSize(),
                )
                ImportPhase.Importing -> AxLoadingState(
                    text = stringResource(R.string.models_import_importing),
                    modifier = Modifier.fillMaxSize(),
                )
                is ImportPhase.Invalid -> AxErrorState(
                    title = stringResource(R.string.models_import_invalid_title),
                    message = stringResource(phase.problem.messageRes()),
                    actionLabel = stringResource(R.string.models_import_choose_another),
                    onAction = actions.onChoose,
                    modifier = Modifier.fillMaxSize(),
                )
                is ImportPhase.Ready -> ReadyContent(phase.info, state, actions, onEditName = { nameDialog = true })
            }
        }
    }
    if (nameDialog) {
        val problems = NameProblem.entries.associateWith { stringResource(it.messageRes()) }
        TextEntryDialog(
            title = stringResource(R.string.models_name_title),
            initialValue = state.name,
            maxLength = ModelNameRules.MAX_LENGTH,
            validate = { ModelNameRules.check(it, state.existingNames)?.let(problems::getValue) },
            onConfirm = {
                nameDialog = false
                actions.onName(it)
            },
            onDismiss = { nameDialog = false },
        )
    }
}

@Composable
private fun ReadyContent(
    info: ImportValidation.Valid,
    state: ImportUiState,
    actions: ImportActions,
    onEditName: () -> Unit,
) {
    Column(
        modifier = Modifier.width(600.dp).padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (info.nameExists) {
            InfoBanner(
                text = stringResource(R.string.models_import_name_exists, info.name),
                tone = BannerTone.WARNING,
            )
        }
        AxListGroup {
            AxSettingRow(title = stringResource(R.string.models_field_name)) {
                AxValueBox(
                    text = state.name.ifBlank { stringResource(R.string.models_name_placeholder) },
                    onClick = onEditName,
                    trailingIcon = AxIcons.Edit,
                    textAlign = TextAlign.Start,
                    highlighted = state.nameProblem != null,
                    modifier = Modifier.width(RowControlWidth),
                )
            }
            AxDivider()
            InfoRow(
                title = stringResource(R.string.models_import_format),
                value = stringResource(
                    if (info.kind == ImportKind.ARCHIVE) R.string.models_import_format_archive else R.string.models_import_format_stock,
                ),
            )
            AxDivider()
            InfoRow(
                title = stringResource(R.string.models_field_type),
                value = info.type?.let { stringResource(it.labelRes()) } ?: stringResource(R.string.models_import_type_unknown),
            )
            AxDivider()
            InfoRow(
                title = stringResource(R.string.models_import_contents),
                value = stringResource(
                    if (info.hasPicture) R.string.models_import_with_picture else R.string.models_import_without_picture,
                    formatSize(info.sizeBytes),
                ),
            )
        }
        if (info.kind == ImportKind.STOCK_MODEL) {
            Text(
                text = stringResource(R.string.models_import_stock_hint),
                style = AxTheme.typography.label,
                color = AxTheme.colors.textMuted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        Row {
            AxButton(
                text = stringResource(R.string.models_import_choose_another),
                onClick = actions.onChoose,
                variant = AxButtonVariant.Text,
            )
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    AxSettingRow(title = title) {
        Text(
            text = value,
            style = AxTheme.typography.body,
            color = AxTheme.colors.text,
            maxLines = 2,
            textAlign = TextAlign.End,
            modifier = Modifier.width(RowControlWidth + 80.dp),
        )
    }
}
