package com.ghmanager.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Light = lightColorScheme(
    primary = Color(0xFF5B5BD6), onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E4FF), onPrimaryContainer = Color(0xFF16165C),
    secondary = Color(0xFF0EA5A4), tertiary = Color(0xFFE5484D),
    background = Color(0xFFF5F6FB), surface = Color.White,
    surfaceVariant = Color(0xFFEDEEF7), outline = Color(0xFF787A92),
    outlineVariant = Color(0xFFE1E3F0), error = Color(0xFFD92D3A)
)
private val Dark = darkColorScheme(
    primary = Color(0xFF9A9AFF), onPrimary = Color(0xFF14143F),
    primaryContainer = Color(0xFF26275E), onPrimaryContainer = Color(0xFFE0E0FF),
    secondary = Color(0xFF2DD4BF), tertiary = Color(0xFFFF7A80),
    background = Color(0xFF0B0D16), surface = Color(0xFF151827),
    surfaceVariant = Color(0xFF1E2234), outline = Color(0xFF8C90AA),
    outlineVariant = Color(0xFF2A2F46), error = Color(0xFFFF6B7A)
)

val Good = Color(0xFF2FB36D)
val Warn = Color(0xFFF2B84B)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp)
        ),
        content = {
            CompositionLocalProvider(LocalLayoutDirection provides if (Lang.isAr) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                content()
            }
        }
    )
}
