package com.limelight.shell.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources

/**
 * The resources of the current language for code that builds text outside composition (toast and dialog callbacks, menu items).
 * Reading the configuration makes the caller recompose when the language changes: the shell handles the `locale` configuration
 * change in place (no activity restart), and `LocalConfiguration` is replaced by a new object on every change. The `Resources`
 * object is the activity's own, which the system updates, so it always returns text of the current language. Inside composition
 * prefer `stringResource` and `pluralStringResource`; call `res.getString` / `res.getQuantityString` from callbacks that capture
 * this value. Anything that keeps a string across recompositions (`remember { res.getString(...) }`) must be keyed on
 * `LocalConfiguration.current`, or it keeps the old language. See docs/LOCALIZATION.md.
 */
@Composable
@ReadOnlyComposable
fun textResources(): Resources {
    LocalConfiguration.current
    return LocalResources.current
}
