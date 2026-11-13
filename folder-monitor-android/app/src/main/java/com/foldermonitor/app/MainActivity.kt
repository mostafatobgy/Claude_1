package com.foldermonitor.app

import android.Manifest
import android.app.TimePickerDialog
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: FolderAdapter
    private lateinit var statusText: TextView
    private lateinit var rulesText: TextView
    private lateinit var empty: TextView
    private lateinit var setupCard: View
    private lateinit var setupButtons: LinearLayout
    private lateinit var enabledSwitch: MaterialSwitch
    private var settingSwitch = false

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun attachBaseContext(base: Context) {
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale("ar"))
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyInsets()
        Notifications.ensureChannels(this)

        statusText = findViewById(R.id.statusText)
        rulesText = findViewById(R.id.rulesText)
        empty = findViewById(R.id.empty)
        setupCard = findViewById(R.id.setupCard)
        setupButtons = findViewById(R.id.setupButtons)
        enabledSwitch = findViewById(R.id.enabledSwitch)

        adapter = FolderAdapter(::confirmRemove)
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        findViewById<View>(R.id.addFab).setOnClickListener { addFolder() }
        findViewById<View>(R.id.settingsBtn).setOnClickListener { showSettings() }
        findViewById<View>(R.id.refreshBtn).setOnClickListener { checkNow() }
        enabledSwitch.setOnCheckedChangeListener { _, on -> if (!settingSwitch) setMonitoring(on) }

        if (Build.VERSION.SDK_INT >= 33 && !hasNotifications()) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, updateReceiver, IntentFilter(Checker.ACTION_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Make sure monitoring is really running if it is switched on.
        if (Store.isEnabled(this)) Scheduler.ensureScheduled(this)
        refresh()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(updateReceiver)
    }

    // ----------------------------------------------------------- permissions

    private fun hasStorage() =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasNotifications() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    private fun ignoresBattery() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun buildSetup() {
        while (setupButtons.childCount > 1) setupButtons.removeViewAt(1)
        fun add(text: String, required: Boolean, action: () -> Unit) {
            val b = MaterialButton(
                this, null,
                if (required) com.google.android.material.R.attr.materialButtonStyle
                else com.google.android.material.R.attr.materialButtonOutlinedStyle
            )
            b.text = text
            b.isAllCaps = false
            b.setOnClickListener { action() }
            setupButtons.addView(b, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 })
        }
        if (!hasStorage()) add("السماح بقراءة أحجام الملفات (ضروري)", true, ::requestStorage)
        if (!hasNotifications()) add("السماح بالإشعارات (ضروري للتنبيه)", true) {
            if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!ignoresBattery()) add("السماح بالعمل في الخلفية (مستحسن)", false) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        if (Build.VERSION.SDK_INT >= 31 && !Scheduler.canExact(this)) add("السماح بالفحص في الموعد بالضبط (مستحسن)", false) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
        setupCard.visibility = if (setupButtons.childCount > 1) View.VISIBLE else View.GONE
    }

    // --------------------------------------------------------------- actions

    private fun setMonitoring(on: Boolean) {
        if (on) {
            if (!hasStorage()) {
                Toast.makeText(this, "اسمح أولًا بقراءة الملفات", Toast.LENGTH_LONG).show()
                requestStorage()
            } else if (Store.targets(this).isEmpty()) {
                Toast.makeText(this, "أضف فولدرًا واحدًا على الأقل", Toast.LENGTH_SHORT).show()
            } else {
                startMonitoring()
            }
        } else {
            Store.setEnabled(this, false)
            Scheduler.cancel(this)
        }
        refresh()
    }

    /** Turn monitoring on, set the next alarm, and measure now so there is a starting size. */
    private fun startMonitoring() {
        Store.setEnabled(this, true)
        Scheduler.scheduleNext(this)
        Checker.runAsync(this)
    }

    private fun addFolder() {
        if (!hasStorage()) {
            Toast.makeText(this, "اسمح أولًا بقراءة الملفات، ثم ارجع للتطبيق", Toast.LENGTH_LONG).show()
            requestStorage()
            return
        }
        FolderPicker(this) { dir: File ->
            val path = dir.absolutePath
            val targets = Store.targets(this)
            if (path in folders) {
                Toast.makeText(this, "هذا الفولدر مضاف بالفعل", Toast.LENGTH_SHORT).show()
                return@FolderPicker
            }
            Store.setFolders(this, folders + path)
            if (!Store.isEnabled(this)) startMonitoring() else Checker.runAsync(this)
            Toast.makeText(this, "جاري قياس الحجم…", Toast.LENGTH_SHORT).show()
            refresh()
        }.show()
    }

    private fun confirmRemove(path: String) {
        val isPhone = path == Alert.PHONE
        AlertDialog.Builder(this)
            .setMessage(if (isPhone) "إيقاف فحص الهاتف كله؟\n(يمكن تشغيله مرة أخرى من الإعدادات)" else "إيقاف مراقبة «${File(path).name}»؟")
            .setPositiveButton(if (isPhone) "إيقاف" else "حذف") { _, _ ->
                if (isPhone) Store.setPhoneEnabled(this, false) else Store.setFolders(this, Store.folders(this) - path)
                if (Store.targets(this).isEmpty()) { Store.setEnabled(this, false); Scheduler.cancel(this) }
                refresh()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun checkNow() {
        if (Store.targets(this).isEmpty()) return
        if (Checker.isBusy) {
            Toast.makeText(this, "الفحص جارٍ بالفعل…", Toast.LENGTH_SHORT).show()
            return
        }
        Checker.runAsync(this)
        refresh()
    }

    private fun showSettings() {
        val r = Store.rules(this)
        var hour = r.dailyHour
        var minute = r.dailyMinute
        val container = layoutInflater.inflate(R.layout.dialog_settings, null)
        val fields = container.findViewById<LinearLayout>(R.id.fields)
        fun params(top: Int = 12) = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = top * resources.displayMetrics.density.toInt() }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            fields.addView(this, params(16))
        }
        fun field(hint: String, value: Number, parent: LinearLayout = fields): TextInputEditText {
            val layout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle)
            layout.hint = hint
            val edit = TextInputEditText(layout.context)
            edit.inputType = InputType.TYPE_CLASS_NUMBER
            edit.setText(value.toString())
            layout.addView(edit)
            parent.addView(layout, params())
            return edit
        }

        label("متى يتم الفحص؟")
        val toggle = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        fun toggleButton(text: String) = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            id = View.generateViewId()
            this.text = text
            toggle.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val dailyBtn = toggleButton("يوميًا")
        val intervalBtn = toggleButton("كل عدة دقائق")
        fields.addView(toggle, params(4))

        // daily: pick the time
        val timeBtn = MaterialButton(this).apply {
            fun show() { text = "الساعة " + formatClock(hour, minute) + "  (اضغط للتغيير)" }
            show()
            setOnClickListener {
                TimePickerDialog(this@MainActivity, { _, h, m -> hour = h; minute = m; show() }, hour, minute, false).show()
            }
        }
        fields.addView(timeBtn, params(8))

        // interval: how often and over what period
        val intervalBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val interval = field("افحص كل (دقيقة)", r.intervalMin, intervalBox)
        val window = field("قارن بالحجم قبل (دقيقة)", r.windowMin, intervalBox)
        val cooldown = field("لا تكرر التنبيه لنفس الفولدر قبل (دقيقة)", r.cooldownMin, intervalBox)
        fields.addView(intervalBox, params(0))

        fun showMode(daily: Boolean) {
            timeBtn.visibility = if (daily) View.VISIBLE else View.GONE
            intervalBox.visibility = if (daily) View.GONE else View.VISIBLE
        }
        toggle.addOnButtonCheckedListener { _, id, checked -> if (checked) showMode(id == dailyBtn.id) }
        toggle.check(if (r.daily) dailyBtn.id else intervalBtn.id)
        showMode(r.daily)

        label("📱 الهاتف كله: نبّهني إذا زادت المساحة المستخدمة عن الفحص السابق بمقدار")
        val phoneSwitch = MaterialSwitch(this).apply {
            text = "فحص الهاتف كله"
            isChecked = Store.phoneEnabled(this@MainActivity)
        }
        fields.addView(phoneSwitch, params(4))
        val phoneMb = field("≥ ميجابايت (0 = معطّل)  •  1024 = 1 GB", r.phoneMb)
        val phonePct = field("أو ≥ نسبة % (0 = معطّل)", r.phonePercent)

        label("📁 الفولدرات المضافة: نبّهني إذا زاد حجم أي فولدر عن الفحص السابق بمقدار")
        val pct = field("≥ نسبة % (0 = معطّل)", r.thresholdPercent)
        val mb = field("أو ≥ ميجابايت (0 = معطّل)", r.thresholdMb)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(container)
            .setPositiveButton("حفظ", null)
            .setNegativeButton("إلغاء", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            fun num(e: TextInputEditText) = e.text?.toString()?.trim()?.toLongOrNull()
            val daily = toggle.checkedButtonId == dailyBtn.id
            val n = Rules(
                daily = daily,
                dailyHour = hour,
                dailyMinute = minute,
                intervalMin = (num(interval) ?: -1).toInt(),
                windowMin = (num(window) ?: -1).toInt(),
                thresholdMb = num(mb) ?: -1,
                thresholdPercent = (num(pct) ?: -1).toInt(),
                cooldownMin = (num(cooldown) ?: -1).toInt(),
                phoneMb = num(phoneMb) ?: -1,
                phonePercent = (num(phonePct) ?: -1).toInt(),
            )
            val error = when {
                n.thresholdMb < 0 || n.thresholdPercent < 0 || n.phoneMb < 0 || n.phonePercent < 0 -> "أدخل أرقامًا صحيحة"
                n.thresholdMb == 0L && n.thresholdPercent == 0 -> "حدّد النسبة أو الميجابايت للفولدرات"
                phoneSwitch.isChecked && n.phoneMb == 0L && n.phonePercent == 0 -> "حدّد مقدار الزيادة للهاتف كله"
                daily -> null
                n.intervalMin < 1 -> "الفحص يجب أن يكون كل دقيقة على الأقل"
                n.windowMin < n.intervalMin -> "مدة المقارنة يجب أن تكون أطول من أو تساوي مدة الفحص"
                n.cooldownMin < 0 -> "أدخل أرقامًا صحيحة"
                else -> null
            }
            if (error != null) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            // Keep the old interval values if they were invalid but unused in daily mode.
            val saved = if (daily && (n.intervalMin < 1 || n.windowMin < n.intervalMin || n.cooldownMin < 0))
                n.copy(intervalMin = r.intervalMin, windowMin = r.windowMin, cooldownMin = r.cooldownMin) else n
            Store.setRules(this, saved)
            val phoneTurnedOn = phoneSwitch.isChecked && !Store.phoneEnabled(this)
            Store.setPhoneEnabled(this, phoneSwitch.isChecked)
            if (Store.targets(this).isEmpty()) { Store.setEnabled(this, false); Scheduler.cancel(this) }
            if (Store.isEnabled(this)) Scheduler.scheduleNext(this)
            if (phoneTurnedOn && Store.isEnabled(this)) Checker.runAsync(this) // starting size
            dialog.dismiss()
            refresh()
        }
    }

    /** The app draws behind the status and navigation bars (Android 15): keep content clear of them. */
    private fun applyInsets() {
        val header = findViewById<View>(R.id.header)
        val fab = findViewById<View>(R.id.addFab)
        val list = findViewById<View>(R.id.list)
        val headerTop = header.paddingTop
        val fabBottom = (fab.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        val listBottom = list.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.setPadding(header.paddingLeft, headerTop + bars.top, header.paddingRight, header.paddingBottom)
            (fab.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = fabBottom + bars.bottom
            fab.requestLayout()
            list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, listBottom + bars.bottom)
            insets
        }
    }

    // ------------------------------------------------------------------- UI

    /** "اليوم 12:00 ص" / "غدًا …" / "أمس …" / "13/11 …". */
    private fun whenText(t: Long): String {
        fun dayStart(ms: Long) = java.util.Calendar.getInstance().apply {
            timeInMillis = ms
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val days = Math.round((dayStart(t) - dayStart(System.currentTimeMillis())) / 86_400_000.0)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = t }
        val clock = formatClock(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        return when (days) {
            0L -> "اليوم $clock"
            1L -> "غدًا $clock"
            -1L -> "أمس $clock"
            else -> SimpleDateFormat("d/M", Locale.US).format(Date(t)) + " " + clock
        }
    }

    private fun refresh() {
        val targets = Store.targets(this)
        val history = Store.loadHistory(this)
        val rules = Store.rules(this)
        val enabled = Store.isEnabled(this)

        adapter.submit(targets.map { it to history[it] }, rules)
        empty.visibility = if (targets.isEmpty()) View.VISIBLE else View.GONE

        settingSwitch = true
        enabledSwitch.isChecked = enabled
        settingSwitch = false

        val last = Store.lastRun(this)
        val next = Store.nextRun(this)
        statusText.text = when {
            Checker.isBusy -> "⏳ جاري الفحص…"
            enabled && next > 0 -> "● الفحص القادم: " + whenText(next)
            enabled -> "● المراقبة تعمل"
            else -> "○ المراقبة متوقفة"
        }
        fun limits(mb: Long, pct: Int) = listOfNotNull(
            if (pct > 0) "$pct%" else null,
            if (mb > 0) (if (mb >= 1024 && mb % 1024 == 0L) "${mb / 1024} GB" else "$mb MB") else null,
        ).joinToString(" أو ")
        val schedule = if (rules.daily) "فحص يومي الساعة ${formatClock(rules.dailyHour, rules.dailyMinute)}"
        else "فحص كل ${rules.intervalMin} دقيقة"
        val compare = if (rules.daily) "عن أمس" else "خلال ${rules.windowMin} دقيقة"
        val alerts = listOfNotNull(
            if (Store.phoneEnabled(this)) "الهاتف ≥ ${limits(rules.phoneMb, rules.phonePercent)}" else null,
            if (Store.folders(this).isNotEmpty()) "الفولدرات ≥ ${limits(rules.thresholdMb, rules.thresholdPercent)}" else null,
        )
        val lastText = if (last > 0) "\nآخر فحص: " + whenText(last) else ""
        rulesText.text = "$schedule\nتنبيه عند زيادة " + alerts.joinToString(" • ") + " $compare$lastText"
        buildSetup()
    }
}
