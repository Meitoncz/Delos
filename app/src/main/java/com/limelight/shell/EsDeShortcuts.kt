package com.limelight.shell

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.limelight.shell.data.App
import com.limelight.shell.data.EsDeFolder
import com.limelight.shell.data.EsDeFolderChoice
import com.limelight.shell.data.EsDeFolderUnavailable
import com.limelight.shell.data.Pc
import com.limelight.shell.ui.Symbols
import com.limelight.utils.artFileContent
import com.limelight.utils.artFileName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Create ES-DE shortcut" (docs/ES-DE.md): writes a `.art` file of a game into the folder chosen in
 * Android's folder picker. The picker opens the first time, and again when the remembered folder cannot be
 * used any more; every other time the file is written at once.
 */
@Stable
class EsDeShortcuts internal constructor(
    private val folder: EsDeFolder,
    private val shell: ShellState,
    private val scope: CoroutineScope,
) {
    /** Opens the folder picker (set by [rememberEsDeShortcuts], which owns the activity result launcher). */
    internal var openPicker: () -> Unit = {}

    /** The game whose shortcut waits for the folder being picked. */
    private var pending: Pair<Pc, App>? = null

    /** The name of the chosen folder, or null while none is. */
    val folderName: String? get() = folder.choice?.name

    /** Writes the shortcut of [app] on [pc] to the ES-DE folder, asking for the folder first when there is none. */
    fun create(pc: Pc, app: App) {
        val target = folder.usable()
        if (target == null) pick(pc to app) else write(target, pc, app)
    }

    /** Settings: opens the folder picker to choose (another) ES-DE folder. */
    fun choose() = pick(null)

    /** Settings: forgets the folder. */
    fun forget() {
        folder.forget()
        shell.showToast("ES-DE folder forgotten", Symbols.LINK_OFF)
    }

    private fun pick(game: Pair<Pc, App>?) {
        pending = game
        try {
            openPicker()
        } catch (e: ActivityNotFoundException) {
            pending = null
            shell.showToast("This device has no folder picker", Symbols.ERROR)
        }
    }

    internal fun picked(tree: Uri?) {
        val game = pending
        pending = null
        // Cancelled: nothing was chosen, nothing is written
        if (tree == null) return
        if (!folder.choose(tree)) {
            shell.showToast("Android did not allow access to that folder", Symbols.ERROR)
            return
        }
        val target = folder.usable()
        if (game != null && target != null) write(target, game.first, game.second)
        else shell.showToast("ES-DE folder: ${folder.choice?.name.orEmpty()}", Symbols.CHECK_CIRCLE)
    }

    private fun write(target: EsDeFolderChoice, pc: Pc, app: App) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val nvApp = app.nvApp
                    folder.write(target, artFileName(app.name), artFileContent(pc.uuid, pc.name, nvApp.appUUID, app.name, nvApp.appId))
                }
            }
            result.onSuccess { name ->
                shell.showToast("Shortcut created: $name", Symbols.CHECK_CIRCLE)
            }.onFailure { error ->
                if (error is EsDeFolderUnavailable) {
                    shell.showToast("The ES-DE folder is not available · choose it again", Symbols.ERROR)
                    folder.forget()
                    pick(pc to app)
                } else {
                    shell.showToast("Could not create the shortcut", Symbols.ERROR)
                }
            }
        }
    }
}

val LocalEsDeShortcuts = staticCompositionLocalOf<EsDeShortcuts> { error("EsDeShortcuts not provided") }

/** The ES-DE shortcuts of the shell: the folder picker is started from the shell's activity. */
@Composable
fun rememberEsDeShortcuts(shell: ShellState, scope: CoroutineScope): EsDeShortcuts {
    val context = LocalContext.current
    val shortcuts = remember(shell) { EsDeShortcuts(EsDeFolder(context), shell, scope) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { shortcuts.picked(it) }
    SideEffect { shortcuts.openPicker = { picker.launch(null) } }
    return shortcuts
}
