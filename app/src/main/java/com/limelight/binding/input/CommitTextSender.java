package com.limelight.binding.input;

import android.os.Handler;

import com.limelight.nvstream.input.KeyboardPacket;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Sends IME commit text to the host as UTF-8 chunks of at most {@link #UTF8_CHUNK_SIZE} bytes,
 * one chunk every {@link #FLUSH_INTERVAL_MS} ms on the given handler, and turns deleted
 * preceding characters into backspace key presses.
 */
public final class CommitTextSender {

    /** Receives one UTF-8 text chunk. */
    public interface TextSink {
        void sendUtf8Text(String text);
    }

    /** Receives one key event (translated key code and {@link KeyboardPacket} direction). */
    public interface KeySink {
        void sendKey(short keyCode, byte keyDirection);
    }

    static final int UTF8_CHUNK_SIZE = 512;
    private static final long FLUSH_INTERVAL_MS = 15;

    private final Queue<String> queue = new ArrayDeque<>();
    private final Handler handler;
    private final TextSink textSink;
    private final KeySink keySink;

    private final Runnable flushQueue = new Runnable() {
        @Override
        public void run() {
            if (queue.isEmpty()) {
                return;
            }
            textSink.sendUtf8Text(queue.poll());
            if (!queue.isEmpty()) {
                handler.postDelayed(this, FLUSH_INTERVAL_MS);
            }
        }
    };

    public CommitTextSender(Handler handler, TextSink textSink, KeySink keySink) {
        this.handler = handler;
        this.textSink = textSink;
        this.keySink = keySink;
    }

    /** Queues the text in chunks and starts flushing it. Must be called on the handler's thread. */
    public void enqueue(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        // Kick off flushing if not already scheduled: a pending flush keeps going while the
        // queue is not empty, so only an empty queue needs a new one
        boolean idle = queue.isEmpty();
        queue.addAll(splitUtf8(text));
        if (idle) {
            handler.post(flushQueue);
        }
    }

    /** Sends {@code count} presses (down and up) of the given backspace key code. */
    public void sendBackspaces(short backspaceCode, int count) {
        for (int i = 0; i < count; i++) {
            keySink.sendKey(backspaceCode, KeyboardPacket.KEY_DOWN);
            keySink.sendKey(backspaceCode, KeyboardPacket.KEY_UP);
        }
    }

    /**
     * Splits the text into strings of at most {@link #UTF8_CHUNK_SIZE} UTF-8 bytes, never cutting
     * inside a multi-byte sequence. A code point has at most 3 continuation bytes, so the cut point
     * always stays well after the chunk start.
     */
    static List<String> splitUtf8(String text) {
        List<String> chunks = new ArrayList<>();
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        int offset = 0;
        while (offset < utf8.length) {
            int end = Math.min(offset + UTF8_CHUNK_SIZE, utf8.length);
            // Ensure we don't cut inside a multi-byte sequence
            while (end < utf8.length && (utf8[end] & 0xC0) == 0x80) {
                end--; // step back until we are at start of code point
            }
            chunks.add(new String(utf8, offset, end - offset, StandardCharsets.UTF_8));
            offset = end;
        }
        return chunks;
    }
}
