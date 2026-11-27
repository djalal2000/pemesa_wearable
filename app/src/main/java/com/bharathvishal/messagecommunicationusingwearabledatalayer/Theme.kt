package com.bharathvishal.messagecommunicationusingwearabledatalayer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0066CC),
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Color.Black
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3399FF),
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White
)

@Composable
fun MessageCommunicationUsingWearableDataLayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        shapes = Shapes(),
        content = content
    )
}
