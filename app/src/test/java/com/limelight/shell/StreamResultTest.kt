package com.limelight.shell

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.limelight.nvstream.jni.MoonBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StreamFailureTextTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources

    private fun start(code: Int, ports: String? = null, blocked: Boolean = false, decoder: Boolean = false, stage: String = "RTSP handshake") =
        describeStreamFailure(res, stage, code, ports, blocked, decoder)

    private fun lost(code: Int, ports: String? = null, blocked: Boolean = false) = describeStreamFailure(res, null, code, ports, blocked)

    @Test
    fun aFailedStageNamesTheStageAndCodeInTheDetailsOnly() {
        val text = start(-1)
        assertEquals("Couldn't start the stream", text.title)
        assertEquals("Something went wrong while connecting to the PC.", text.sentence)
        assertEquals("Failed to start RTSP handshake (error -1)", text.details)
        assertFalse("RTSP" in text.sentence || "-1" in text.sentence)
    }

    @Test
    fun permissionDeniedAndTimeoutKeepTheirHints() {
        val denied = start(403)
        assertEquals("The PC didn't allow Delos to start the app.", denied.sentence)
        assertTrue(denied.details.endsWith("Permission Denied (Launch Apps)"))

        val timeout = start(-408)
        assertEquals("The PC didn't answer in time.", timeout.sentence)
        assertTrue(timeout.details.endsWith("Connection Timeout"))
    }

    @Test
    fun aVideoStageThatCannotStartBlamesTheDecoder() {
        val text = start(-1, stage = "video stream", decoder = true)
        assertEquals("This device couldn't start the video decoder.", text.sentence)
        assertTrue("Your device may not support the selected resolution or frame rate." in text.details)
        // Without a valid surface it is an ordinary failure
        assertEquals("Something went wrong while connecting to the PC.", start(-1, stage = "video stream").sentence)
    }

    @Test
    fun portsAndABlockedNetworkAreHintsInTheDetails() {
        val text = start(-1, ports = "TCP 47984\nUDP 47998", blocked = true)
        assertEquals("This network seems to block Delos.", text.sentence)
        assertTrue("Streaming over the Internet may not work while connected to this network." in text.details)
        assertTrue(text.details.endsWith("Check your firewall and port forwarding rules for port(s):\nTCP 47984\nUDP 47998"))
        // A permission error is more specific than a network that may be blocking
        assertEquals("The PC didn't allow Delos to start the app.", start(403, blocked = true).sentence)
    }

    @Test
    fun noVideoAndSlowNetworkMeanTheConnectionWasLost() {
        val none = lost(MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC)
        assertEquals("Connection lost", none.title)
        assertEquals("No video arrived from the PC.", none.sentence)
        assertEquals("No video received from host.", none.details)

        val slow = lost(MoonBridge.ML_ERROR_NO_VIDEO_FRAME)
        assertEquals("Connection lost", slow.title)
        assertEquals("The network connection isn't keeping up.", slow.sentence)
        assertTrue("Reduce your video bitrate setting" in slow.details)
    }

    @Test
    fun hostSideErrorsSayThePcClosedTheStream() {
        for (code in intArrayOf(MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION, MoonBridge.ML_ERROR_PROTECTED_CONTENT)) {
            val text = lost(code)
            assertEquals("The PC closed the stream", text.title)
            assertEquals("Something went wrong on the PC while starting the stream.", text.sentence)
            assertTrue("DRM-protected content" in text.details)
        }
        val encoding = lost(MoonBridge.ML_ERROR_FRAME_CONVERSION)
        assertEquals("The PC closed the stream", encoding.title)
        assertEquals("The PC reported a video encoding error.", encoding.sentence)
        assertTrue("Try disabling HDR mode" in encoding.details)
    }

    @Test
    fun anUnknownTerminationShowsSmallCodesInDecimalAndLargeOnesInHex() {
        val small = lost(-7)
        assertEquals("Connection lost", small.title)
        assertEquals("The connection to the PC ended unexpectedly.", small.sentence)
        assertEquals("The connection was terminated.\n\nError code: -7", small.details)
        assertTrue(lost(0x1234).details.endsWith("Error code: 1234"))
        assertTrue(lost(-0x2000).details.endsWith("Error code: ffffe000"))
    }

    @Test
    fun aBlockedNetworkSupersedesAnyOtherTerminationReason() {
        val text = lost(MoonBridge.ML_ERROR_NO_VIDEO_FRAME, ports = "UDP 47998", blocked = true)
        assertEquals("Connection lost", text.title)
        assertEquals("This network seems to block Delos.", text.sentence)
        assertTrue("is blocking Delos" in text.details)
        assertFalse("Reduce your video bitrate" in text.details)
        assertTrue(text.details.endsWith("port(s):\nUDP 47998"))
    }

    @Test
    fun noH264DecoderAndShortcutFailuresHaveTheirOwnWords() {
        val decoder = describeNoH264Decoder(res)
        assertEquals("Couldn't start the stream", decoder.title)
        assertTrue("H.264" in decoder.details)

        val offline = describeShortcutFailure(res, "Desk", ShortcutFailure.OFFLINE)
        assertEquals("Desk is offline.", offline.sentence)
        assertEquals("Computer is offline", offline.details)
        val unpaired = describeShortcutFailure(res, "Desk", ShortcutFailure.NOT_PAIRED)
        assertEquals("Desk isn't paired with this device.", unpaired.sentence)
        assertEquals("PC not paired", unpaired.details)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StreamResultIntentTest {
    private val result = StreamResult(
        StreamResultKind.CONNECTION_LOST, "Connection lost", "The connection to the PC ended unexpectedly.", "Error code: -7",
        "pc-uuid", "Desk", 1234, "Game",
    )

    @Test
    fun aResultReachesTheShellOnceAndIsClearedFromTheIntent() {
        val intent = result.putInto(Intent())
        val taken = StreamResult.take(intent)
        assertNotNull(taken)
        assertEquals(result.copy(id = taken!!.id), taken)
        // The intent stays with the activity (recreation, resume): the result is gone from it
        assertNull(StreamResult.take(intent))
    }

    @Test
    fun anIntentWithoutAResultOrWithAnUnknownKindGivesNothing() {
        assertNull(StreamResult.take(null))
        assertNull(StreamResult.take(Intent()))
        assertNull(StreamResult.take(result.putInto(Intent()).putExtra("DelosResultKind", "SOMETHING_ELSE")))
    }

    @Test
    fun aResultWithoutDetailsKeepsThemNull() {
        val closed = StreamResult(StreamResultKind.HOST_CLOSED, hostClosedToast(ApplicationProvider.getApplicationContext<Context>().resources), "", null, "pc-uuid", "Desk", -1, "")
        val taken = StreamResult.take(closed.putInto(Intent()))!!
        assertEquals(StreamResultKind.HOST_CLOSED, taken.kind)
        assertNull(taken.details)
        assertEquals("The PC ended the stream", taken.title)
    }
}
