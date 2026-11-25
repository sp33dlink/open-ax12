package org.openax12.feature.functions.common

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openax12.core.designsystem.AxSegmented
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.NumberField
import org.openax12.core.model.Repeat
import org.openax12.feature.functions.R

/** Interval offered when the user switches from "once" to "every N seconds". */
internal const val REPEAT_DEFAULT_SEC: Int = 30

/** Card with the repeat settings of a momentary action or a voice alert: once / every N seconds, skip at startup. */
@Composable
internal fun RepeatEditor(repeat: Repeat, onChange: (Repeat) -> Unit, modifier: Modifier = Modifier) {
    FormCard(modifier = modifier, title = stringResource(R.string.fn_repeat_title)) {
        AxSegmented(
            options = listOf(stringResource(R.string.fn_repeat_once), stringResource(R.string.fn_repeat_every)),
            selectedIndex = if (repeat.isOnce) 0 else 1,
            onSelected = { onChange(repeat.copy(intervalSec = if (it == 0) 0 else REPEAT_DEFAULT_SEC)) },
            modifier = Modifier.width(260.dp),
        )
        if (!repeat.isOnce) {
            FormRow(label = stringResource(R.string.fn_repeat_interval)) {
                NumberField(
                    value = repeat.intervalSec,
                    onValueChange = { onChange(repeat.copy(intervalSec = it)) },
                    min = 1,
                    max = 3600,
                    unit = " s",
                    title = stringResource(R.string.fn_repeat_interval),
                    modifier = Modifier.width(NumberWidth),
                )
            }
        }
        FormRow(label = stringResource(R.string.fn_repeat_skip), description = stringResource(R.string.fn_repeat_skip_help)) {
            AxSwitch(checked = repeat.skipAtStartup, onCheckedChange = { onChange(repeat.copy(skipAtStartup = it)) })
        }
    }
}
