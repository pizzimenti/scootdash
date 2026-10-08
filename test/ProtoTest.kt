import io.github.pizzimenti.scootdash.Proto
import java.io.File

// Replays the captured traffic through Proto: every write must be rebuilt
// byte-for-byte by a builder, every notification must parse.
fun main(args: Array<String>) {
    var tx = 0; var rx = 0; var status = 0; var replies = 0; var pw = 0
    var first: Proto.Status? = null
    for (line in File(args[0]).readLines()) {
        if (line.startsWith("#") || line.isBlank()) continue
        val wire = Proto.parseHex(line.substring(3))
        if (line.startsWith("TX")) {
            val rebuilt: ByteArray
            if (wire.contentEquals(Proto.startStatusStream())) rebuilt = Proto.startStatusStream()
            else {
                val p = Proto.decrypt(wire)
                check(Proto.u8(p, 0) == 0xBD && Proto.u8(p, 1) == 0xBE) { "bad header ${Proto.hex(p)}" }
                rebuilt = when (Proto.u8(p, 2)) {
                    Proto.CMD_PASSWORD -> Proto.password(String(p, 4, 6, Charsets.US_ASCII))
                    Proto.CMD_REGISTER -> if (Proto.u8(p, 4) == Proto.QUERY) Proto.readRegister(Proto.u8(p, 3)) else Proto.writeRegister(Proto.u8(p, 3), Proto.u8(p, 4))
                    else -> error("unknown cmd")
                }
            }
            check(rebuilt.contentEquals(wire)) { "mismatch ${Proto.hex(wire)}" }
            tx++
        } else {
            val m = Proto.parse(wire)
            check(m !is Proto.Unknown) { "unparsed ${Proto.hex(wire)}" }
            when (m) {
                is Proto.Status -> { status++; if (first == null) first = m }
                is Proto.RegReply -> { replies++; check(m.gearForTopSpeed >= 0) }
                is Proto.PwReply -> { pw++; check(m.accepted) }
            }
            rx++
        }
    }
    val f = first!!
    check(f.gear == 3 && f.battery == 100 && f.odoMeters == 99390L && f.tripMeters == 840L && f.accel == 7 && f.brake == 7)
    check(f.cruise && f.unitsFlag && f.zeroStart && !f.headlight && !f.lock)
    check(Proto.describeTx(Proto.writeRegister(Proto.TOP_SPEED_REG[3], 33)) == "WRITE gear 3 top speed = 33 km/h")
    println("TX rebuilt $tx, RX parsed $rx (status $status, replies $replies, password $pw)")
    println("first: " + Proto.describeRx(f))
    println("sample TX: " + Proto.describeTx(Proto.readRegister(0xA2)) + " | " + Proto.describeTx(Proto.password("000000")))
    println("PROTO TEST PASS")
}
