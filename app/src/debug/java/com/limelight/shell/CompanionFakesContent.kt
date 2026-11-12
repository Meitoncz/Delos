package com.limelight.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import com.limelight.shell.companion.ArtLine
import com.limelight.shell.companion.ArtTone
import com.limelight.shell.companion.ArtworkPage
import com.limelight.shell.companion.BootPage
import com.limelight.shell.companion.CompanionHint
import com.limelight.shell.companion.CompanionHostLine
import com.limelight.shell.companion.CompanionPage
import com.limelight.shell.companion.CompanionState
import com.limelight.shell.companion.CreateProfilePage
import com.limelight.shell.companion.FilterCount
import com.limelight.shell.companion.FilterPage
import com.limelight.shell.companion.FilterUnit
import com.limelight.shell.companion.ProfilePage
import com.limelight.shell.companion.ShellContext
import com.limelight.shell.data.AVATARS

/**
 * Debug builds only: made-up companion states of the artwork, filter, profile, create-profile and boot pages, by the name of `companion_fake`:
 * `artwork`, `artwork_filter`, `library_filter`, `library_filter_none`, `profile`, `create_profile`, `boot`. The data has the shape the screens publish;
 * the buttons only write to the log (`adb logcat -s CompanionPreview`).
 */
internal object CompanionFakesContent {
    private const val TAG = "CompanionPreview"

    fun state(name: String): CompanionState? = when (name) {
        "artwork" -> shell(artwork())
        "artwork_filter" -> shell(artwork(), FilterPage(false, listOf("Cover", "Alternative", "600 × 900"), FilterCount.Of(12, 36), FilterUnit.IMAGES) { log("reset artwork filters") })
        "library_filter" -> shell(FilterPage(true, listOf("Action", "Native HDR", "10–30 h"), FilterCount.Of(7, 48), FilterUnit.GAMES) { log("reset library filters") })
        "library_filter_none" -> shell(FilterPage(true, emptyList(), FilterCount.All(48), FilterUnit.GAMES) { log("reset library filters") })
        "profile" -> shell(ProfilePage("Voyager", AVATARS[1], 8, 3, "Gaming PC"))
        "create_profile" -> shell(CreateProfilePage("Voyager", AVATARS[1]))
        "boot" -> shell(BootPage(canGoBack = false), welcome = true)
        else -> null
    }

    private fun log(message: String) {
        android.util.Log.i(TAG, message)
    }

    private fun artwork() = ArtworkPage(
        title = "Asterfall cover",
        image = portal(),
        placeholderRatio = 600f / 900f,
        lines = listOf(
            ArtLine("600 × 900 · PNG", "600 × 900 · PNG", ArtTone.NORMAL, 90),
            ArtLine("Score 95", "Score 95", ArtTone.SEA, 70),
            ArtLine("Studio Sample", "Studio Sample", ArtTone.NORMAL, 30),
            ArtLine("Added 10 Nov 2026", "10 Nov 2026", ArtTone.MUTED, 50),
            ArtLine("In use", "In use", ArtTone.GOLD, 100),
        ),
        inUse = false,
    ) { log("use image") }

    /** A 600 x 900 picture drawn in code (a ring over dark mountains), the 2:3 shape of a cover; no asset file. */
    private fun portal(): androidx.compose.ui.graphics.ImageBitmap {
        val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFF0B1B2B.toInt()
        canvas.drawRect(0f, 0f, 600f, 900f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 18f
        paint.color = 0xFF9BC9BA.toInt()
        canvas.drawCircle(300f, 330f, 150f, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFF2A4D52.toInt()
        canvas.drawPath(Path().apply { moveTo(0f, 760f); lineTo(140f, 560f); lineTo(260f, 640f); lineTo(400f, 470f); lineTo(600f, 640f); lineTo(600f, 900f); lineTo(0f, 900f); close() }, paint)
        paint.color = 0xFF0A1824.toInt()
        canvas.drawPath(Path().apply { moveTo(0f, 900f); lineTo(260f, 650f); lineTo(420f, 710f); lineTo(600f, 620f); lineTo(600f, 900f); close() }, paint)
        return bitmap.asImageBitmap()
    }

    private val hints = listOf(CompanionHint("A", null, "Select"), CompanionHint("B", null, "Back"))

    private fun shell(vararg pages: CompanionPage, welcome: Boolean = false): CompanionState {
        val context = ShellContext("Artwork", "Choose the pictures for this game.", hints, CompanionHostLine("GAMING-PC", "Online", null), welcome)
        return CompanionState.Shell(if (welcome) null else "GAMING-PC", pages.toList(), context)
    }
}
