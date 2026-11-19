package forage.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The accent: a pastel pink. In light mode it fills surfaces, with a deeper rose for text that must stay readable. */
object Accent {
    val pastelPink = Color(0xFFF7B6CF)
    val lavender = Color(0xFFCDB8F5)
}

private val Light = lightColorScheme(
    primary = Color(0xFF9E3D68),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD6E5),
    onPrimaryContainer = Color(0xFF3E0423),
    secondary = Color(0xFF74565F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBE1EA),
    onSecondaryContainer = Color(0xFF2B151C),
    tertiary = Color(0xFF6E5491),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEEDCFF),
    onTertiaryContainer = Color(0xFF28104A),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFFF8F9),
    onBackground = Color(0xFF22191C),
    surface = Color(0xFFFFF8F9),
    onSurface = Color(0xFF22191C),
    surfaceVariant = Color(0xFFF3DDE4),
    onSurfaceVariant = Color(0xFF534348),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFCF0F3),
    surfaceContainer = Color(0xFFF7EAEE),
    surfaceContainerHigh = Color(0xFFF1E4E8),
    surfaceContainerHighest = Color(0xFFEBDEE2),
    outline = Color(0xFF857378),
    outlineVariant = Color(0xFFD8C2C8),
    inverseSurface = Color(0xFF382E31),
    inverseOnSurface = Color(0xFFFDEDF1),
    inversePrimary = Color(0xFFFFB0CB),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB0CB),
    onPrimary = Color(0xFF5E1136),
    primaryContainer = Color(0xFF7C2B4F),
    onPrimaryContainer = Color(0xFFFFD9E4),
    secondary = Color(0xFFE3BDC7),
    onSecondary = Color(0xFF422931),
    secondaryContainer = Color(0xFF4F363E),
    onSecondaryContainer = Color(0xFFFFD9E2),
    tertiary = Color(0xFFD8BAFF),
    onTertiary = Color(0xFF3E2560),
    tertiaryContainer = Color(0xFF553C78),
    onTertiaryContainer = Color(0xFFEEDCFF),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF181113),
    onBackground = Color(0xFFEFDFE3),
    surface = Color(0xFF181113),
    onSurface = Color(0xFFEFDFE3),
    surfaceVariant = Color(0xFF534348),
    onSurfaceVariant = Color(0xFFD8C2C8),
    surfaceContainerLowest = Color(0xFF120C0E),
    surfaceContainerLow = Color(0xFF20181B),
    surfaceContainer = Color(0xFF251C1F),
    surfaceContainerHigh = Color(0xFF302629),
    surfaceContainerHighest = Color(0xFF3B3134),
    outline = Color(0xFFA08C92),
    outlineVariant = Color(0xFF534348),
    inverseSurface = Color(0xFFEFDFE3),
    inverseOnSurface = Color(0xFF382E31),
    inversePrimary = Color(0xFF9E3D68),
)

@Composable
fun ForageTheme(dark: Boolean = false, content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
