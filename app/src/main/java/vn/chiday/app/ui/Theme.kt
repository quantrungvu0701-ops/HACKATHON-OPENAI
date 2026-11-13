package vn.chiday.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Brand = Color(0xFFD9480F)
val BrandDark = Color(0xFFA6360B)
val Paper = Color(0xFFFFF7EE)
val Ink = Color(0xFF2B2118)
val InkSoft = Color(0xFF6B5B4D)
val Ok = Color(0xFF2B8A3E)
val Warn = Color(0xFFE67700)
val Card = Color(0xFFFFFFFF)

private val scheme = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = Ok,
    background = Paper,
    onBackground = Ink,
    surface = Card,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF3E7DA),
    onSurfaceVariant = InkSoft,
    error = Color(0xFFC92A2A),
)

// Larger than Material defaults on purpose: the audience is older, less confident users.
private val type = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    labelLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun ChiDayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
