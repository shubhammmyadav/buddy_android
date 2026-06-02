package com.buddy.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val BuddyLightColors = lightColorScheme(
    primary               = BuddyBlue,
    onPrimary             = BuddyOnPrimary,
    primaryContainer      = BuddyBlueLight,
    onPrimaryContainer    = BuddyBlueDark,
    secondary             = BuddyViolet,
    onSecondary           = BuddyOnPrimary,
    secondaryContainer    = BuddyBlueLight,
    onSecondaryContainer  = BuddyViolet,
    surface               = BuddySurface,
    background            = BuddyBackground
)

private val BuddyDarkColors = darkColorScheme(
    primary               = BuddyBlueDarkMode,
    onPrimary             = BuddyBlueDark,
    primaryContainer      = BuddyBlueDark,
    onPrimaryContainer    = BuddyBlueDarkMode,
    secondary             = BuddyVioletDark,
    onSecondary           = BuddyViolet,
    secondaryContainer    = BuddyViolet,
    onSecondaryContainer  = BuddyVioletDark,
    surface               = BuddySurfaceDark,
    background            = BuddyBackgroundDark
)

@Composable
fun BuddyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color respects the user's Material You wallpaper on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> BuddyDarkColors
        else      -> BuddyLightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = BuddyTypography,
        content     = content
    )
}
