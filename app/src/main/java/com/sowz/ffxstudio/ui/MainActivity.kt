package com.sowz.ffxstudio.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.sowz.ffxstudio.core.FfxParser
import com.sowz.ffxstudio.core.LookEngine
import com.sowz.ffxstudio.core.RenderEngine
import java.io.File
import java.io.FileOutputStream

/**
 * Tek ekranli akis: .ffx sec -> gercekten parse et (FfxParser) -> taninan
 * efektleri FFmpeg filtresine cevir (LookEngine) -> foto/video sec -> gercek
 * FFmpegKit render'i al (RenderEngine) -> MediaStore'a kaydet -> paylas.
 *
 * DURUSTLUK: bu ekran hicbir adimda "birebir ayni sonuc" iddia etmez. .ffx'ten
 * okunan efekt TURLERI gercek, ama sayisal degerler yaklasimdir (bkz.
 * FfxParser.kt ve LookEngine.kt'nin kendi yorumlari). Bu, her render oncesi
 * kullaniciya acikca gosterilir.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_PICK_FFX = 501
        private const val REQ_PICK_MEDIA = 502
        private const val BG = "#0A0A0A"
        private const val CARD = "#15181A"
        private const val TEAL = "#2DD4BF"
        private const val ORANGE = "#F2821A"
        private const val TEXT = "#F2F5F5"
        private const val TEXT_DIM = "#8FA0A0"
        private const val DANGER = "#FF5C5C"
    }

    private lateinit var ffxStatus: TextView
    private lateinit var ffxEffectList: LinearLayout
    private lateinit var mediaStatus: TextView
    private lateinit var renderRow: LinearLayout
    private lateinit var renderLabel: TextView
    private lateinit var progressText: TextView
    private lateinit var resultCard: LinearLayout
    private lateinit var resultText: TextView
    private lateinit var shareRow: LinearLayout

    private var ffxPlan: LookEngine.Plan? = null
    private var mediaUri: Uri? = null
    private var mediaIsVideo = false
    private var mediaDisplayName: String = ""
    private var currentSession: com.arthenica.ffmpegkit.FFmpegSession? = null
    private var lastOutputUri: Uri? = null
    private var lastOutputIsVideo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun card(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor(CARD))
        cornerRadius = dp(14f).toFloat()
    }

    private fun spacer(h: Float): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
    }

    private fun pillButton(label: String, colorHex: String, onClick: () -> Unit): LinearLayout {
        val text = TextView(this).apply {
            text = label
            setTextColor(Color.BLACK)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor(colorHex))
                cornerRadius = dp(14f).toFloat()
            }
            isClickable = true
            isFocusable = true
            setPadding(0, dp(16f), 0, dp(16f))
            addView(text)
            setOnClickListener { onClick() }
        }
    }

    private fun sectionTitle(titleText: String): TextView = TextView(this).apply {
        text = titleText
        setTextColor(Color.parseColor(TEXT))
        textSize = 15f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor(BG)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(48f), dp(20f), dp(40f))
        }

        root.addView(TextView(this).apply {
            text = "Sowz FFX Studio"
            setTextColor(Color.parseColor(TEXT))
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Adobe After Effects .ffx preset'ini gercekten okur, taninan efektleri foto/videona yaklasik olarak uygular"
            setTextColor(Color.parseColor(TEXT_DIM))
            textSize = 13f
            setPadding(0, dp(6f), 0, 0)
        })
        root.addView(spacer(24f))

        // --- Adim 1: .ffx sec ---
        val ffxCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
        }
        ffxCard.addView(sectionTitle("1. .ffx preset dosyasi"))
        ffxCard.addView(spacer(10f))
        ffxCard.addView(pillButton(".ffx Sec", TEAL) { pickFfx() })
        ffxCard.addView(spacer(10f))
        ffxStatus = TextView(this).apply {
            text = "Henuz secilmedi."
            setTextColor(Color.parseColor(TEXT_DIM))
            textSize = 12f
        }
        ffxCard.addView(ffxStatus)
        ffxEffectList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10f), 0, 0)
        }
        ffxCard.addView(ffxEffectList)
        root.addView(ffxCard)
        root.addView(spacer(16f))

        // --- Adim 2: foto/video sec ---
        val mediaCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
        }
        mediaCard.addView(sectionTitle("2. Fotograf veya video"))
        mediaCard.addView(spacer(10f))
        mediaCard.addView(pillButton("Fotograf / Video Sec", ORANGE) { pickMedia() })
        mediaCard.addView(spacer(10f))
        mediaStatus = TextView(this).apply {
            text = "Henuz secilmedi."
            setTextColor(Color.parseColor(TEXT_DIM))
            textSize = 12f
        }
        mediaCard.addView(mediaStatus)
        root.addView(mediaCard)
        root.addView(spacer(16f))

        // --- Adim 3: render ---
        val renderCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
        }
        renderCard.addView(sectionTitle("3. Render"))
        renderCard.addView(spacer(10f))
        renderLabel = TextView(this).apply {
            text = "Render Al"
            setTextColor(Color.BLACK)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        renderRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor(TEXT_DIM)); cornerRadius = dp(14f).toFloat() }
            setPadding(0, dp(16f), 0, dp(16f))
            isEnabled = false
            addView(renderLabel)
            setOnClickListener { if (isEnabled) startRender() }
        }
        renderCard.addView(renderRow)
        renderCard.addView(spacer(10f))
        progressText = TextView(this).apply {
            setTextColor(Color.parseColor(TEAL))
            textSize = 12f
            gravity = Gravity.CENTER
        }
        renderCard.addView(progressText)

        resultCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14f), 0, 0)
            visibility = View.GONE
        }
        resultText = TextView(this).apply {
            setTextColor(Color.parseColor(TEXT))
            textSize = 13f
        }
        resultCard.addView(resultText)
        resultCard.addView(spacer(10f))
        shareRow = pillButton("Paylas", TEAL) { shareLastOutput() }
        resultCard.addView(shareRow)
        renderCard.addView(resultCard)

        root.addView(renderCard)

        scroll.addView(root)
        setContentView(scroll)
    }

    // ============================= Adim 1: .ffx =============================

    private fun pickFfx() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, ".ffx sec"), REQ_PICK_FFX)
    }

    private fun handleFfxPicked(uri: Uri) {
        ffxStatus.text = "Okunuyor..."
        ffxEffectList.removeAllViews()
        ffxPlan = null
        updateRenderButtonState()
        Thread {
            val result = try {
                contentResolver.openInputStream(uri)?.use { FfxParser.parse(it) }
                    ?: FfxParser.ParseResult(false, null, emptyList(), 0, "Dosya acilamadi.")
            } catch (e: Exception) {
                FfxParser.ParseResult(false, null, emptyList(), 0, "Hata: ${e.message}")
            }
            runOnUiThread {
                if (!result.isValidRifx) {
                    ffxStatus.text = result.errorMessage ?: "Bu dosya gecerli bir .ffx degil."
                    return@runOnUiThread
                }
                val plan = LookEngine.plan(result.effects)
                ffxPlan = plan
                ffxStatus.text = "${result.effects.size} efekt tespit edildi (${plan.applied.size} uygulanacak, ${plan.unmapped.size} taninmadi)."
                ffxEffectList.removeAllViews()
                plan.applied.forEach { eff ->
                    ffxEffectList.addView(effectRow(eff.displayName ?: eff.matchName, eff.filterDescription, TEAL))
                }
                plan.unmapped.forEach { eff ->
                    ffxEffectList.addView(effectRow(eff.displayName ?: eff.matchName, "Taninmadi - render'a eklenmedi", TEXT_DIM))
                }
                updateRenderButtonState()
            }
        }.start()
    }

    private fun effectRow(title: String, caption: String, accent: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6f), 0, dp(6f))
            addView(TextView(this@MainActivity).apply {
                text = "• $title"
                setTextColor(Color.parseColor(accent))
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@MainActivity).apply {
                text = caption
                setTextColor(Color.parseColor(TEXT_DIM))
                textSize = 11f
                setPadding(dp(12f), dp(2f), 0, 0)
            })
        }
    }

    // ============================= Adim 2: medya =============================

    private fun pickMedia() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "Fotograf veya video sec"), REQ_PICK_MEDIA)
    }

    private fun handleMediaPicked(uri: Uri) {
        val mime = contentResolver.getType(uri) ?: ""
        mediaIsVideo = mime.startsWith("video/")
        if (!mime.startsWith("image/") && !mediaIsVideo) {
            mediaStatus.text = "Bu dosya fotograf ya da video gibi gorunmuyor (tur: $mime)."
            mediaUri = null
            updateRenderButtonState()
            return
        }
        mediaUri = uri
        mediaDisplayName = queryDisplayName(uri) ?: if (mediaIsVideo) "video" else "fotograf"
        mediaStatus.text = "${if (mediaIsVideo) "Video" else "Fotograf"}: $mediaDisplayName"
        updateRenderButtonState()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ============================= Adim 3: render =============================

    private fun updateRenderButtonState() {
        val ready = ffxPlan != null && mediaUri != null
        renderRow.isEnabled = ready
        renderRow.background = GradientDrawable().apply {
            setColor(Color.parseColor(if (ready) TEAL else TEXT_DIM))
            cornerRadius = dp(14f).toFloat()
        }
    }

    private fun startRender() {
        val plan = ffxPlan ?: return
        val uri = mediaUri ?: return
        renderRow.isEnabled = false
        progressText.text = "Hazirlaniyor..."
        resultCard.visibility = View.GONE

        Thread {
            try {
                val ext = if (mediaIsVideo) "mp4" else "jpg"
                val inFile = File(cacheDir, "ffx_in_${System.currentTimeMillis()}.$ext")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(inFile).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Medya okunamadi.")

                val outFile = File(cacheDir, "ffx_out_${System.currentTimeMillis()}.$ext")

                if (mediaIsVideo) {
                    var durationMs = 0L
                    try {
                        MediaMetadataRetriever().use { r ->
                            r.setDataSource(inFile.absolutePath)
                            durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        }
                    } catch (_: Exception) { }

                    runOnUiThread { progressText.text = "Render ediliyor..." }
                    currentSession = RenderEngine.renderVideo(inFile.absolutePath, outFile.absolutePath, plan.filterGraph, durationMs) { progress ->
                        runOnUiThread { handleProgress(progress, outFile, isVideo = true, inFile) }
                    }
                } else {
                    runOnUiThread { progressText.text = "Render ediliyor..." }
                    currentSession = RenderEngine.renderImage(inFile.absolutePath, outFile.absolutePath, plan.filterGraph) { progress ->
                        runOnUiThread { handleProgress(progress, outFile, isVideo = false, inFile) }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressText.text = "Hata: ${e.message}"
                    renderRow.isEnabled = true
                }
            }
        }.start()
    }

    private fun handleProgress(progress: RenderEngine.Progress, outFile: File, isVideo: Boolean, inFile: File) {
        when (progress) {
            is RenderEngine.Progress.PercentUpdate -> progressText.text = "Render ediliyor... %${progress.percent}"
            is RenderEngine.Progress.Done -> {
                progressText.text = "Tamamlandi."
                renderRow.isEnabled = true
                saveToMediaStore(outFile, isVideo)
                inFile.delete()
            }
            is RenderEngine.Progress.Cancelled -> {
                progressText.text = "Iptal edildi."
                renderRow.isEnabled = true
                outFile.delete()
                inFile.delete()
            }
            is RenderEngine.Progress.Failed -> {
                progressText.text = progress.message
                renderRow.isEnabled = true
                outFile.delete()
                inFile.delete()
            }
        }
    }

    private fun saveToMediaStore(file: File, isVideo: Boolean) {
        try {
            val resolver = contentResolver
            val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val relPath = if (isVideo) "Movies/SowzFfxStudio/" else "Pictures/SowzFfxStudio/"
            val mime = if (isVideo) "video/mp4" else "image/jpeg"
            val name = "SOWZ_FFX_${System.currentTimeMillis()}.${if (isVideo) "mp4" else "jpg"}"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val target = resolver.insert(collection, values) ?: throw IllegalStateException("MediaStore kaydi olusturulamadi.")
            resolver.openOutputStream(target, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Cikti yazilamadi.")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(target, done, null, null)
            file.delete()

            lastOutputUri = target
            lastOutputIsVideo = isVideo
            resultText.text = "Kaydedildi: ${if (isVideo) "Movies" else "Pictures"}/SowzFfxStudio/$name\n\n" +
                "Not: bu, .ffx'in BIREBIR ayni sonucu degil - taninan efekt turlerine yakin bir yaklasim render'idir."
            resultCard.visibility = View.VISIBLE
        } catch (e: Exception) {
            progressText.text = "Kaydetme hatasi: ${e.message}"
        }
    }

    private fun shareLastOutput() {
        val uri = lastOutputUri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (lastOutputIsVideo) "video/mp4" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Paylas"))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_PICK_FFX -> handleFfxPicked(uri)
            REQ_PICK_MEDIA -> handleMediaPicked(uri)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        RenderEngine.cancel(currentSession)
    }
}
