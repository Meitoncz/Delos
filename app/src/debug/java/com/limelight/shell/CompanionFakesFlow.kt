package com.limelight.shell

import android.util.Log
import com.limelight.diag.ProblemKind
import com.limelight.shell.companion.CompanionPage
import com.limelight.shell.companion.CompanionState
import com.limelight.shell.companion.FlowLoader
import com.limelight.shell.companion.FlowResult
import com.limelight.shell.companion.LoaderPage
import com.limelight.shell.companion.PortalPage
import com.limelight.shell.companion.ProblemPage
import com.limelight.shell.companion.ReportPage
import com.limelight.shell.companion.ResultPage
import com.limelight.shell.companion.StartStage
import com.limelight.shell.ui.Symbols

/**
 * Debug builds only: made-up companion states of the launch / connect flow, by the name of `preview_state` / `companion_fake`:
 * `loader` (the host accepted the launch, video awaited, Cancel offered), `loader_short` (long names, the settings in their short forms),
 * `loader_started` (the connection runs, no Cancel), `loader_launching` (the request is still being sent), `portal` (a PC that wakes up, cancellable),
 * `portal_closing` (the exit of a game, not cancellable), `result` (a stream that lost its connection), `result_disconnected` (the PC ended the stream),
 * `problem` (a start that failed), `worst_error` (the same with a 610-character error), `report` (the report offer of a crash).
 * Buttons only write a line to the log (`adb logcat -s CompanionPreview`).
 */
internal object CompanionFakesFlow {
    fun state(name: String): CompanionState? = when (name) {
        "loader" -> shell(LoaderPage(loader(StartStage.CONNECTING), log("cancel stream start")))
        "loader_short" -> shell(
            LoaderPage(
                FlowLoader(LONG_GAME, LONG_PC, "7c2a9e41-5b3d-4f10-8a66-0d2c71e4b7c2", "7680 \u00d7 4320 \u00b7 PyroWave \u00b7 120 FPS", StartStage.LAUNCHING),
                log("cancel stream start"),
            ),
            pc = LONG_PC,
        )
        "loader_started" -> shell(LoaderPage(loader(StartStage.CONNECTED), null))
        "loader_launching" -> shell(LoaderPage(loader(StartStage.LAUNCHING), log("cancel stream start")))
        "portal" -> shell(PortalPage(Symbols.WAKE, "Waking up GAMING-PC", "Wake-up signal sent \u00b7 B cancels", log("cancel portal")))
        "portal_closing" -> shell(PortalPage(Symbols.POWER_SETTINGS_NEW, "Closing Asterfall", "GAMING-PC", null))
        "result" -> shell(ResultPage(result(StreamResultKind.CONNECTION_LOST, "Connection lost", "No video arrived from the PC.", null), log("reconnect"), log("back to library")))
        "result_disconnected" -> shell(
            ResultPage(result(StreamResultKind.HOST_CLOSED, "The PC ended the stream", "", null), log("reconnect"), log("back to library")),
        )
        "problem" -> shell(
            ProblemPage(
                result(
                    StreamResultKind.FAILED_TO_START, "Couldn't start the stream", "The PC didn't answer in time.",
                    "Failed to start video stream (error -408)\n\nConnection Timeout",
                ),
                log("try again"), log("back"),
            ),
        )
        "worst_error" -> shell(
            ProblemPage(
                result(
                    StreamResultKind.FAILED_TO_START, "Couldn't start the stream", WORST_ERROR,
                    "Failed to start video stream (error 110)",
                ),
                log("try again"), log("back"),
            ),
            pc = LONG_PC,
        )
        "report" -> shell(
            ReportPage(
                "Delos had a problem", "Delos crashed in its native code the last time.", "Native crash: SIGSEGV (SEGV_MAPERR)",
                log("report"), log("dismiss"),
            ),
        )
        else -> null
    }

    private const val LONG_PC = "The extraordinarily long living-room gaming workstation for the entire household and all our shared adventures \u2014 primary desktop PC"
    private const val LONG_GAME = "The Remarkably Extended Chronicles of an Improbable Journey Across Seven Shattered Kingdoms: Definitive Collector's Edition"

    /** 610 characters: the prototype's worst-case error, far beyond any panel's budget. */
    private const val WORST_ERROR = "The connection to the extraordinarily long living-room gaming workstation timed out while waiting for the first video frame after the host " +
        "accepted the launch request. The host remained reachable during the initial handshake but did not deliver a usable video frame before the connection deadline. " +
        "The game may still be running on the host and its status must be refreshed rather than inferred from this timeout. This example intentionally contains a long " +
        "technical explanation and repeated context that must not take over the companion screen. Check the host service, firewall and network before trying again."

    private fun shell(page: CompanionPage, pc: String = "GAMING-PC") = CompanionState.Shell(pc, listOf(page))

    private fun loader(stage: StartStage) = FlowLoader("Asterfall", "GAMING-PC", "0c3e2a71-77d1-4b8e-91a4-6f5d3b7e2a10", "1920 \u00d7 1080 \u00b7 HEVC \u00b7 120 FPS", stage)

    private fun result(kind: StreamResultKind, title: String, sentence: String, details: String?) =
        FlowResult(kind, title, sentence, details, "Asterfall", "GAMING-PC", "0c3e2a71-77d1-4b8e-91a4-6f5d3b7e2a10")

    private fun log(what: String): () -> Unit = { Log.i("CompanionPreview", what) }
}
