package com.metersnap.offline

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Offline OCR proof: report visible text only; never invent meter fields. */
class MainActivity : Activity() {
    private val picker = 41
    private val camera = 42
    private val timeoutMs = 20_000L
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var photoUri: Uri? = null
    private var scanId = 0
    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var read: Button
    private lateinit var take: Button
    private lateinit var choose: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.WHITE)
        }
        page.addView(TextView(this).apply { text = "MeterSnap · Offline Prof"; textSize = 24f; setTextColor(Color.rgb(15, 61, 49)) })
        page.addView(TextView(this).apply { text = "Text recognition stays on this phone. No account or network call."; textSize = 15f })
        take = button("TAKE METER PHOTO") { takePhoto() }
        choose = button("CHOOSE SAVED PHOTO") { choosePhoto() }
        read = button("READ PHOTO ON THIS PHONE") { runOcr() }.apply { isEnabled = false }
        page.addView(take, row())
        page.addView(choose, row())
        page.addView(read, row())
        preview = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Meter photo" }
        page.addView(preview, LinearLayout.LayoutParams(-1, 300))
        status = TextView(this).apply { textSize = 16f; setPadding(0, 14, 0, 8); text = "Ready. Take or choose a meter photo." }
        output = TextView(this).apply { textSize = 16f; setPadding(12, 12, 12, 12); text = "No photo analysed yet."; setBackgroundColor(Color.rgb(244, 248, 246)) }
        page.addView(status, row())
        page.addView(TextView(this).apply { text = "TEXT FOUND IN PHOTO"; textSize = 12f })
        page.addView(output, row())
        page.addView(TextView(this).apply { text = "Offline OCR test only. Confirm every result manually. It does not yet identify meter model, serial, register or confirmed reading. If text is missing or uncertain, retake the photo or enter details manually."; textSize = 13f; setPadding(0, 14, 0, 0) })
        setContentView(page)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } }
    private fun row() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 }

    private fun choosePhoto() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        startActivityForResult(intent, picker)
    }

    private fun takePhoto() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) == null) { status.text = "No camera app found. Choose a saved photo instead."; return }
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(Date())
        val file = File(dir, "metersnap_$stamp.jpg")
        photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivityForResult(intent, camera)
    }

    @Deprecated("System camera intent keeps this offline test build dependency-light.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) { status.text = "No photo selected. Try again when ready."; return }
        val uri = if (requestCode == picker) data?.data else if (requestCode == camera) photoUri else null
        if (uri == null) { status.text = "Could not open that photo. Choose it again."; return }
        photoUri = uri
        try { preview.setImageURI(uri); read.isEnabled = true; output.text = "Ready to analyse locally."; status.text = "Photo ready. Tap READ PHOTO ON THIS PHONE." }
        catch (_: Exception) { status.text = "Could not preview this photo. Choose another." }
    }

    private fun runOcr() {
        val uri = photoUri ?: run { status.text = "Take or choose a photo first."; return }
        val id = ++scanId
        setWorking(true); output.text = ""; status.text = "Reading locally on this phone…"
        val timeout = Runnable {
            if (id == scanId) { scanId++; setWorking(false); status.text = "Scan took too long. Retake the photo or enter details manually."; output.text = "No result returned. Scan stopped." }
        }
        handler.postDelayed(timeout, timeoutMs)
        try {
            recognizer.process(InputImage.fromFilePath(this, uri))
                .addOnSuccessListener { result ->
                    if (id != scanId) return@addOnSuccessListener
                    handler.removeCallbacks(timeout); setWorking(false)
                    val text = result.text.trim()
                    if (text.isEmpty()) { status.text = "No text found. Try a closer, sharper photo with less glare, or enter details manually."; output.text = "No text detected." }
                    else { status.text = "Text found. Confirm it manually; OCR can misread characters."; output.text = text }
                }
                .addOnFailureListener { error ->
                    if (id != scanId) return@addOnFailureListener
                    handler.removeCallbacks(timeout); setWorking(false); status.text = "Could not read this image. Try again or enter details manually."; output.text = error.localizedMessage ?: "Local text check failed."
                }
        } catch (error: Exception) {
            handler.removeCallbacks(timeout); setWorking(false); status.text = "Could not open this image. Choose it again or enter details manually."; output.text = error.localizedMessage ?: "Image could not be opened."
        }
    }

    private fun setWorking(working: Boolean) { read.isEnabled = !working && photoUri != null; take.isEnabled = !working; choose.isEnabled = !working }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); recognizer.close(); super.onDestroy() }
}
