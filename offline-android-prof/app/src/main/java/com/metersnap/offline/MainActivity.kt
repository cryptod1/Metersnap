package com.metersnap.offline

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Offline three-meter field trial: live frames are sampled, OCR is never accepted without colleague confirmation. */
class MainActivity : ComponentActivity() {
    private data class TrialRecord(val manufacturer: String, val model: String, val serial: String, val register: String, val reading: String, val digits: String, val serialConfirmed: Boolean, val safetyCheck: String)

    private val pickPhotoRequest = 41
    private val takePhotoRequest = 42
    private val cameraPermissionRequest = 43
    private val scanTimeoutMs = 20_000L
    private val sweepTimeoutMs = 60_000L
    private val frameIntervalMs = 850L
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val handler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val frameBusy = AtomicBoolean(false)
    private val trialRecords = mutableListOf<TrialRecord>()
    private val observedLines = linkedMapOf<String, Int>()
    private var photoUri: Uri? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var sweeping = false
    private var lastFrameAt = 0L
    private var analysedFrames = 0
    private var activeScan = 0
    private val sweepTimeout = Runnable { if (sweeping) stopSweepAndReview() }

    private lateinit var progress: TextView
    private lateinit var status: TextView
    private lateinit var frameStatus: TextView
    private lateinit var preview: PreviewView
    private lateinit var photoPreview: ImageView
    private lateinit var startSweepButton: Button
    private lateinit var stopSweepButton: Button
    private lateinit var readPhotoButton: Button
    private lateinit var fieldsPanel: LinearLayout
    private lateinit var result: TextView
    private lateinit var diagnosticButton: Button
    private lateinit var diagnosticText: TextView
    private lateinit var manufacturer: Spinner
    private lateinit var model: Spinner
    private lateinit var otherManufacturer: EditText
    private lateinit var otherModel: EditText
    private lateinit var serial: EditText
    private lateinit var serialConfirmed: CheckBox
    private lateinit var serialUnable: CheckBox
    private lateinit var register: Spinner
    private lateinit var otherRegister: EditText
    private lateinit var reading: EditText
    private lateinit var digits: Spinner
    private lateinit var readingUnable: CheckBox
    private lateinit var safetyStatus: Spinner
    private lateinit var saveButton: Button
    private lateinit var cannotConfirmButton: Button

    private val manufacturerModels = mapOf(
        "Landis+Gyr" to listOf("E470", "E350", "E650", "Other / enter manually", "Unable to confirm"),
        "Itron" to listOf("ACE6000", "ACE3000", "Other / enter manually", "Unable to confirm"),
        "Elster / Honeywell" to listOf("AS300P", "A1140", "Other / enter manually", "Unable to confirm"),
        "Secure" to listOf("Liberty 100", "Liberty 110", "Liberty 200", "Other / enter manually", "Unable to confirm"),
        "EDMI" to listOf("Mk7C", "Mk10", "Other / enter manually", "Unable to confirm"),
        "UGI Meters" to listOf("Other / enter manually", "Unable to confirm")
    )
    private val manufacturerChoices = listOf("Select manufacturer", "Landis+Gyr", "Itron", "Elster / Honeywell", "Secure", "EDMI", "UGI Meters", "Other / enter manually", "Unable to identify")
    private val digitChoices = listOf("Select digit count", "4", "5", "6", "7", "8", "9", "10", "Unable to confirm")
    private val registerChoices = listOf("Select register", "Single register", "Gas index", "Rate 1", "Rate 2", "A+ import", "A− export", "Other / enter manually", "Unable to confirm")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        updateProgress()
        setStatus("Ready. Start a slow sweep from a safe position, or choose a photo.")
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); setBackgroundColor(Color.WHITE) }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(TextView(this).apply { text = "MeterSnap · Field Trial"; textSize = 23f; setTextColor(green); setTypeface(null, android.graphics.Typeface.BOLD) })
        content.addView(TextView(this).apply { text = "3 test meters · on-device scan · no account or network call"; textSize = 14f; setTextColor(Color.DKGRAY); setPadding(0, dp(5), 0, dp(8)) })
        progress = TextView(this).apply { textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(green); setPadding(0, dp(4), 0, dp(8)) }
        content.addView(progress)

        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; visibility = View.GONE; contentDescription = "Live meter camera preview" }
        content.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))
        photoPreview = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE; contentDescription = "Selected meter photo" }
        content.addView(photoPreview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))

        status = TextView(this).apply { textSize = 15f; setTextColor(green); setPadding(0, dp(10), 0, dp(4)) }
        frameStatus = TextView(this).apply { textSize = 13f; setTextColor(Color.DKGRAY); setPadding(0, dp(2), 0, dp(6)) }
        content.addView(status)
        content.addView(frameStatus)

        startSweepButton = actionButton("START SLOW CAMERA SWEEP") { requestCameraAndStart() }
        stopSweepButton = actionButton("STOP SWEEP AND CHECK DETAILS") { stopSweepAndReview() }.apply { visibility = View.GONE }
        val takePhotoButton = secondaryButton("TAKE ONE PHOTO") { takePhoto() }
        val choosePhotoButton = secondaryButton("CHOOSE SAVED PHOTO") { choosePhoto() }
        readPhotoButton = actionButton("READ SELECTED PHOTO ON THIS PHONE") { readSelectedPhoto() }.apply { isEnabled = false }
        content.addView(startSweepButton, row())
        content.addView(stopSweepButton, row(top = 5))
        content.addView(takePhotoButton, row(top = 5))
        content.addView(choosePhotoButton, row(top = 5))
        content.addView(readPhotoButton, row(top = 5))
        content.addView(TextView(this).apply { text = "Sweep slowly. Pause when text appears. Stay where it is safe; the app does not certify safety."; textSize = 12f; setTextColor(Color.DKGRAY); setPadding(0, dp(8), 0, dp(6)) })

        result = TextView(this).apply { text = "No meter details confirmed yet."; textSize = 14f; setTextColor(Color.rgb(18, 33, 28)); setPadding(dp(10), dp(10), dp(10), dp(10)); setBackgroundColor(Color.rgb(244, 248, 246)) }
        content.addView(result, row(top = 6))

        fieldsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, dp(12), 0, 0) }
        fieldsPanel.addView(heading("Required work details"), row())
        fieldsPanel.addView(hint("Check each value against the meter. Reading is entered manually; OCR never submits it."), row())

        manufacturer = addSpinner(fieldsPanel, "Manufacturer", manufacturerChoices)
        model = addSpinner(fieldsPanel, "Model", listOf("Choose manufacturer first"))
        otherManufacturer = addEdit(fieldsPanel, "Manufacturer if not listed", "Enter as printed on meter", show = false)
        otherModel = addEdit(fieldsPanel, "Model if not listed", "Enter as printed on meter", show = false)
        manufacturer.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = manufacturerChoices.getOrNull(position) ?: return
                val options = manufacturerModels[selected] ?: if (selected == "Select manufacturer") listOf("Choose manufacturer first") else listOf("Other / enter manually", "Unable to confirm")
                setSpinnerItems(model, options)
                showEditField(otherManufacturer, selected == "Other / enter manually")
                showEditField(otherModel, false)
            }
        }
        model.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val choice = model.selectedItem?.toString().orEmpty()
                showEditField(otherModel, choice == "Other / enter manually")
            }
        }

        serial = addEdit(fieldsPanel, "Serial number", "Enter or correct the number")
        serialConfirmed = CheckBox(this).apply { text = "Serial checked against the meter"; textSize = 14f; setTextColor(Color.DKGRAY) }
        serialUnable = CheckBox(this).apply { text = "Unable to confirm serial number"; textSize = 14f; setTextColor(Color.DKGRAY) }
        serialConfirmed.setOnCheckedChangeListener { _, checked -> if (checked) serialUnable.isChecked = false }
        serialUnable.setOnCheckedChangeListener { _, checked -> if (checked) { serialConfirmed.isChecked = false; serial.text.clear() } }
        fieldsPanel.addView(serialConfirmed, row(top = 3)); fieldsPanel.addView(serialUnable, row())

        register = addSpinner(fieldsPanel, "Register", registerChoices)
        otherRegister = addEdit(fieldsPanel, "Register if not listed", "Enter visible register label", show = false)
        register.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { showEditField(otherRegister, registerChoices.getOrNull(position) == "Other / enter manually") }
        }
        reading = addEdit(fieldsPanel, "Reading (manual entry)", "Enter visible digits", numeric = true)
        digits = addSpinner(fieldsPanel, "Number of reading digits", digitChoices)
        readingUnable = CheckBox(this).apply { text = "Unable to confirm reading"; textSize = 14f; setTextColor(Color.DKGRAY); setOnCheckedChangeListener { _, checked -> if (checked) reading.text.clear() } }
        fieldsPanel.addView(readingUnable, row())
        safetyStatus = addSpinner(fieldsPanel, "Visible safety / tampering check", listOf("Select check status", "Check completed · no concern visible", "Possible concern · follow company procedure", "Not checked / could not check safely"))
        fieldsPanel.addView(hint("This records an observation only. ‘No concern visible’ is not a safety clearance."), row())

        diagnosticButton = secondaryButton("SHOW OCR TEXT FOR DIAGNOSIS") { diagnosticText.visibility = if (diagnosticText.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        diagnosticText = TextView(this).apply { textSize = 12f; setTextColor(Color.DKGRAY); setPadding(dp(10), dp(8), dp(10), dp(8)); setBackgroundColor(Color.rgb(244, 248, 246)); visibility = View.GONE }
        fieldsPanel.addView(diagnosticButton, row(top = 8)); fieldsPanel.addView(diagnosticText, row(top = 4))

        saveButton = actionButton("SAVE TEST ${trialRecords.size + 1} OF 3") { submitTrial() }
        cannotConfirmButton = secondaryButton("SUBMIT WITH UNCONFIRMED DETAILS") { submitTrial() }
        fieldsPanel.addView(saveButton, row(top = 10)); fieldsPanel.addView(cannotConfirmButton, row(top = 4))
        content.addView(fieldsPanel)
        content.addView(TextView(this).apply { text = "Only the three trial records are held in memory on this screen. No video, address or customer details are saved or sent."; textSize = 12f; setTextColor(Color.DKGRAY); setPadding(0, dp(12), 0, dp(8)) })
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private val green get() = Color.rgb(15, 61, 49)

    private fun heading(value: String) = TextView(this).apply { text = value; textSize = 18f; setTextColor(green); setTypeface(null, android.graphics.Typeface.BOLD) }
    private fun hint(value: String) = TextView(this).apply { text = value; textSize = 13f; setTextColor(Color.DKGRAY) }

    private fun addSpinner(parent: LinearLayout, label: String, options: List<String>): Spinner {
        parent.addView(TextView(this).apply { text = label; textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.rgb(55, 70, 64)); setPadding(0, dp(8), 0, 0) }, row())
        return Spinner(this).also { spinner -> setSpinnerItems(spinner, options); parent.addView(spinner, row()) }
    }

    private fun setSpinnerItems(spinner: Spinner, options: List<String>) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.setSelection(0)
    }

    private fun addEdit(parent: LinearLayout, label: String, placeholder: String, numeric: Boolean = false, show: Boolean = true): EditText {
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (show) View.VISIBLE else View.GONE }
        group.addView(TextView(this).apply { text = label; textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.rgb(55, 70, 64)); setPadding(0, dp(8), 0, 0) })
        val input = EditText(this).apply {
            hint = placeholder
            textSize = 16f
            singleLine = true
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
            setPadding(dp(10), dp(7), dp(10), dp(7))
            group.addView(this)
        }
        parent.addView(group, row())
        return input
    }

    private fun showEditField(edit: EditText, show: Boolean) { (edit.parent as? View)?.visibility = if (show) View.VISIBLE else View.GONE }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; textSize = 14f; setTextColor(Color.WHITE); setBackgroundColor(green); gravity = Gravity.CENTER; setOnClickListener { action() } }
    private fun secondaryButton(label: String, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; textSize = 13f; setTextColor(green); setOnClickListener { action() } }
    private fun row(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (top > 0) topMargin = dp(top) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun requestCameraAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startSweep()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), cameraPermissionRequest)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == cameraPermissionRequest && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startSweep()
        else if (requestCode == cameraPermissionRequest) setStatus("Camera permission was not granted. Choose a saved photo or enter the details manually.")
    }

    private fun startSweep() {
        if (sweeping) return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val previewUC = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val analysisUC = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { useCase -> useCase.setAnalyzer(analysisExecutor) { image -> analyseFrame(image) } }
                previewUseCase = previewUC
                analysisUseCase = analysisUC
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUC, analysisUC)
                sweeping = true
                lastFrameAt = 0L
                analysedFrames = 0

                observedLines.clear()
                photoPreview.visibility = View.GONE
                preview.visibility = View.VISIBLE
                startSweepButton.visibility = View.GONE
                stopSweepButton.visibility = View.VISIBLE
                readPhotoButton.isEnabled = false
                fieldsPanel.visibility = View.GONE
                frameStatus.text = "Sweep slowly; pause briefly over the label and reading window."
                setStatus("Live scan running on this phone. No video is being recorded.")
                handler.postDelayed(sweepTimeout, sweepTimeoutMs)
            } catch (error: Exception) { setStatus("Could not start the camera. Choose a photo or enter details manually.") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyseFrame(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (!sweeping || now - lastFrameAt < frameIntervalMs || !frameBusy.compareAndSet(false, true)) { proxy.close(); return }
        lastFrameAt = now
        val mediaImage = proxy.image
        if (mediaImage == null) { frameBusy.set(false); proxy.close(); return }
        try {
            val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
            recognizer.process(input)
                .addOnSuccessListener { recognized ->
                    if (!sweeping) return@addOnSuccessListener
                    analysedFrames += 1
                    val lines = recognized.textBlocks.flatMap { it.lines }.map { it.text.trim() }.filter { it.length >= 2 }
                    lines.forEach { line ->
                        val key = line.lowercase(Locale.UK)
                        observedLines[key] = (observedLines[key] ?: 0) + 1
                    }
                    frameStatus.text = "Frames checked: $analysedFrames · ${if (lines.isEmpty()) "keep moving slowly or pause" else "text spotted — pause to check it"}"
                }
                .addOnFailureListener { if (sweeping) frameStatus.text = "Frame unclear — keep sweeping slowly or use a still photo." }
                .addOnCompleteListener {
                    proxy.close()
                    frameBusy.set(false)
                }
        } catch (_: Exception) { proxy.close(); frameBusy.set(false) }
    }

    private fun stopSweepAndReview() {
        if (!sweeping) return
        sweeping = false
        handler.removeCallbacks(sweepTimeout)
        cameraProvider?.unbind(previewUseCase, analysisUseCase)
        preview.visibility = View.GONE
        startSweepButton.visibility = View.VISIBLE
        stopSweepButton.visibility = View.GONE
        val stable = observedLines.entries.filter { it.value >= 2 }.sortedByDescending { it.value }.map { it.key }
        val found = if (stable.isNotEmpty()) stable else observedLines.keys.toList()
        diagnosticText.text = if (found.isEmpty()) "No readable text found in the sampled frames." else found.joinToString("\n")
        showFields(if (found.isEmpty()) "No text confirmed. Enter details manually or mark what cannot be confirmed." else "Sweep complete. Check the meter details below; nothing has been submitted automatically.")
    }

    private fun takePhoto() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), cameraPermissionRequest); return
        }
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) == null) { setStatus("No camera app is available. Choose a saved photo instead."); return }
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(Date())
        val file = File(dir, "metersnap_$stamp.jpg")
        photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivityForResult(intent, takePhotoRequest)
    }

    private fun choosePhoto() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        startActivityForResult(intent, pickPhotoRequest)
    }

    @Deprecated("System photo picker and camera intent keep the offline fallback simple.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) { if (requestCode == pickPhotoRequest || requestCode == takePhotoRequest) setStatus("No photo selected. Try again or enter details manually."); return }
        photoUri = if (requestCode == pickPhotoRequest) data?.data else if (requestCode == takePhotoRequest) photoUri else null
        val uri = photoUri ?: run { setStatus("Could not open that photo. Choose it again."); return }
        try {
            photoPreview.setImageURI(uri)
            photoPreview.visibility = View.VISIBLE
            preview.visibility = View.GONE
            readPhotoButton.isEnabled = true
            fieldsPanel.visibility = View.GONE
            frameStatus.text = "Still photo selected."
            setStatus("Photo ready. Read it locally, then verify fields by hand.")
        } catch (_: Exception) { setStatus("Could not preview this photo. Choose another.") }
    }

    private fun readSelectedPhoto() {
        val uri = photoUri ?: run { setStatus("Take or choose a photo first."); return }
        val id = ++activeScan
        readPhotoButton.isEnabled = false
        setStatus("Reading selected photo on this phone…")
        val timeout = Runnable { if (id == activeScan) { activeScan++; readPhotoButton.isEnabled = true; showFields("Photo scan stopped at 20 seconds. Enter details manually or mark what cannot be confirmed.") } }
        handler.postDelayed(timeout, scanTimeoutMs)
        try {
            recognizer.process(InputImage.fromFilePath(this, uri))
                .addOnSuccessListener { text ->
                    if (id != activeScan) return@addOnSuccessListener
                    handler.removeCallbacks(timeout); readPhotoButton.isEnabled = true
                    diagnosticText.text = text.text.ifBlank { "No readable text found." }
                    showFields(if (text.text.isBlank()) "No text found. Enter details manually or mark what cannot be confirmed." else "Text found. Check every detail against the meter; no reading is accepted automatically.")
                }
                .addOnFailureListener {
                    if (id != activeScan) return@addOnFailureListener
                    handler.removeCallbacks(timeout); readPhotoButton.isEnabled = true
                    showFields("Photo scan failed. Enter details manually or mark what cannot be confirmed.")
                }
        } catch (_: Exception) { handler.removeCallbacks(timeout); readPhotoButton.isEnabled = true; showFields("Could not open this photo. Enter details manually or choose another.") }
    }

    private fun showFields(message: String) {
        fieldsPanel.visibility = View.VISIBLE
        result.text = message
        setStatus(message)
        if (trialRecords.size < 3) saveButton.text = "SAVE TEST ${trialRecords.size + 1} OF 3"
    }

    private fun submitTrial() {
        if (trialRecords.size >= 3) { setStatus("All three trial records are complete."); return }
        val maker = manufacturer.selectedItem?.toString().orEmpty()
        val makerEntry = if (maker == "Other / enter manually") otherManufacturer.text.toString().trim() else maker
        val modelChoice = model.selectedItem?.toString().orEmpty()
        val modelEntry = if (modelChoice == "Other / enter manually") otherModel.text.toString().trim() else modelChoice
        val registerChoice = register.selectedItem?.toString().orEmpty()
        val registerEntry = if (registerChoice == "Other / enter manually") otherRegister.text.toString().trim() else registerChoice
        val digitsEntry = digits.selectedItem?.toString().orEmpty()
        val serialEntry = serial.text.toString().trim()
        val readingEntry = reading.text.toString().trim()

        if (maker == "Select manufacturer" || (maker == "Other / enter manually" && makerEntry.isBlank())) { setStatus("Choose or enter the manufacturer, or select Unable to identify."); return }
        if (modelChoice == "Choose manufacturer first" || modelChoice.isBlank() || (modelChoice == "Other / enter manually" && modelEntry.isBlank())) { setStatus("Choose or enter the model, or select Unable to confirm."); return }
        if (serialEntry.isBlank() && !serialUnable.isChecked) { setStatus("Enter the serial number or tick Unable to confirm serial number."); return }
        if (serialEntry.isNotBlank() && !serialConfirmed.isChecked && !serialUnable.isChecked) { setStatus("Tick Serial checked against the meter, or mark it Unable to confirm."); return }
        if (registerEntry.isBlank() || registerEntry == "Select register") { setStatus("Choose or enter the register, or select Unable to confirm."); return }
        if (readingEntry.isBlank() && !readingUnable.isChecked) { setStatus("Enter the reading manually or tick Unable to confirm reading."); return }
        if (digitsEntry == "Select digit count") { setStatus("Choose the digit count or Unable to confirm."); return }
        if (safetyStatus.selectedItemPosition == 0) { setStatus("Choose a safety-check status, including Not checked if it was not safe to check."); return }

        trialRecords.add(TrialRecord(makerEntry, modelEntry, serialEntry, registerEntry, readingEntry, digitsEntry, serialConfirmed.isChecked, safetyStatus.selectedItem.toString()))
        updateProgress()
        if (trialRecords.size == 3) {
            fieldsPanel.visibility = View.GONE
            result.text = "Three trial meters recorded in this session. No video or customer details were saved."
            setStatus("Three of three trial records complete.")
            frameStatus.text = "Trial entries are session-only; nothing has been uploaded."
        } else {
            resetForm()
            photoUri = null
            photoPreview.visibility = View.GONE
            readPhotoButton.isEnabled = false
            fieldsPanel.visibility = View.GONE
            setStatus("Test ${trialRecords.size} recorded. Ready for the next meter.")
            result.text = "Test ${trialRecords.size} of 3 recorded."
        }
    }

    private fun resetForm() {
        manufacturer.setSelection(0)
        model.setSelection(0)
        otherManufacturer.text.clear(); otherModel.text.clear(); serial.text.clear(); register.setSelection(0); otherRegister.text.clear(); reading.text.clear(); digits.setSelection(0)
        serialConfirmed.isChecked = false; serialUnable.isChecked = false; readingUnable.isChecked = false; safetyStatus.setSelection(0)
        diagnosticText.text = ""; diagnosticText.visibility = View.GONE
    }

    private fun updateProgress() {
        val n = trialRecords.size.coerceAtMost(3)
        progress.text = if (n < 3) "FIELD TRIAL · TEST ${n + 1} OF 3 · $n SAVED" else "FIELD TRIAL · 3 OF 3 COMPLETE"
    }

    private fun setStatus(message: String) { status.text = message }

    override fun onPause() { if (sweeping) stopSweepAndReview(); super.onPause() }

    override fun onDestroy() {
        sweeping = false
        cameraProvider?.unbindAll()
        handler.removeCallbacksAndMessages(null)
        analysisExecutor.shutdown()
        recognizer.close()
        super.onDestroy()
    }
}
