package com.limelight.shell.screens

import android.content.res.Resources
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.limelight.shell.ContextMenu
import com.limelight.shell.DialogField
import com.limelight.shell.DialogSpec
import com.limelight.shell.FocusTracker
import com.limelight.shell.Layer
import com.limelight.shell.LocalFocusTracker
import com.limelight.shell.LocalShell
import com.limelight.shell.MenuItem
import com.limelight.shell.Screen
import com.limelight.shell.ShellState
import com.limelight.shell.ToastAction
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.data.Profiles
import com.limelight.shell.ui.AvatarBadge
import com.limelight.shell.ui.CenterColumn
import com.limelight.shell.ui.CircleItem
import com.limelight.shell.ui.Circles
import com.limelight.shell.ui.Cue
import com.limelight.shell.ui.Feedback
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.InitialFocus
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.px
import com.limelight.shell.ui.textResources
import com.limelight.shell.ui.tpx
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.limelight.R

/** `#scr-profile`: the current profile with its avatar, and changing, renaming, switching or adding one. */
@Composable
fun ProfileScreen(profiles: Profiles, store: ProfileStore) {
    val res = textResources()
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val density = LocalDensity.current.density
    val shell = LocalShell.current
    val tracker = LocalFocusTracker.current
    val feedback = LocalFeedback.current
    val profile = profiles.current ?: return
    val first = remember { FocusRequester() }
    InitialFocus(first)
    CenterColumn(gap = 54f) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.px)) {
            val k = stage.dpPerPx * density
            AvatarBadge(
                profile.look, 240f, 120f,
                // box-shadow: 0 30px 80px -24px glow
                Modifier.dropShadow(CircleShape) {
                    radius = 80f * k
                    spread = -24f * k
                    offset = Offset(0f, 30f * k)
                    color = colors.glow
                },
            )
            BasicText(profile.name, style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 64.tpx, letterSpacing = (-.01).em))
            val count = profiles.all.size
            BasicText(
                pluralStringResource(R.plurals.profile_signed_in, count, Build.MODEL, count),
                style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 26.tpx),
            )
        }
        Circles {
            CircleItem(Symbols.FACE, stringResource(R.string.profile_change_avatar), { shell.navigate(Screen.CreateProfile(editing = true, back = Screen.Profile)) }, Modifier.focusRequester(first))
            CircleItem(Symbols.EDIT, stringResource(R.string.profile_rename), { renameProfile(res, shell, tracker, store, feedback, profile.id, profile.name) })
            CircleItem(Symbols.SWITCH_ACCOUNT, stringResource(R.string.profile_switch), { openProfilesMenu(res, shell, tracker, feedback, profiles, store, Screen.Profile) })
            CircleItem(Symbols.PERSON_ADD, stringResource(R.string.profile_add), { shell.navigate(Screen.CreateProfile(editing = false, back = Screen.Profile)) })
            CircleItem(Symbols.DELETE, stringResource(R.string.profile_delete), { deleteProfile(res, shell, store, profile.id, profile.name) })
        }
    }
}

/**
 * Deletes the current profile at once: choosing "Delete profile" is the confirmation. What it loses
 * (its settings, stream settings and control layouts) stays on the device for [ProfileStore.DELETE_GRACE_MS]
 * so the toast can undo it with Y or a tap. The next profile takes over; without one, the first launch
 * starts again (and Undo still brings the profile back).
 */
private fun deleteProfile(res: Resources, shell: ShellState, store: ProfileStore, id: String, name: String) {
    val another = store.delete(id)
    val undo = ToastAction(res.getString(R.string.profile_undo)) {
        if (store.undoDelete(id)) {
            shell.showToast(res.getString(R.string.profile_restored, name), Symbols.CHECK_CIRCLE)
            // The first launch screens make no sense with a profile again
            if (shell.screen is Screen.Boot || shell.screen is Screen.CreateProfile) shell.navigate(Screen.Start)
        } else {
            shell.showToast(res.getString(R.string.profile_restore_failed, name), Symbols.ERROR)
        }
    }
    shell.showToast(res.getString(R.string.profile_deleted, name), Symbols.DELETE, undo)
    shell.navigate(if (another) Screen.Start else Screen.Boot())
}

/** The profiles to switch to (the current one marked), and a new one; [from] is where creating returns to. */
fun profilesMenu(res: Resources, shell: ShellState, profiles: Profiles, store: ProfileStore, from: Screen): ContextMenu {
    val current = profiles.current?.id
    return ContextMenu(
        res.getString(R.string.profile_menu_title),
        below = true,
        items = profiles.all.map { p ->
            MenuItem(if (p.id == current) Symbols.CHECK else Symbols.ACCOUNT_CIRCLE, p.name) {
                if (p.id != current) {
                    store.switchTo(p.id)
                    shell.showToast(res.getString(R.string.profile_playing_as, p.name), Symbols.ACCOUNT_CIRCLE)
                }
            }
        } + MenuItem(Symbols.PERSON_ADD, res.getString(R.string.profile_new)) { shell.navigate(Screen.CreateProfile(editing = false, back = from)) },
    )
}

/** Opens [profilesMenu] at the focused element. */
fun openProfilesMenu(res: Resources, shell: ShellState, tracker: FocusTracker, feedback: Feedback, profiles: Profiles, store: ProfileStore, from: Screen) {
    feedback.play(Cue.OPEN)
    shell.openLayer(Layer.Context(profilesMenu(res, shell, profiles, store, from), tracker.bounds()), tracker)
}

private fun renameProfile(res: Resources, shell: ShellState, tracker: FocusTracker, store: ProfileStore, feedback: Feedback, id: String, name: String) {
    openProfileNameDialog(res, shell, tracker, feedback, res.getString(R.string.profile_rename_title), name) { value ->
        store.update(id) { it.copy(name = value) }
        shell.showToast(res.getString(R.string.profile_renamed), Symbols.CHECK_CIRCLE)
    }
}

/**
 * The one dialog a profile's name is typed in: Rename on the profile screen and "Change name" on the new / edit profile pages. A blank
 * name is refused with the same toast; [onName] gets the trimmed name when it is accepted and the dialog closes.
 */
fun openProfileNameDialog(res: Resources, shell: ShellState, tracker: FocusTracker, feedback: Feedback, title: String, initial: String, onName: (String) -> Unit) {
    feedback.play(Cue.OPEN)
    shell.openLayer(
        Layer.Dialog(
            DialogSpec(
                title, res.getString(R.string.profile_name_note),
                listOf(DialogField(Symbols.BADGE, initial = initial, maxLength = 20)), res.getString(R.string.profile_name_save),
            ) { (value) ->
                if (value.isBlank()) {
                    feedback.play(Cue.ERROR)
                    shell.showToast(res.getString(R.string.profile_name_required), Symbols.BADGE)
                    false
                } else {
                    onName(value.trim())
                    true
                }
            }
        ),
        tracker,
    )
}
