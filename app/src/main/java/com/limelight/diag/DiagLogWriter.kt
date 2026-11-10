package com.limelight.diag

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The rolling diagnostic log on disk: two files of at most [maxFileBytes] each in [dir] (`diag-0.log` is the one
 * being written, `diag-1.log` the previous one). Writing happens on its own low-priority thread; [offer] never
 * blocks, a full queue drops the line (and a later line says how many were lost), so a flood of messages can
 * never stall the stream or the render thread.
 */
class DiagLogWriter(
    private val dir: File,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    capacity: Int = QUEUE_CAPACITY,
    start: Boolean = true,
) {
    private sealed interface Item
    private class Line(val text: String) : Item
    private class Barrier(val done: CountDownLatch) : Item

    private val queue = ArrayBlockingQueue<Item>(capacity)
    private val dropped = AtomicInteger()
    private val fileLock = Any()
    private var stream: BufferedOutputStream? = null
    private var written = 0L

    private val thread = Thread({ runLoop() }, "DiagLog").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
    }

    init {
        dir.mkdirs()
        if (start) start()
    }

    /** Starts the writer thread (the constructor does unless told not to; tests hold it back to fill the queue). */
    fun start() {
        if (!thread.isAlive) thread.start()
    }

    /** Queues one finished line (without its newline). Returns false when it was dropped. */
    fun offer(line: String): Boolean {
        if (queue.offer(Line(line))) return true
        dropped.incrementAndGet()
        return false
    }

    /** Waits until every line queued so far is on disk (at most [timeoutMs]). */
    fun flush(timeoutMs: Long = 2_000): Boolean {
        val barrier = Barrier(CountDownLatch(1))
        if (!queue.offer(barrier, timeoutMs, TimeUnit.MILLISECONDS)) return false
        return barrier.done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Everything on disk, the older file first, exactly as written. */
    fun readAll(): String {
        flush()
        synchronized(fileLock) {
            stream?.flush()
            return listOf(older(), current()).filter { it.isFile }.joinToString("") { it.readText(Charsets.UTF_8) }
        }
    }

    /** The disk size of the log in bytes. */
    fun sizeBytes(): Long = synchronized(fileLock) { listOf(older(), current()).sumOf { if (it.isFile) it.length() else 0L } }

    /** Deletes both files; lines queued meanwhile start a new log. */
    fun clear() {
        flush()
        synchronized(fileLock) {
            closeStream()
            older().delete()
            current().delete()
            written = 0
        }
    }

    private fun runLoop() {
        while (true) {
            val item = try { queue.take() } catch (_: InterruptedException) { return }
            when (item) {
                is Line -> {
                    synchronized(fileLock) {
                        val lost = dropped.getAndSet(0)
                        if (lost > 0) append("--- $lost log lines dropped (queue full) ---")
                        append(item.text)
                    }
                }
                is Barrier -> {
                    synchronized(fileLock) { stream?.flush() }
                    item.done.countDown()
                }
            }
            // Flush the buffered bytes whenever the queue runs dry, so a crash loses almost nothing
            if (queue.isEmpty()) synchronized(fileLock) { stream?.flush() }
        }
    }

    private fun append(text: String) {
        try {
            val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
            if (stream == null) open()
            if (written > 0 && written + bytes.size > maxFileBytes) rotate()
            stream!!.write(bytes)
            written += bytes.size
        } catch (_: IOException) {
            // Diagnostics must never become a problem of their own: close the stream and try again with the next line
            closeStream()
        }
    }

    private fun open() {
        dir.mkdirs()
        val file = current()
        written = if (file.isFile) file.length() else 0L
        stream = BufferedOutputStream(FileOutputStream(file, true), 8 * 1024)
    }

    private fun rotate() {
        closeStream()
        older().delete()
        current().renameTo(older())
        open()
    }

    private fun closeStream() {
        try { stream?.close() } catch (_: IOException) { }
        stream = null
    }

    private fun current() = File(dir, "diag-0.log")
    private fun older() = File(dir, "diag-1.log")

    companion object {
        /** Each of the two log files. */
        const val MAX_FILE_BYTES = 1L shl 20

        /** Lines waiting for the writer thread; more than this are dropped. */
        const val QUEUE_CAPACITY = 1024
    }
}
