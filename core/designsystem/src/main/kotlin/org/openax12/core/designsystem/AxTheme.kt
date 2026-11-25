package org.openax12.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openax12.core.model.AppTheme

/** The three colour schemes of the app. Mirrors [AppTheme] of `:core:model`. */
enum class AxThemeMode {
    /** Stock-like icy light scheme with neumorphic surfaces (default). */
    LIGHT,

    /** Dark scheme with flat surfaces. */
    DARK,

    /** Black on white with saturated colours for direct sunlight. */
    HIGH_CONTRAST,
}

/** Maps the persisted [AppTheme] setting to the design-system scheme. */
fun AppTheme.toAxThemeMode(): AxThemeMode = when (this) {
    AppTheme.LIGHT -> AxThemeMode.LIGHT
    AppTheme.DARK -> AxThemeMode.DARK
    AppTheme.HIGH_CONTRAST -> AxThemeMode.HIGH_CONTRAST
}

/** The [AxColors] of a scheme. */
fun AxThemeMode.colors(): AxColors = when (this) {
    AxThemeMode.LIGHT -> LightAxColors
    AxThemeMode.DARK -> DarkAxColors
    AxThemeMode.HIGH_CONTRAST -> HighContrastAxColors
}

/**
 * Text styles sized for the 853x480 dp canvas (1 dp = 1.5 px on the AX12, a 5.5" panel held at
 * arm's length). Numbers use tabular figures so live values do not jitter.
 *
 * @property display large numbers (number pad display, big telemetry values)
 * @property title screen and dialog titles, model name
 * @property titleSmall card titles, status-bar title
 * @property body default text
 * @property bodyStrong emphasised default text (list titles, active channels)
 * @property label secondary text, column headers on light surfaces
 * @property labelSmall captions, units, gauge labels
 * @property value live numeric values
 * @property valueLarge timers and prominent values
 * @property button button labels
 * @property chrome tab and column-header labels in the navy chrome
 */
@Immutable
class AxTypography(
    val display: TextStyle,
    val title: TextStyle,
    val titleSmall: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val label: TextStyle,
    val labelSmall: TextStyle,
    val value: TextStyle,
    val valueLarge: TextStyle,
    val button: TextStyle,
    val chrome: TextStyle,
)

private val CenteredLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun axStyle(size: Int, line: Int, weight: FontWeight, tabular: Boolean = false, spacing: Float = 0f) = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = spacing.sp,
    fontFeatureSettings = if (tabular) "tnum" else null,
    lineHeightStyle = CenteredLineHeight,
)

/** Default typography; [strong] raises the weights for the high-contrast scheme. */
fun axTypography(strong: Boolean = false): AxTypography {
    val regular = if (strong) FontWeight.Medium else FontWeight.Normal
    val medium = if (strong) FontWeight.Bold else FontWeight.Medium
    val semi = if (strong) FontWeight.Bold else FontWeight.SemiBold
    return AxTypography(
        display = axStyle(30, 36, medium, tabular = true),
        title = axStyle(18, 24, medium),
        titleSmall = axStyle(15, 20, semi, spacing = 0.3f),
        body = axStyle(14, 19, regular),
        bodyStrong = axStyle(14, 19, semi),
        label = axStyle(12, 16, medium),
        labelSmall = axStyle(10, 13, medium),
        value = axStyle(13, 17, medium, tabular = true),
        valueLarge = axStyle(20, 26, regular, tabular = true),
        button = axStyle(14, 18, semi),
        chrome = axStyle(15, 20, semi),
    )
}

/**
 * Corner radii and stroke widths.
 *
 * @property card large raised cards
 * @property panel medium containers (dialogs, wells, list groups)
 * @property control buttons, value boxes, keys
 * @property small chips, bars
 * @property border width of flat-surface outlines (thicker in the high-contrast scheme)
 */
@Immutable
class AxShapes(
    val card: Dp = 22.dp,
    val panel: Dp = 16.dp,
    val control: Dp = 10.dp,
    val small: Dp = 6.dp,
    val border: Dp = 1.dp,
)

/** Spacing scale in dp for the 853x480 canvas. */
@Immutable
class AxSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 24.dp,
)

/** Fixed sizes shared by the chrome and by screens laying out against it. */
object AxDimens {
    /** Height of [AxStatusBar]. */
    val StatusBarHeight: Dp = 40.dp

    /** Height of [AxBottomBar]. */
    val BottomBarHeight: Dp = 52.dp

    /** Height of the navy tab bar of [SetupScaffold]. */
    val SetupBarHeight: Dp = 44.dp

    /** Height of the column-header row of [SetupScaffold]. */
    val SetupHeaderHeight: Dp = 34.dp

    /** Minimum touch target (CLAUDE.md). */
    val MinTouch: Dp = 44.dp

    /** Default height of buttons, value boxes and list controls. */
    val ControlHeight: Dp = 40.dp
}

internal val LocalAxTypography = staticCompositionLocalOf { axTypography() }
internal val LocalAxShapes = staticCompositionLocalOf { AxShapes() }
internal val LocalAxSpacing = staticCompositionLocalOf { AxSpacing() }
internal val LocalAxThemeMode = staticCompositionLocalOf { AxThemeMode.LIGHT }

/** Accessors for the current theme, in the style of `MaterialTheme`. */
object AxTheme {
    /** Colour tokens of the active scheme. */
    val colors: AxColors
        @Composable @ReadOnlyComposable get() = LocalAxColors.current

    /** Text styles. */
    val typography: AxTypography
        @Composable @ReadOnlyComposable get() = LocalAxTypography.current

    /** Corner radii and border width. */
    val shapes: AxShapes
        @Composable @ReadOnlyComposable get() = LocalAxShapes.current

    /** Spacing scale. */
    val spacing: AxSpacing
        @Composable @ReadOnlyComposable get() = LocalAxSpacing.current

    /** The active scheme. */
    val mode: AxThemeMode
        @Composable @ReadOnlyComposable get() = LocalAxThemeMode.current
}

/**
 * Theme of the app. Provides [AxTheme] tokens and a matching Material 3 theme so that plain
 * Material components pick up sensible colours as well.
 *
 * Wrap the root in `AxTheme { FixedCanvas { … } }`.
 */
@Composable
fun AxTheme(mode: AxThemeMode = AxThemeMode.LIGHT, content: @Composable () -> Unit) {
    val colors = mode.colors()
    val typography = axTypography(strong = mode == AxThemeMode.HIGH_CONTRAST)
    val shapes = AxShapes(border = if (mode == AxThemeMode.HIGH_CONTRAST) 2.dp else 1.dp)
    CompositionLocalProvider(
        LocalAxColors provides colors,
        LocalAxTypography provides typography,
        LocalAxShapes provides shapes,
        LocalAxSpacing provides AxSpacing(),
        LocalAxThemeMode provides mode,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = typography.toMaterial(),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(shapes.small),
                small = RoundedCornerShape(shapes.control),
                medium = RoundedCornerShape(shapes.panel),
                large = RoundedCornerShape(shapes.card),
                extraLarge = RoundedCornerShape(shapes.card),
            ),
        ) {
            CompositionLocalProvider(
                LocalContentColor provides colors.text,
                LocalTextSelectionColors provides TextSelectionColors(
                    handleColor = colors.accent,
                    backgroundColor = colors.accent.copy(alpha = 0.3f),
                ),
                content = content,
            )
        }
    }
}

/** [AxTheme] for the persisted [AppTheme] setting. */
@Composable
fun AxTheme(theme: AppTheme, content: @Composable () -> Unit) {
    AxTheme(mode = theme.toAxThemeMode(), content = content)
}

/** Alias of [AxTheme] under the name used in CLAUDE.md. */
@Composable
fun Ax12Theme(mode: AxThemeMode = AxThemeMode.LIGHT, content: @Composable () -> Unit) {
    AxTheme(mode = mode, content = content)
}

private fun AxColors.toMaterial(): ColorScheme {
    val base = if (isLight) lightColorScheme() else darkColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = surfaceRaised,
        onPrimaryContainer = text,
        secondary = teal,
        onSecondary = onAccent,
        tertiary = orange,
        onTertiary = text,
        background = background,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surfaceSunken,
        onSurfaceVariant = textMuted,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceRaised,
        surfaceContainerHighest = surfaceBright,
        surfaceContainerLow = surfaceSunken,
        surfaceContainerLowest = surfaceSunken,
        error = error,
        onError = onAccent,
        outline = outline,
        outlineVariant = outline,
        scrim = scrim,
    )
}

private fun AxTypography.toMaterial(): Typography = Typography(
    displaySmall = display,
    headlineSmall = title,
    titleLarge = title,
    titleMedium = titleSmall,
    titleSmall = titleSmall,
    bodyLarge = body,
    bodyMedium = body,
    bodySmall = label,
    labelLarge = button,
    labelMedium = label,
    labelSmall = labelSmall,
)
