package io.github.pizzimenti.scootdash

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), Link.Listener, LogBook.Listener, PanelPager.Listener, GpsCheck.Listener {

    private lateinit var prefs: SharedPreferences
    private val log = LogBook()
    private lateinit var link: Link
    private lateinit var gps: GpsCheck
    private val main = Handler(Looper.getMainLooper())

    // live state
    private var status: Proto.Status? = null
    private val limits = intArrayOf(-1, -1, -1, -1)          // km/h per mode, as last read
    private val firstLimits = intArrayOf(-1, -1, -1, -1)     // first value read this session
    private val pendingLimit = intArrayOf(-1, -1, -1, -1)    // stepper values
    private val askedLimit = intArrayOf(-1, -1, -1, -1)      // last value written
    private val askedAt = LongArray(4)                       // when it was written
    private val replyAt = LongArray(4)                       // when the scooter last answered
    private var mph = true
    private var peakRaw = 0.0                                // km/h as the scooter reports it
    private var syncing = false
    private var draggingAccel = false
    private var draggingBrake = false
    private var pendingAction = 0
    private var rawReg = -1
    private var savedFactor = 1.0
    private var applyFactor = false
    private var shownMode = -2

    // header
    private lateinit var pager: PanelPager
    private lateinit var tabs: LinearLayout
    private lateinit var tabDash: TextView
    private lateinit var tabSetup: TextView
    private lateinit var linkDot: View
    private lateinit var linkText: TextView
    private lateinit var linkRate: TextView

    // dash
    private lateinit var dial: DialView
    private val modeCells = arrayOfNulls<LinearLayout>(4)
    private val modeLabels = arrayOfNulls<TextView>(4)
    private val modeCaps = arrayOfNulls<TextView>(4)
    private lateinit var rBattery: Readout
    private lateinit var rPower: Readout
    private lateinit var rAccel: Readout
    private lateinit var rBrake: Readout
    private lateinit var rTrip: Readout
    private lateinit var rOdo: Readout
    private lateinit var rGps: Readout
    private lateinit var rFactor: Readout
    private lateinit var rRaw: Readout
    private lateinit var tCruise: Telltale
    private lateinit var tZero: Telltale
    private lateinit var tLight: Telltale
    private lateinit var tLock: Telltale
    private lateinit var tUnits: Telltale

    // setup
    private lateinit var macField: EditText
    private lateinit var connectBtn: TextView
    private lateinit var scanBtn: TextView
    private lateinit var accelBar: SeekBar
    private lateinit var accelVal: TextView
    private lateinit var brakeBar: SeekBar
    private lateinit var brakeVal: TextView
    private val switches = arrayOfNulls<Switch>(5)
    private val switchRegs = intArrayOf(Proto.REG_CRUISE, Proto.REG_ZERO_START, Proto.REG_HEADLIGHT, Proto.REG_LOCK, Proto.REG_UNITS)
    private lateinit var pinField: EditText
    private lateinit var pinResult: TextView
    private lateinit var gpsSwitch: Switch
    private lateinit var gpsStatus: TextView
    private lateinit var applySwitch: Switch
    private lateinit var saveFactorBtn: TextView
    private val limitNow = arrayOfNulls<TextView>(4)
    private val limitEdit = arrayOfNulls<TextView>(4)
    private lateinit var regField: EditText
    private lateinit var valField: EditText
    private lateinit var regResult: TextView
    private lateinit var unitsSwitch: Switch
    private lateinit var diagText: TextView
    private lateinit var logCount: TextView
    private lateinit var logPreview: TextView

    // ------------------------------------------------------------- lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        prefs = getSharedPreferences("scootdash", Context.MODE_PRIVATE)
        mph = prefs.getBoolean("mph", true)
        savedFactor = prefs.getFloat("gpsFactor", 1f).toDouble()
        applyFactor = prefs.getBoolean("applyFactor", false) && savedFactor != 1.0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Ui.DUSK
        window.navigationBarColor = Ui.DUSK
        link = Link(this, log, this)
        link.address = prefs.getString("mac", Proto.DEFAULT_MAC) ?: Proto.DEFAULT_MAC
        gps = GpsCheck(this, log)
        gps.listener = this
        log.listener = this
        setContentView(buildRoot())
        log.event("app start, Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") on " + Build.MODEL)
        refreshDash()
        refreshSetup()
        refreshGps()
        main.postDelayed(ticker, 1_000)
        connectWithPermission()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        gps.stop()
        link.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- layout
    private fun buildRoot(): View {
        val root = Ui.column(this)
        root.setBackgroundColor(Ui.DUSK)

        // link strip
        val strip = Ui.row(this)
        strip.setPadding(Ui.dp(18), Ui.dp(12), Ui.dp(18), Ui.dp(6))
        linkDot = View(this)
        linkDot.background = Ui.box(Ui.SLATE, 5f)
        strip.addView(linkDot, LinearLayout.LayoutParams(Ui.dp(10), Ui.dp(10)))
        linkText = Ui.text(this, 15f, Ui.PUMICE, Ui.bodyMedium)
        linkText.setPadding(Ui.dp(10), 0, Ui.dp(8), 0)
        linkText.maxLines = 1
        linkText.ellipsize = android.text.TextUtils.TruncateAt.END
        strip.addView(linkText, Ui.weighted())
        linkRate = Ui.text(this, 14f, Ui.SLATE, Ui.body)
        strip.addView(linkRate, Ui.wrap())
        root.addView(strip, Ui.full())

        // tabs
        tabs = Ui.row(this)
        tabs.setPadding(Ui.dp(10), 0, Ui.dp(10), 0)
        tabDash = tab("Dash", 0)
        tabSetup = tab("Setup", 1)
        tabs.addView(tabDash, Ui.wrap())
        tabs.addView(tabSetup, Ui.wrap())
        root.addView(tabs, Ui.full())

        pager = PanelPager(this)
        pager.listener = this
        pager.addPanel(buildDash())
        pager.addPanel(buildSetup())
        root.addView(pager, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        onPanel(0, false)
        return root
    }

    private fun tab(label: String, index: Int): TextView {
        val t = Ui.text(this, 19f, Ui.SLATE, Ui.condSemi)
        t.text = label
        t.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(14), Ui.dp(10))
        t.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) { pager.show(index, true) }
        })
        return t
    }

    override fun onPanel(index: Int, sideBySide: Boolean) {
        tabs.visibility = if (sideBySide) View.GONE else View.VISIBLE
        styleTab(tabDash, index == 0)
        styleTab(tabSetup, index == 1)
    }

    private fun styleTab(t: TextView, on: Boolean) {
        t.setTextColor(if (on) Ui.PUMICE else Ui.SLATE)
        if (!on) { t.background = null; return }
        val underline = android.graphics.drawable.GradientDrawable()
        underline.setColor(Ui.PUMICE)
        underline.setCornerRadius(Ui.dpf(1.5f))
        val ld = android.graphics.drawable.LayerDrawable(arrayOf<android.graphics.drawable.Drawable>(underline))
        ld.setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
        ld.setLayerHeight(0, Ui.dp(3))
        ld.setLayerInset(0, Ui.dp(8), 0, Ui.dp(14), 0)
        t.background = ld
    }

    private fun scroller(content: View): ScrollView {
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        sv.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        return sv
    }

    // ------------------------------------------------------------------ dash
    private fun buildDash(): View {
        val col = Ui.column(this)
        col.setPadding(Ui.dp(14), 0, Ui.dp(14), Ui.dp(24))

        dial = DialView(this)
        dial.mph = mph
        dial.isClickable = true
        dial.contentDescription = "Speedometer. Tap to reset the peak marker."
        dial.setOnClickListener(click {
            peakRaw = 0.0
            refreshDash()
            toast("Peak marker reset")
        })
        col.addView(dial, Ui.full())

        // riding modes: the scooter's gears 0..3
        val modes = Ui.row(this)
        modes.setPadding(0, Ui.dp(4), 0, Ui.dp(6))
        for (g in 0 until 4) {
            val cell = Ui.column(this)
            cell.setGravity(Gravity.CENTER)
            cell.setPadding(0, Ui.dp(8), 0, Ui.dp(8))
            val emoji = Ui.text(this, 24f, Ui.PUMICE, Ui.body)
            emoji.text = MODE_EMOJI[g]
            emoji.gravity = Gravity.CENTER
            val name = Ui.text(this, 15f, Ui.PUMICE, Ui.bodySemi)
            name.text = Proto.MODE_NAMES[g]
            name.gravity = Gravity.CENTER
            name.setPadding(0, Ui.dp(4), 0, 0)
            val cap = Ui.text(this, 12.5f, Ui.SLATE, Ui.body)
            cap.gravity = Gravity.CENTER
            cell.addView(emoji, Ui.wrap())
            cell.addView(name, Ui.wrap())
            cell.addView(cap, Ui.wrap())
            cell.isClickable = true
            cell.contentDescription = Proto.MODE_NAMES[g] + " mode. While riding, touch and hold to switch."
            cell.setOnClickListener(click { pickMode(g, false) })
            cell.setOnLongClickListener(object : View.OnLongClickListener {
                override fun onLongClick(v: View): Boolean { pickMode(g, true); return true }
            })
            modeCells[g] = cell; modeLabels[g] = name; modeCaps[g] = cap
            val lp = Ui.weighted()
            if (g > 0) lp.leftMargin = Ui.dp(8)
            modes.addView(cell, lp)
        }
        col.addView(modes, Ui.full())

        rBattery = Readout(this, "Battery", 10)
        rAccel = Readout(this, "Acceleration", 9)
        rBrake = Readout(this, "Brake", 9)
        rTrip = Readout(this, "Trip")
        rOdo = Readout(this, "Odometer")
        rPower = Readout(this, "Power")
        rGps = Readout(this, "GPS speed")
        rFactor = Readout(this, "Scale factor")
        rRaw = Readout(this, "Speed field")
        rTrip.note("since power-on")
        rGps.isClickable = true
        rGps.background = Ui.ripple(Ui.box(0, 10f), 10f)
        rGps.setOnClickListener(click { toggleGps(!gps.running) })

        col.addView(triple(rBattery, rAccel, rBrake), Ui.full())
        col.addView(triple(rTrip, rOdo, rPower), Ui.full())
        col.addView(triple(rGps, rFactor, rRaw), Ui.full())

        val lamps = FlowRow(this, 8, 8)
        lamps.setPadding(0, Ui.dp(12), 0, 0)
        tCruise = Telltale(this, "Cruise", Ui.PONDEROSA)
        tZero = Telltale(this, "Zero start", Ui.SAGE)
        tLight = Telltale(this, "Light", Ui.PUMICE)
        tLock = Telltale(this, "Locked", Ui.SIGNAL)
        tUnits = Telltale(this, "mph", Ui.SLATE)
        lamps.addView(tCruise); lamps.addView(tZero); lamps.addView(tLight); lamps.addView(tLock); lamps.addView(tUnits)
        col.addView(lamps, Ui.full())
        return scroller(col)
    }

    private fun triple(a: View, b: View, c: View): LinearLayout {
        val r = Ui.row(this)
        r.setGravity(Gravity.TOP)
        r.addView(a, Ui.weighted())
        r.addView(b, Ui.weighted())
        r.addView(c, Ui.weighted())
        return r
    }

    // ----------------------------------------------------------------- setup
    private fun buildSetup(): View {
        val col = Ui.column(this)
        col.setPadding(Ui.dp(18), 0, Ui.dp(18), Ui.dp(40))

        // scooter / connection
        col.addView(heading("Scooter", null))
        macField = field("Bluetooth address", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        macField.setText(link.address)
        macField.typeface = Typeface.MONOSPACE
        col.addView(macField, Ui.full())
        val connRow = Ui.row(this)
        connRow.setPadding(0, Ui.dp(10), 0, 0)
        connectBtn = Ui.button(this, "Connect", Ui.PRIMARY)
        connectBtn.setOnClickListener(click { onConnectButton() })
        scanBtn = Ui.button(this, "Find scooter")
        scanBtn.setOnClickListener(click { scanWithPermission() })
        connRow.addView(connectBtn, Ui.weighted())
        connRow.addView(spacer(10), LinearLayout.LayoutParams(Ui.dp(10), 1))
        connRow.addView(scanBtn, Ui.weighted())
        col.addView(connRow, Ui.full())
        col.addView(caption("The scooter in the original capture was " + Proto.DEFAULT_MAC + ". Find scooter scans for anything named 365Bluetooth."))

        // ride feel
        col.addView(heading("Ride feel", "Sent when you let go of the slider. The stock app used levels 3 to 9."))
        accelVal = Ui.text(this, 22f, Ui.PUMICE, Ui.condSemi)
        accelBar = slider(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) accelVal.text = (p + 1).toString() }
            override fun onStartTrackingTouch(s: SeekBar) { draggingAccel = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                draggingAccel = false
                if (!command(Proto.writeRegister(Proto.REG_ACCEL, s.progress + 1))) resyncSliders()
            }
        })
        col.addView(sliderBlock("Acceleration", accelVal, accelBar), Ui.full())
        brakeVal = Ui.text(this, 22f, Ui.PUMICE, Ui.condSemi)
        brakeBar = slider(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) brakeVal.text = (p + 1).toString() }
            override fun onStartTrackingTouch(s: SeekBar) { draggingBrake = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                draggingBrake = false
                if (!command(Proto.writeRegister(Proto.REG_BRAKE, s.progress + 1))) resyncSliders()
            }
        })
        col.addView(sliderBlock("Brake", brakeVal, brakeBar), Ui.full())

        // switches
        col.addView(heading("Switches", "Names are my best reading of the capture. Flip one and watch the scooter to confirm it."))
        val names = arrayOf("Cruise control", "Zero start", "Headlight", "Lock", "Units (on = mph)")
        for (i in 0 until 5) col.addView(switchRow(i, names[i]), Ui.full())

        // GPS check
        col.addView(buildGpsSection())

        // speed limits per mode
        col.addView(buildLimits())

        // password
        col.addView(heading("Password", "The stock app sent 000000 right after turning Lock on, and the scooter accepted it."))
        val pwRow = Ui.row(this)
        pinField = field("6 digits", InputType.TYPE_CLASS_NUMBER)
        pinField.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(6))
        pinField.setText(prefs.getString("pin", "000000"))
        pwRow.addView(pinField, Ui.weighted())
        val pwBtn = Ui.button(this, "Send password")
        pwBtn.setOnClickListener(click { sendPassword() })
        val pwLp = Ui.wrap()
        pwLp.leftMargin = Ui.dp(10)
        pwRow.addView(pwBtn, pwLp)
        col.addView(pwRow, Ui.full())
        pinResult = caption("")
        col.addView(pinResult)

        // raw register
        col.addView(heading("Raw register", "Read sends <reg> FF. Write sends <reg> <value>. Values are hex."))
        val rawRow = Ui.row(this)
        regField = field("Reg", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        regField.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(2))
        regField.setText("05")
        valField = field("Value", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        valField.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(2))
        rawRow.addView(regField, Ui.weighted())
        rawRow.addView(spacer(8), LinearLayout.LayoutParams(Ui.dp(8), 1))
        rawRow.addView(valField, Ui.weighted())
        col.addView(rawRow, Ui.full())
        val rawBtns = Ui.row(this)
        rawBtns.setPadding(0, Ui.dp(10), 0, 0)
        val rd = Ui.button(this, "Read")
        rd.setOnClickListener(click { rawRead() })
        val wr = Ui.button(this, "Write", Ui.RISKY)
        wr.setOnClickListener(click { rawWrite() })
        rawBtns.addView(rd, Ui.weighted())
        rawBtns.addView(spacer(10), LinearLayout.LayoutParams(Ui.dp(10), 1))
        rawBtns.addView(wr, Ui.weighted())
        col.addView(rawBtns, Ui.full())
        regResult = caption("")
        col.addView(regResult)

        // display
        col.addView(heading("Display", null))
        val uRow = Ui.row(this)
        val uLabel = Ui.text(this, 17f, Ui.PUMICE, Ui.bodyMedium)
        uLabel.text = "Show speed and distance in mph"
        uRow.addView(uLabel, Ui.weighted())
        unitsSwitch = Switch(this)
        tintSwitch(unitsSwitch)
        unitsSwitch.isChecked = mph
        unitsSwitch.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(b: CompoundButton, checked: Boolean) {
                mph = checked
                prefs.edit().putBoolean("mph", checked).apply()
                dial.mph = checked
                refreshDash()
                refreshSetup()
                refreshGps()
            }
        })
        uRow.addView(unitsSwitch, Ui.wrap())
        uRow.setPadding(0, Ui.dp(6), 0, Ui.dp(6))
        col.addView(uRow, Ui.full())
        col.addView(caption("With its units switch on, the scooter sends speed as mph × 10 (whole mph only) and distance in thousandths of a mile. With it off, it should send km/h and km instead. This switch only changes how ScootDash shows them."))

        // diagnostics
        col.addView(heading("Diagnostics", null))
        diagText = caption("")
        diagText.typeface = Typeface.MONOSPACE
        diagText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        col.addView(diagText)

        // log
        col.addView(heading("Log", "Copies the recent back-and-forth with the scooter, plus GPS lines when the GPS check is on, so you can paste it into a chat."))
        val logBtns = Ui.row(this)
        val copy = Ui.button(this, "Copy log", Ui.PRIMARY)
        copy.setOnClickListener(click { copyLog() })
        val clear = Ui.button(this, "Clear log")
        clear.setOnClickListener(click { log.clear() })
        logBtns.addView(copy, Ui.weighted(1.4f))
        logBtns.addView(spacer(10), LinearLayout.LayoutParams(Ui.dp(10), 1))
        logBtns.addView(clear, Ui.weighted())
        col.addView(logBtns, Ui.full())
        logCount = caption("")
        col.addView(logCount)
        logPreview = Ui.text(this, 11f, Ui.SLATE, Typeface.MONOSPACE)
        logPreview.setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(10), Ui.dp(8))
        logPreview.background = Ui.box(Ui.BASALT, 8f)
        logPreview.setLineSpacing(Ui.dpf(2f), 1f)
        val lpLp = Ui.full()
        lpLp.topMargin = Ui.dp(8)
        col.addView(logPreview, lpLp)

        val about = caption("ScootDash 1.1. Built from a decoded capture of the stock 365Scooter app. Unofficial and unsupported, so test changes standing still.")
        about.setPadding(0, Ui.dp(28), 0, 0)
        col.addView(about)
        return scroller(col)
    }

    private fun buildGpsSection(): View {
        val block = Ui.column(this)
        block.addView(heading("GPS check", "Once a second, ScootDash logs how far GPS says you went and how fast, next to what the scooter reported for the same second. From that it works out the scale factor: ×0.88 would mean the scooter reads 14 % high. Ride a few minutes under open sky, above 8 mph (13 km/h), and vary your speed: the scooter only reports whole mph, and at one steady speed that rounding never averages out. Only distances go in the log, never your coordinates."))
        val row = Ui.row(this)
        row.setPadding(0, Ui.dp(6), 0, Ui.dp(6))
        val l = Ui.text(this, 17f, Ui.PUMICE, Ui.bodyMedium)
        l.text = "Compare with GPS"
        row.addView(l, Ui.weighted())
        gpsSwitch = Switch(this)
        tintSwitch(gpsSwitch)
        gpsSwitch.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(b: CompoundButton, checked: Boolean) {
                if (syncing) return
                toggleGps(checked)
            }
        })
        row.addView(gpsSwitch, Ui.wrap())
        block.addView(row, Ui.full())
        gpsStatus = caption("")
        block.addView(gpsStatus)

        val btns = Ui.row(this)
        btns.setPadding(0, Ui.dp(10), 0, 0)
        saveFactorBtn = Ui.button(this, "Save factor")
        saveFactorBtn.setOnClickListener(click { saveFactor() })
        val reset = Ui.button(this, "Reset totals")
        reset.setOnClickListener(click { gps.reset() })
        btns.addView(saveFactorBtn, Ui.weighted())
        btns.addView(spacer(10), LinearLayout.LayoutParams(Ui.dp(10), 1))
        btns.addView(reset, Ui.weighted())
        block.addView(btns, Ui.full())

        val aRow = Ui.row(this)
        aRow.setPadding(0, Ui.dp(12), 0, Ui.dp(4))
        val al = Ui.text(this, 17f, Ui.PUMICE, Ui.bodyMedium)
        al.text = "Correct speed and distance with the saved factor"
        aRow.addView(al, Ui.weighted())
        applySwitch = Switch(this)
        tintSwitch(applySwitch)
        applySwitch.isChecked = applyFactor
        applySwitch.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(b: CompoundButton, checked: Boolean) {
                if (syncing) return
                if (checked && savedFactor == 1.0) {
                    toast("Save a factor first")
                    syncing = true; b.isChecked = false; syncing = false
                    return
                }
                applyFactor = checked
                prefs.edit().putBoolean("applyFactor", checked).apply()
                log.event("GPS correction " + (if (checked) "on, x" + fmt3(savedFactor) else "off"))
                refreshDash()
                refreshGps()
            }
        })
        aRow.addView(applySwitch, Ui.wrap())
        block.addView(aRow, Ui.full())
        return block
    }

    private fun buildLimits(): View {
        val block = Ui.column(this)
        val edge = android.graphics.drawable.GradientDrawable()
        edge.setColor(Ui.alpha(Ui.CAUTION, 0x10))
        edge.setStroke(Ui.dp(1), Ui.alpha(Ui.CAUTION, 0x88))
        edge.setCornerRadius(Ui.dpf(12f))
        block.background = edge
        block.setPadding(Ui.dp(14), 0, Ui.dp(14), Ui.dp(14))
        val lp = Ui.full()
        lp.topMargin = Ui.dp(28)
        block.layoutParams = lp
        val h = heading("Speed limit per mode", "The scooter answers every write with the value it kept. Lower values stick. Anything above 31 km/h is clamped: asking Drive for 42 while it sat at 14 set it to 31. Still untested: whether another mode can go above its factory value while staying under 31, for example Bike at 25 (factory 18).")
        (h.layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(14)
        block.addView(h)
        for (g in 0 until 4) {
            val row = Ui.row(this)
            row.setPadding(0, Ui.dp(10), 0, Ui.dp(4))
            val info = Ui.column(this)
            val name = Ui.text(this, 17f, Ui.PUMICE, Ui.bodySemi)
            name.text = MODE_EMOJI[g] + " " + Proto.MODE_NAMES[g]
            val now = Ui.text(this, 13f, Ui.SLATE, Ui.body)
            info.addView(name)
            info.addView(now)
            limitNow[g] = now
            row.addView(info, Ui.weighted())
            val minus = stepButton("−")
            minus.setOnClickListener(click { nudgeLimit(g, -1) })
            val value = Ui.text(this, 26f, Ui.CAUTION, Ui.condSemi)
            value.gravity = Gravity.CENTER
            value.minWidth = Ui.dp(44)
            limitEdit[g] = value
            val plus = stepButton("+")
            plus.setOnClickListener(click { nudgeLimit(g, +1) })
            val write = Ui.button(this, "Write", Ui.RISKY)
            write.setOnClickListener(click { confirmLimitWrite(g) })
            row.addView(minus, LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)))
            row.addView(value, Ui.wrap())
            row.addView(plus, LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)))
            val wlp = Ui.wrap()
            wlp.leftMargin = Ui.dp(10)
            row.addView(write, wlp)
            block.addView(row, Ui.full())
        }
        val readAll = Ui.button(this, "Read all four")
        readAll.setOnClickListener(click {
            for (g in 0 until 4) if (!command(Proto.readRegister(Proto.TOP_SPEED_REG[g]))) break
        })
        val rlp = Ui.full()
        rlp.topMargin = Ui.dp(12)
        block.addView(readAll, rlp)
        return block
    }

    // ------------------------------------------------------- small builders
    private fun click(body: () -> Unit): View.OnClickListener = object : View.OnClickListener {
        override fun onClick(v: View) { body() }
    }

    private fun heading(title: String, sub: String?): LinearLayout {
        val c = Ui.column(this)
        val lp = Ui.full()
        lp.topMargin = Ui.dp(28)
        lp.bottomMargin = Ui.dp(10)
        c.layoutParams = lp
        val t = Ui.text(this, 24f, Ui.PUMICE, Ui.condSemi)
        t.text = title
        c.addView(t)
        if (sub != null) {
            val s = Ui.text(this, 14f, Ui.SLATE, Ui.body)
            s.text = sub
            s.setPadding(0, Ui.dp(6), 0, 0)
            s.setLineSpacing(Ui.dpf(2f), 1f)
            c.addView(s)
        }
        return c
    }

    private fun caption(s: String): TextView {
        val t = Ui.text(this, 13.5f, Ui.SLATE, Ui.body)
        t.text = s
        t.setPadding(0, Ui.dp(8), 0, 0)
        t.setLineSpacing(Ui.dpf(2f), 1f)
        return t
    }

    @Suppress("UNUSED_PARAMETER")
    private fun spacer(dp: Int): View = View(this)

    private fun field(hint: String, type: Int): EditText {
        val e = EditText(this)
        e.hint = hint
        e.inputType = type
        e.setSingleLine(true)
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        e.setTextColor(Ui.PUMICE)
        e.setHintTextColor(Ui.SLATE)
        e.typeface = Ui.bodyMedium
        e.background = Ui.box(Ui.BASALT, 10f, 1f, Ui.RIDGE)
        e.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12))
        return e
    }

    private fun stepButton(label: String): TextView {
        val b = Ui.text(this, 22f, Ui.PUMICE, Ui.bodyMedium)
        b.text = label
        b.gravity = Gravity.CENTER
        b.background = Ui.ripple(Ui.box(Ui.BASALT, 22f, 1f, Ui.RIDGE), 22f)
        b.isClickable = true
        return b
    }

    private fun slider(l: SeekBar.OnSeekBarChangeListener): SeekBar {
        val s = SeekBar(this)
        s.max = 8
        s.progressTintList = ColorStateList.valueOf(Ui.PUMICE)
        s.thumbTintList = ColorStateList.valueOf(Ui.PUMICE)
        s.progressBackgroundTintList = ColorStateList.valueOf(Ui.RIDGE)
        s.setOnSeekBarChangeListener(l)
        s.setPadding(Ui.dp(4), Ui.dp(12), Ui.dp(4), Ui.dp(12))
        PanelPager.holdTouch(s)
        return s
    }

    private fun sliderBlock(name: String, value: TextView, bar: SeekBar): LinearLayout {
        val c = Ui.column(this)
        c.setPadding(0, Ui.dp(6), 0, Ui.dp(4))
        val top = Ui.row(this)
        val l = Ui.text(this, 17f, Ui.PUMICE, Ui.bodyMedium)
        l.text = name
        top.addView(l, Ui.weighted())
        value.text = "–"
        top.addView(value, Ui.wrap())
        c.addView(top, Ui.full())
        c.addView(bar, Ui.full())
        return c
    }

    private fun switchRow(i: Int, name: String): LinearLayout {
        val row = Ui.row(this)
        row.setPadding(0, Ui.dp(8), 0, Ui.dp(8))
        val txt = Ui.column(this)
        val l = Ui.text(this, 17f, Ui.PUMICE, Ui.bodyMedium)
        l.text = name
        val c = Ui.text(this, 13f, Ui.SLATE, Ui.body)
        c.text = "Register 0x" + Proto.hex2(switchRegs[i])
        c.setPadding(0, Ui.dp(3), 0, 0)
        txt.addView(l)
        txt.addView(c)
        row.addView(txt, Ui.weighted())
        val sw = Switch(this)
        tintSwitch(sw)
        sw.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(b: CompoundButton, checked: Boolean) {
                if (syncing) return
                if (!command(Proto.writeRegister(switchRegs[i], if (checked) 1 else 0))) {
                    syncing = true
                    b.isChecked = !checked
                    syncing = false
                }
            }
        })
        switches[i] = sw
        row.addView(sw, Ui.wrap())
        return row
    }

    private fun tintSwitch(sw: Switch) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(Ui.PUMICE, Ui.SLATE))
        sw.trackTintList = ColorStateList(states, intArrayOf(Ui.alpha(Ui.SAGE, 0xAA), Ui.RIDGE))
    }

    // --------------------------------------------------------------- actions
    /** Sends if connected; returns false (and says so) otherwise. */
    private fun command(wire: ByteArray): Boolean {
        if (!link.isReady) { toast("Not connected yet"); return false }
        link.send(wire)
        return true
    }

    /**
     * The mode buttons sit right under the dial, and Walk caps the scooter at a few km/h.
     * So while you're moving, a tap only explains itself and a touch-and-hold switches.
     * "Moving" goes by the last frame received, even a stale one: right after a Bluetooth
     * reconnect is exactly when you're likely to still be riding.
     */
    private fun pickMode(g: Int, held: Boolean) {
        val s = status
        if (!held && s != null && s.speedKmh >= MOVING_KMH && s.gear != g) {
            toast("While riding, touch and hold " + Proto.MODE_NAMES[g] + " to switch")
            return
        }
        command(Proto.writeRegister(Proto.REG_GEAR, g))
    }

    private fun onConnectButton() {
        val s = link.state
        if (s == Link.READY || s == Link.CONNECTING || s == Link.WAITING || s == Link.SCANNING) {
            link.stopScan()
            link.disconnect()
        } else connectWithPermission()
    }

    private fun sendPassword() {
        val pin = pinField.text.toString()
        if (pin.length != 6) { toast("The password is 6 digits"); return }
        prefs.edit().putString("pin", pin).apply()
        if (!command(Proto.password(pin))) return
        pinResult.text = "Sent. Waiting for the scooter…"
    }

    private fun nudgeLimit(g: Int, d: Int) {
        val base = if (pendingLimit[g] >= 0) pendingLimit[g] else if (limits[g] >= 0) limits[g] else 0
        pendingLimit[g] = Math.max(1, Math.min(MAX_WRITE_KMH, base + d))
        refreshSetup()
    }

    private fun confirmLimitWrite(g: Int) {
        val v = pendingLimit[g]
        if (v < 1) { toast("Read the current value first"); return }
        if (!link.isReady) { toast("Not connected yet"); return }
        val reg = Proto.TOP_SPEED_REG[g]
        val back = if (firstLimits[g] >= 0) " To undo, write " + firstLimits[g] + "." else ""
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Set " + Proto.MODE_NAMES[g] + " to $v km/h?")
            .setMessage("Sends register 0x" + Proto.hex2(reg) + " = " + v + " km/h (" + fmt1(v / Proto.MI_KM) + " mph). " +
                "Do this standing still. The scooter answers with the value it kept." + back)
            .setPositiveButton("Write", object : DialogInterface.OnClickListener {
                override fun onClick(d: DialogInterface, which: Int) {
                    if (!command(Proto.writeRegister(reg, v))) return
                    askedLimit[g] = v
                    askedAt[g] = SystemClock.elapsedRealtime()
                    log.event("user asked " + Proto.modeTag(g) + " for $v km/h (was " + limits[g] + ")")
                    readBack(reg, 400)
                    readBack(reg, 1_500)
                    refreshSetup()
                    main.removeCallbacks(refreshSetupLater)
                    main.postDelayed(refreshSetupLater, LIMIT_REPLY_MS + 100)
                }
            })
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Turns "waiting…" into "no reply" if the scooter never answers a limit write. */
    private val refreshSetupLater = object : Runnable {
        override fun run() { refreshSetup() }
    }

    private fun limitWaiting(g: Int): Boolean = askedLimit[g] >= 0 && replyAt[g] < askedAt[g]

    private fun readBack(reg: Int, delay: Long) {
        main.postDelayed(object : Runnable {
            override fun run() { if (link.isReady) link.send(Proto.readRegister(reg)) }
        }, delay)
    }

    private fun parseHexByte(e: EditText): Int {
        val s = e.text.toString().trim()
        if (s.isEmpty()) return -1
        return try { Integer.parseInt(s, 16).let { if (it in 0..255) it else -1 } } catch (x: NumberFormatException) { -1 }
    }

    private fun rawRead() {
        val r = parseHexByte(regField)
        if (r < 0) { toast("Register is one hex byte, like 05"); return }
        if (!command(Proto.readRegister(r))) return
        rawReg = r
        regResult.text = "Reading 0x" + Proto.hex2(r) + "…"
    }

    private fun rawWrite() {
        val r = parseHexByte(regField)
        val v = parseHexByte(valField)
        if (r < 0 || v < 0) { toast("Register and value are hex bytes, like 05 and 02"); return }
        if (v == 0xFF) { toast("FF means read. Use Read instead"); return }
        if (!link.isReady) { toast("Not connected yet"); return }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Write register 0x" + Proto.hex2(r) + "?")
            .setMessage("Sends 0x" + Proto.hex2(r) + " = 0x" + Proto.hex2(v) + " (" + v + "). Unknown registers can change settings you can't see in this app.")
            .setPositiveButton("Write", object : DialogInterface.OnClickListener {
                override fun onClick(d: DialogInterface, which: Int) {
                    if (!command(Proto.writeRegister(r, v))) return
                    rawReg = r
                    regResult.text = "Wrote 0x" + Proto.hex2(r) + " = 0x" + Proto.hex2(v) + ", reading back…"
                    readBack(r, 500)
                }
            })
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------- GPS check
    private fun toggleGps(on: Boolean) {
        if (!on) {
            gps.stop()
            log.quietStatus = false
            refreshGps()
            return
        }
        val need = missingLocation()
        if (need.isNotEmpty()) { pendingAction = ACTION_GPS; requestPermissions(need, REQ_PERMS); refreshGps(); return }
        if (!gps.gpsEnabled()) {
            toast("Turn on Location, then try again")
            try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (e: RuntimeException) { }
            refreshGps()
            return
        }
        if (gps.start()) log.quietStatus = true
        refreshGps()
    }

    private fun saveFactor() {
        val k = gps.speedFactor
        if (k.isNaN() || gps.goodSeconds < 30) { toast("Ride at least 30 counted seconds first"); return }
        savedFactor = k
        prefs.edit().putFloat("gpsFactor", k.toFloat()).apply()
        log.event("saved GPS factor x" + fmt3(k) + " from " + gps.goodSeconds + " s. " + gps.summary())
        toast("Saved ×" + fmt3(k) + ": scooter " + GpsCheck.readsText(k))
        refreshGps()
        refreshDash()
    }

    override fun onGps() {
        refreshGps()
    }

    private fun refreshGps() {
        if (!this::gpsStatus.isInitialized) return
        syncing = true
        if (gpsSwitch.isChecked != gps.running) gpsSwitch.isChecked = gps.running
        if (applySwitch.isChecked != applyFactor) applySwitch.isChecked = applyFactor
        syncing = false
        val k = gps.speedFactor
        val sb = StringBuilder()
        if (!gps.running) sb.append("Off.")
        else if (gps.lastFixAt == 0L) sb.append("Waiting for a GPS fix…")
        else {
            val age = (SystemClock.elapsedRealtime() - gps.lastFixAt) / 1000
            sb.append("Fix ").append(if (age <= 2) "live" else "$age s old").append(", accuracy ")
                .append(if (gps.accuracy >= 0) fmt0(gps.accuracy.toDouble()) + " m" else "unknown").append('.')
        }
        sb.append(' ').append(gps.goodSeconds).append(" seconds counted")
        if (!k.isNaN()) sb.append(", scooter speed ×").append(fmt3(k))
        val kd = gps.distanceFactor
        if (!kd.isNaN()) sb.append(", distance ×").append(fmt3(kd))
        sb.append('.')
        if (savedFactor != 1.0) sb.append(" Saved factor ×").append(fmt3(savedFactor)).append(if (applyFactor) ", applied." else ", not applied.")
        gpsStatus.text = sb.toString()
        saveFactorBtn.alpha = if (!k.isNaN() && gps.goodSeconds >= 30) 1f else 0.5f

        // dash tiles
        if (gps.running && gps.gpsKmh >= 0 && SystemClock.elapsedRealtime() - gps.lastFixAt < 3_000) {
            rGps.value.text = fmt1(dispSpeed(gps.gpsKmh))
            rGps.note(unitName() + ", acc " + (if (gps.accuracy >= 0) fmt0(gps.accuracy.toDouble()) else "?") + " m")
        } else {
            rGps.value.text = "—"
            rGps.note(if (gps.running) "waiting for fix" else "tap to start")
        }
        if (!k.isNaN()) {
            rFactor.value.text = "×" + fmt2(k)
            rFactor.note("scooter " + GpsCheck.readsText(k))
        } else if (savedFactor != 1.0) {
            rFactor.value.text = "×" + fmt2(savedFactor)
            rFactor.note(if (applyFactor) "saved, applied" else "saved")
        } else {
            rFactor.value.text = "—"
            rFactor.note("needs GPS riding")
        }
    }

    // ------------------------------------------------------------ copy log
    private fun copyLog() {
        val sb = StringBuilder()
        sb.append("ScootDash log (app 1.1, Android ").append(Build.VERSION.RELEASE).append(" / API ")
            .append(Build.VERSION.SDK_INT).append(", ").append(Build.MODEL).append(")\n")
        sb.append("copied ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            .append(", link: ").append(linkText.text).append(", device ").append(link.deviceName).append(' ').append(link.address).append('\n')
        sb.append("display ").append(if (mph) "mph" else "km/h").append(", status frames ")
            .append(fmt1(link.framesPerSecond)).append("/s\n")
        val s = status
        if (s != null) sb.append("now: ").append(Proto.describeRx(s)).append("\n      plain ").append(Proto.hex(s.plain)).append('\n')
        sb.append("speed limit km/h by mode: ")
        for (g in 0 until 4) {
            sb.append(Proto.MODE_NAMES[g]).append('=').append(limits[g])
            if (firstLimits[g] >= 0 && firstLimits[g] != limits[g]) sb.append(" (was ").append(firstLimits[g]).append(')')
            if (limitWaiting(g)) sb.append(" (asked ").append(askedLimit[g]).append(", no reply yet)")
            else if (askedLimit[g] >= 0 && askedLimit[g] != limits[g]) sb.append(" (asked ").append(askedLimit[g]).append(')')
            if (g < 3) sb.append(", ")
        }
        sb.append("\ngps: ").append(if (gps.running) "on" else "off").append(", ").append(gps.summary())
            .append(", saved factor x").append(fmt3(savedFactor)).append(if (applyFactor) " (applied)" else " (not applied)")
        val text = log.export(sb.toString(), EXPORT_LINES)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ScootDash log", text))
        toast("Copied " + Math.min(EXPORT_LINES, log.size) + " log lines")
    }

    // ----------------------------------------------------------- permissions
    private fun missing(scan: Boolean): Array<String?> {
        val want = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            want.add("android.permission.BLUETOOTH_CONNECT")
            if (scan) {
                want.add("android.permission.BLUETOOTH_SCAN")
                // Android 12+ ignores a FINE request that doesn't also ask for COARSE
                want.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                want.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        } else if (scan) {
            want.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            want.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return notGranted(want)
    }

    private fun missingLocation(): Array<String?> {
        val want = ArrayList<String>()
        want.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        want.add(Manifest.permission.ACCESS_FINE_LOCATION)
        return notGranted(want)
    }

    private fun notGranted(want: ArrayList<String>): Array<String?> {
        val miss = ArrayList<String>()
        for (p in want) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) miss.add(p)
        val out = arrayOfNulls<String>(miss.size)
        for (i in 0 until miss.size) out[i] = miss[i]
        return out
    }

    private fun connectWithPermission() {
        val need = missing(false)
        if (need.isNotEmpty()) { pendingAction = ACTION_CONNECT; requestPermissions(need, REQ_PERMS); return }
        if (!link.bluetoothOn()) { askBluetoothOn(ACTION_CONNECT); return }
        val mac = cleanMac(macField.text.toString())
        macField.setText(mac)
        prefs.edit().putString("mac", mac).apply()
        link.connect(mac)
    }

    private fun scanWithPermission() {
        val need = missing(true)
        if (need.isNotEmpty()) { pendingAction = ACTION_SCAN; requestPermissions(need, REQ_PERMS); return }
        if (!link.bluetoothOn()) { askBluetoothOn(ACTION_SCAN); return }
        if (link.state != Link.IDLE && link.state != Link.FAILED) link.disconnect()
        link.scan()
    }

    private fun askBluetoothOn(action: Int) {
        pendingAction = action
        try {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT)
        } catch (e: RuntimeException) {
            onLinkState(Link.FAILED, "Bluetooth is off")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_PERMS) return
        if (grantResults.isEmpty()) return   // request was interrupted; wait for the user to try again
        var all = true
        for (r in grantResults) if (r != PackageManager.PERMISSION_GRANTED) all = false
        if (!all) {
            log.error("permission denied: " + permissions.size + " requested")
            if (pendingAction == ACTION_GPS) {
                toast("The GPS check needs precise location")
                refreshGps()
                return
            }
            onLinkState(Link.FAILED, "Needs Nearby devices permission" + if (pendingAction == ACTION_SCAN) " and Location to scan" else "")
            return
        }
        when (pendingAction) {
            ACTION_SCAN -> scanWithPermission()
            ACTION_GPS -> toggleGps(true)
            else -> connectWithPermission()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_BT) return
        if (resultCode == RESULT_OK) {
            if (pendingAction == ACTION_SCAN) scanWithPermission() else connectWithPermission()
        } else onLinkState(Link.FAILED, "Bluetooth is off")
    }

    // ----------------------------------------------------- link callbacks
    override fun onLinkState(state: Int, detail: String) {
        val name = if (link.deviceName.isNotEmpty()) link.deviceName else link.address
        val (color, text) = when (state) {
            Link.READY -> Pair(Ui.SAGE, "Connected to $name")
            Link.CONNECTING -> Pair(Ui.CAUTION, detail)
            Link.SCANNING -> Pair(Ui.CAUTION, detail)
            Link.WAITING -> Pair(Ui.CAUTION, detail)
            Link.FAILED -> Pair(Ui.SIGNAL, detail)
            else -> Pair(Ui.SLATE, detail)
        }
        linkDot.background = Ui.box(color, 5f)
        linkText.text = text
        val active = state == Link.READY || state == Link.CONNECTING || state == Link.WAITING || state == Link.SCANNING
        connectBtn.text = if (active) "Disconnect" else "Connect"
        Ui.styleButton(connectBtn, if (active) Ui.QUIET else Ui.PRIMARY)
        if (state != Link.READY) {
            dial.stateText = when (state) {
                Link.CONNECTING, Link.WAITING -> "Connecting…"
                Link.SCANNING -> "Searching…"
                else -> "Not connected"
            }
        }
        refreshDash()
    }

    override fun onStatus(s: Proto.Status) {
        status = s
        gps.onScooter(s)
        if (s.speedKmh > peakRaw) peakRaw = s.speedKmh
        refreshDash()
        syncSetup(s)
    }

    override fun onReply(m: Proto.Msg) {
        if (m is Proto.RegReply) {
            val g = m.gearForTopSpeed
            if (g >= 0) {
                val changed = limits[g] != m.value
                val wasWaiting = limitWaiting(g)
                limits[g] = m.value
                replyAt[g] = SystemClock.elapsedRealtime()
                if (firstLimits[g] < 0) firstLimits[g] = m.value
                if (pendingLimit[g] < 0) pendingLimit[g] = m.value
                if (changed) refreshDash()
                if (changed || wasWaiting) refreshSetup()
            }
            if (m.reg == rawReg) regResult.text = "Register 0x" + Proto.hex2(m.reg) + " = " + m.value + " (0x" + Proto.hex2(m.value) + ")"
        } else if (m is Proto.PwReply) {
            pinResult.text = if (m.accepted) "Scooter accepted the password (reply FF)." else "Scooter replied 0x" + Proto.hex2(m.result) + "."
        } else {
            regResult.text = "Unrecognised reply: " + Proto.hex(m.plain)
        }
    }

    override fun onScanFound(name: String, address: String) {
        macField.setText(address)
        prefs.edit().putString("mac", address).apply()
        toast("Found $name")
        link.connect(address, name)
    }

    override fun onLogChanged() {
        if (!this::logCount.isInitialized) return
        logCount.text = "" + log.size + " lines recorded. Copy takes the newest " + EXPORT_LINES + "."
        val sb = StringBuilder()
        for (l in log.tail(8)) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(if (l.length > 160) l.substring(0, 160) + "…" else l)
        }
        logPreview.text = sb.toString()
    }

    // --------------------------------------------------------------- refresh
    private val ticker = object : Runnable {
        override fun run() {
            if (link.state == Link.READY) {
                val age = SystemClock.elapsedRealtime() - link.lastFrameAt
                linkRate.text = if (age > 2_000) "no data " + (age / 1000) + " s" else fmt1(link.framesPerSecond) + "/s"
                refreshDash()
            } else linkRate.text = ""
            refreshGps()
            main.postDelayed(this, 1_000)
        }
    }

    private fun maxLimit(): Int {
        var m = -1
        for (v in limits) if (v > m) m = v
        return if (m > 0) m else 31
    }

    /** A speed or distance as shown: the scooter's value, times the saved GPS factor when that's on. */
    private fun corrected(v: Double): Double = if (applyFactor) v * savedFactor else v

    private fun refreshDash() {
        val s = status
        val ready = link.state == Link.READY && s != null && link.lastFrameAt > link.connectedAt
        val age = SystemClock.elapsedRealtime() - link.lastFrameAt
        dial.connected = ready && age < 3_000
        dial.mph = mph
        dial.maxKmh = corrected(maxLimit().toDouble())
        if (s != null) {
            dial.speedKmh = corrected(s.speedKmh)
            dial.gear = s.gear
            dial.cruise = s.cruise
            dial.locked = s.lock
            dial.capKmh = if (s.gear in 0..3 && limits[s.gear] > 0) corrected(limits[s.gear].toDouble()) else -1.0
            if (ready) {
                val mode = if (s.gear in 0..3) MODE_EMOJI[s.gear] + " " + Proto.MODE_NAMES[s.gear] else "Mode ?"
                dial.stateText = if (age >= 3_000) "No data for " + (age / 1000) + " s"
                else if (dial.capKmh > 0) mode + ", up to " + speedText(dial.capKmh)
                else mode
            }
        }
        dial.peakKmh = corrected(peakRaw)
        dial.invalidate()
        val sel = if (s != null && dial.connected) s.gear else -1
        if (sel != shownMode) {
            shownMode = sel
            for (g in 0 until 4) styleMode(g, sel == g)
        }
        for (g in 0 until 4) modeCaps[g]?.text = if (limits[g] > 0) speedText(corrected(limits[g].toDouble())) else "–"

        if (s == null) return
        rBattery.value.text = "" + s.battery + "%"
        rBattery.bar?.set(s.battery / 100f, if (s.battery <= 15) Ui.SIGNAL else if (s.battery <= 30) Ui.CAUTION else Ui.SAGE)
        if (s.reserved67 == 0) {
            rPower.value.text = "—"
            rPower.note("not reported")
        } else {
            rPower.value.text = "" + s.reserved67
            rPower.note("raw, unknown units")
        }
        rAccel.value.text = "" + s.accel
        rAccel.bar?.set(s.accel / 9f, Ui.PUMICE)
        rBrake.value.text = "" + s.brake
        rBrake.bar?.set(s.brake / 9f, Ui.PUMICE)
        rTrip.value.text = distText(corrected(s.tripMeters), 2)
        rOdo.value.text = distText(corrected(s.odoMeters), 1)
        rRaw.value.text = "" + s.speedRaw
        rRaw.note("= " + fmt1(s.speedRaw / 10.0) + " " + s.speedUnit + (if (applyFactor) ", shown ×" + fmt2(savedFactor) else ""))
        tCruise.setOn(s.cruise)
        tZero.setOn(s.zeroStart)
        tLight.setOn(s.headlight)
        tLock.setOn(s.lock)
        tUnits.setOn(s.unitsFlag)
        if (this::diagText.isInitialized) {
            diagText.text = "speed field  " + s.speedRaw + " = " + fmt1(s.speedRaw / 10.0) + " " + s.speedUnit +
                "\ntrip         " + s.tripRaw + " × 0.001 " + (if (s.unitsFlag) "mi" else "km") +
                "\nodometer     " + s.odoRaw + " × 0.001 " + (if (s.unitsFlag) "mi" else "km") +
                "\ntrip timer   " + String.format(Locale.US, "%,d", s.tripTime) + " counts" +
                "\ntotal timer  " + String.format(Locale.US, "%,d", s.totalTime) + " counts" +
                "\nbytes 6–7    " + s.reserved67 +
                "\nframes       " + fmt1(link.framesPerSecond) + " per second"
        }
    }

    private fun styleMode(g: Int, on: Boolean) {
        val cell = modeCells[g] ?: return
        cell.background = Ui.ripple(if (on) Ui.box(Ui.PUMICE, 12f) else Ui.box(Ui.BASALT, 12f, 1f, Ui.RIDGE), 12f)
        modeLabels[g]?.setTextColor(if (on) Ui.DUSK else Ui.PUMICE)
        modeCaps[g]?.setTextColor(if (on) Ui.DUSK else Ui.SLATE)
    }

    private fun syncSetup(s: Proto.Status) {
        syncing = true
        val vals = booleanArrayOf(s.cruise, s.zeroStart, s.headlight, s.lock, s.unitsFlag)
        for (i in 0 until 5) {
            val sw = switches[i] ?: continue
            if (sw.isChecked != vals[i]) sw.isChecked = vals[i]
        }
        if (!draggingAccel && s.accel in 1..9) { accelBar.progress = s.accel - 1; accelVal.text = "" + s.accel }
        if (!draggingBrake && s.brake in 1..9) { brakeBar.progress = s.brake - 1; brakeVal.text = "" + s.brake }
        syncing = false
    }

    private fun resyncSliders() {
        val st = status
        if (st != null) { syncSetup(st); return }
        accelVal.text = "–"; brakeVal.text = "–"
    }

    private fun refreshSetup() {
        val t = SystemClock.elapsedRealtime()
        for (g in 0 until 4) {
            val now = limitNow[g] ?: continue
            val sb = StringBuilder()
            if (limits[g] > 0) sb.append("Now ").append(limits[g]).append(" km/h (").append(fmt1(limits[g] / Proto.MI_KM)).append(" mph)")
            else sb.append("Not read yet")
            if (limitWaiting(g)) {
                sb.append(", asked ").append(askedLimit[g]).append(if (t - askedAt[g] < LIMIT_REPLY_MS) ", waiting…" else ", no reply")
            } else if (askedLimit[g] >= 0 && askedLimit[g] != limits[g]) {
                sb.append(", asked ").append(askedLimit[g]).append(", kept ").append(limits[g])
            }
            if (limits[g] > 0 && firstLimits[g] >= 0 && firstLimits[g] != limits[g]) sb.append(", was ").append(firstLimits[g])
            sb.append(". Register 0x").append(Proto.hex2(Proto.TOP_SPEED_REG[g]))
            now.text = sb.toString()
            limitEdit[g]?.text = if (pendingLimit[g] > 0) pendingLimit[g].toString() else "–"
        }
    }

    // --------------------------------------------------------------- format
    private fun dispSpeed(kmh: Double): Double = if (mph) kmh / Proto.MI_KM else kmh

    private fun unitName(): String = if (mph) "mph" else "km/h"

    private fun speedText(kmh: Double): String = fmt0(dispSpeed(kmh)) + " " + unitName()

    private fun distText(m: Double, decimals: Int): String {
        val f = "%." + decimals + "f"
        return if (mph) String.format(Locale.US, "$f mi", m / 1609.344) else String.format(Locale.US, "$f km", m / 1000.0)
    }

    private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)
    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
    private fun fmt3(v: Double): String = String.format(Locale.US, "%.3f", v)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        const val REQ_PERMS = 7
        const val REQ_BT = 8
        const val ACTION_CONNECT = 1
        const val ACTION_SCAN = 2
        const val ACTION_GPS = 3
        const val MAX_WRITE_KMH = 45
        const val EXPORT_LINES = 1500
        /** Above this, a tap on a mode button only explains; touch and hold switches. */
        const val MOVING_KMH = 5.0
        const val LIMIT_REPLY_MS = 4_000L

        /** 🚶‍♀️ 🏃‍♀️ 🚴‍♀️ 🛵 for gears 0..3 */
        val MODE_EMOJI = arrayOf(
            "🚶‍♀️",
            "🏃‍♀️",
            "🚴‍♀️",
            "🛵")
    }
}
