package com.limelight.binding.input;

import android.os.Handler;
import android.os.Looper;

import com.limelight.nvstream.input.KeyboardPacket;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class CommitTextSenderTest {
    private static final int MAX = CommitTextSender.UTF8_CHUNK_SIZE;

    private static String repeat(String s, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(s);
        return sb.toString();
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Every chunk fits, no chunk is empty, no chunk is cut inside a code point, nothing is lost. */
    private static List<String> splitChecked(String text) {
        List<String> chunks = CommitTextSender.splitUtf8(text);
        StringBuilder joined = new StringBuilder();
        for (String chunk : chunks) {
            int bytes = utf8Length(chunk);
            assertTrue("chunk too long: " + bytes, bytes <= MAX);
            assertTrue("empty chunk", bytes > 0);
            assertFalse("chunk cut inside a code point", chunk.contains("\uFFFD"));
            joined.append(chunk);
        }
        assertEquals(text, joined.toString());
        return chunks;
    }

    @Test(timeout = 5000)
    public void emptyText_hasNoChunks() {
        assertTrue(CommitTextSender.splitUtf8("").isEmpty());
    }

    @Test(timeout = 5000)
    public void ascii_splitsAtTheLimit() {
        List<String> chunks = splitChecked(repeat("a", 1000));
        assertEquals(2, chunks.size());
        assertEquals(MAX, utf8Length(chunks.get(0)));
        assertEquals(1000 - MAX, utf8Length(chunks.get(1)));
    }

    @Test(timeout = 5000)
    public void shortText_isOneChunk() {
        assertEquals(List.of("hello"), splitChecked("hello"));
    }

    @Test(timeout = 5000)
    public void twoByteSequence_acrossTheBoundary_movesToTheNextChunk() {
        List<String> chunks = splitChecked(repeat("a", MAX - 1) + "\u00e9" + "b");
        assertEquals(MAX - 1, utf8Length(chunks.get(0)));
        assertEquals("\u00e9b", chunks.get(1));
    }

    @Test(timeout = 5000)
    public void twoByteSequence_endingAtTheBoundary_staysInTheChunk() {
        List<String> chunks = splitChecked(repeat("a", MAX - 2) + "\u00e9" + "b");
        assertEquals(MAX, utf8Length(chunks.get(0)));
        assertEquals("b", chunks.get(1));
    }

    @Test(timeout = 5000)
    public void threeByteSequence_acrossTheBoundary_movesToTheNextChunk() {
        List<String> first = splitChecked(repeat("a", MAX - 2) + "\u20ac" + "b");
        assertEquals(MAX - 2, utf8Length(first.get(0)));
        assertEquals("\u20acb", first.get(1));

        List<String> second = splitChecked(repeat("a", MAX - 1) + "\u20ac" + "b");
        assertEquals(MAX - 1, utf8Length(second.get(0)));
        assertEquals("\u20acb", second.get(1));
    }

    @Test(timeout = 5000)
    public void fourByteEmoji_acrossTheBoundary_movesToTheNextChunk() {
        String emoji = "\uD83D\uDE00"; // U+1F600
        for (int pad = 1; pad <= 3; pad++) {
            List<String> chunks = splitChecked(repeat("a", MAX - pad) + emoji + "b");
            assertEquals(MAX - pad, utf8Length(chunks.get(0)));
            assertEquals(emoji + "b", chunks.get(1));
        }
    }

    @Test(timeout = 5000)
    public void onlyEmoji_splitsOnCodePoints() {
        String emoji = "\uD83D\uDE00";
        List<String> chunks = splitChecked(repeat(emoji, 300)); // 1200 bytes
        assertEquals(3, chunks.size());
        assertEquals(MAX, utf8Length(chunks.get(0)));
    }

    @Test(timeout = 5000)
    public void onlyThreeByteChars_alwaysProgresses() {
        // 512 is not a multiple of 3: every cut walks back over continuation bytes
        List<String> chunks = splitChecked(repeat("\u20ac", 400)); // 1200 bytes
        assertEquals(510, utf8Length(chunks.get(0)));
        assertEquals(3, chunks.size());
    }

    @Test(timeout = 5000)
    public void mixedWidths_atEveryAlignment_alwaysProgress() {
        String mixed = "a\u00e9\u20ac\uD83D\uDE00";
        for (int pad = 0; pad < 10; pad++) {
            splitChecked(repeat("a", pad) + repeat(mixed, 200));
        }
    }

    @Test(timeout = 5000)
    public void loneSurrogate_isEncodedAsReplacementByJava_andTerminates() {
        // String.getBytes never yields invalid UTF-8, so the walk-back cannot reach the chunk start
        List<String> chunks = CommitTextSender.splitUtf8(repeat("a", MAX - 1) + "\uD800" + "b");
        StringBuilder joined = new StringBuilder();
        for (String chunk : chunks) {
            assertTrue(utf8Length(chunk) <= MAX);
            joined.append(chunk);
        }
        assertEquals(repeat("a", MAX - 1) + "?b", joined.toString());
    }

    @Test
    public void shortText_isSentOnTheHandler() {
        List<String> sent = new ArrayList<>();
        CommitTextSender sender = new CommitTextSender(new Handler(Looper.getMainLooper()), sent::add, (k, d) -> { });
        sender.enqueue("hi");
        sender.enqueue("");
        sender.enqueue(null);
        assertTrue(sent.isEmpty());
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of("hi"), sent);
    }

    @Test
    public void backspaces_sendDownAndUpPerCharacter() {
        List<String> keys = new ArrayList<>();
        CommitTextSender sender = new CommitTextSender(new Handler(Looper.getMainLooper()), t -> { },
                (k, d) -> keys.add(k + ":" + d));
        sender.sendBackspaces((short) 8, 2);
        String down = "8:" + KeyboardPacket.KEY_DOWN;
        String up = "8:" + KeyboardPacket.KEY_UP;
        assertEquals(List.of(down, up, down, up), keys);
    }
}
