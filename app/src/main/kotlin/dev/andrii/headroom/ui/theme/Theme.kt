package dev.andrii.headroom.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.andrii.headroom.R

/**
 * A fixed scheme, deliberately not dynamic colour.
 *
 * Status colours have to mean the same thing on every phone, and Material You
 * would re-seed `tertiary` from the wallpaper — turning "approaching your
 * limit" into whatever hue the user's background happens to be.
 *
 * There is no green: "fine" is a neutral steel blue, so the screen carries no
 * traffic-light identity and the first appearance of chroma is itself the
 * signal. Nothing warm appears in either theme.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF3E5C76),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E4F8),
    onPrimaryContainer = Color(0xFF0D2136),
    tertiary = Color(0xFF8F6300),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDF9E),
    onTertiaryContainer = Color(0xFF2A1D00),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    surface = Color(0xFFF8F9FC),
    onSurface = Color(0xFF191C1F),
    onSurfaceVariant = Color(0xFF43474E),
    surfaceContainer = Color(0xFFECEEF2),
    surfaceContainerHigh = Color(0xFFE6E8EC),
    surfaceContainerHighest = Color(0xFFE0E3E7),
    outline = Color(0xFF737780),
    outlineVariant = Color(0xFFC3C7CF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA6C8E8),
    onPrimary = Color(0xFF0A3350),
    primaryContainer = Color(0xFF254566),
    onPrimaryContainer = Color(0xFFD3E4F8),
    tertiary = Color(0xFFF2BE4A),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4300),
    onTertiaryContainer = Color(0xFFFFDF9E),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    surface = Color(0xFF101417),
    onSurface = Color(0xFFE1E3E6),
    onSurfaceVariant = Color(0xFFC3C7CF),
    surfaceContainer = Color(0xFF1C2023),
    surfaceContainerHigh = Color(0xFF262A2E),
    surfaceContainerHighest = Color(0xFF313539),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
)

/**
 * Atkinson Hyperlegible Next, bundled (OFL).
 *
 * Chosen for a screen that is mostly digits read at a glance: its figures are
 * unambiguous where a grotesque's are not, and the slashed zero shows in
 * "02:00" and "100".
 */
private val Atkinson = FontFamily(
    Font(R.font.atkinson_regular, FontWeight.Normal),
    Font(R.font.atkinson_semibold, FontWeight.SemiBold),
    Font(R.font.atkinson_bold, FontWeight.Bold),
)

/**
 * The M3 scale with the bundled family applied, and one deliberate exception.
 *
 * The hero number is larger than `displayLarge`'s default: a three-bar screen
 * leaves air, and the number is the thing the eye should land on. Tabular
 * figures stop the digits shifting as the value changes.
 */
private val AppTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = Atkinson),
        displayMedium = base.displayMedium.copy(fontFamily = Atkinson),
        displaySmall = base.displaySmall.copy(fontFamily = Atkinson),
        headlineLarge = base.headlineLarge.copy(fontFamily = Atkinson),
        headlineMedium = base.headlineMedium.copy(fontFamily = Atkinson),
        headlineSmall = base.headlineSmall.copy(fontFamily = Atkinson),
        titleLarge = base.titleLarge.copy(fontFamily = Atkinson),
        titleMedium = base.titleMedium.copy(fontFamily = Atkinson),
        titleSmall = base.titleSmall.copy(fontFamily = Atkinson),
        bodyLarge = base.bodyLarge.copy(fontFamily = Atkinson),
        bodyMedium = base.bodyMedium.copy(fontFamily = Atkinson),
        bodySmall = base.bodySmall.copy(fontFamily = Atkinson),
        labelLarge = base.labelLarge.copy(fontFamily = Atkinson),
        labelMedium = base.labelMedium.copy(fontFamily = Atkinson),
        labelSmall = base.labelSmall.copy(fontFamily = Atkinson),
    )
}

val HeroNumberStyle = TextStyle(
    fontFamily = Atkinson,
    fontSize = 72.sp,
    lineHeight = 80.sp,
    letterSpacing = (-2.5).sp,
    fontWeight = FontWeight.Normal,
    fontFeatureSettings = "tnum",
)

val CompactNumberStyle = TextStyle(
    fontFamily = Atkinson,
    fontSize = 24.sp,
    lineHeight = 32.sp,
    fontWeight = FontWeight.Normal,
    fontFeatureSettings = "tnum",
)

@Composable
fun HeadroomTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
