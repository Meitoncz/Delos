package com.limelight.shell

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.input.KeyboardType
import com.limelight.shell.data.Library
import com.limelight.shell.data.Pc
import com.limelight.shell.data.PcRepository
import com.limelight.shell.data.PcSetup
import com.limelight.shell.link.LinkTestResult
import com.limelight.shell.link.LinkTestStore
import com.limelight.shell.link.LinkTester
import com.limelight.shell.link.currentTransport
import com.limelight.shell.link.mbps
import com.limelight.shell.settings.StartupStore
import com.limelight.shell.ui.Cue
import com.limelight.shell.ui.Feedback
import com.limelight.shell.ui.Symbols
import com.limelight.utils.ToastKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the screens can do with PCs (implemented by the shell). */
interface DeviceActions {
    /** Opens home for [pc] through the portal; an offline PC is woken up first and waited for. */
    fun connect(pc: Pc)

    /** Pairs [pc] with a PIN shown on the screen; Back and a failure return to [from]. */
    fun pair(pc: Pc, from: Screen)

    /** Asks for a one-time code from Vibepollo, then pairs [pc] with it. */
    fun pairWithCode(pc: Pc, from: Screen)

    /** Asks for an address, adds that PC and pairs it. */
    fun addByAddress(from: Screen)

    /** Adds the PC at [address] and pairs it, with [otp] if given (else a PIN); a paired one opens right away. */
    fun addAndPair(address: String, otp: Otp?, from: Screen)

    /** Called by the PIN screen once [pcUuid] is paired: on into home. */
    fun paired(pcUuid: String)

    fun rename(pc: Pc)
    fun wake(pc: Pc)
    fun unpair(pc: Pc)
    fun remove(pc: Pc)

    /** The PC to connect to automatically at start, or none. */
    fun setDefault(pc: Pc?)

    /** Searches the local network again. */
    fun rescan()

    /**
     * Measures what the network carries from [pc] (PyroWave link test), starting at [startKbps]: a cancellable portal says
     * what is being tested, B stops it, and the result is stored with its date. It never changes a setting.
     */
    fun testLink(pc: Pc, startKbps: Int)

    /** Cancels a portal that is still waiting (B while a PC wakes up, or a link test). */
    fun cancelPortal()
}

val LocalDeviceActions = staticCompositionLocalOf<DeviceActions> { error("DeviceActions not provided") }

/** How long the portal waits for a PC to wake up. */
private const val WAKE_TIMEOUT_MS = 120_000L

/** How long the portal waits at most for the PC's games before it opens home anyway. */
private const val LIBRARY_TIMEOUT_MS = 5_000L

/** The shell's [DeviceActions]: portal, toasts and dialogs, no confirmation prompts. */
class ShellDeviceActions internal constructor(
    private val shell: ShellState,
    private val tracker: FocusTracker,
    private val repo: PcRepository,
    private val setup: PcSetup,
    private val startup: StartupStore,
    private val feedback: Feedback,
    private val scope: CoroutineScope,
    private val linkTester: LinkTester,
    private val linkTests: LinkTestStore,
    private val reducedMotion: () -> Boolean,
) : DeviceActions {
    private var portals = 0
    private var portalJob: Job? = null

    override fun connect(pc: Pc) {
        if (shell.portal != null) return
        repo.selectPc(pc.uuid)
        portalJob = scope.launch {
            var arrived = false
            val id = ++portals
            try {
                feedback.play(Cue.OPEN)
                if (!pc.online) {
                    val error = setup.wake(pc)
                    shell.portal = Portal(
                        id, Symbols.WAKE,
                        if (error == null) "Waking up ${pc.name}" else "Waiting for ${pc.name}",
                        if (error == null) "Wake-up signal sent · B cancels" else "Turn the PC on · B cancels",
                        cancellable = true,
                    )
                    if (repo.awaitPc(pc.uuid, WAKE_TIMEOUT_MS) { it.online } == null) {
                        feedback.play(Cue.ERROR)
                        shell.showToast("${pc.name} did not wake up", Symbols.WAKE, kind = ToastKind.ERROR)
                        return@launch
                    }
                }
                shell.portalBurst = false
                shell.portal = Portal(id, Symbols.DESKTOP_WINDOWS, "Connecting to ${pc.name}", "Vibepollo · paired", cancellable = false)
                // The prototype's 2.1 s in the portal, and the PC's games ready by then if possible
                val minimum = async { delay(if (reducedMotion()) 600L else 2100L) }
                withTimeoutOrNull(LIBRARY_TIMEOUT_MS) { repo.library.first { it is Library.Ready && it.pc.uuid == pc.uuid } }
                minimum.await()
                shell.portalBurst = true
                feedback.play(Cue.SELECT)
                delay(380)
                arrived = true
                shell.navigate(Screen.Home)
                shell.portal = null
                delay(500)
                shell.portalBurst = false
            } finally {
                if (!arrived) {
                    shell.portal = null
                    shell.portalBurst = false
                }
            }
        }
    }

    override fun testLink(pc: Pc, startKbps: Int) {
        if (shell.portal != null) return
        // The portal holds the focus; the row that started the test gets it back afterwards
        val returnTo = tracker.requester
        portalJob = scope.launch {
            val id = ++portals
            val context = linkTester.context
            val transport = currentTransport(context)
            val show = { line: String ->
                shell.portalBurst = false
                shell.portal = Portal(id, Symbols.LINK_TEST, "Testing the link to ${pc.name}", line, cancellable = true)
            }
            try {
                feedback.play(Cue.OPEN)
                show("Stay on ${transport.label} · B cancels")
                val result = linkTester.run(pc, startKbps) { step ->
                    scope.launch {
                        if (shell.portal?.id == id) show("${if (step.confirming) "Confirming" else "Testing"} ${mbps(step.kbps)} · stay on ${transport.label} · B cancels")
                    }
                }
                when (result) {
                    is LinkTestResult.Done -> {
                        linkTests.put(pc.uuid, result.test)
                        feedback.play(Cue.SELECT)
                        shell.showToast("${transport.label} carries ${mbps(result.test.wireKbps)}${if (result.jitter) " (a little jitter)" else ""}", Symbols.CHECK_CIRCLE, kind = ToastKind.SUCCESS)
                    }
                    is LinkTestResult.Failed -> {
                        feedback.play(Cue.ERROR)
                        shell.showToast(result.text, Symbols.ERROR, kind = ToastKind.ERROR)
                    }
                    LinkTestResult.Cancelled -> shell.showToast("Link test stopped", Symbols.INFO)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                shell.showToast("Link test stopped", Symbols.INFO)
                throw e
            } finally {
                shell.portal = null
                shell.portalBurst = false
                returnTo?.let {
                    feedback.silenceNextFocus()
                    runCatching { it.requestFocus() }
                }
            }
        }
    }

    override fun cancelPortal() {
        if (shell.portal?.cancellable != true) return
        feedback.play(Cue.BACK)
        portalJob?.cancel()
    }

    override fun pair(pc: Pc, from: Screen) {
        shell.navigate(Screen.Pin(pc.uuid, pc.name, null, from))
    }

    override fun pairWithCode(pc: Pc, from: Screen) {
        openDialog(
            DialogSpec(
                "Pair with a one-time code",
                "In the Vibepollo web UI, create a one-time PIN with a passphrase, then enter both here.",
                listOf(
                    DialogField(Symbols.DIALPAD, placeholder = "PIN", keyboard = KeyboardType.NumberPassword, maxLength = 4),
                    DialogField(Symbols.PASSWORD, placeholder = "Passphrase", password = true, maxLength = 64),
                ),
                "Pair",
            ) { (pin, passphrase) ->
                when {
                    pin.length != 4 -> error("The PIN has 4 digits")
                    passphrase.length < 4 -> error("The passphrase has at least 4 characters")
                    else -> {
                        shell.navigate(Screen.Pin(pc.uuid, pc.name, Otp(pin, passphrase), from))
                        true
                    }
                }
            }
        )
    }

    override fun addByAddress(from: Screen) {
        openDialog(
            DialogSpec(
                "Enter the PC address",
                "The IP address or host name of the PC running Vibepollo, optionally with a port.",
                listOf(DialogField(Symbols.MAP_PIN, placeholder = "192.168.1.20", keyboard = KeyboardType.Uri, maxLength = 64)),
                "Add PC",
            ) { (address) ->
                if (address.isBlank()) return@DialogSpec error("Enter an address")
                addAndPair(address.trim(), null, from)
                true
            }
        )
    }

    override fun addAndPair(address: String, otp: Otp?, from: Screen) {
        scope.launch {
            shell.showToast("Looking for $address…", Symbols.SEARCH)
            setup.add(address).fold(
                onSuccess = { uuid ->
                    val pc = repo.awaitPc(uuid, 5_000) { true }
                    when {
                        pc == null -> error("$address answered, but could not be added")
                        pc.paired -> connect(pc)
                        else -> shell.navigate(Screen.Pin(pc.uuid, pc.name, otp, from))
                    }
                },
                onFailure = { error(it.message ?: "No PC answered at $address") },
            )
        }
    }

    override fun paired(pcUuid: String) {
        scope.launch {
            // The service reads the new pair state on its next poll
            val pc = repo.awaitPc(pcUuid, 8_000) { it.paired } ?: repo.pcs.value.firstOrNull { it.uuid == pcUuid }
            if (pc != null) connect(pc) else shell.navigate(Screen.Start)
        }
    }

    override fun rename(pc: Pc) {
        openDialog(
            DialogSpec(
                "Rename PC",
                "The new name is only used in Delos on this device. Leave it empty for the PC's own name (${pc.serverName}).",
                listOf(DialogField(Symbols.EDIT, initial = pc.name, maxLength = 24)),
                "Save",
            ) { (name) ->
                repo.rename(pc.uuid, name)
                feedback.play(Cue.SELECT)
                shell.showToast(if (name.isBlank()) "Back to ${pc.serverName}" else "Renamed to ${name.trim()}", Symbols.CHECK_CIRCLE)
                true
            }
        )
    }

    override fun wake(pc: Pc) {
        scope.launch {
            val error = setup.wake(pc)
            if (error != null) feedback.play(Cue.ERROR)
            shell.showToast(error ?: "Wake-up signal sent to ${pc.name}", Symbols.WAKE, kind = if (error != null) ToastKind.ERROR else ToastKind.INFO)
        }
    }

    override fun unpair(pc: Pc) {
        scope.launch {
            val (ok, message) = setup.unpair(pc)
            if (!ok) feedback.play(Cue.ERROR)
            shell.showToast(message, if (ok) Symbols.LINK_OFF else Symbols.ERROR, kind = if (ok) ToastKind.SUCCESS else ToastKind.ERROR)
        }
    }

    override fun remove(pc: Pc) {
        setup.remove(pc)
        if (startup.startup.value.defaultPc == pc.uuid) startup.update { it.copy(defaultPc = null, skipStart = false) }
        shell.showToast("${pc.name} was removed", Symbols.DELETE)
    }

    override fun setDefault(pc: Pc?) {
        startup.update { it.copy(defaultPc = pc?.uuid, skipStart = pc != null && it.skipStart) }
        shell.showToast(if (pc != null) "Delos starts with ${pc.name}" else "No default device", Symbols.STAR)
    }

    override fun rescan() {
        repo.rescan()
    }

    private fun openDialog(spec: DialogSpec) {
        feedback.play(Cue.OPEN)
        shell.openLayer(Layer.Dialog(spec), tracker)
    }

    /** Shows [message] as an error toast; false keeps a dialog open. */
    private fun error(message: String): Boolean {
        feedback.play(Cue.ERROR)
        shell.showToast(message, Symbols.ERROR)
        return false
    }
}
