package com.dexility.rmtool

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private val lamps = listOf(
        "Музыка" to "nubia_media_lamp_mode",
        "Уведомления" to "nubia_notify_lamp_mode",
        "Звонок" to "nubia_calling_mode",
        "Будильник" to "nubia_alarm_mode",
        "Связь" to "nubia_comm_lamp_mode",
        "Кнопки" to "nubia_gamekeys_lamp"
    )
    private val toggles = listOf(
        "Главный выключатель подсветки" to "switch_main_lamp_enable",
        "Подсветка для видео" to "switch_video_lamp_enable"
    )
    private val fanKeys = listOf("fan_state_of_manual", "fan_state_of_mode")

    private var loading = false
    private val spin by lazy { Spinner(this) }
    private val d1 by lazy { picker(2) }
    private val d2 by lazy { picker(9) }
    private val d3 by lazy { picker(9) }
    private val codeText by lazy { TextView(this) }
    private val fanText by lazy { TextView(this) }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun gInt(k: String) = Settings.Global.getInt(contentResolver, k, -1)
    private fun sInt(k: String) = Settings.System.getInt(contentResolver, k, -1)

    private fun put(global: Boolean, k: String, v: Int) {
        try {
            if (global) Settings.Global.putInt(contentResolver, k, v)
            else Settings.System.putInt(contentResolver, k, v)
        } catch (e: Exception) {
            Toast.makeText(this, "Не записалось: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun picker(max: Int) = NumberPicker(this).apply {
        minValue = 0
        maxValue = max
        setOnValueChangedListener { _, _, _ -> applyLamp() }
    }

    private fun key() = lamps[spin.selectedItemPosition].second

    private fun loadLamp() {
        loading = true
        val v = gInt(key()).coerceIn(0, 255)
        d1.value = v / 100
        d2.value = v / 10 % 10
        d3.value = v % 10
        codeText.text = "Код: $v"
        loading = false
    }

    private fun applyLamp() {
        if (loading) return
        val v = (d1.value * 100 + d2.value * 10 + d3.value).coerceAtMost(255)
        put(true, key(), v)
        codeText.text = "Код: $v"
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // Ключи вентилятора лежат в system: приложению туда писать нельзя, shell (Shizuku) можно
    private fun runShell(cmd: String) {
        try {
            if (!Shizuku.pingBinder()) {
                toast("Запустите Shizuku")
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1)
                toast("Подтвердите доступ Shizuku и нажмите ещё раз")
                return
            }
        } catch (e: Throwable) {
            toast("Shizuku недоступен")
            return
        }
        Thread {
            val ok = try {
                val m = Shizuku::class.java.getDeclaredMethod(
                    "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
                )
                m.isAccessible = true
                val pr = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
                pr.waitFor() == 0
            } catch (e: Throwable) {
                false
            }
            runOnUiThread {
                if (!ok) toast("Команда не выполнилась")
                refreshFan()
            }
        }.start()
    }

    private fun refreshFan() {
        fanText.text = "manual=${sInt("fan_state_of_manual")} mode=${sInt("fan_state_of_mode")}"
    }

    private fun fan(manual: Int, mode: Int) {
        runShell("settings put system fan_state_of_manual $manual; settings put system fan_state_of_mode $mode")
    }

    private fun allKeys() =
        lamps.map { true to it.second } + toggles.map { true to it.second } + fanKeys.map { false to it }

    private fun backupOnce() {
        val p = getSharedPreferences("bk", MODE_PRIVATE)
        if (p.contains("done")) return
        val e = p.edit()
        for ((g, k) in allKeys()) e.putInt(k, if (g) gInt(k) else sInt(k))
        e.putBoolean("done", true).apply()
    }

    private fun restore() {
        val p = getSharedPreferences("bk", MODE_PRIVATE)
        val cmds = ArrayList<String>()
        for ((g, k) in allKeys()) {
            val v = p.getInt(k, -1)
            if (v < 0) continue
            if (g) put(true, k, v) else cmds.add("settings put system $k $v")
        }
        loadLamp()
        if (cmds.isNotEmpty()) runShell(cmds.joinToString("; "))
        Toast.makeText(this, "Значения возвращены", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        backupOnce()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 18f
            setPadding(0, dp(16), 0, dp(4))
        }

        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            root.addView(TextView(this).apply {
                text = "Нет права записи. Выполните в Termux:\n" +
                    "./rish -c \"pm grant $packageName android.permission.WRITE_SECURE_SETTINGS\""
                setTextColor(Color.RED)
                setTextIsSelectable(true)
            })
        }

        root.addView(label("Подсветка"))
        for ((name, k) in toggles) {
            root.addView(Switch(this).apply {
                text = name
                isChecked = gInt(k) == 1
                setOnCheckedChangeListener { _, c -> put(true, k, if (c) 1 else 0) }
            })
        }

        spin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, lamps.map { it.first })
        spin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = loadLamp()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(spin)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        for (d in listOf(d1, d2, d3)) row.addView(d)
        root.addView(row)
        root.addView(codeText)
        root.addView(TextView(this).apply { text = "Цифры: режим (0-2), эффект, цвет. Код не больше 255." })

        root.addView(label("Вентилятор"))
        fun btn(t: String, m: Int, d: Int) = Button(this).apply {
            text = t
            setOnClickListener { fan(m, d) }
        }
        root.addView(btn("Только при зарядке", 0, 0))
        root.addView(btn("Максимум", 1, 0))
        root.addView(btn("Интеллектуальный", 1, 1))
        root.addView(fanText)
        refreshFan()

        root.addView(label("Сервис"))
        root.addView(Button(this).apply {
            text = "Вернуть исходные значения"
            setOnClickListener { restore() }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }
}
