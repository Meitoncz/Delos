package com.limelight.shell.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.limelight.shell.data.ArtworkProgress

/**
 * Downloading artwork or metadata: a glass pill with the count and a bar at the bottom
 * of every screen, above the button hints.
 */
@Composable
fun ArtworkProgressBar(progress: ArtworkProgress?, label: String = "Downloading artwork") {
    val colors = LocalDelosColors.current
    val last = remember { arrayOfNulls<ArtworkProgress>(1) }
    val lastLabel = remember { arrayOf(label) }
    if (progress != null) {
        last[0] = progress
        lastLabel[0] = label
    }
    Box(Modifier.fillMaxSize().padding(bottom = 118.px), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            progress != null,
            enter = fadeIn(tween(300)) + slideInVertically(tween(400, easing = EaseOut)) { it / 2 },
            exit = fadeOut(tween(400)),
        ) {
            val p = last[0] ?: return@AnimatedVisibility
            val fraction by animateFloatAsState(if (p.total == 0) 0f else p.done / p.total.toFloat(), tween(500, easing = EaseOut), label = "artworkProgress")
            Column(
                Modifier.width(620.px).clip(RoundedCornerShape(26.px)).glass(colors.surface3).border(1.px, colors.line, RoundedCornerShape(26.px))
                    .padding(horizontal = 26.px, vertical = 18.px),
                verticalArrangement = Arrangement.spacedBy(12.px),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.px)) {
                    Spinner(28f, color = colors.accent2)
                    BasicText(
                        lastLabel[0], modifier = Modifier.weight(1f),
                        style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 22.tpx),
                    )
                    BasicText("${p.done} of ${p.total}", style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 22.tpx, fontFeatureSettings = "tnum"))
                }
                Box(Modifier.fillMaxWidth().height(8.px).clip(CircleShape).background(colors.surface2)) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(CircleShape).background(Brush.horizontalGradient(listOf(colors.accent2, colors.accent3))))
                }
            }
        }
    }
}
