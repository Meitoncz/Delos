package com.limelight.shell.screens

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import com.limelight.R
import com.limelight.binding.input.virtual_controller.VirtualController
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx

/**
 * The on-screen controls without a stream (Debug info → Preview on-screen controls): the profile's layout
 * over a dark backdrop like a game, every button, the D-pad and the sticks reacting to touch, nothing sent
 * anywhere. The gear in the corner edits the layout as in a stream (turn buttons on or off, move, resize),
 * and the layout is saved.
 */
@Composable
fun OscPreviewScreen() {
    val context = LocalContext.current
    val layout = remember { FrameLayout(context) }
    val controller = remember {
        // No controller handler: the presses go nowhere
        VirtualController(null, layout, context).apply {
            refreshLayout()
            show()
        }
    }
    DisposableEffect(controller) { onDispose { controller.hide() } }
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(0f to Color(0xFF2A3446), 1f to Color(0xFF07090E), radius = 1600f),
        ),
    ) {
        BasicText(
            stringResource(R.string.settings_osc_preview_note),
            style = TextStyle(color = Color.White.copy(alpha = .45f), fontFamily = Figtree, fontSize = 20.tpx, textAlign = TextAlign.Center),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.px),
        )
        AndroidView(factory = { layout }, modifier = Modifier.fillMaxSize())
    }
}
