package ir.mas.dastyar.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * پالت رنگی با کنتراست بالا (مطابق راهنمای WCAG AA حداقل ۴.۵:۱)
 * تا برای کاربر کم‌بینا خوانا باشد.
 */
private val DastyarBlue = Color(0xFF0B5FFF)
private val DastyarBlueDark = Color(0xFF6C9CFF)
private val DastyarBackground = Color(0xFFFFFFFF)
private val DastyarOnBackground = Color(0xFF101010)
private val DastyarBackgroundDark = Color(0xFF0A0A0A)
private val DastyarOnBackgroundDark = Color(0xFFF5F5F5)

private val LightColors = lightColorScheme(
    primary = DastyarBlue,
    onPrimary = Color.White,
    background = DastyarBackground,
    onBackground = DastyarOnBackground,
    surface = DastyarBackground,
    onSurface = DastyarOnBackground
)

private val DarkColors = darkColorScheme(
    primary = DastyarBlueDark,
    onPrimary = Color.Black,
    background = DastyarBackgroundDark,
    onBackground = DastyarOnBackgroundDark,
    surface = DastyarBackgroundDark,
    onSurface = DastyarOnBackgroundDark
)

// اندازه فونت‌ها عمداً بزرگ‌تر از حالت پیش‌فرض Material انتخاب شده‌اند.
private val DastyarTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 20.sp),
    labelLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun DastyarTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = DastyarTypography,
        content = content
    )
}
