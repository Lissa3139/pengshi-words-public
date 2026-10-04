package com.pengshi.words.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable

private val PengshiLightColors = lightColorScheme(
    primary = Color(0xFF08B69D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC7F4EA),
    onPrimaryContainer = Color(0xFF00382F),
    secondary = Color(0xFF6FC1AC),
    onSecondary = Color(0xFF00382F),
    surface = Color.White,
    onSurface = Color(0xFF202322),
    surfaceVariant = Color(0xFFF3F5F4),
    onSurfaceVariant = Color(0xFF626967),
)

@Composable
fun PengshiWordsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PengshiLightColors,
        content = content,
    )
}
