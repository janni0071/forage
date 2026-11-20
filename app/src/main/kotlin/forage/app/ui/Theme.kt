package forage.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The accent, as the theme sets it: in the normal library a pastel pink that fills buttons and
 * badges, with dark text on it ([onAccent]), and a lavender beside it in the logo. The private
 * vault's look brings its own (see [VaultLook]).
 */
object Accent {
    val pastelPink: Color @Composable get() = LocalAccent.current.fill
    val lavender: Color @Composable get() = LocalAccent.current.second
    /** Text and icons on [pastelPink]. */
    val onAccent: Color @Composable get() = LocalAccent.current.onFill
}

/** The accent colours of a theme. */
data class AccentColors(val fill: Color, val onFill: Color, val second: Color)

private val PastelAccent = AccentColors(Color(0xFFF7B6CF), Color(0xFF3E0423), Color(0xFFCDB8F5))

val LocalAccent = staticCompositionLocalOf { PastelAccent }

/** Looks the private vault can wear instead of the normal pastel theme; each is always dark. */
enum class VaultLook(val label: String) { VELVET("Velvet"), NEON("Neon"), NOIR("Noir") }

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

/** Deep wine and black, a rose-red accent, warm gold where something is selected. */
private val Velvet = darkColorScheme(
    primary = Color(0xFFFF8DA6),
    onPrimary = Color(0xFF4A0618),
    primaryContainer = Color(0xFF5C1A2C),
    onPrimaryContainer = Color(0xFFFFD49A),
    secondary = Color(0xFFE6BFA0),
    onSecondary = Color(0xFF3A2414),
    secondaryContainer = Color(0xFF4A2630),
    onSecondaryContainer = Color(0xFFFFDCC4),
    tertiary = Color(0xFFF0C27A),
    onTertiary = Color(0xFF3E2A00),
    tertiaryContainer = Color(0xFF5A3E10),
    onTertiaryContainer = Color(0xFFFFDDA6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF13090C),
    onBackground = Color(0xFFF3DDE2),
    surface = Color(0xFF13090C),
    onSurface = Color(0xFFF3DDE2),
    surfaceVariant = Color(0xFF4E2F38),
    onSurfaceVariant = Color(0xFFD9B8C1),
    surfaceContainerLowest = Color(0xFF0D0507),
    surfaceContainerLow = Color(0xFF1C0E13),
    surfaceContainer = Color(0xFF231218),
    surfaceContainerHigh = Color(0xFF2E1820),
    surfaceContainerHighest = Color(0xFF3A1F29),
    outline = Color(0xFFA6828C),
    outlineVariant = Color(0xFF4E2F38),
    inverseSurface = Color(0xFFF3DDE2),
    inverseOnSurface = Color(0xFF34191F),
    inversePrimary = Color(0xFFB0284D),
)
private val VelvetAccent = AccentColors(Color(0xFFE3466E), Color(0xFFFFF1F4), Color(0xFFF0C27A))

/** Near-black with hot pink and electric violet. */
private val Neon = darkColorScheme(
    primary = Color(0xFFFF6BCB),
    onPrimary = Color(0xFF4D0036),
    primaryContainer = Color(0xFF3E1258),
    onPrimaryContainer = Color(0xFFFFA8E6),
    secondary = Color(0xFFB79CFF),
    onSecondary = Color(0xFF2A0F66),
    secondaryContainer = Color(0xFF2E1A4E),
    onSecondaryContainer = Color(0xFFE6DAFF),
    tertiary = Color(0xFF6EE7FF),
    onTertiary = Color(0xFF00363F),
    tertiaryContainer = Color(0xFF0F4A57),
    onTertiaryContainer = Color(0xFFC4F5FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0A0610),
    onBackground = Color(0xFFF1E4FF),
    surface = Color(0xFF0A0610),
    onSurface = Color(0xFFF1E4FF),
    surfaceVariant = Color(0xFF3A2A50),
    onSurfaceVariant = Color(0xFFCDBDE6),
    surfaceContainerLowest = Color(0xFF06030A),
    surfaceContainerLow = Color(0xFF130C1D),
    surfaceContainer = Color(0xFF191024),
    surfaceContainerHigh = Color(0xFF22162F),
    surfaceContainerHighest = Color(0xFF2C1C3D),
    outline = Color(0xFF9585B0),
    outlineVariant = Color(0xFF3A2A50),
    inverseSurface = Color(0xFFF1E4FF),
    inverseOnSurface = Color(0xFF2A1F38),
    inversePrimary = Color(0xFFB0247F),
)
private val NeonAccent = AccentColors(Color(0xFFFF3DB4), Color(0xFF2B0020), Color(0xFF8B5CFF))

/** Graphite, nearly without colour, and one crimson accent. */
private val Noir = darkColorScheme(
    primary = Color(0xFFFF6B7D),
    onPrimary = Color(0xFF5C0012),
    primaryContainer = Color(0xFF3B1B20),
    onPrimaryContainer = Color(0xFFFFB3BC),
    secondary = Color(0xFFC8C5C6),
    onSecondary = Color(0xFF303031),
    secondaryContainer = Color(0xFF2E2E31),
    onSecondaryContainer = Color(0xFFE6E2E3),
    tertiary = Color(0xFFBDBABB),
    onTertiary = Color(0xFF2E2E2F),
    tertiaryContainer = Color(0xFF3A3A3D),
    onTertiaryContainer = Color(0xFFE6E2E3),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E0E10),
    onBackground = Color(0xFFE8E6E7),
    surface = Color(0xFF0E0E10),
    onSurface = Color(0xFFE8E6E7),
    surfaceVariant = Color(0xFF38383C),
    onSurfaceVariant = Color(0xFFADAAAD),
    surfaceContainerLowest = Color(0xFF09090A),
    surfaceContainerLow = Color(0xFF161618),
    surfaceContainer = Color(0xFF1B1B1E),
    surfaceContainerHigh = Color(0xFF242427),
    surfaceContainerHighest = Color(0xFF2E2E32),
    outline = Color(0xFF8E8B8E),
    outlineVariant = Color(0xFF38383C),
    inverseSurface = Color(0xFFE8E6E7),
    inverseOnSurface = Color(0xFF2C2C2E),
    inversePrimary = Color(0xFFB3263C),
)
private val NoirAccent = AccentColors(Color(0xFFD7263D), Color(0xFFFFFFFF), Color(0xFF6E6E74))

/** The normal pastel theme, light or dark; or with [vault], that look, always dark. */
@Composable
fun ForageTheme(dark: Boolean = false, vault: VaultLook? = null, content: @Composable () -> Unit) {
    val (scheme, accent) = when (vault) {
        VaultLook.VELVET -> Velvet to VelvetAccent
        VaultLook.NEON -> Neon to NeonAccent
        VaultLook.NOIR -> Noir to NoirAccent
        null -> (if (dark) Dark else Light) to PastelAccent
    }
    CompositionLocalProvider(LocalAccent provides accent) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
