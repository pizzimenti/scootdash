package io.github.pizzimenti.scootdash

/**
 * 365Scooter BLE protocol: byte cipher, command builders and frame parser.
 * Reverse-engineered from an Android HCI snoop log of the 365Scooter app
 * (com.gongxiaoqing.scooter365 v1.1.6) talking to "365Bluetooth_26".
 *
 * Plaintext framing:  app -> scooter  BD BE <cmd> <arg> <value...>
 *                     scooter -> app  DB EB <cmd'> <arg'> <data...>   (cmd/arg nibble-swapped)
 * Every byte on the air goes through ENC, except the start command, which is
 * sent in the clear. Pure Kotlin: no Android imports, so it runs on the JVM for tests.
 */
object Proto {
    const val NUS_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val NUS_WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"   // app -> scooter
    const val NUS_NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  // scooter -> app
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
    const val NAME_PREFIX = "365Bluetooth"
    const val DEFAULT_MAC = "00:00:00:01:84:0B"                     // from the capture

    const val CMD_STATUS = 0xAF
    const val CMD_REGISTER = 0xAB
    const val CMD_PASSWORD = 0xAC
    const val QUERY = 0xFF

    const val REG_HEADLIGHT = 0x00   // name inferred
    const val REG_CRUISE = 0x01      // name inferred
    const val REG_LOCK = 0x02        // name inferred
    const val REG_UNITS = 0x03       // name inferred
    const val REG_GEAR = 0x05
    const val REG_ZERO_START = 0x07  // name inferred
    const val REG_ACCEL = 0x08
    const val REG_BRAKE = 0x09

    /** Top-speed (km/h) register for gear 0..3. The stock app only reads these. */
    val TOP_SPEED_REG = intArrayOf(0xA3, 0xA0, 0xA1, 0xA2)

    private val ENC = ByteArray(256)
    private val DEC = ByteArray(256)

    init {
        for (b in 0 until 256) {
            var x = (b + 0x24) and 0xFF
            x = x xor 0x56
            x = (x + 0x39) and 0xFF
            x = x xor 0x3E
            x = (x + 0x6B) and 0xFF
            x = x xor 0xDE
            ENC[b] = x.toByte()
            DEC[x] = b.toByte()
        }
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val out = ByteArray(plain.size)
        for (i in 0 until plain.size) out[i] = ENC[plain[i].toInt() and 0xFF]
        return out
    }

    fun decrypt(wire: ByteArray): ByteArray {
        val out = ByteArray(wire.size)
        for (i in 0 until wire.size) out[i] = DEC[wire[i].toInt() and 0xFF]
        return out
    }

    fun swapNibbles(b: Int): Int = ((b shl 4) or (b ushr 4)) and 0xFF

    // ------------------------------------------------------------ builders
    // Each returns the exact bytes to write to NUS_WRITE.

    fun startStatusStream(): ByteArray = bytes(0xBD, 0xBE, CMD_STATUS, 0xFF, 0xFF)

    fun writeRegister(reg: Int, value: Int): ByteArray =
        encrypt(bytes(0xBD, 0xBE, CMD_REGISTER, reg and 0xFF, value and 0xFF))

    fun readRegister(reg: Int): ByteArray = writeRegister(reg, QUERY)

    fun password(pin: String): ByteArray {
        if (pin.length != 6) throw IllegalArgumentException("PIN must be 6 digits")
        val p = ByteArray(10)
        p[0] = 0xBD.toByte(); p[1] = 0xBE.toByte(); p[2] = CMD_PASSWORD.toByte(); p[3] = CMD_PASSWORD.toByte()
        for (i in 0 until 6) {
            val c = pin[i]
            if (c < '0' || c > '9') throw IllegalArgumentException("PIN must be 6 digits")
            p[4 + i] = c.code.toByte()
        }
        return encrypt(p)
    }

    private fun bytes(vararg v: Int): ByteArray {
        val out = ByteArray(v.size)
        for (i in 0 until v.size) out[i] = v[i].toByte()
        return out
    }

    // ------------------------------------------------------------- parsing
    abstract class Msg(val plain: ByteArray)

    class Status(plain: ByteArray) : Msg(plain) {
        val flags = u8(plain, 4)
        val gearRaw = u8(plain, 5)
        val gear = when (gearRaw and 0x78) { 0x08 -> 0; 0x10 -> 1; 0x20 -> 2; 0x40 -> 3; else -> -1 }
        val reserved67 = u16(plain, 6)          // always 0 in the capture; watch under load
        val speedX10 = u16(plain, 8)            // assumed km/h x10
        val battery = u8(plain, 10)
        val tripTime = u32(plain, 11)           // counts since power-on (~1.5 s each observed)
        val totalTime = u32(plain, 15)
        val tripMeters = u32(plain, 19)
        val odoMeters = u32(plain, 23)
        val accel = u8(plain, 27)
        val brake = u8(plain, 28)

        val cruise: Boolean get() = flags and 0x01 != 0
        val headlight: Boolean get() = flags and 0x02 != 0
        val lock: Boolean get() = flags and 0x04 != 0
        val unitsFlag: Boolean get() = flags and 0x08 != 0
        val zeroStart: Boolean get() = flags and 0x10 != 0

        /** True when anything other than the two time counters differs from [o]. */
        fun differsIgnoringTimers(o: Status?): Boolean {
            if (o == null || o.plain.size != plain.size) return true
            for (i in 0 until plain.size) {
                if (i in 11..18) continue
                if (plain[i] != o.plain[i]) return true
            }
            return false
        }
    }

    class RegReply(plain: ByteArray, val reg: Int, val value: Int) : Msg(plain) {
        /** Gear 0..3 when this answers a top-speed read, else -1. */
        val gearForTopSpeed: Int
            get() {
                for (g in 0 until 4) if (TOP_SPEED_REG[g] == reg) return g
                return -1
            }
    }

    class PwReply(plain: ByteArray, val result: Int) : Msg(plain) {
        val accepted: Boolean get() = result == 0xFF
    }

    class Unknown(plain: ByteArray) : Msg(plain)

    fun parse(wire: ByteArray): Msg {
        val p = decrypt(wire)
        if (p.size < 5 || u8(p, 0) != 0xDB || u8(p, 1) != 0xEB) return Unknown(p)
        val kind = u8(p, 2)
        if (kind == swapNibbles(CMD_STATUS) && p.size >= 29) return Status(p)
        if (kind == swapNibbles(CMD_REGISTER)) return RegReply(p, swapNibbles(u8(p, 3)), u8(p, 4))
        if (kind == swapNibbles(CMD_PASSWORD)) return PwReply(p, u8(p, 4))
        return Unknown(p)
    }

    /** Plain-language description of an outgoing frame (wire bytes) for the log. */
    fun describeTx(wire: ByteArray): String {
        if (wire.size == 5 && u8(wire, 0) == 0xBD && u8(wire, 2) == CMD_STATUS) return "start status stream (sent in the clear)"
        val p = decrypt(wire)
        if (p.size < 5 || u8(p, 0) != 0xBD || u8(p, 1) != 0xBE) return "unrecognised frame"
        val cmd = u8(p, 2)
        if (cmd == CMD_PASSWORD) {
            val sb = StringBuilder("password ")
            for (i in 4 until p.size) sb.append(p[i].toInt().toChar())
            return sb.toString()
        }
        if (cmd != CMD_REGISTER) return "command 0x" + hex2(cmd)
        val reg = u8(p, 3)
        val v = u8(p, 4)
        for (g in 0 until 4) if (TOP_SPEED_REG[g] == reg) {
            return if (v == QUERY) "read gear $g top speed" else "WRITE gear $g top speed = $v km/h"
        }
        if (v == QUERY) return "read register 0x" + hex2(reg)
        return when (reg) {
            REG_GEAR -> "set gear $v"
            REG_ACCEL -> "set acceleration $v"
            REG_BRAKE -> "set brake $v"
            REG_CRUISE -> "cruise control " + onOff(v)
            REG_ZERO_START -> "zero start " + onOff(v)
            REG_HEADLIGHT -> "headlight " + onOff(v)
            REG_LOCK -> "lock " + onOff(v)
            REG_UNITS -> "units flag " + onOff(v)
            else -> "write register 0x" + hex2(reg) + " = " + v
        }
    }

    fun describeRx(m: Msg): String = when (m) {
        is Status -> {
            val sb = StringBuilder()
            sb.append("status gear=").append(m.gear).append(" spd=").append(m.speedX10)
            sb.append(" bat=").append(m.battery).append(" acc=").append(m.accel).append(" brk=").append(m.brake)
            sb.append(" flags=").append(hex2(m.flags)).append(" g=").append(hex2(m.gearRaw))
            sb.append(" trip=").append(m.tripMeters).append("m odo=").append(m.odoMeters).append('m')
            sb.append(" t=").append(m.tripTime).append('/').append(m.totalTime)
            sb.append(" b67=").append(m.reserved67)
            sb.toString()
        }
        is RegReply -> {
            val g = m.gearForTopSpeed
            if (g >= 0) "gear $g top speed = ${m.value} km/h" else "register 0x" + hex2(m.reg) + " = " + m.value
        }
        is PwReply -> if (m.accepted) "password accepted" else "password reply 0x" + hex2(m.result)
        else -> "unrecognised frame"
    }

    // ------------------------------------------------------------- helpers
    fun u8(p: ByteArray, o: Int): Int = p[o].toInt() and 0xFF
    fun u16(p: ByteArray, o: Int): Int = u8(p, o) or (u8(p, o + 1) shl 8)
    fun u32(p: ByteArray, o: Int): Long =
        (u8(p, o).toLong()) or (u8(p, o + 1).toLong() shl 8) or (u8(p, o + 2).toLong() shl 16) or (u8(p, o + 3).toLong() shl 24)

    private const val DIGITS = "0123456789abcdef"

    fun hex2(v: Int): String {
        val sb = StringBuilder(2)
        sb.append(DIGITS[(v ushr 4) and 0xF]).append(DIGITS[v and 0xF])
        return sb.toString()
    }

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 3)
        for (i in 0 until b.size) {
            if (i > 0) sb.append(' ')
            val v = b[i].toInt() and 0xFF
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0xF])
        }
        return sb.toString()
    }

    fun parseHex(s: String): ByteArray {
        val clean = StringBuilder()
        for (c in s) if (c != ' ' && c != ':') clean.append(c)
        val n = clean.length / 2
        val out = ByteArray(n)
        for (i in 0 until n) out[i] = Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16).toByte()
        return out
    }

    private fun onOff(v: Int): String = if (v == 0) "off" else if (v == 1) "on" else "= $v"
}
