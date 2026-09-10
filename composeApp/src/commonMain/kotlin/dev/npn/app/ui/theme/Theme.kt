package dev.npn.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// "Quiet Paper": warm paper-white neutrals with a single indigo accent. Surfaces read as paper
// (warm off-whites, hairline outlines) rather than the cool blue-greys of stock Material.

private val Accent = Color(0xFF4C5CC7)
private val AccentDark = Color(0xFFAEB6FF)

// Warm neutral ramp shared by both schemes.
private val PaperBg = Color(0xFFFAF9F6)
private val PaperSurface = Color(0xFFFFFFFF)
private val PaperInk = Color(0xFF201E1B)

// 4.81:1 against PaperBg, keeping body-small secondary text above WCAG AA contrast.
private val PaperMuted = Color(0xFF736E64)
private val PaperLine = Color(0xFFECE8E0)
private val PaperField = Color(0xFFF2F0EB)

private val InkBg = Color(0xFF191816)
private val InkSurface = Color(0xFF191816)
private val InkText = Color(0xFFE9E6DF)
private val InkMuted = Color(0xFFA6A199)
private val InkLine = Color(0xFF35332E)
private val InkField = Color(0xFF262420)

private val LightColors =
    lightColorScheme(
        primary = Accent,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE9EBFA),
        onPrimaryContainer = Color(0xFF232E6E),
        secondary = Color(0xFF5D5A52),
        onSecondary = Color.White,
        secondaryContainer = PaperField,
        onSecondaryContainer = Color(0xFF2C2A25),
        // Warm amber "attention" tint (e.g. the needs-review badge). Defined explicitly so the
        // stock M3 teal tertiary never leaks into the warm-neutral palette.
        tertiary = Color(0xFF7C5800),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFF7E3C2),
        onTertiaryContainer = Color(0xFF5C4200),
        background = PaperBg,
        onBackground = PaperInk,
        surface = PaperSurface,
        onSurface = PaperInk,
        surfaceVariant = PaperField,
        onSurfaceVariant = PaperMuted,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = PaperBg,
        surfaceContainer = Color(0xFFF5F3EE),
        surfaceContainerHigh = PaperField,
        surfaceContainerHighest = Color(0xFFEDEAE3),
        outline = Color(0xFFB4AFA5),
        outlineVariant = PaperLine,
        error = Color(0xFFBA1A1A),
        onError = Color.White,
    )

private val DarkColors =
    darkColorScheme(
        primary = AccentDark,
        onPrimary = Color(0xFF152578),
        primaryContainer = Color(0xFF333D82),
        onPrimaryContainer = Color(0xFFE0E3FF),
        secondary = Color(0xFFCBC6BC),
        onSecondary = Color(0xFF32302B),
        secondaryContainer = Color(0xFF3C3A34),
        onSecondaryContainer = Color(0xFFE4E1D9),
        // Dark counterpart of the warm amber tertiary; see LightColors.
        tertiary = Color(0xFFEEC06C),
        onTertiary = Color(0xFF3F2E00),
        tertiaryContainer = Color(0xFF5C4200),
        onTertiaryContainer = Color(0xFFF7E3C2),
        background = InkBg,
        onBackground = InkText,
        surface = InkSurface,
        onSurface = InkText,
        surfaceVariant = InkField,
        onSurfaceVariant = InkMuted,
        surfaceContainerLowest = Color(0xFF141311),
        surfaceContainerLow = Color(0xFF1E1D1A),
        surfaceContainer = Color(0xFF23211D),
        surfaceContainerHigh = InkField,
        surfaceContainerHighest = Color(0xFF302E28),
        outline = Color(0xFF6C6860),
        outlineVariant = InkLine,
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
    )

// Softer, slightly larger corner language than stock M3: cards and fields feel like paper stock.
private val QuietShapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(18.dp),
        extraLarge = RoundedCornerShape(24.dp),
    )

/** System font with a quieter display scale: semibold titles, slightly tightened tracking. */
private fun quietTypography(base: Typography): Typography =
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    )

@Composable
fun NpnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = quietTypography(MaterialTheme.typography),
        shapes = QuietShapes,
        content = content,
    )
}
