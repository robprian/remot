package com.robrion.remot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- Brand ----
private val Brand = Color(0xFF0B5FFF)
private val BrandDark = Color(0xFF6EA8FF)

// ---- Semantic status colors (subtle, used sparingly) ----
val Success = Color(0xFF2E7D32)
val SuccessDark = Color(0xFF81C784)
val Warning = Color(0xFFED6C02)
val WarningDark = Color(0xFFFFB74D)
val Error = Color(0xFFC62828)
val ErrorDark = Color(0xFFEF9A9A)
val Neutral = Color(0xFF6E6E73)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF0B2B6E),
    secondary = Color(0xFF5A6072),
    onSecondary = Color.White,
    surface = Color(0xFFFAFBFD),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFEDEFF4),
    onSurfaceVariant = Color(0xFF44474E),
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF191C20),
    outline = Color(0xFFC4C9D4),
)

private val DarkColors = darkColorScheme(
    primary = BrandDark,
    onPrimary = Color(0xFF062E75),
    primaryContainer = Color(0xFF1B3A7A),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFBEC3D2),
    onSecondary = Color(0xFF282C35),
    surface = Color(0xFF15171C),
    onSurface = Color(0xFFE3E5EB),
    surfaceVariant = Color(0xFF23262D),
    onSurfaceVariant = Color(0xFFB9BDC7),
    background = Color(0xFF101216),
    onBackground = Color(0xFFE3E5EB),
    outline = Color(0xFF3A3F48),
)

// Typography — clean, consistent, slightly tighter for a control app.
private val RemotTypography = Typography(
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = 2.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 26.sp, lineHeight = 32.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp, lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp, lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.3.sp
    ),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default, fontSize = 12.sp, lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.3.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp
    ),
)

// Soft, modern radii.
private val RemotShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun RemotTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = RemotTypography,
        shapes = RemotShapes,
        content = content
    )
}
