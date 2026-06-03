package com.aipay.listener.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

val AiBlack = Color(0xFF0A0A0A)
val AiPaper = Color(0xFFF4F4EF)
val AiYellow = Color(0xFFD9FB50)
val AiMuted = Color(0xFFE3E3DC)
val AiGreen = Color(0xFF1F9D55)

private val colors = lightColorScheme(
    primary = AiBlack,
    onPrimary = AiPaper,
    secondary = AiYellow,
    background = AiPaper,
    surface = AiPaper,
    onSurface = AiBlack,
    outline = AiBlack
)

@Composable
fun AiPayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography.copy(
            headlineLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 30.sp),
            titleLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 20.sp),
            titleMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp),
            bodyLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            labelLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        ),
        content = content
    )
}
