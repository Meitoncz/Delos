package com.limelight.shell.data

import java.util.Locale

/** Offline artwork for host utilities; logos intentionally remain the rendered application name. */
internal object BundledArtwork {
    enum class Kind(val slug: String) {
        DESKTOP("desktop"), REMOTE_INPUT("remote-input"), REMOTE_MONITOR("remote-monitor"), STEAM_BIG_PICTURE("steam-big-picture");

        fun assetPath(artKind: ArtKind): String? =
            if (artKind == ArtKind.LOGO) null else "artwork/$slug/${artKind.file}.jpg"
    }

    fun kind(app: App): Kind? {
        val uuid = app.nvApp.getAppUUID()
        if ("9a1c5a25-58fe-40e0-b9aa-7d3f00000006".equals(uuid, ignoreCase = true)) return Kind.REMOTE_INPUT
        if ("9a1c5a25-58fe-40e0-b9aa-7d3f00000005".equals(uuid, ignoreCase = true)) return Kind.REMOTE_MONITOR
        return when (app.name.trim().lowercase(Locale.ROOT)) {
            "desktop" -> Kind.DESKTOP
            "remote input" -> Kind.REMOTE_INPUT
            "remote monitor" -> Kind.REMOTE_MONITOR
            "steam big picture", "steam bigpicture" -> Kind.STEAM_BIG_PICTURE
            else -> null
        }
    }

    fun assetPath(app: App, artKind: ArtKind): String? = kind(app)?.assetPath(artKind)
}
