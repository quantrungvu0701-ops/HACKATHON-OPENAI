package vn.chiday.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Palette: ballpoint ink on a paper form, highlighter yellow marks where to act,
// stamp red for steps that send, pay or delete. See design/ui-options.html.
val Ink = Color(0xFF16223B)
val Ink2 = Color(0xFF2A3A5E)
val InkSoft = Color(0xFF5D6880)
val Paper = Color(0xFFF7F8FA)
val Card = Color(0xFFFFFFFF)
val Line = Color(0xFFDDE2EA)
val Highlight = Color(0xFFFFD43B)
val HighlightDeep = Color(0xFFE8B500)
val Stamp = Color(0xFFC8102E)
val StampBg = Color(0xFFFDECEE)
val Ok = Color(0xFF2F7D4F)
val OkBg = Color(0xFFE8F3EC)
val Warn = Color(0xFFA35F00)

private val scheme = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Highlight,
    onPrimaryContainer = Ink,
    secondary = Ok,
    background = Paper,
    onBackground = Ink,
    surface = Card,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEEF1F5),
    onSurfaceVariant = InkSoft,
    outline = Line,
    error = Stamp,
)

// Larger than Material defaults on purpose: the audience is older, less confident users.
private val type = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    labelLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun ChiDayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
