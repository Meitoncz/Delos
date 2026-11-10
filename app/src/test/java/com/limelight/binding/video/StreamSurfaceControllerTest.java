package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.limelight.binding.video.StreamSurfaceController.Resize;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.UpscalerMode;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Characterisation tests: the surface and upscaler decisions Game made before the controller was extracted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class StreamSurfaceControllerTest {
    private static final int SDR = MoonBridge.VIDEO_FORMAT_H265;
    private static final int HDR = MoonBridge.VIDEO_FORMAT_H265_MAIN10;

    private final PreferenceConfiguration prefs = new PreferenceConfiguration();
    private final SurfaceHolder holder = mock(SurfaceHolder.class);
    private final SurfaceView view = mock(SurfaceView.class);
    private final MediaCodecDecoderRenderer decoder = mock(MediaCodecDecoderRenderer.class);
    private final FakeHost host = new FakeHost();
    private StreamSurfaceController controller;

    private final class FakeHost implements StreamSurfaceController.Host {
        SurfaceView surfaceView = view;
        final Surface surface = mock(Surface.class);
        boolean connected;
        final List<String> calls = new ArrayList<>();

        @Override public SurfaceView streamSurfaceView() { return surfaceView; }
        @Override public Surface activeSurface() { return surface; }
        @Override public int displayWidth() { return 1920; }
        @Override public int displayHeight() { return 1080; }
        @Override public boolean isConnected() { return connected; }
        @Override public void startConnection() { calls.add("start"); }
        @Override public void stopConnection() { calls.add("stop"); }
    }

    @Before
    public void setUp() {
        when(view.getHolder()).thenReturn(holder);
        prefs.width = 1280;
        prefs.height = 720;
        prefs.sgsrMode = UpscalerMode.STANDARD;
        controller = new StreamSurfaceController(Runnable::run, prefs, host);
        controller.setDecoder(decoder);
    }

    // --- Pure decisions ---

    @Test
    public void onlySdrScalingUpscalerStartsLayoutSized() {
        for (UpscalerMode mode : UpscalerMode.values()) {
            assertEquals(mode.toString(), mode.scales(), StreamSurfaceController.layoutSizedAtStart(true, mode, false));
            assertFalse(mode.toString(), StreamSurfaceController.layoutSizedAtStart(true, mode, true));
            assertFalse(mode.toString(), StreamSurfaceController.layoutSizedAtStart(false, mode, false));
        }
    }

    @Test
    public void liveUpscalerResizeFollowsModeOnlyOnEightBitOutput() {
        assertEquals(Resize.LAYOUT, StreamSurfaceController.resizeForUpscaler(SDR, UpscalerMode.STANDARD));
        assertEquals(Resize.LAYOUT, StreamSurfaceController.resizeForUpscaler(SDR, UpscalerMode.VIDEO_QUALITY));
        assertEquals(Resize.STREAM, StreamSurfaceController.resizeForUpscaler(SDR, UpscalerMode.SHARPEN_ONLY));
        assertEquals(Resize.STREAM, StreamSurfaceController.resizeForUpscaler(0, UpscalerMode.SHARPEN_ONLY));
        assertEquals(Resize.NONE, StreamSurfaceController.resizeForUpscaler(HDR, UpscalerMode.STANDARD));
        assertEquals(Resize.NONE, StreamSurfaceController.resizeForUpscaler(HDR, UpscalerMode.SHARPEN_ONLY));
        assertEquals(Resize.NONE, StreamSurfaceController.resizeForUpscaler(MoonBridge.VIDEO_FORMAT_AV1_MAIN10, UpscalerMode.HIGH));
    }

    @Test
    public void streamSurfaceSizeFallsBackToTheDisplay() {
        assertEquals(1280, StreamSurfaceController.streamSurfaceSize(1280, 1920));
        assertEquals(1920, StreamSurfaceController.streamSurfaceSize(0, 1920));
        assertEquals(1920, StreamSurfaceController.streamSurfaceSize(-1, 1920));
    }

    // --- Live upscaler controls ---

    @Test
    public void enablingAScalingModeSizesTheSurfaceFromLayoutBeforeTheDecoder() {
        when(decoder.getActiveVideoFormat()).thenReturn(SDR);
        controller.setUpscalingEnabled(true);
        InOrder order = inOrder(holder, decoder);
        order.verify(holder).setSizeFromLayout();
        order.verify(decoder).setUpscalingEnabled(true);
        verify(holder, never()).setFixedSize(1280, 720);
    }

    @Test
    public void enablingSharpenOnlyKeepsTheStreamSize() {
        prefs.sgsrMode = UpscalerMode.SHARPEN_ONLY;
        prefs.width = 0;
        when(decoder.getActiveVideoFormat()).thenReturn(SDR);
        controller.setUpscalingEnabled(true);
        InOrder order = inOrder(holder, decoder);
        order.verify(holder).setFixedSize(1920, 720);
        order.verify(decoder).setUpscalingEnabled(true);
        verify(holder, never()).setSizeFromLayout();
    }

    @Test
    public void disablingTheUpscalerOrTenBitOutputLeavesTheSurfaceAlone() {
        controller.setUpscalingEnabled(false);
        verify(decoder).setUpscalingEnabled(false);
        verifyNoInteractions(view);

        when(decoder.getActiveVideoFormat()).thenReturn(HDR);
        controller.setUpscalingEnabled(true);
        verify(decoder).setUpscalingEnabled(true);
        verifyNoInteractions(holder);
    }

    @Test
    public void noSurfaceViewSkipsTheResizeWithoutReadingTheFormat() {
        host.surfaceView = null;
        controller.setUpscalingEnabled(true);
        verify(decoder, never()).getActiveVideoFormat();
        verify(decoder).setUpscalingEnabled(true);
    }

    @Test
    public void modeChangeResizesOnlyWhileTheUpscalerIsRequested() {
        when(decoder.getActiveVideoFormat()).thenReturn(SDR);
        controller.setUpscalingMode(UpscalerMode.SHARPEN_ONLY.index());
        verify(decoder).setUpscalingMode(UpscalerMode.SHARPEN_ONLY.index());
        verifyNoInteractions(holder);

        when(decoder.isUpscalingRequested()).thenReturn(true);
        controller.setUpscalingMode(UpscalerMode.SHARPEN_ONLY.index());
        InOrder order = inOrder(holder, decoder);
        order.verify(holder).setFixedSize(1280, 720);
        order.verify(decoder).setUpscalingMode(UpscalerMode.SHARPEN_ONLY.index());

        controller.setUpscalingMode(UpscalerMode.VIDEO.index());
        order.verify(holder).setSizeFromLayout();
        order.verify(decoder).setUpscalingMode(UpscalerMode.VIDEO.index());
    }

    @Test
    public void sharpnessAndSupersamplingLevelNeverResizeTheSurface() {
        when(decoder.getActiveVideoFormat()).thenReturn(SDR);
        when(decoder.isUpscalingRequested()).thenReturn(true);
        controller.setSupersamplingLevel(2);
        controller.setSupersamplingLevel(1);
        controller.setUpscalingSharpness(70);
        verify(decoder).setSupersamplingLevel(2);
        verify(decoder).setSupersamplingLevel(1);
        verify(decoder).setUpscalingSharpness(70);
        verifyNoInteractions(view);
    }

    @Test
    public void readsComeFromTheDecoder() {
        when(decoder.isUpscalingRequested()).thenReturn(true);
        when(decoder.isPyroWave()).thenReturn(true);
        when(decoder.getUpscalingStatus()).thenReturn("On");
        when(decoder.isSupersamplingMode()).thenReturn(true);
        when(decoder.getSupersamplingLevel()).thenReturn(2);
        assertTrue(controller.isUpscalingRequested());
        assertTrue(controller.isPyroWave());
        assertEquals("On", controller.getUpscalingStatus());
        assertTrue(controller.isSupersamplingMode());
        assertEquals(2, controller.getSupersamplingLevel());
        assertSame(view, controller.getStreamSurface());
    }

    // --- Surface callbacks ---

    @Test
    public void firstAvailableSurfaceSetsTheRenderTargetThenStartsTheConnectionOnce() {
        controller.onSurfaceAvailable();
        controller.onSurfaceAvailable();
        verify(decoder).setRenderTarget(host.surface);
        assertEquals(List.of("start"), host.calls);
    }

    @Test
    public void surfaceCallbacksBeforeCreationThrow() {
        assertThrows(IllegalStateException.class, () -> controller.onSurfaceChanged(1920, 1080));
        assertThrows(IllegalStateException.class, () -> controller.onSurfaceDestroyed());
    }

    @Test
    public void destroyedSurfaceStopsTheDecoderAndAConnectedStream() {
        SurfaceHolder created = mock(SurfaceHolder.class);
        when(created.getSurface()).thenReturn(mock(Surface.class));
        controller.onSurfaceCreated(created, 60f);
        controller.onSurfaceChanged(1920, 1080);

        // No connection attempt yet: nothing to stop
        controller.onSurfaceDestroyed();
        verify(decoder, never()).prepareForStop();

        controller.onSurfaceAvailable();
        controller.onSurfaceDestroyed();
        verify(decoder).prepareForStop();
        assertEquals(List.of("start"), host.calls);

        host.connected = true;
        controller.onSurfaceDestroyed();
        assertEquals(List.of("start", "stop"), host.calls);
    }

    @Test
    public void hdrModeGoesToTheDecoder() {
        byte[] metadata = {1, 2};
        controller.setHdrMode(true, metadata);
        verify(decoder).setHdrMode(true, metadata);
    }

    // --- Decoder thread to UI thread handshake ---

    @Test
    public void layoutSizedSurfaceRunsOnTheUiExecutorAndWaitsForIt() throws Exception {
        List<Runnable> posted = new ArrayList<>();
        StreamSurfaceController posting = new StreamSurfaceController(posted::add, prefs, host);
        AtomicBoolean returned = new AtomicBoolean();
        Thread decoderThread = new Thread(() -> {
            posting.useLayoutSizedStreamSurface();
            returned.set(true);
        });
        decoderThread.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (posted.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertEquals(1, posted.size());
        Thread.sleep(50);
        assertFalse("the decoder thread waits for the UI thread", returned.get());
        posted.get(0).run();
        decoderThread.join(5000);
        assertTrue(returned.get());
        verify(holder).setSizeFromLayout();
    }

    @Test
    public void handshakeGivesUpAfterTheBoundedWait() {
        long start = System.nanoTime();
        StreamSurfaceController.runAndAwait(task -> { }, () -> { }, 100);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue("waited " + elapsedMs, elapsedMs >= 90 && elapsedMs < 2000);
        assertEquals(1000, StreamSurfaceController.RESIZE_WAIT_MS);
    }

    @Test
    public void failedResizeStillReleasesTheDecoderThread() throws Exception {
        Executor otherThread = task -> new Thread(task).start();
        long start = System.nanoTime();
        StreamSurfaceController.runAndAwait(otherThread, () -> { throw new IllegalStateException("boom"); }, 5000);
        assertTrue((System.nanoTime() - start) / 1_000_000 < 4000);
    }

    @Test
    public void interruptEndsTheWaitAndStaysSet() {
        Thread.currentThread().interrupt();
        StreamSurfaceController.runAndAwait(task -> { }, () -> { }, 5000);
        assertTrue(Thread.interrupted());
    }

    @Test
    public void noSurfaceViewStillReleasesTheHandshake() {
        host.surfaceView = null;
        controller.useLayoutSizedStreamSurface();
        verifyNoInteractions(holder);
    }

    // --- Launch arrival ---

    @Test
    public void launchArrivalBeforeTheDecoderExists() {
        StreamSurfaceController early = new StreamSurfaceController(Runnable::run, prefs, host);
        assertFalse(early.isLaunchFrameReady());
        assertFalse(early.isLaunchArrivalReady());
        assertTrue(early.isLaunchArrivalComplete());
        assertNull(early.getLaunchArrivalError());
        assertNull(early.getLinkMonitor());
        early.setLaunchArrivalProgress(.5f);
        early.reportLaunchArrivalError("none");
    }

    @Test
    public void launchArrivalFollowsTheDecoder() {
        when(decoder.hasPresentedFrame()).thenReturn(true);
        when(decoder.isLaunchArrivalReady()).thenReturn(true);
        when(decoder.isLaunchArrivalComplete()).thenReturn(false);
        when(decoder.getLaunchArrivalError()).thenReturn("gpu");
        assertTrue(controller.isLaunchFrameReady());
        assertTrue(controller.isLaunchArrivalReady());
        assertFalse(controller.isLaunchArrivalComplete());
        assertEquals("gpu", controller.getLaunchArrivalError());

        controller.setLaunchArrivalProgress(.25f);
        controller.setLaunchArrivalProgress(1f);
        controller.reportLaunchArrivalError("timed out");
        InOrder order = inOrder(decoder);
        order.verify(decoder).setLaunchArrivalProgress(.25f);
        order.verify(decoder).setLaunchArrivalProgress(1f);
        order.verify(decoder).setLaunchArrivalProgress(-1f);
    }
}
