package io.github.pizzimenti.scootdash

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import java.util.Locale

/**
 * Compares the scooter's own speed and distance with GPS, once per GPS fix (about 1 Hz).
 *
 * Each fix gives two references: the chip's Doppler speed, which is accurate even when
 * the position wanders, and the fix-to-fix distance. On the scooter side, the status
 * frames that arrived since the last fix are averaged for speed, and the trip counter
 * delta is used for distance.
 *
 * A second only counts toward the scale factor when:
 * - status frames arrived during it, so a Bluetooth dropout can't feed in a frozen speed
 *   or dump the whole outage's trip distance into one second;
 * - the fix is tight (12 m or better) and 0.5 to 2.5 s after the previous one;
 * - GPS says 2 m/s or more and the scooter about 8 mph (12.5 km/h) or more, because the
 *   speed field only has whole-mph resolution, which is too coarse at walking pace.
 *
 * The factor is GPS / scooter, so 0.88 means the scooter reads 1/0.88 - 1 = 14 % high
 * (see [readsText]). No coordinates are logged, only distances and speeds.
 */
class GpsCheck(private val ctx: Context, private val log: LogBook) : LocationListener {
    interface Listener { fun onGps() }

    var listener: Listener? = null
    var running = false
        private set
    var lastFixAt = 0L
        private set
    var accuracy = -1f
        private set
    var gpsKmh = -1.0
        private set

    private var prev: Location? = null
    private var scooterSum = 0.0
    private var scooterCount = 0
    private var tripNow = -1.0
    private var tripAtFix = -1.0
    private var lastSummaryAt = 0L

    var goodSeconds = 0
        private set
    var gpsMeters = 0.0          // Doppler speed integrated over good seconds
        private set
    var gpsFixMeters = 0.0       // fix-to-fix distance over good seconds with a valid trip delta
        private set
    var scooterMeters = 0.0      // scooter speed integrated over good seconds
        private set
    var scooterTripMeters = 0.0  // scooter trip-counter delta over the same seconds as gpsFixMeters
        private set

    /** GPS / scooter speed, once there's enough riding to trust it; NaN before that. */
    val speedFactor: Double get() = if (goodSeconds >= 10 && scooterMeters > 30) gpsMeters / scooterMeters else Double.NaN

    /** GPS / scooter distance. Coarser: the trip counter moves in 0.01 mi steps, so it needs distance. */
    val distanceFactor: Double get() = if (scooterTripMeters >= MIN_TRIP_M) gpsFixMeters / scooterTripMeters else Double.NaN

    fun gpsEnabled(): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager? ?: return false
        return try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (e: RuntimeException) { false }
    }

    fun start(): Boolean {
        if (running) return true
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager? ?: return false
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        } catch (e: SecurityException) {
            log.error("gps: " + e.message); return false
        } catch (e: IllegalArgumentException) {
            log.error("gps: " + e.message); return false
        }
        running = true
        prev = null
        lastFixAt = 0L
        accuracy = -1f
        gpsKmh = -1.0
        tripNow = -1.0
        tripAtFix = -1.0
        scooterSum = 0.0
        scooterCount = 0
        log.event("GPS check on")
        listener?.onGps()
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
        try { lm?.removeUpdates(this) } catch (e: SecurityException) { }
        prev = null
        gpsKmh = -1.0
        log.event("GPS check off. " + summary())
        listener?.onGps()
    }

    fun reset() {
        goodSeconds = 0
        gpsMeters = 0.0
        gpsFixMeters = 0.0
        scooterMeters = 0.0
        scooterTripMeters = 0.0
        log.event("GPS totals reset")
        listener?.onGps()
    }

    /** Feed every status frame. */
    fun onScooter(s: Proto.Status) {
        if (!running) return
        scooterSum += s.speedKmh
        scooterCount++
        tripNow = s.tripMeters
    }

    override fun onLocationChanged(loc: Location) {
        if (!running) return
        val p = prev
        prev = loc
        lastFixAt = SystemClock.elapsedRealtime()
        accuracy = if (loc.hasAccuracy()) loc.accuracy else -1f

        // Only scooter data that arrived since the last fix counts. After a gap there is
        // none, and the trip baseline is dropped so the next second can't claim the gap.
        val fresh = scooterCount > 0
        val scooterKmh = if (fresh) scooterSum / scooterCount else -1.0
        scooterSum = 0.0
        scooterCount = 0
        val tripValid = fresh && tripAtFix >= 0 && tripNow >= 0
        val tripDelta = if (tripValid) tripNow - tripAtFix else 0.0
        tripAtFix = if (fresh) tripNow else -1.0

        if (p == null) {
            gpsKmh = if (loc.hasSpeed()) loc.speed * 3.6 else -1.0
            listener?.onGps()
            return
        }
        val dt = (loc.elapsedRealtimeNanos - p.elapsedRealtimeNanos) / 1e9
        val fixMeters = loc.distanceTo(p).toDouble()
        val mps = if (loc.hasSpeed()) loc.speed.toDouble() else if (dt > 0) fixMeters / dt else 0.0
        gpsKmh = mps * 3.6
        val why = when {
            !fresh -> "no scooter data"
            dt < 0.5 || dt > 2.5 -> "gap"
            accuracy < 0f || accuracy > MAX_ACCURACY_M -> "accuracy"
            mps < MIN_GPS_MPS || scooterKmh < MIN_SCOOTER_KMH -> "slow"
            else -> ""
        }
        val good = why.isEmpty()
        // A negative delta is a trip reset (scooter power-cycled); a huge one is a glitch.
        val tripUsable = tripValid && tripDelta >= 0 && tripDelta <= MAX_TRIP_STEP_M
        if (good) {
            goodSeconds++
            gpsMeters += mps * dt
            scooterMeters += scooterKmh / 3.6 * dt
            if (tripUsable) {
                gpsFixMeters += fixMeters
                scooterTripMeters += tripDelta
            }
        }
        val tripText = if (!tripValid) "?"
            else String.format(Locale.US, "%+.0f m", tripDelta) + (if (good && !tripUsable) " (not used)" else "")
        val scooterPart = if (!fresh) "scooter no data"
            else String.format(Locale.US, "scooter %.1f km/h trip %s", scooterKmh, tripText)
        log.gps(String.format(Locale.US, "%.2fs gps %.1f km/h %.1f m acc %.0f | %s | %s",
            dt, gpsKmh, fixMeters, accuracy, scooterPart, if (good) "used" else "skip, $why"))
        val now = SystemClock.elapsedRealtime()
        if (goodSeconds > 0 && now - lastSummaryAt >= 15_000) {
            lastSummaryAt = now
            log.cal(summary())
        }
        listener?.onGps()
    }

    fun summary(): String = String.format(Locale.US,
        "%d good s: gps %.0f m (fix-to-fix %.0f m), scooter %.0f m (trip counter %.0f m), speed %s, distance %s",
        goodSeconds, gpsMeters, gpsFixMeters, scooterMeters, scooterTripMeters,
        factorText(speedFactor), factorText(distanceFactor))

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        log.error("GPS was switched off in system settings")
        listener?.onGps()
    }

    companion object {
        const val MAX_ACCURACY_M = 12f
        const val MIN_GPS_MPS = 2.0
        /** About 8 mph. There, rounding to whole mph is worth up to 6 %, and slower is worse. */
        const val MIN_SCOOTER_KMH = 12.5
        /** About 0.25 mi, where one 0.01 mi step of the trip counter is worth 4 %. */
        const val MIN_TRIP_M = 400.0
        const val MAX_TRIP_STEP_M = 60.0

        /** How far off the scooter is, for a GPS / scooter factor [k]: 0.88 gives "reads 14% high". */
        fun readsText(k: Double): String {
            if (k.isNaN() || k <= 0.0) return "unknown"
            val pct = Math.round((1.0 / k - 1.0) * 100).toInt()
            return if (pct == 0) "reads true" else "reads " + Math.abs(pct) + "% " + (if (pct > 0) "high" else "low")
        }

        fun factorText(k: Double): String =
            if (k.isNaN()) "n/a" else String.format(Locale.US, "x%.3f (%s)", k, readsText(k))
    }
}
