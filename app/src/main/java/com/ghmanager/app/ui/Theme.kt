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
    primary = Color(0xFF0F8F6E), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F5EA), onPrimaryContainer = Color(0xFF053D2E),
    secondary = Color(0xFF2E6FD8), tertiary = Color(0xFF7A4FD6),
    background = Color(0xFFF4F7F6), surface = Color.White,
    surfaceVariant = Color(0xFFE6ECEA), outline = Color(0xFF7C8B86),
    outlineVariant = Color(0xFFD3DCD9), error = Color(0xFFC9372C)
)
private val Dark = darkColorScheme(
    primary = Color(0xFF2FD3A5), onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF12392F), onPrimaryContainer = Color(0xFFB4F2DF),
    secondary = Color(0xFF5AA9FF), tertiary = Color(0xFFB79CFF),
    background = Color(0xFF0E1116), surface = Color(0xFF151A21),
    surfaceVariant = Color(0xFF1F262E), outline = Color(0xFF7D8A96),
    outlineVariant = Color(0xFF2A333D), error = Color(0xFFFF6B6B)
)

val Good = Color(0xFF2FB36D)
val Warn = Color(0xFFF2B84B)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(22.dp)
        ),
        content = {
            CompositionLocalProvider(LocalLayoutDirection provides if (Lang.isAr) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                content()
            }
        }
    )
}
