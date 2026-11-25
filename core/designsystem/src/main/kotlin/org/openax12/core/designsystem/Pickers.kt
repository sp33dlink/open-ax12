package org.openax12.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Single-line text field in a sunken well.
 *
 * @param placeholder hint shown while empty
 * @param leadingIcon optional icon at the start
 * @param showClear shows a clear button while there is text
 * @param focusRequester lets the caller move the focus into the field
 * @param onImeAction called for the keyboard's action key (Done / Search)
 */
@Composable
fun AxTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingIcon: ImageVector? = null,
    showClear: Boolean = true,
    enabled: Boolean = true,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    focusRequester: FocusRequester? = null,
    onImeAction: (() -> Unit)? = null,
) {
    val colors = AxTheme.colors
    val radius = AxTheme.shapes.control
    val border = AxTheme.shapes.border + 0.5.dp
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .height(AxDimens.ControlHeight)
            .alpha(if (enabled) 1f else 0.45f)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        enabled = enabled,
        singleLine = true,
        textStyle = AxTheme.typography.body.copy(color = colors.text),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = keyboardType,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onDone = { onImeAction?.invoke() },
            onSearch = { onImeAction?.invoke() },
            onGo = { onImeAction?.invoke() },
        ),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .axSunken(cornerRadius = radius, depth = 2.dp, color = colors.surfaceSunken)
                    .drawBehind {
                        if (isError) {
                            val b = border.toPx()
                            drawRoundRect(
                                color = colors.error,
                                topLeft = Offset(b / 2f, b / 2f),
                                size = Size(size.width - b, size.height - b),
                                cornerRadius = CornerRadius(radius.toPx()),
                                style = Stroke(b),
                            )
                        }
                    }
                    .padding(start = 10.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    AxIcon(icon = leadingIcon, contentDescription = null, tint = colors.textMuted, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(text = placeholder, style = AxTheme.typography.body, color = colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
                if (showClear && value.isNotEmpty() && enabled) {
                    AxIconButton(
                        icon = AxIcons.Close,
                        contentDescription = stringResource(R.string.ax_clear),
                        onClick = { onValueChange("") },
                        tint = colors.textMuted,
                        size = 36.dp,
                        iconSize = 18.dp,
                    )
                } else {
                    Spacer(Modifier.width(8.dp))
                }
            }
        },
    )
}

/**
 * Dialog for entering a short text (model name, sensor name, voice text). It sits at the top of
 * the canvas so the soft keyboard does not cover it, and focuses the field when it opens.
 *
 * @param initialValue text shown when the dialog opens
 * @param onConfirm called with the trimmed text
 * @param maxLength longer input is cut
 * @param validate returns an error message for an unacceptable text, or null if it is fine;
 *   Confirm is disabled while there is an error
 * @param allowEmpty false disables Confirm for blank input
 */
@Composable
fun TextEntryDialog(
    title: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    maxLength: Int = 32,
    confirmLabel: String = stringResource(R.string.ax_ok),
    allowEmpty: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    validate: ((String) -> String?)? = null,
) {
    var text by rememberSaveable { mutableStateOf(initialValue.take(maxLength)) }
    val error = validate?.invoke(text.trim())
    val valid = error == null && (allowEmpty || text.isNotBlank())
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AxDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = title,
        alignment = Alignment.TopCenter,
        dismissOnOutsideClick = false,
        buttons = {
            AxButton(text = stringResource(R.string.ax_cancel), onClick = onDismiss, variant = AxButtonVariant.Text)
            AxButton(text = confirmLabel, onClick = { onConfirm(text.trim()) }, variant = AxButtonVariant.Primary, enabled = valid)
        },
    ) {
        AxTextField(
            value = text,
            onValueChange = { text = it.take(maxLength) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder,
            isError = error != null,
            keyboardType = keyboardType,
            focusRequester = focus,
            onImeAction = { if (valid) onConfirm(text.trim()) },
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 2.dp, end = 2.dp)) {
            Text(
                text = error ?: "",
                style = AxTheme.typography.label,
                color = AxTheme.colors.error,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.ax_length_of, text.length, maxLength),
                style = AxTheme.typography.labelSmall,
                color = AxTheme.colors.textMuted,
            )
        }
    }
}

/**
 * One choice of a [PickerDialog].
 *
 * @property value the value reported when the option is picked
 * @property label text of the option
 * @property group optional group name; groups appear as filter chips above the options
 * @property description optional second line (list layout) / small caption (grid layout)
 * @property icon optional icon
 * @property enabled false greys the option out
 */
@Immutable
data class PickerOption<T>(
    val value: T,
    val label: String,
    val group: String? = null,
    val description: String? = null,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
)

/**
 * Generic picker: a searchable grid (or list) of options, optionally filtered by group chips.
 * Used for sources, curves, sensors, switches, sounds — anything chosen from a set.
 *
 * Picking an option calls [onSelect]; the caller closes the dialog by no longer calling it.
 *
 * @param options all options
 * @param selected the currently selected value (highlighted), or null
 * @param columns number of grid columns; 1 gives a list with descriptions
 * @param searchable shows the search field; by default only for long lists. With a search
 *   field the dialog sits at the top of the canvas (soft keyboard)
 * @param width dialog width
 */
@Composable
fun <T> PickerDialog(
    title: String,
    options: List<PickerOption<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 4,
    searchable: Boolean = options.size > 24,
    width: Dp = if (columns > 1) 600.dp else 400.dp,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    val groups = remember(options) { options.mapNotNull { it.group }.distinct() }
    val filtered = remember(options, query, group) {
        val q = query.trim()
        options.filter { option ->
            (group == null || option.group == group) &&
                (q.isEmpty() || option.label.contains(q, ignoreCase = true) || option.description?.contains(q, ignoreCase = true) == true)
        }
    }
    val gridState = rememberLazyGridState()
    LaunchedEffect(Unit) {
        val index = filtered.indexOfFirst { it.value == selected }
        if (index > 0) gridState.scrollToItem(index)
    }
    AxDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = title,
        width = width,
        alignment = if (searchable) Alignment.TopCenter else Alignment.Center,
        contentPadding = PaddingValues(horizontal = 14.dp),
        buttons = {
            AxButton(text = stringResource(R.string.ax_cancel), onClick = onDismiss, variant = AxButtonVariant.Text)
        },
    ) {
        if (searchable) {
            AxTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                placeholder = stringResource(R.string.ax_search),
                leadingIcon = AxIcons.Search,
                imeAction = ImeAction.Search,
            )
        }
        if (groups.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AxChip(text = stringResource(R.string.ax_all), selected = group == null, onClick = { group = null })
                groups.forEach { g ->
                    AxChip(text = g, selected = group == g, onClick = { group = g })
                }
            }
        }
        if (filtered.isEmpty()) {
            AxEmptyState(
                title = stringResource(R.string.ax_no_matches),
                icon = AxIcons.SearchOff,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns.coerceAtLeast(1)),
                state = gridState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp),
                contentPadding = PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered) { option ->
                    PickerCell(
                        option = option,
                        selected = option.value == selected,
                        list = columns <= 1,
                        onClick = { onSelect(option.value) },
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> PickerCell(option: PickerOption<T>, selected: Boolean, list: Boolean, onClick: () -> Unit) {
    val colors = AxTheme.colors
    val radius = AxTheme.shapes.control
    val border = AxTheme.shapes.border + 1.dp
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (list && option.description != null) 52.dp else 44.dp)
            .alpha(if (option.enabled) 1f else 0.4f)
            .axRaised(cornerRadius = radius, elevation = 2.5.dp, color = colors.surfaceRaised, pressed = pressed && option.enabled)
            .drawBehind {
                if (selected) {
                    val b = border.toPx()
                    drawRoundRect(
                        color = colors.accent,
                        topLeft = Offset(b / 2f, b / 2f),
                        size = Size(size.width - b, size.height - b),
                        cornerRadius = CornerRadius(radius.toPx()),
                        style = Stroke(b),
                    )
                }
            }
            .clip(RoundedCornerShape(radius))
            .clickable(interactionSource = interaction, indication = null, enabled = option.enabled, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (list) Arrangement.Start else Arrangement.Center,
    ) {
        if (option.icon != null) {
            AxIcon(icon = option.icon, contentDescription = null, tint = if (selected) colors.accent else colors.textMuted, size = 18.dp)
            Spacer(Modifier.width(6.dp))
        }
        Column(horizontalAlignment = if (list) Alignment.Start else Alignment.CenterHorizontally) {
            Text(
                text = option.label,
                style = AxTheme.typography.bodyStrong,
                color = if (selected) colors.accent else colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (list) TextAlign.Start else TextAlign.Center,
            )
            if (option.description != null) {
                Text(
                    text = option.description,
                    style = AxTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Filter chip: pill that is filled with the accent colour while selected. */
@Composable
fun AxChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val colors = AxTheme.colors
    val border = AxTheme.shapes.border
    Row(
        modifier = modifier
            .height(34.dp)
            .drawBehind {
                val r = CornerRadius(size.height / 2f)
                if (selected) {
                    drawRoundRect(colors.accent, cornerRadius = r)
                } else {
                    val b = border.toPx()
                    drawRoundRect(colors.surfaceSunken, cornerRadius = r)
                    drawRoundRect(colors.outline, topLeft = Offset(b / 2f, b / 2f), size = Size(size.width - b, size.height - b), cornerRadius = r, style = Stroke(b))
                }
            }
            .clip(RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            AxIcon(icon = icon, contentDescription = null, tint = if (selected) colors.onAccent else colors.textMuted, size = 16.dp)
            Spacer(Modifier.width(5.dp))
        }
        Text(text = text, style = AxTheme.typography.label, color = if (selected) colors.onAccent else colors.text, maxLines = 1)
    }
}

/**
 * Dropdown-style field: shows the selected option with a chevron and opens a [PickerDialog]
 * (a popup menu would be too small for fingers on this screen).
 *
 * @param options option labels
 * @param selectedIndex index of the selected option; out of range shows [placeholder]
 * @param onSelected called with the index of the picked option
 * @param title title of the picker; defaults to a generic one
 * @param columns grid columns of the picker (1 = list)
 */
@Composable
fun AxDropdown(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    placeholder: String = "—",
    enabled: Boolean = true,
    highlighted: Boolean = false,
    columns: Int = if (options.size > 6) 3 else 1,
    height: Dp = AxDimens.ControlHeight,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    AxValueBox(
        text = options.getOrNull(selectedIndex) ?: placeholder,
        onClick = { open = true },
        enabled = enabled,
        trailingIcon = AxIcons.ExpandMore,
        highlighted = highlighted,
        height = height,
        modifier = modifier,
    )
    if (open) {
        val pickerOptions = remember(options) { options.mapIndexed { index, label -> PickerOption(value = index, label = label) } }
        PickerDialog(
            title = title ?: stringResource(R.string.ax_choose),
            options = pickerOptions,
            selected = selectedIndex.takeIf { it in options.indices },
            onSelect = {
                open = false
                if (it != selectedIndex) onSelected(it)
            },
            onDismiss = { open = false },
            columns = columns,
            searchable = false,
            width = if (columns > 1) 520.dp else 340.dp,
        )
    }
}
