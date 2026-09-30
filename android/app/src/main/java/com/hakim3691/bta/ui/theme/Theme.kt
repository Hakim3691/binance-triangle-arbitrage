package com.hakim3691.bta.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Binance-inspired dark trading palette
val BtaBlack = Color(0xFF0B0E11)
val BtaSurface = Color(0xFF181A20)
val BtaSurfaceElevated = Color(0xFF1E2329)
val BtaYellow = Color(0xFFF0B90B)
val ProfitGreen = Color(0xFF2EBD85)
val LossRed = Color(0xFFF6465D)
val TextPrimary = Color(0xFFEAECEF)
val TextSecondary = Color(0xFF848E9C)
val CardBorder = Color(0xFF2B3139)

private val DarkColorScheme = darkColorScheme(
    primary = BtaYellow,
    onPrimary = BtaBlack,
    secondary = ProfitGreen,
    onSecondary = BtaBlack,
    tertiary = LossRed,
    background = BtaBlack,
    onBackground = TextPrimary,
    surface = BtaSurface,
    onSurface = TextPrimary,
    surfaceVariant = BtaSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = CardBorder,
    error = LossRed
)

@Composable
fun BtaTheme(content: @Composable () -> Unit) {
    // The app is intentionally always dark (trading-terminal aesthetic).
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
