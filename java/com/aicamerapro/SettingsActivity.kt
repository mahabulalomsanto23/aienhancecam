package com.aicamerapro

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aicamerapro.processing.BlurStrength
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var store: SettingsStore
    private lateinit var root: LinearLayout
    private var cur = AppSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(applicationContext)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(16), dp(20), dp(24))
        val scroll = ScrollView(this)
        scroll.fitsSystemWindows = true
        scroll.addView(root)
        setContentView(scroll)

        lifecycleScope.launch {
            cur = store.load()
            build()
        }
    }

    private fun build() {
        title("সেটিংস")
        row("Save Image Preference", listOf("Original + Enhanced (ডিফল্ট)", "Enhanced Only"),
            if (cur.saveBoth) 0 else 1) { cur = cur.copy(saveBoth = it == 0) }
        row("Default Startup Mode", CameraMode.values().map { it.label }, cur.startupMode) {
            cur = cur.copy(startupMode = it)
        }
        row("Image Resolution (প্রসেসিংয়ের সর্বোচ্চ সাইজ)",
            listOf("Full (আসল সাইজ)", "সর্বোচ্চ ৪০৯৬ পিক্সেল", "সর্বোচ্চ ৩০৭২ পিক্সেল"), cur.sizeIdx) {
            cur = cur.copy(sizeIdx = it)
        }
        row("Aspect Ratio", listOf("4:3", "16:9"), if (cur.aspect169) 1 else 0) {
            cur = cur.copy(aspect169 = it == 1)
        }
        row("Flash Preference", listOf("Off", "Auto", "On"), cur.flash) { cur = cur.copy(flash = it) }
        row("Default Portrait Blur Strength", BlurStrength.values().map { it.label }, cur.blur) {
            cur = cur.copy(blur = it)
        }
        row("Processing Preferences (Enhancement strength)", listOf("Gentle", "Normal"), cur.strengthIdx) {
            cur = cur.copy(strengthIdx = it)
        }

        val storage = text("Storage Information: হিসাব হচ্ছে…")
        lifecycleScope.launch {
            val (n, bytes) = withContext(Dispatchers.IO) { ImageSaver.storageInfo(applicationContext) }
            storage.text = "Storage Information: Pictures/AI Camera Pro-তে $n টি ছবি, মোট " +
                String.format("%.1f", bytes / 1048576.0) + " MB"
        }
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "1.0"
        }
        text("Application Version: $ver")
    }

    private fun persist() {
        lifecycleScope.launch { store.save(cur) }
    }

    private fun title(s: String) {
        val t = text(s)
        t.textSize = 22f
    }

    private fun text(s: String): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = 14f
        t.setPadding(0, dp(14), 0, dp(4))
        root.addView(t)
        return t
    }

    private fun row(label: String, options: List<String>, selected: Int, onChange: (Int) -> Unit) {
        text(label)
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        sp.setSelection(selected.coerceIn(0, options.size - 1))
        var first = true
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (first) { first = false; return }
                onChange(pos)
                persist()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(sp)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
