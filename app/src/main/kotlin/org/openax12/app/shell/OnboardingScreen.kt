package org.openax12.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.openax12.app.R
import org.openax12.app.onboardingOffersStockSwitch
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxCard
import org.openax12.core.designsystem.AxDivider
import org.openax12.core.designsystem.AxIcon
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxScreen
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.AxTheme

/**
 * The first-start onboarding on a single page: what open-ax12 is, that it cannot share the radio
 * with the stock app, the two switches and "Get started". Stateless (the switch values come in)
 * so that it is rendered in screenshot tests.
 *
 * @param stockInstalled the stock app exists; without it the stock text and switch are left out
 * @param connecting show the note that the connection runs in the background
 * @param statusBar the status bar of the shell, so the onboarding looks like every other screen
 */
@Composable
internal fun OnboardingContent(
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
                .padding(horizontal = 32.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(modifier = Modifier.widthIn(max = 720.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.onboarding_welcome_title),
                        style = AxTheme.typography.display,
                        color = colors.text,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.onboarding_welcome_text),
                        style = AxTheme.typography.title,
                        color = colors.text,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (onboardingOffersStockSwitch(stockInstalled)) {
                        Text(
                            text = stringResource(R.string.onboarding_exclusive_text),
                            style = AxTheme.typography.title,
                            color = colors.text,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .background(colors.surface, RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    AxCard(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                        Column {
                            if (onboardingOffersStockSwitch(stockInstalled)) {
                                ChoiceRow(
                                    title = stringResource(R.string.onboarding_close_stock),
                                    description = stringResource(R.string.onboarding_close_stock_desc),
                                    icon = AxIcons.Link,
                                    checked = closeStockApp,
                                    onCheckedChange = onCloseStockAppChange,
                                )
                                AxDivider()
                            }
                            ChoiceRow(
                                title = stringResource(R.string.onboarding_start_on_boot),
                                description = stringResource(R.string.onboarding_start_on_boot_desc),
                                icon = AxIcons.Play,
                                checked = startOnBoot,
                                onCheckedChange = onStartOnBootChange,
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.onboarding_changeable),
                        style = AxTheme.typography.body,
                        color = colors.textMuted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (connecting) {
                        Text(
                            text = stringResource(R.string.onboarding_connecting_note),
                            style = AxTheme.typography.label,
                            color = colors.textMuted,
                        )
                    }
                }
                AxButton(
                    text = stringResource(R.string.onboarding_get_started),
                    onClick = onFinish,
                    variant = AxButtonVariant.Primary,
                )
            }
        }
    }
}

/** A switch row with large text: icon, title, description and the switch at the end. */
@Composable
private fun ChoiceRow(
    title: String,
    description: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = AxTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AxIcon(icon = icon, contentDescription = null, tint = colors.textMuted, size = 28.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = AxTheme.typography.title, color = colors.text)
            Text(text = description, style = AxTheme.typography.body, color = colors.textMuted)
        }
        AxSwitch(checked, onCheckedChange)
    }
}
