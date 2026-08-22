package com.BalancedLight.dynamicdriving.shared.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Fallback palette used wherever dynamic colour is unavailable.
 *
 * Warm headlight amber against a deep night-drive blue, chosen so the Now Playing controls stay
 * legible at a glance in both a bright cabin and a dark one.
 */
private val HeadlightAmber = Color(0xFFF2B33D)
private val HeadlightAmberDark = Color(0xFF6B4A00)
private val DuskBlue = Color(0xFF3F5C8C)
private val DuskBlueDark = Color(0xFF17233D)
private val TaillightRed = Color(0xFFB3402F)
private val NightSurface = Color(0xFF12141A)
private val NightSurfaceVariant = Color(0xFF262A33)
private val DaySurface = Color(0xFFFBF9F4)
private val DaySurfaceVariant = Color(0xFFE6E1D6)

private val LightDynamicDrivingScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF7A5900),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDF9E),
    onPrimaryContainer = Color(0xFF261A00),
    secondary = DuskBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3FF),
    onSecondaryContainer = Color(0xFF101C33),
    tertiary = Color(0xFF7C5635),
    onTertiary = Color.White,
    error = TaillightRed,
    onError = Color.White,
    background = DaySurface,
    onBackground = Color(0xFF1D1B16),
    surface = DaySurface,
    onSurface = Color(0xFF1D1B16),
    surfaceVariant = DaySurfaceVariant,
    onSurfaceVariant = Color(0xFF4C4639),
    outline = Color(0xFF7E7667)
)

private val DarkDynamicDrivingScheme: ColorScheme = darkColorScheme(
    primary = HeadlightAmber,
    onPrimary = Color(0xFF402D00),
    primaryContainer = HeadlightAmberDark,
    onPrimaryContainer = Color(0xFFFFDF9E),
    secondary = Color(0xFFACC7FF),
    onSecondary = Color(0xFF102F60),
    secondaryContainer = DuskBlueDark,
    onSecondaryContainer = Color(0xFFD7E3FF),
    tertiary = Color(0xFFEEBD94),
    onTertiary = Color(0xFF48290C),
    error = Color(0xFFFFB4A6),
    onError = Color(0xFF690003),
    background = NightSurface,
    onBackground = Color(0xFFE7E2D6),
    surface = NightSurface,
    onSurface = Color(0xFFE7E2D6),
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = Color(0xFFCFC7B4),
    outline = Color(0xFF989080)
)

/**
 * @param dynamicColor honour the wallpaper palette on Android 12+. Turning it off (or running on an
 * older release) falls back to the fixed Dynamic Driving palette, which is what the car surfaces and
 * the screenshots in the README are built against.
 */
@Composable
fun DynamicDrivingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkDynamicDrivingScheme
        else -> LightDynamicDrivingScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DynamicDrivingTypography,
        content = content
    )
}
