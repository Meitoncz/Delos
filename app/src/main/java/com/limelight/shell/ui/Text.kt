package com.limelight.shell.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * The resources of the current language for code that builds text outside composition (toast and dialog callbacks, menu items).
 * Reading the configuration makes the caller recompose when the language changes. Inside composition prefer `stringResource`
 * and `pluralStringResource`; call `res.getString` / `res.getQuantityString` from callbacks that capture this value.
 * See docs/LOCALIZATION.md.
 */
@Composable
@ReadOnlyComposable
fun textResources(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}
