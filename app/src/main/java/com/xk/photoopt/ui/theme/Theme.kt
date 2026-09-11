package com.xk.photoopt.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF192E2A)
val Teal = Color(0xFF267B67)
val Lime = Color(0xFFDDF3A0)
val Paper = Color(0xFFF5F6F0)
val Muted = Color(0xFF77857D)

@Composable
fun PhotoOptTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Teal, onPrimary = Color.White,
            primaryContainer = Lime, onPrimaryContainer = Ink,
            secondary = Teal, secondaryContainer = Color(0xFFE8EFE5),
            background = Paper, onBackground = Ink,
            surface = Color.White, onSurface = Ink,
            surfaceVariant = Color(0xFFEDF0E7), onSurfaceVariant = Muted,
            outline = Color(0xFFD7DFD4), error = Color(0xFFAB493B)
        ),
        typography = Typography,
        content = content
    )
}
