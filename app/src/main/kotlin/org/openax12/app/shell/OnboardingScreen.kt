package org.openax12.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.openax12.app.R
import org.openax12.app.onboardingOffersStockSwitch
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxDivider
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxScreen
import org.openax12.core.designsystem.AxSettingRow
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.AxTheme

/** Number of onboarding pages. */
internal const val ONBOARDING_PAGES: Int = 2

/**
 * The first-start onboarding: page 0 says what open-ax12 is and that it cannot share the radio
 * with the stock app, page 1 offers the two switches and "Get started". Stateless (the page and
 * the switch values come in) so that it is rendered in screenshot tests.
 *
 * @param page current page, 0 until [ONBOARDING_PAGES] - 1
 * @param stockInstalled the stock app exists; without it the stock text and switch are left out
 * @param connecting show the note that the connection runs in the background
 * @param statusBar the status bar of the shell, so the onboarding looks like every other screen
 */
@Composable
internal fun OnboardingContent(
    page: Int,
    onPageChange: (Int) -> Unit,
    stockInstalled: Boolean,
    closeStockApp: Boolean,
    onCloseStockAppChange: (Boolean) -> Unit,
    startOnBoot: Boolean,
    onStartOnBootChange: (Boolean) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    connecting: Boolean = true,
    statusBar: @Composable () -> Unit = {},
) {
    val colors = AxTheme.colors
    AxScreen(modifier = modifier.fillMaxSize().background(colors.background), statusBar = statusBar) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(modifier = Modifier.widthIn(max = 620.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (page == 0) {
                        WelcomePage(stockInstalled)
                    } else {
                        ChoicesPage(stockInstalled, closeStockApp, onCloseStockAppChange, startOnBoot, onStartOnBootChange)
                    }
                }
            }
            if (connecting) {
                Text(
                    text = stringResource(R.string.onboarding_connecting_note),
                    style = AxTheme.typography.labelSmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (page > 0) {
                        AxButton(
                            text = stringResource(R.string.onboarding_back),
                            onClick = { onPageChange(page - 1) },
                            variant = AxButtonVariant.Outlined,
                        )
                    }
                }
                PageDots(page)
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (page < ONBOARDING_PAGES - 1) {
                        AxButton(
                            text = stringResource(R.string.onboarding_next),
                            onClick = { onPageChange(page + 1) },
                            variant = AxButtonVariant.Primary,
                        )
                    } else {
                        AxButton(
                            text = stringResource(R.string.onboarding_get_started),
                            onClick = onFinish,
                            variant = AxButtonVariant.Primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomePage(stockInstalled: Boolean) {
    val colors = AxTheme.colors
    Text(
        text = stringResource(R.string.onboarding_welcome_title),
        style = AxTheme.typography.title,
        color = colors.text,
        textAlign = TextAlign.Center,
    )
    Text(
        text = stringResource(R.string.onboarding_welcome_text),
        style = AxTheme.typography.body,
        color = colors.text,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 12.dp),
    )
    if (onboardingOffersStockSwitch(stockInstalled)) {
        Text(
            text = stringResource(R.string.onboarding_exclusive_text),
            style = AxTheme.typography.body,
            color = colors.text,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 16.dp)
                .background(colors.surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ChoicesPage(
    stockInstalled: Boolean,
    closeStockApp: Boolean,
    onCloseStockAppChange: (Boolean) -> Unit,
    startOnBoot: Boolean,
    onStartOnBootChange: (Boolean) -> Unit,
) {
    val colors = AxTheme.colors
    Text(
        text = stringResource(R.string.onboarding_choices_title),
        style = AxTheme.typography.title,
        color = colors.text,
    )
    AxCard(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column {
            if (onboardingOffersStockSwitch(stockInstalled)) {
                AxSettingRow(
                    title = stringResource(R.string.onboarding_close_stock),
                    description = stringResource(R.string.onboarding_close_stock_desc),
                    icon = AxIcons.Link,
                ) {
                    AxSwitch(closeStockApp, onCloseStockAppChange)
                }
                AxDivider()
            }
            AxSettingRow(
                title = stringResource(R.string.onboarding_start_on_boot),
                description = stringResource(R.string.onboarding_start_on_boot_desc),
                icon = AxIcons.Play,
            ) {
                AxSwitch(startOnBoot, onStartOnBootChange)
            }
        }
    }
    Text(
        text = stringResource(R.string.onboarding_changeable),
        style = AxTheme.typography.label,
        color = colors.textMuted,
        modifier = Modifier.padding(top = 10.dp),
    )
}

/** One dot per page, the current one in the accent colour. */
@Composable
private fun PageDots(page: Int) {
    val colors = AxTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(ONBOARDING_PAGES) { index ->
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(if (index == page) colors.accent else colors.textFaint, CircleShape),
            )
        }
    }
}
