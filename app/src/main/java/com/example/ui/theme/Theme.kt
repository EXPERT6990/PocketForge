package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class IdeExtendedColors(
    val diffAddText: Color = TerminalEmerald,
    val diffAddBg: Color = EmeraldContainer,
    val diffRemoveText: Color = CoralRose,
    val diffRemoveBg: Color = RoseContainer,
    val warningText: Color = AmberSignal,
    val warningBg: Color = AmberContainer,
    val keywordSyntax: Color = VioletKeyword,
    val stringSyntax: Color = TerminalEmerald,
    val numberSyntax: Color = AmberSignal,
    val commentSyntax: Color = TextMuted,
    val terminalBg: Color = Color(0xFF070B12),
    val ambientGlow: Color = ObsidianAmbient,
    val hairlineBorder: Color = HairlineSlate
)

val LocalIdeColors = staticCompositionLocalOf { IdeExtendedColors() }

private val PocketForgeDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = OnElectricCyan,
    primaryContainer = CyanMutedContainer,
    onPrimaryContainer = ElectricCyan,
    secondary = SteelSky,
    onSecondary = OnElectricCyan,
    secondaryContainer = SkyMutedContainer,
    onSecondaryContainer = SteelSky,
    tertiary = TerminalEmerald,
    onTertiary = OnElectricCyan,
    tertiaryContainer = EmeraldContainer,
    onTertiaryContainer = TerminalEmerald,
    error = CoralRose,
    onError = Color.White,
    errorContainer = RoseContainer,
    onErrorContainer = CoralRose,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = MidnightSurface,
    onSurface = TextPrimary,
    surfaceVariant = EditorSlate,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianBg,
    surfaceContainerLow = MidnightSurface,
    surfaceContainer = EditorSlate,
    surfaceContainerHigh = ActiveSlate,
    surfaceContainerHighest = ElevatedSlate,
    outline = TextMuted,
    outlineVariant = HairlineSlate
)

private val PocketForgeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp)
)

@Composable
fun PocketForgeTheme(
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalIdeColors provides IdeExtendedColors()) {
        MaterialTheme(
            colorScheme = PocketForgeDarkColorScheme,
            typography = AppTypography,
            shapes = PocketForgeShapes,
            content = content
        )
    }
}
