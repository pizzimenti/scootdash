package io.github.pizzimenti.scootdash

import android.os.SystemClock
import java.util.ArrayDeque
import java.util.Locale

/**
 * Ring buffer of the conversation with the scooter, formatted for pasting into a chat.
 * TX lines show plaintext, a plain-language meaning and the wire bytes. Status frames
 * are logged only when something other than the two timers changes, plus a heartbeat
 * every 10 s, so the log stays readable.
 */
class LogBook {
    interface Listener { fun onLogChanged() }

    var listener: Listener? = null
    private val lines = ArrayDeque<String>()
    private var t0 = SystemClock.elapsedRealtime()
    private var lastStatus: Proto.Status? = null
    private var lastStatusLoggedAt = 0L
    private var suppressed = 0

    val size: Int get() = lines.size

    private fun stamp(): String {
        val ms = SystemClock.elapsedRealtime() - t0
        return String.format(Locale.US, "%9.3f", ms / 1000.0)
    }

    private fun push(line: String) {
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
        listener?.onLogChanged()
    }

    fun event(text: String) = push(stamp() + " EV  " + text)

    fun error(text: String) = push(stamp() + " ERR " + text)

    fun tx(wire: ByteArray) {
        val plain = if (wire.size == 5 && Proto.u8(wire, 0) == 0xBD) wire else Proto.decrypt(wire)
        push(stamp() + " TX  " + Proto.hex(plain) + "  | " + Proto.describeTx(wire) + "  [wire " + Proto.hex(wire) + "]")
    }

    fun rx(wire: ByteArray, m: Proto.Msg) {
        if (m is Proto.Status) {
            val now = SystemClock.elapsedRealtime()
            if (!m.differsIgnoringTimers(lastStatus) && now - lastStatusLoggedAt < 10_000) {
                suppressed++
                lastStatus = m
                return
            }
            val note = if (suppressed > 0) "  (+$suppressed similar)" else ""
            suppressed = 0
            lastStatus = m
            lastStatusLoggedAt = now
            push(stamp() + " RX  " + Proto.describeRx(m) + note + "  [plain " + Proto.hex(m.plain) + "]")
            return
        }
        push(stamp() + " RX  " + Proto.describeRx(m) + "  [plain " + Proto.hex(m.plain) + " | wire " + Proto.hex(wire) + "]")
    }

    fun clear() {
        lines.clear()
        t0 = SystemClock.elapsedRealtime()
        lastStatus = null
        suppressed = 0
        event("log cleared")
    }

    /** Newest [n] lines, oldest first. */
    fun tail(n: Int): List<String> {
        val out = ArrayList<String>()
        val skip = lines.size - n
        var i = 0
        for (l in lines) {
            if (i >= skip) out.add(l)
            i++
        }
        return out
    }

    fun export(header: String, maxLines: Int): String {
        val sb = StringBuilder(header)
        sb.append("\n--- last ").append(Math.min(maxLines, lines.size)).append(" of ").append(lines.size)
            .append(" lines (t = seconds since log start) ---\n")
        for (l in tail(maxLines)) sb.append(l).append('\n')
        return sb.toString()
    }

    companion object {
        const val MAX = 3000
    }
}
