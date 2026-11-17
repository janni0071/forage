package forage.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF3D6B3A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBFEFB4),
    onPrimaryContainer = Color(0xFF002203),
    secondary = Color(0xFF54634F),
    secondaryContainer = Color(0xFFD7E8CE),
    onSecondaryContainer = Color(0xFF121F10),
    tertiary = Color(0xFF8A4F2A),
    tertiaryContainer = Color(0xFFFFDBC9),
    onTertiaryContainer = Color(0xFF331200),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF8FAF3),
    surface = Color(0xFFF8FAF3),
    surfaceVariant = Color(0xFFDFE4D8),
    surfaceContainerLow = Color(0xFFF2F5EC),
    surfaceContainer = Color(0xFFECEFE6),
    surfaceContainerHigh = Color(0xFFE6E9E0),
    outline = Color(0xFF73796E),
    outlineVariant = Color(0xFFC3C8BC),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA4D39A),
    onPrimary = Color(0xFF0D390F),
    primaryContainer = Color(0xFF255223),
    onPrimaryContainer = Color(0xFFBFEFB4),
    secondary = Color(0xFFBBCBB2),
    onSecondary = Color(0xFF263423),
    secondaryContainer = Color(0xFF3C4B38),
    onSecondaryContainer = Color(0xFFD7E8CE),
    tertiary = Color(0xFFFFB68D),
    onTertiary = Color(0xFF522303),
    tertiaryContainer = Color(0xFF6E3815),
    onTertiaryContainer = Color(0xFFFFDBC9),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF11140F),
    onBackground = Color(0xFFE1E4DA),
    surface = Color(0xFF11140F),
    onSurface = Color(0xFFE1E4DA),
    surfaceVariant = Color(0xFF43483F),
    onSurfaceVariant = Color(0xFFC3C8BC),
    surfaceContainerLowest = Color(0xFF0C0F0A),
    surfaceContainerLow = Color(0xFF191D17),
    surfaceContainer = Color(0xFF1D211B),
    surfaceContainerHigh = Color(0xFF272B25),
    surfaceContainerHighest = Color(0xFF32362F),
    outline = Color(0xFF8D9387),
    outlineVariant = Color(0xFF43483F),
    inverseSurface = Color(0xFFE1E4DA),
    inverseOnSurface = Color(0xFF2E322B),
    inversePrimary = Color(0xFF3D6B3A),
)

@Composable
fun ForageTheme(dark: Boolean = false, content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
