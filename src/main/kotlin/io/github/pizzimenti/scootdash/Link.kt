package io.github.pizzimenti.scootdash

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.UUID

/**
 * BLE link to the scooter over Nordic UART: connect (direct by MAC, or scan first),
 * MTU 517, notifications on, plaintext start command, then a serialized write queue.
 * All callbacks are delivered on the main thread.
 */
class Link(private val ctx: Context, private val log: LogBook, private val listener: Listener) {

    interface Listener {
        fun onLinkState(state: Int, detail: String)
        fun onStatus(s: Proto.Status)
        fun onReply(m: Proto.Msg)
        fun onScanFound(name: String, address: String)
    }

    companion object {
        const val IDLE = 0
        const val SCANNING = 1
        const val CONNECTING = 2
        const val READY = 3       // streaming status frames
        const val WAITING = 4     // dropped; will retry
        const val FAILED = 5

        private val NUS: UUID = UUID.fromString(Proto.NUS_SERVICE)
        private val RX_CHAR: UUID = UUID.fromString(Proto.NUS_WRITE)
        private val TX_CHAR: UUID = UUID.fromString(Proto.NUS_NOTIFY)
        private val CCCD: UUID = UUID.fromString(Proto.CCCD)
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val queue = ArrayDeque<ByteArray>()
    private var busy = false
    private var busySince = 0L
    private var wantConnected = false
    private var scanning = false
    private var attempt = 0

    var state = IDLE
        private set
    var address: String = Proto.DEFAULT_MAC
    var deviceName: String = ""
        private set
    var lastFrameAt = 0L
        private set
    var connectedAt = 0L
        private set
    var lastGear = -1

    // frame-rate estimate
    private var frames = 0
    private var frameWindowStart = 0L
    var framesPerSecond = 0.0
        private set

    private fun setState(s: Int, detail: String) {
        state = s
        listener.onLinkState(s, detail)
    }

    private fun adapter(): BluetoothAdapter? {
        val m = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        return m?.adapter
    }

    fun bluetoothOn(): Boolean = adapter()?.isEnabled == true

    // ---------------------------------------------------------------- connect
    fun connect(mac: String, name: String = "") {
        address = cleanMac(mac)
        deviceName = name
        wantConnected = true
        attempt = 0
        openGatt()
    }

    private fun openGatt() {
        main.removeCallbacks(retryRunnable)
        main.removeCallbacks(connectTimeout)
        closeGatt()
        val a = adapter()
        if (a == null || !a.isEnabled) {
            setState(FAILED, "Bluetooth is off")
            log.error("bluetooth adapter off or missing")
            return
        }
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            setState(FAILED, "Not a valid address: $address")
            log.error("invalid MAC $address")
            return
        }
        attempt++
        setState(CONNECTING, "Connecting to $address")
        log.event("connect attempt $attempt to $address")
        try {
            val dev = a.getRemoteDevice(address)
            gatt = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
            main.postDelayed(connectTimeout, 15_000)
        } catch (e: SecurityException) {
            setState(FAILED, "Nearby devices permission needed")
            log.error("connect: " + e.message)
        } catch (e: IllegalArgumentException) {
            setState(FAILED, "Not a valid address: $address")
            log.error("connect: " + e.message)
        }
    }

    fun disconnect() {
        wantConnected = false
        main.removeCallbacks(retryRunnable)
        main.removeCallbacks(connectTimeout)
        main.removeCallbacks(pollRunnable)
        try { gatt?.disconnect() } catch (e: SecurityException) { }
        closeGatt()
        setState(IDLE, "Disconnected")
        log.event("disconnected by user")
    }

    fun shutdown() {
        wantConnected = false
        stopScan()
        main.removeCallbacksAndMessages(null)
        closeGatt()
    }

    private fun closeGatt() {
        main.removeCallbacks(mtuFallback)
        main.removeCallbacks(streamFallback)
        main.removeCallbacks(pumpWatchdog)
        main.removeCallbacks(streamWatch)
        val g = gatt
        gatt = null
        writeChar = null
        queue.clear()
        busy = false
        if (g != null) {
            try { g.close() } catch (e: SecurityException) { }
        }
    }

    private val connectTimeout = object : Runnable {
        override fun run() {
            if (state == CONNECTING) {
                log.error("connect timed out")
                dropped("No answer from the scooter")
            }
        }
    }

    private val retryRunnable = object : Runnable {
        override fun run() { if (wantConnected) openGatt() }
    }

    private fun dropped(why: String) {
        main.removeCallbacks(pollRunnable)
        main.removeCallbacks(connectTimeout)
        closeGatt()
        if (wantConnected) {
            setState(WAITING, "$why. Retrying")
            main.postDelayed(retryRunnable, if (attempt < 3) 2_000L else 5_000L)
        } else {
            setState(IDLE, why)
        }
    }

    // ------------------------------------------------------------------- scan
    fun scan() {
        val a = adapter()
        if (a == null || !a.isEnabled) { setState(FAILED, "Bluetooth is off"); return }
        val sc = a.bluetoothLeScanner
        if (sc == null) { setState(FAILED, "Scanner unavailable"); return }
        stopScan()
        try {
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            sc.startScan(null, settings, scanCallback)
            scanning = true
            setState(SCANNING, "Looking for 365Bluetooth scooters")
            log.event("scan started")
            main.postDelayed(scanTimeout, 12_000)
        } catch (e: SecurityException) {
            setState(FAILED, "Scanning needs Nearby devices and Location permission")
            log.error("scan: " + e.message)
        }
    }

    fun stopScan() {
        main.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        try { adapter()?.bluetoothLeScanner?.stopScan(scanCallback) } catch (e: SecurityException) { } catch (e: IllegalStateException) { }
    }

    private val scanTimeout = object : Runnable {
        override fun run() {
            if (!scanning) return
            stopScan()
            log.event("scan finished, nothing found")
            setState(FAILED, "No scooter found. Is it on, and is Location on?")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: return
            if (!name.startsWith(Proto.NAME_PREFIX)) return
            val mac = result.device.address
            main.post(object : Runnable {
                override fun run() {
                    if (!scanning) return
                    stopScan()
                    deviceName = name
                    log.event("found $name $mac rssi ${result.rssi}")
                    listener.onScanFound(name, mac)
                }
            })
        }

        override fun onScanFailed(errorCode: Int) {
            main.post(object : Runnable {
                override fun run() {
                    scanning = false
                    log.error("scan failed code $errorCode")
                    setState(FAILED, "Scan failed (code $errorCode)")
                }
            })
        }
    }

    // ------------------------------------------------------------------ GATT
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post(object : Runnable {
                override fun run() {
                    if (g !== gatt) return
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        // give MTU, discovery and notification setup 10 s to finish
                        main.removeCallbacks(connectTimeout)
                        main.postDelayed(connectTimeout, 10_000)
                        log.event("link up (status $status), requesting MTU 517")
                        try {
                            if (!g.requestMtu(517)) discover(g)
                            else main.postDelayed(mtuFallback, 2_500)
                        } catch (e: SecurityException) { log.error("mtu: " + e.message) }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        log.event("link down (status $status)")
                        dropped(if (status == 8) "Scooter went out of range or off" else "Link dropped (code $status)")
                    }
                }
            })
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post(object : Runnable {
                override fun run() {
                    if (g !== gatt) return
                    main.removeCallbacks(mtuFallback)
                    log.event("MTU $mtu (status $status)")
                    discover(g)
                }
            })
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post(object : Runnable {
                override fun run() {
                    if (g !== gatt) return
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        log.error("service discovery failed, status $status")
                        dropped("Service discovery failed (code $status)")
                        return
                    }
                    val svc = g.getService(NUS)
                    val wc = svc?.getCharacteristic(RX_CHAR)
                    val nc = svc?.getCharacteristic(TX_CHAR)
                    if (wc == null || nc == null) {
                        log.error("Nordic UART service not found")
                        wantConnected = false
                        main.removeCallbacks(connectTimeout)
                        try { g.disconnect() } catch (e: SecurityException) { }
                        closeGatt()
                        setState(FAILED, "This device doesn't speak the 365 protocol")
                        return
                    }
                    writeChar = wc
                    try {
                        g.setCharacteristicNotification(nc, true)
                        val d = nc.getDescriptor(CCCD)
                        if (d == null) { log.error("no CCCD on notify characteristic"); startStream(); return }
                        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        if (!g.writeDescriptor(d)) { log.error("CCCD write refused"); startStream() }
                        else main.postDelayed(streamFallback, 2_500)
                    } catch (e: SecurityException) { log.error("notify: " + e.message) }
                }
            })
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post(object : Runnable {
                override fun run() {
                    if (g !== gatt) return
                    main.removeCallbacks(streamFallback)
                    log.event("notifications on (status $status)")
                    if (state != READY) startStream()
                }
            })
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post(object : Runnable {
                override fun run() {
                    if (g !== gatt) return
                    if (status != BluetoothGatt.GATT_SUCCESS) log.error("write failed, GATT status $status")
                    busy = false
                    pump()
                }
            })
        }

        // On Android 13+ the framework calls the 3-argument form, whose default implementation
        // sets the characteristic's value and forwards here. GATT callbacks arrive one at a time
        // (oneway binder), so copying the value immediately is race-free on every version.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            deliver(g, v.clone())
        }
    }

    private val mtuFallback = object : Runnable {
        override fun run() {
            val g = gatt ?: return
            log.event("no MTU answer, discovering anyway")
            discover(g)
        }
    }

    private val streamFallback = object : Runnable {
        override fun run() {
            if (gatt != null && state != READY) {
                log.event("no CCCD answer, starting stream anyway")
                startStream()
            }
        }
    }

    private fun discover(g: BluetoothGatt) {
        try {
            if (!g.discoverServices()) log.error("discoverServices refused")
        } catch (e: SecurityException) { log.error("discover: " + e.message) }
    }

    private fun startStream() {
        if (state == READY) return
        main.removeCallbacks(connectTimeout)
        connectedAt = SystemClock.elapsedRealtime()
        lastFrameAt = connectedAt
        attempt = 0
        frames = 0
        frameWindowStart = connectedAt
        send(Proto.startStatusStream())
        main.removeCallbacks(streamWatch)
        main.postDelayed(streamWatch, 3_000)
        // Learn every gear's top speed, then keep polling the current gear's.
        for (g in 0 until 4) send(Proto.readRegister(Proto.TOP_SPEED_REG[g]))
        main.removeCallbacks(pollRunnable)
        main.postDelayed(pollRunnable, 2_000)
        setState(READY, "Connected")
    }

    /** If no status frame shows up, ask again (the start command may have been lost). */
    private val streamWatch = object : Runnable {
        override fun run() {
            if (state != READY || gatt == null) return
            if (SystemClock.elapsedRealtime() - lastFrameAt > 2_500) {
                log.event("no status frames, resending start command")
                send(Proto.startStatusStream())
            }
            main.postDelayed(this, 5_000)
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (state != READY) return
            if (lastGear in 0..3 && queue.size < 3) send(Proto.readRegister(Proto.TOP_SPEED_REG[lastGear]))
            main.postDelayed(this, 2_000)
        }
    }

    private fun deliver(g: BluetoothGatt, wire: ByteArray) {
        main.post(object : Runnable {
            override fun run() {
                if (g !== gatt) return
                val m = Proto.parse(wire)
                log.rx(wire, m)
                if (m is Proto.Status) {
                    val now = SystemClock.elapsedRealtime()
                    lastFrameAt = now
                    lastGear = m.gear
                    frames++
                    if (now - frameWindowStart >= 2_000) {
                        framesPerSecond = frames * 1000.0 / (now - frameWindowStart)
                        frames = 0
                        frameWindowStart = now
                    }
                    listener.onStatus(m)
                } else {
                    listener.onReply(m)
                }
            }
        })
    }

    // ------------------------------------------------------------- write queue
    /** Queue wire bytes for the NUS write characteristic (ATT Write Request, like the stock app). */
    fun send(wire: ByteArray) {
        queue.addLast(wire)
        pump()
    }

    private fun pump() {
        if (busy && SystemClock.elapsedRealtime() - busySince > 1_500) {
            log.error("write ack timed out")
            busy = false
        }
        if (busy) return
        val g = gatt ?: return
        val c = writeChar ?: return
        val next = queue.pollFirst() ?: return
        try {
            c.value = next
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (g.writeCharacteristic(c)) {
                busy = true
                busySince = SystemClock.elapsedRealtime()
                log.tx(next)
                main.removeCallbacks(pumpWatchdog)
                main.postDelayed(pumpWatchdog, 1_600)
            } else {
                queue.addFirst(next)
                main.removeCallbacks(pumpWatchdog)
                main.postDelayed(pumpWatchdog, 120)
            }
        } catch (e: SecurityException) {
            log.error("write: " + e.message)
        }
    }

    private val pumpWatchdog = object : Runnable {
        override fun run() { pump() }
    }

    val isReady: Boolean get() = state == READY
}

/** Uppercase, strip spaces; accepts "00:00:00:01:84:0b" or "000000 01840B"-style input. */
fun cleanMac(s: String): String {
    val hexOnly = StringBuilder()
    for (c in s) {
        if ((c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')) hexOnly.append(Character.toUpperCase(c))
    }
    if (hexOnly.length != 12) return s.uppercase(java.util.Locale.ROOT)
    val out = StringBuilder(17)
    for (i in 0 until 12) {
        if (i > 0 && i % 2 == 0) out.append(':')
        out.append(hexOnly[i])
    }
    return out.toString()
}
