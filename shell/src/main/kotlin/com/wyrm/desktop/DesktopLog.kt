package com.wyrm.desktop

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The app's own log on disk (D6, 2026-10-12), beside the engine's: every
 * line the shell prints (System.out / System.err, android.util.Log) also goes
 * to %LOCALAPPDATA%\Wyrm\logs\app.log, flushed per line. The last launch's is
 * kept as app.previous.log, so a crash report can carry what happened before
 * the crash. The engine writes engine.log the same way (desktop_bridge.c).
 */
object DesktopLog {
    val folder: File = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Wyrm/logs").apply { mkdirs() }
    private val file = File(folder, "app.log")
    private var out: FileOutputStream? = null
    private var bytes = 0L
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private var installed = false

    /** Once, first thing in main: rotate the last launch's file, then tee stdout and stderr. */
    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        val previous = File(folder, "app.previous.log")
        runCatching { previous.delete(); if (file.exists()) file.renameTo(previous) }
        out = runCatching { FileOutputStream(file, false) }.getOrNull()
        System.setOut(PrintStream(Tee(System.out), true, Charsets.UTF_8))
        System.setErr(PrintStream(Tee(System.err), true, Charsets.UTF_8))
    }

    @Synchronized
    private fun append(chunk: ByteArray, offset: Int, length: Int) {
        val stream = out ?: return
        runCatching {
            if (bytes > 4L * 1024 * 1024) {
                stream.close()
                out = FileOutputStream(file, false)
                bytes = 0
            }
            out!!.write(chunk, offset, length)
            out!!.flush()
            bytes += length
        }
    }

    /** Copies everything to the console as before, and each finished line, stamped, to the file. */
    private class Tee(private val console: OutputStream) : OutputStream() {
        private val pending = java.io.ByteArrayOutputStream()
        override fun write(b: Int) {
            console.write(b)
            if (b == '\n'.code) flushLine() else pending.write(b)
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            console.write(b, off, len)
            for (i in off until off + len) {
                if (b[i] == '\n'.code.toByte()) flushLine() else pending.write(b[i].toInt())
            }
        }
        override fun flush() = console.flush()
        private fun flushLine() {
            val text = pending.toString(Charsets.UTF_8).trimEnd('\r')
            pending.reset()
            val stamped = "${LocalTime.now().format(clock)} $text\n".toByteArray(Charsets.UTF_8)
            append(stamped, 0, stamped.size)
        }
    }

    /** The newest [limit] characters of a log file (empty when there is none). */
    fun tail(name: String, limit: Int): String = runCatching {
        val target = File(folder, name)
        if (!target.isFile) return ""
        java.io.RandomAccessFile(target, "r").use { raf ->
            val size = raf.length()
            val start = (size - limit * 2L).coerceAtLeast(0L)
            raf.seek(start)
            val bytes = ByteArray((size - start).toInt())
            raf.readFully(bytes)
            val text = String(bytes, Charsets.UTF_8)
            val cut = if (start > 0) text.substringAfter('\n') else text
            cut.takeLast(limit)
        }
    }.getOrDefault("")
}
