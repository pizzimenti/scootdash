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
    check(f.gear == 3 && f.battery == 100 && f.odoRaw == 99390L && f.tripRaw == 840L && f.accel == 7 && f.brake == 7)
    check(f.cruise && f.unitsFlag && f.zeroStart && !f.headlight && !f.lock)
    check(Proto.describeTx(Proto.writeRegister(Proto.TOP_SPEED_REG[3], 33)) == "WRITE Drive (gear 3) limit = 33 km/h")
    check(Proto.describeRx(Proto.parse(Proto.encrypt(Proto.parseHex("db eb ba 2a 1f")))) == "Drive (gear 3) limit = 31 km/h")
    // A frame from the 2026-10-08 ride: Drive mode held at its 31 km/h limit, units flag set.
    val ride = Proto.parse(Proto.encrypt(Proto.parseHex("db eb fa db 3b 41 00 00 be 00 64 4e 01 00 00 20 6d 01 00 c6 02 00 00 ae 87 01 00 09 09")))
    check(ride is Proto.Status && ride.speedRaw == 190 && ride.unitsFlag && ride.speedUnit == "mph")
    ride as Proto.Status
    check(Math.abs(ride.speedKmh - 30.577) < 0.01) { "speed ${ride.speedKmh}" }       // 19.0 mph ~ 31 km/h limit
    check(Math.abs(ride.odoMeters - 100270 * 1.609344) < 0.5)
    println("ride frame: " + Proto.describeRx(ride))
    println("TX rebuilt $tx, RX parsed $rx (status $status, replies $replies, password $pw)")
    println("first: " + Proto.describeRx(f))
    println("sample TX: " + Proto.describeTx(Proto.readRegister(0xA2)) + " | " + Proto.describeTx(Proto.password("000000")))
    println("PROTO TEST PASS")
}
