package com.limelight.shell.screens

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
import com.limelight.shell.ui.tpx

/** `#scr-profile`: the current profile with its avatar, and changing, renaming, switching or adding one. */
@Composable
fun ProfileScreen(profiles: Profiles, store: ProfileStore) {
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
                "Signed in on this ${Build.MODEL} · $count ${if (count == 1) "profile" else "profiles"}",
                style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 26.tpx),
            )
        }
        Circles {
            CircleItem(Symbols.FACE, "Change avatar", { shell.navigate(Screen.CreateProfile(editing = true, back = Screen.Profile)) }, Modifier.focusRequester(first))
            CircleItem(Symbols.EDIT, "Rename", { renameProfile(shell, tracker, store, feedback, profile.id, profile.name) })
            CircleItem(Symbols.SWITCH_ACCOUNT, "Switch profile", { openProfilesMenu(shell, tracker, feedback, profiles, store, Screen.Profile) })
            CircleItem(Symbols.PERSON_ADD, "Add profile", { shell.navigate(Screen.CreateProfile(editing = false, back = Screen.Profile)) })
            CircleItem(Symbols.DELETE, "Delete profile", { deleteProfile(shell, tracker, store, feedback, profile.id, profile.name) })
        }
    }
}

/**
 * Deletes the current profile after asking once: it loses data (its settings, stream settings and
 * control layouts), the one case the plan keeps a question for. The next profile takes over; without
 * one, the first launch starts again.
 */
private fun deleteProfile(shell: ShellState, tracker: FocusTracker, store: ProfileStore, feedback: Feedback, id: String, name: String) {
    feedback.play(Cue.OPEN)
    shell.openLayer(
        Layer.Dialog(
            DialogSpec(
                "Delete $name?", "Its settings, stream settings and control layouts are removed. This cannot be undone.",
                emptyList(), "Delete",
            ) {
                if (store.delete(id)) {
                    shell.showToast("$name was deleted", Symbols.DELETE)
                    shell.navigate(Screen.Start)
                } else {
                    shell.navigate(Screen.Boot())
                }
                true
            }
        ),
        tracker,
    )
}

/** The profiles to switch to (the current one marked), and a new one; [from] is where creating returns to. */
fun profilesMenu(shell: ShellState, profiles: Profiles, store: ProfileStore, from: Screen): ContextMenu {
    val current = profiles.current?.id
    return ContextMenu(
        "Profiles",
        profiles.all.map { p ->
            MenuItem(if (p.id == current) Symbols.CHECK else p.look.icon, p.name) {
                if (p.id != current) {
                    store.switchTo(p.id)
                    shell.showToast("Playing as ${p.name}", p.look.icon)
                }
            }
        } + MenuItem(Symbols.PERSON_ADD, "New profile") { shell.navigate(Screen.CreateProfile(editing = false, back = from)) },
    )
}

/** Opens [profilesMenu] at the focused element. */
fun openProfilesMenu(shell: ShellState, tracker: FocusTracker, feedback: Feedback, profiles: Profiles, store: ProfileStore, from: Screen) {
    feedback.play(Cue.OPEN)
    shell.openLayer(Layer.Context(profilesMenu(shell, profiles, store, from), tracker.bounds(), below = true), tracker)
}

private fun renameProfile(shell: ShellState, tracker: FocusTracker, store: ProfileStore, feedback: Feedback, id: String, name: String) {
    feedback.play(Cue.OPEN)
    shell.openLayer(
        Layer.Dialog(
            DialogSpec(
                "Rename profile", "Shown on the start screen and in the side menu.",
                listOf(DialogField(Symbols.BADGE, initial = name, maxLength = 20)), "Save",
            ) { (value) ->
                if (value.isBlank()) {
                    feedback.play(Cue.ERROR)
                    shell.showToast("Enter a name for your profile", Symbols.BADGE)
                    false
                } else {
                    store.update(id) { it.copy(name = value.trim()) }
                    shell.showToast("Profile renamed", Symbols.CHECK_CIRCLE)
                    true
                }
            }
        ),
        tracker,
    )
}
