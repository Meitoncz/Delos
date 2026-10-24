package com.limelight.shell.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.limelight.shell.Screen
import com.limelight.GameMenu
import com.limelight.binding.input.virtual_controller.VirtualControllerConfigurationLoader
import com.limelight.shell.DialogField
import com.limelight.shell.DialogSpec
import com.limelight.shell.Layer
import com.limelight.shell.LocalFocusTracker
import com.limelight.shell.LocalShell
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.SettingSpec
import com.limelight.shell.settings.StreamSettingsStore
import com.limelight.shell.settings.ProfileLayouts
import com.limelight.shell.ui.Symbols
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Existing reset, import, export and help actions, exposed without the old settings activity. */
@Composable
internal fun settingsActions(profiles: ProfileStore, stream: StreamSettingsStore): (SettingSpec) -> Unit {
    val context = LocalContext.current
    val shell = LocalShell.current
    val tracker = LocalFocusTracker.current
    val scope = rememberCoroutineScope()
    var importing by rememberSaveable { mutableStateOf("") }
    var layout by rememberSaveable { mutableStateOf("OSC_Keyboard") }
    var exported by rememberSaveable { mutableStateOf("") }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= 1_048_576) {
                            val count = input.read(buffer, 0, minOf(buffer.size, 1_048_577 - output.size()))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                        ?: error("The file could not be opened")
                    require(bytes.size <= 1_048_576) { "The file is too large" }
                    val json = JSONObject(bytes.toString(Charsets.UTF_8))
                    if (importing == "import_keyboard_file") {
                        val values = json.keys().asSequence().associateWith { key ->
                            val value = json.get(key)
                            require(value is String) { "The layout must contain text values" }
                            value
                        }
                        ProfileLayouts.preferences(context, layout).edit().apply {
                            clear()
                            values.forEach { (key, value) -> putString(key, value) }
                        }.apply()
                    } else {
                        require(json.optJSONArray("data") != null) { "The file has no shortcut data" }
                        ProfileLayouts.preferences(context, GameMenu.PREF_NAME).edit()
                            .putString(GameMenu.KEY_NAME, json.toString()).apply()
                    }
                }
            }
            shell.showToast(result.fold({ "Imported" }, { it.message ?: "Import failed" }), if (result.isSuccess) Symbols.CHECK_CIRCLE else Symbols.ERROR)
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val output = context.contentResolver.openOutputStream(uri) ?: error("The file could not be opened")
                    output.bufferedWriter().use { it.write(exported) }
                }
            }
            shell.showToast(if (result.isSuccess) "Layout exported" else "Export failed", if (result.isSuccess) Symbols.CHECK_CIRCLE else Symbols.ERROR)
        }
    }
    return { spec ->
        when (spec.key) {
            "option_reset_osc_preference" -> {
                ProfileLayouts.preferences(context, VirtualControllerConfigurationLoader.OSC_PREFERENCE).edit().clear().apply()
                shell.showToast("On-screen controls reset", Symbols.RESTART_ALT)
            }
            "option_reset_osc" -> {
                val name = profiles.settings().getString("keyboard_axi_list", "OSC_Keyboard")!!
                ProfileLayouts.preferences(context, name).edit().clear().apply()
                shell.showToast("Keyboard layout reset", Symbols.RESTART_ALT)
            }
            "import_keyboard_file", "import_special_button_file" -> {
                importing = spec.key
                layout = profiles.settings().getString("keyboard_axi_list", "OSC_Keyboard")!!
                runCatching { importFile.launch(arrayOf("application/json", "text/plain")) }
                    .onFailure { shell.showToast("No file picker available", Symbols.ERROR) }
            }
            "export_keyboard_file" -> {
                val name = profiles.settings().getString("keyboard_axi_list", "OSC_Keyboard")!!
                exported = JSONObject(ProfileLayouts.preferences(context, name).all).toString(2)
                runCatching { exportFile.launch("$name.json") }.onFailure { shell.showToast("No file picker available", Symbols.ERROR) }
            }
            "pref_debug_info" -> shell.navigate(Screen.DebugInfo)
            else -> if (spec.url != null) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(spec.url))) }
                    .onFailure { shell.showToast("No app can open this link", Symbols.ERROR) }
            } else {
                shell.openLayer(Layer.Dialog(DialogSpec(
                    spec.label, spec.description,
                    listOf(DialogField(spec.icon, initial = stream.value(spec)?.toString() ?: "", maxLength = 32)),
                    "Save",
                ) { (text) ->
                    val error = stream.writeText(spec, text)
                    if (error != null) shell.showToast(error, Symbols.ERROR)
                    error == null
                }), tracker)
            }
        }
    }
}
