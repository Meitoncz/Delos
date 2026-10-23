package com.limelight.shell

import android.content.Context
import android.content.Intent
import com.limelight.AppView
import com.limelight.Game
import com.limelight.ShortcutTrampoline

/**
 * What a link, another app or a shortcut asks the new UI to do. Parsed like the classic
 * AddComputerManually (art:// links) and AppView (PC shortcuts). The system still sends them to the
 * classic UI until the switch-over in step 6.9.
 */
sealed interface ShellLink {
    /** `art://host[:port]?pin=…&passphrase=…[&name=…]`: add the PC and pair it (with the one-time code, if given). */
    data class Pair(val address: String, val name: String?, val otp: Otp?) : ShellLink

    /** `art://launch?host_uuid=…&app_id=…`: start a game the way pinned game shortcuts do ([intent] for ShortcutTrampoline). */
    data class Launch(val intent: Intent, val appName: String?, val hostName: String?) : ShellLink

    /** A PC shortcut: open that PC's games. */
    data class OpenPc(val uuid: String) : ShellLink

    companion object {
        fun from(context: Context, intent: Intent?): ShellLink? {
            intent ?: return null
            val data = intent.data
            if (intent.action == Intent.ACTION_VIEW && data != null && data.scheme == "art") {
                if (data.port == -1 && data.host == "launch") {
                    val launch = Intent(context, ShortcutTrampoline::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(AppView.UUID_EXTRA, data.getQueryParameter("host_uuid"))
                        .putExtra(AppView.NAME_EXTRA, data.getQueryParameter("host_name"))
                        .putExtra(Game.EXTRA_APP_UUID, data.getQueryParameter("app_uuid"))
                        .putExtra(Game.EXTRA_APP_NAME, data.getQueryParameter("app_name"))
                        .putExtra(Game.EXTRA_APP_ID, data.getQueryParameter("app_id"))
                    return Launch(launch, data.getQueryParameter("app_name"), data.getQueryParameter("host_name"))
                }
                val address = data.authority?.takeIf { it.isNotEmpty() } ?: return null
                val pin = data.getQueryParameter("pin")
                val passphrase = data.getQueryParameter("passphrase")
                return Pair(address, data.getQueryParameter("name"), if (pin != null && passphrase != null) Otp(pin, passphrase) else null)
            }
            return intent.getStringExtra(AppView.UUID_EXTRA)?.let { OpenPc(it) }
        }
    }
}
