package org.openax12.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens of the open-ax12 look. They go beyond Material's roles because the stock
 * RadioMaster look needs raised / sunken surfaces with a light and a dark shadow, the dark navy
 * model-setup chrome and fixed meaning colours (teal / orange switch states, green channel bars).
 *
 * Read them with `AxTheme.colors`.
 *
 * @property isLight true for schemes with a light background
 * @property neumorphic true if raised / sunken surfaces are drawn with soft double shadows; false
 *   draws the flat variants (fill + outline) used by the dark and high-contrast schemes
 * @property backgroundTop upper colour of the screen background gradient
 * @property background base screen background (lower colour of the gradient)
 * @property surface face of a raised card or button
 * @property surfaceRaised face of a raised element that sits on a card (knobs, pills, keys)
 * @property surfaceSunken face of a sunken well (tracks, value boxes, stick pad)
 * @property surfaceBright brightest surface: dialogs and editable fields
 * @property shadowLight highlight shadow (top-left) of neumorphic surfaces
 * @property shadowDark dark shadow (bottom-right) of neumorphic surfaces
 * @property text primary text and icons
 * @property textMuted secondary text
 * @property textFaint disabled / de-emphasised text (unused channels)
 * @property accent accent blue: bars, selection, links
 * @property onAccent content on [accent]
 * @property glow bright accent used for "lit" states (stick pad borders, centre marker)
 * @property teal switch "up" state (+100 %)
 * @property orange switch "mid" state (0 %); also the general warm highlight
 * @property neutral switch "down" state (−100 %): a gray that reads on sunken wells in every scheme
 * @property channelGreen channel bars in the setup screens
 * @property chrome dark navy bar of the model-setup chrome
 * @property chromeRaised column-header row inside the chrome
 * @property onChrome content on [chrome]
 * @property onChromeMuted secondary content on [chrome]
 * @property chromeSelected selected tab pill in the chrome
 * @property onChromeSelected content on [chromeSelected]
 * @property outline hairlines and flat-surface borders
 * @property outlineStrong emphasised borders (focused / selected rows)
 * @property track unfilled part of bars, gauges and sliders
 * @property error destructive actions and errors
 * @property warning warnings
 * @property success confirmations
 * @property scrim dimming layer behind dialogs
 */
@Immutable
class AxColors(
    val isLight: Boolean,
    val neumorphic: Boolean,
    val backgroundTop: Color,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val surfaceBright: Color,
    val shadowLight: Color,
    val shadowDark: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val accent: Color,
    val onAccent: Color,
    val glow: Color,
    val teal: Color,
    val orange: Color,
    val neutral: Color,
    val channelGreen: Color,
    val chrome: Color,
    val chromeRaised: Color,
    val onChrome: Color,
    val onChromeMuted: Color,
    val chromeSelected: Color,
    val onChromeSelected: Color,
    val outline: Color,
    val outlineStrong: Color,
    val track: Color,
    val error: Color,
    val warning: Color,
    val success: Color,
    val scrim: Color,
)

/** Stock-like icy light scheme (default). */
val LightAxColors: AxColors = AxColors(
    isLight = true,
    neumorphic = true,
    backgroundTop = Color(0xFFD8F0F5),
    background = Color(0xFFDDF2F6),
    surface = Color(0xFFE1F4F8),
    surfaceRaised = Color(0xFFE9F8FB),
    surfaceSunken = Color(0xFFD2EBF0),
    surfaceBright = Color(0xFFF1FAFC),
    shadowLight = Color(0xFFFFFFFF),
    shadowDark = Color(0xFF8FB4C0),
    text = Color(0xFF1B2B3A),
    textMuted = Color(0xFF557080),
    textFaint = Color(0xFF93AEB9),
    accent = Color(0xFF2F7FB5),
    onAccent = Color(0xFFFFFFFF),
    glow = Color(0xFF2B9BEA),
    teal = Color(0xFF1FB8A6),
    orange = Color(0xFFF2A65A),
    neutral = Color(0xFF8396A1),
    channelGreen = Color(0xFF12A66A),
    chrome = Color(0xFF14283A),
    chromeRaised = Color(0xFF1D3A52),
    onChrome = Color(0xFFFFFFFF),
    onChromeMuted = Color(0xFFB4C8D6),
    chromeSelected = Color(0xFFDDF2F6),
    onChromeSelected = Color(0xFF2F7FB5),
    outline = Color(0xFFB3D0D9),
    outlineStrong = Color(0xFF3F9CCB),
    track = Color(0xFFC6E0E7),
    error = Color(0xFFD2443F),
    warning = Color(0xFFDB8A1D),
    success = Color(0xFF12A66A),
    scrim = Color(0x99102330),
)

/** Dark scheme with flat surfaces. */
val DarkAxColors: AxColors = AxColors(
    isLight = false,
    neumorphic = false,
    backgroundTop = Color(0xFF0C1720),
    background = Color(0xFF0F1C27),
    surface = Color(0xFF172936),
    surfaceRaised = Color(0xFF203747),
    surfaceSunken = Color(0xFF0A141C),
    surfaceBright = Color(0xFF1C3140),
    shadowLight = Color(0x00000000),
    shadowDark = Color(0x00000000),
    text = Color(0xFFE6F1F5),
    textMuted = Color(0xFFA0B6C2),
    textFaint = Color(0xFF61798A),
    accent = Color(0xFF4FA8E0),
    onAccent = Color(0xFF06131C),
    glow = Color(0xFF66C6FF),
    teal = Color(0xFF2FD0BC),
    orange = Color(0xFFF5B36E),
    neutral = Color(0xFF8DA2AF),
    channelGreen = Color(0xFF25C486),
    chrome = Color(0xFF081219),
    chromeRaised = Color(0xFF14283A),
    onChrome = Color(0xFFFFFFFF),
    onChromeMuted = Color(0xFF9FB4C2),
    chromeSelected = Color(0xFF203747),
    onChromeSelected = Color(0xFF7CCBFF),
    outline = Color(0xFF2C4556),
    outlineStrong = Color(0xFF4FA8E0),
    track = Color(0xFF24394A),
    error = Color(0xFFFF6B63),
    warning = Color(0xFFFFB347),
    success = Color(0xFF25C486),
    scrim = Color(0xB3000000),
)

/** High-contrast scheme for direct sunlight: pure white / black, saturated colours, flat. */
val HighContrastAxColors: AxColors = AxColors(
    isLight = true,
    neumorphic = false,
    backgroundTop = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFEDEDED),
    surfaceBright = Color(0xFFFFFFFF),
    shadowLight = Color(0x00000000),
    shadowDark = Color(0x00000000),
    text = Color(0xFF000000),
    textMuted = Color(0xFF1F1F1F),
    textFaint = Color(0xFF5A5A5A),
    accent = Color(0xFF0050B0),
    onAccent = Color(0xFFFFFFFF),
    glow = Color(0xFF0068FF),
    teal = Color(0xFF007566),
    orange = Color(0xFFD35400),
    neutral = Color(0xFF6E6E6E),
    channelGreen = Color(0xFF00703A),
    chrome = Color(0xFF000000),
    chromeRaised = Color(0xFF1C1C1C),
    onChrome = Color(0xFFFFFFFF),
    onChromeMuted = Color(0xFFE0E0E0),
    chromeSelected = Color(0xFFFFFFFF),
    onChromeSelected = Color(0xFF000000),
    outline = Color(0xFF000000),
    outlineStrong = Color(0xFF0050B0),
    track = Color(0xFFCFCFCF),
    error = Color(0xFFB00020),
    warning = Color(0xFF9A4B00),
    success = Color(0xFF00703A),
    scrim = Color(0xB3000000),
)

/** The [AxColors] of the current [AxTheme]. */
val LocalAxColors = staticCompositionLocalOf { LightAxColors }
