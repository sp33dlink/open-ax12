package org.openax12.feature.functions.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.AxValueBox
import org.openax12.core.designsystem.TextEntryDialog
import org.openax12.core.engine.RenderMode
import org.openax12.core.engine.TemplateError
import org.openax12.core.engine.TemplateErrorKind
import org.openax12.core.engine.TemplateRenderer
import org.openax12.core.engine.ValueResolver
import org.openax12.feature.functions.R

/** Longest text template accepted (the text-entry dialog cuts longer input). */
internal const val MAX_TEMPLATE_LENGTH: Int = 120

/** Placeholders offered as chips below the template field. */
internal val SUGGESTED_PLACEHOLDERS: List<String> =
    listOf("RxBt", "TxBt", "Alt", "VSpd", "LQ", "ASpd", "Capa", "Bat%", "T1", "FM", "model")

/** Appends `{name}` to [text], separated by a space when [text] does not end in one. */
internal fun appendPlaceholder(text: String, name: String): String {
    val separator = if (text.isEmpty() || text.endsWith(' ')) "" else " "
    return (text + separator + "{" + name + "}").take(MAX_TEMPLATE_LENGTH)
}

/** The first syntax error of [text], or null. Unknown names are not errors here (offline editing). */
internal fun firstTemplateError(text: String): TemplateError? = TemplateRenderer.validate(text).firstOrNull()

/** Names in [text] that [resolver] does not know (shown as a hint, never blocking). */
internal fun unknownPlaceholders(text: String, resolver: ValueResolver?): List<String> {
    if (resolver == null) return emptyList()
    return TemplateRenderer.validate(text, resolver)
        .filter { it.kind == TemplateErrorKind.UNKNOWN_NAME }
        .map { it.token }
}

/** Message for a syntax error of a template. */
@Composable
internal fun rememberTemplateErrorText(): (TemplateError) -> String {
    val unclosed = stringResource(R.string.fn_tpl_unclosed)
    val unexpected = stringResource(R.string.fn_tpl_unexpected)
    val empty = stringResource(R.string.fn_tpl_empty)
    val precision = stringResource(R.string.fn_tpl_precision)
    val unknown = stringResource(R.string.fn_tpl_unknown)
    return remember(unclosed, unexpected, empty, precision, unknown) {
        { error: TemplateError ->
            val base = when (error.kind) {
                TemplateErrorKind.UNCLOSED_BRACE -> unclosed
                TemplateErrorKind.UNEXPECTED_CLOSING_BRACE -> unexpected
                TemplateErrorKind.EMPTY_NAME -> empty
                TemplateErrorKind.INVALID_PRECISION -> precision
                TemplateErrorKind.UNKNOWN_NAME -> unknown
            }
            "$base ${error.token}"
        }
    }
}

/**
 * Editor of a text template (`Battery {RxBt} volts`): the text in a box that opens a text dialog,
 * placeholder chips and the rendered result as it will be spoken.
 *
 * @param preview the template rendered with the current values (`RenderMode.SPEECH`), or null
 * @param unknown placeholder names the current resolver cannot resolve (hint only)
 */
@Composable
internal fun TemplateEditor(
    text: String,
    onTextChange: (String) -> Unit,
    preview: String?,
    unknown: List<String>,
    modifier: Modifier = Modifier,
) {
    var dialog by rememberSaveable { mutableStateOf(false) }
    val errorText = rememberTemplateErrorText()
    val error = firstTemplateError(text)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AxValueBox(
            text = text.ifEmpty { stringResource(R.string.fn_text_placeholder) },
            onClick = { dialog = true },
            trailingIcon = AxIcons.Edit,
            highlighted = error != null,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        ChipRow(items = SUGGESTED_PLACEHOLDERS.map { "{$it}" }, onClick = { chip -> onTextChange(appendPlaceholder(text, chip.trim('{', '}'))) })
        if (error != null) {
            Caption(text = errorText(error), modifier = Modifier.fillMaxWidth())
        } else if (preview != null && text.isNotBlank()) {
            Text(
                text = stringResource(R.string.fn_spoken_as, preview),
                style = AxTheme.typography.body,
                color = AxTheme.colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (unknown.isNotEmpty()) {
            Caption(text = stringResource(R.string.fn_tpl_unknown_names, unknown.joinToString()), modifier = Modifier.fillMaxWidth())
        }
    }
    if (dialog) {
        TextEntryDialog(
            title = stringResource(R.string.fn_text_title),
            initialValue = text,
            onConfirm = {
                dialog = false
                onTextChange(it)
            },
            onDismiss = { dialog = false },
            maxLength = MAX_TEMPLATE_LENGTH,
            placeholder = stringResource(R.string.fn_text_placeholder),
            allowEmpty = true,
            validate = { candidate -> firstTemplateError(candidate)?.let(errorText) },
        )
    }
}

/** Renders [text] for the live preview: what the template would be spoken as right now. */
internal fun renderPreview(text: String, resolver: ValueResolver?): String? =
    if (resolver == null || text.isBlank()) null else TemplateRenderer.render(text, resolver, RenderMode.SPEECH)
