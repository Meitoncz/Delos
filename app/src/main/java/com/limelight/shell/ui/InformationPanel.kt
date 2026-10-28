package com.limelight.shell.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em

/** A fixed heading and passive, automatically scrolling explanation on the right. */
@Composable
fun InformationPanel(title: String, description: String, modifier: Modifier = Modifier) {
    val colors = LocalDelosColors.current
    val shape = RoundedCornerShape(34.px)
    Column(modifier.clip(shape).background(colors.surface).border(1.px, colors.line, shape)
        .padding(30.px), verticalArrangement = Arrangement.spacedBy(8.px)) {
        BasicText(title, style = TextStyle(color = colors.text, fontFamily = Figtree,
            fontWeight = FontWeight.SemiBold, fontSize = 26.tpx))
        AutoScrollingHelp(title to description, Modifier.weight(1f)) {
            BasicText(description, style = TextStyle(color = colors.text2, fontFamily = Figtree,
                fontSize = 22.tpx, lineHeight = 1.4.em))
        }
    }
}
