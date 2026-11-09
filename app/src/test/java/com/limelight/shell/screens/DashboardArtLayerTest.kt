package com.limelight.shell.screens

import androidx.compose.ui.geometry.Rect
import com.limelight.shell.ContextMenu
import com.limelight.shell.Layer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardArtLayerTest {
    @Test
    fun aContextMenuKeepsTheCoversPlaying() {
        assertFalse(layerStopsDashboardArt(null))
        assertFalse(layerStopsDashboardArt(Layer.Context(ContextMenu("Game", emptyList()), Rect.Zero)))
    }

    @Test
    fun layersThatCoverTheScreenStopIt() {
        assertTrue(layerStopsDashboardArt(Layer.Side))
        assertTrue(layerStopsDashboardArt(Layer.Filters))
    }
}
