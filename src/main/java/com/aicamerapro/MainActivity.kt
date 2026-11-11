package com.aicamerapro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aicamerapro.processing.BlurStrength
import com.aicamerapro.processing.CaptureJob
import com.aicamerapro.processing.CaptureProcessor
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var flashBtn: TextView
    private lateinit var statusTv: TextView
    private lateinit var thumb: ImageView
    private lateinit var modeRow: LinearLayout
    private lateinit var blurRow: LinearLayout
    private lateinit var store: SettingsStore

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var mode = CameraMode.AI
    private var blur = BlurStrength.MEDIUM
    private var flashIdx = 0
    private var settings = AppSettings()
    private var initialized = false
    private var lastUri: Uri? = null
    private val pending = AtomicInteger(0)

    private val flashModes = intArrayOf(
        ImageCapture.FLASH_MODE_OFF, ImageCapture.FLASH_MODE_AUTO, ImageCapture.FLASH_MODE_ON
    )
    private val flashNames = arrayOf("Flash: Off", "Flash: Auto", "Flash: On")

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else toast("ক্যামেরা ব্যবহারের অনুমতি প্রয়োজন")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = SettingsStore(applicationContext)

        previewView = findViewById(R.id.preview)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        flashBtn = findViewById(R.id.flashBtn)
        statusTv = findViewById(R.id.statusTv)
        thumb = findViewById(R.id.thumb)
        modeRow = findViewById(R.id.modeRow)
        blurRow = findViewById(R.id.blurRow)

        CameraMode.values().forEach { m -> modeRow.addView(chip(m.label) { mode = m; refreshUi() }) }
        BlurStrength.values().forEach { b -> blurRow.addView(chip(b.label) { blur = b; refreshUi() }) }

        findViewById<View>(R.id.shutter).setOnClickListener { v ->
            v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(60).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(60).start()
            }.start()
            capture()
        }
        findViewById<View>(R.id.flip).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }
        flashBtn.setOnClickListener {
            flashIdx = (flashIdx + 1) % 3
            imageCapture?.flashMode = flashModes[flashIdx]
            refreshUi()
        }
        findViewById<View>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        thumb.setOnClickListener {
            val u = lastUri ?: return@setOnClickListener
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/jpeg")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            } catch (e: Exception) {
                toast("ছবি খোলার অ্যাপ পাওয়া যায়নি")
            }
        }
        previewView.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                val point = previewView.meteringPointFactory.createPoint(ev.x, ev.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                v.performClick()
            }
            true
        }
        refreshUi()
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            val prev = settings
            settings = store.load()
            if (!initialized) {
                mode = CameraMode.values()[settings.startupMode.coerceIn(0, 3)]
                blur = BlurStrength.values()[settings.blur.coerceIn(0, 3)]
                flashIdx = settings.flash.coerceIn(0, 2)
                initialized = true
            } else {
                if (settings.blur != prev.blur) blur = BlurStrength.values()[settings.blur.coerceIn(0, 3)]
                if (settings.flash != prev.flash) flashIdx = settings.flash.coerceIn(0, 2)
            }
            refreshUi()
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) startCamera() else permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val ratio = if (settings.aspect169) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
            val preview = Preview.Builder().setTargetAspectRatio(ratio).build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val ic = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetAspectRatio(ratio)
                .setFlashMode(flashModes[flashIdx])
                .build()
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, ic)
                imageCapture = ic
            } catch (e: Exception) {
                toast("ক্যামেরা চালু করা যায়নি")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capture() {
        val ic = imageCapture ?: return
        if (pending.get() >= MAX_PENDING) {
            toast("প্রসেসিং চলছে, একটু অপেক্ষা করুন")
            return
        }
        val out = ByteArrayOutputStream()
        val opts = ImageCapture.OutputFileOptions.Builder(out).build()
        val m = mode
        val b = blur
        val s = settings
        pending.incrementAndGet()
        updateStatus()
        ic.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                val job = CaptureJob(out.toByteArray(), m, b, s.saveBoth, s.maxSide, s.strength)
                CaptureProcessor.submit(applicationContext, job) { res ->
                    pending.decrementAndGet()
                    if (!isDestroyed) {
                        res.thumb?.let { thumb.setImageBitmap(it) }
                        if (res.uri != null) lastUri = res.uri
                        updateStatus()
                    }
                    toast(res.message)
                }
            }

            override fun onError(e: ImageCaptureException) {
                pending.decrementAndGet()
                updateStatus()
                toast("ছবি তোলা যায়নি")
            }
        })
    }

    private fun updateStatus() {
        val n = pending.get()
        statusTv.text = if (n > 0) "প্রসেসিং: $n" else ""
    }

    private fun refreshUi() {
        val sel = Color.parseColor("#FFD60A")
        val norm = Color.parseColor("#B3FFFFFF")
        for (i in 0 until modeRow.childCount) {
            val t = modeRow.getChildAt(i) as TextView
            t.setTextColor(if (i == mode.ordinal) sel else norm)
            t.setTypeface(null, if (i == mode.ordinal) Typeface.BOLD else Typeface.NORMAL)
        }
        for (i in 0 until blurRow.childCount) {
            val t = blurRow.getChildAt(i) as TextView
            t.setTextColor(if (i == blur.ordinal) sel else norm)
            t.setTypeface(null, if (i == blur.ordinal) Typeface.BOLD else Typeface.NORMAL)
        }
        blurRow.visibility = if (mode == CameraMode.PORTRAIT) View.VISIBLE else View.GONE
        flashBtn.text = flashNames[flashIdx]
        imageCapture?.flashMode = flashModes[flashIdx]
        updateStatus()
    }

    private fun chip(label: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 15f
        t.setPadding(dp(14), dp(10), dp(14), dp(10))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val MAX_PENDING = 3
    }
}
