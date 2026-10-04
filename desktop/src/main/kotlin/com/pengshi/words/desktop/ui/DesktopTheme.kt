package com.pengshi.words.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Typography
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

object DesktopPalette {
    val ink = Color(0xFF17283D)
    val railSelected = Color(0xFF263E55)
    val railMuted = Color(0xFFAFC2CF)
    val aqua = Color(0xFF76D8D0)
    val teal = Color(0xFF08B69D)
    val canvas = Color(0xFFF3F5F4)
    val line = Color(0xFFDCE5EB)
    val muted = Color(0xFF607487)
    val coral = Color(0xFFD7474C)
    val paleTeal = Color(0xFFE7F5F3)
}

@Composable
fun DesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = lightColors(
            primary = DesktopPalette.teal,
            primaryVariant = DesktopPalette.ink,
            secondary = DesktopPalette.coral,
            background = DesktopPalette.canvas,
            surface = Color.White,
            onPrimary = Color.White,
            onSurface = DesktopPalette.ink,
        ),
        typography = Typography(defaultFontFamily = FontFamily.SansSerif),
        content = content,
    )
}

@Composable
fun DesktopPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = Color.White, elevation = 1.dp) {
        Column(Modifier.padding(24.dp)) { content() }
    }
}
