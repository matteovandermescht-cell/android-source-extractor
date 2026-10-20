package com.example.androextractor

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var details: TextView
    private lateinit var pickButton: Button
    private lateinit var zipButton: Button
    private var lastOutput: File? = null
    private var pendingZip: File? = null

    companion object {
        private const val PICK_APK = 1001
        private const val SAVE_ZIP = 1002
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_ENTRIES = 100_000
        private const val MAX_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024

        private fun formatBytes(value: Long): String = when {
            value < 1024 -> "$value B"
            value < 1024 * 1024 -> "%.1f KiB".format(value / 1024.0)
            value < 1024L * 1024 * 1024 -> "%.1f MiB".format(value / (1024.0 * 1024))
            else -> "%.2f GiB".format(value / (1024.0 * 1024 * 1024))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(20))
            setBackgroundColor(0xFFF5F7FB.toInt())
        }

        val title = TextView(this).apply {
            text = "Android Source Extractor"
            textSize = 24f
            setTextColor(0xFF182033.toInt())
        }
        root.addView(title, matchWrap())

        val subtitle = TextView(this).apply {
            text = "APK-Inhalte extrahieren und als ZIP exportieren"
            textSize = 14f
            setTextColor(0xFF566078.toInt())
            setPadding(0, dp(8), 0, dp(18))
        }
        root.addView(subtitle, matchWrap())

        pickButton = Button(this).apply {
            text = "APK auswählen und extrahieren"
            setOnClickListener { pickApk() }
        }
        root.addView(pickButton, matchWrap())

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val progressParams = matchWrap().apply {
            topMargin = dp(14)
            bottomMargin = dp(8)
        }
        root.addView(progress, progressParams)

        status = TextView(this).apply {
            text = "Bereit. Wähle eine APK-Datei aus."
            textSize = 15f
            setTextColor(0xFF182033.toInt())
            setPadding(0, dp(4), 0, dp(10))
        }
        root.addView(status, matchWrap())

        zipButton = Button(this).apply {
            text = "Ergebnis als ZIP speichern"
            isEnabled = false
            setOnClickListener { saveZip() }
        }
        root.addView(zipButton, matchWrap())

        val detailsScroll = ScrollView(this)
        details = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF30394D.toInt())
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, dp(20))
            text = "Erkannte Dateikategorien erscheinen hier."
        }
        detailsScroll.addView(details)
        root.addView(
            detailsScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(8) }
        )

        val footer = TextView(this).apply {
            text = "DEX-Dateien enthalten Bytecode, nicht den originalen Kotlin-/Java-Quellcode."
            textSize = 12f
            setTextColor(0xFF68728A.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(footer, matchWrap())

        setContentView(root)
    }

    private fun pickApk() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.android.package-archive"
        }
        startActivityForResult(intent, PICK_APK)
    }

    @Deprecated("Using activity result callbacks keeps this sample dependency-free.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return

        when (requestCode) {
            PICK_APK -> {
                val uri = data.data!!
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // Some document providers grant a temporary read permission only.
                }
                startExtraction(uri)
            }
            SAVE_ZIP -> {
                val destination = data.data ?: return
                val zip = pendingZip ?: return
                executor.execute {
                    try {
                        val destinationStream = contentResolver.openOutputStream(destination, "w")
                            ?: error("Zieldatei konnte nicht geöffnet werden.")
                        destinationStream.use { output ->
                            zip.inputStream().buffered().use { input ->
                                input.copyTo(output, bufferSize = BUFFER_SIZE)
                            }
                        }
                        runOnUiThread {
                            status.text = "ZIP erfolgreich gespeichert."
                            Toast.makeText(this, "ZIP gespeichert", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        runOnUiThread { showError("ZIP-Export fehlgeschlagen: ${e.message}") }
                    }
                }
            }
        }
    }

    private fun startExtraction(uri: Uri) {
        if (!busy.compareAndSet(false, true)) return
        pickButton.isEnabled = false
        zipButton.isEnabled = false
        progress.progress = 0
        status.text = "APK wird vorbereitet …"
        details.text = ""

        executor.execute {
            try {
                val workDir = File(cacheDir, "extracted").apply {
                    deleteRecursively()
                    mkdirs()
                }
                val report = extractArchive(uri, workDir)
                lastOutput = workDir
                runOnUiThread {
                    progress.progress = 100
                    status.text = "Fertig: ${report.totalFiles} Dateien extrahiert."
                    details.text = report.summary()
                    zipButton.isEnabled = true
                    pickButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread { showError("Extraktion fehlgeschlagen: ${e.message}") }
            } finally {
                busy.set(false)
                runOnUiThread { pickButton.isEnabled = true }
            }
        }
    }

    private data class ExtractionReport(
        val totalFiles: Int,
        val totalBytes: Long,
        val assets: Int,
        val resources: Int,
        val dex: Int,
        val nativeLibs: Int,
        val other: Int,
        val samples: List<String>
    ) {
        fun summary(): String = buildString {
            appendLine("Extraktion abgeschlossen")
            appendLine("Dateien: $totalFiles")
            appendLine("Gesamtgröße: ${formatBytes(totalBytes)}")
            appendLine()
            appendLine("Assets: $assets")
            appendLine("Ressourcen: $resources")
            appendLine("DEX-Dateien: $dex")
            appendLine("Native Bibliotheken (.so): $nativeLibs")
            appendLine("Sonstige Dateien: $other")
            appendLine()
            appendLine("Beispiele:")
            samples.forEach { appendLine("• $it") }
        }
    }

    private fun extractArchive(uri: Uri, outputDir: File): ExtractionReport {
        var count = 0
        var bytes = 0L
        var assets = 0
        var resources = 0
        var dex = 0
        var nativeLibs = 0
        var other = 0
        val samples = mutableListOf<String>()

        val input = contentResolver.openInputStream(uri)
            ?: error("APK-Datei kann nicht gelesen werden.")

        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry: ZipEntry?
            while (zip.nextEntry.also { entry = it } != null) {
                val item = entry ?: continue
                if (item.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                count++
                require(count <= MAX_ENTRIES) { "Zu viele Archiveinträge (Limit $MAX_ENTRIES)." }

                val safeName = safeEntryName(item.name)
                val target = File(outputDir, safeName)
                val canonicalRoot = outputDir.canonicalFile
                val canonicalTarget = target.canonicalFile
                require(canonicalTarget.toPath().startsWith(canonicalRoot.toPath())) {
                    "Unsicherer Archivpfad erkannt."
                }
                target.parentFile?.mkdirs()

                var copied = 0L
                FileOutputStream(target).buffered(BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        copied += n
                        bytes += n
                        require(bytes <= MAX_UNCOMPRESSED_BYTES) {
                            "Entpackte Daten überschreiten das Limit von 2 GiB."
                        }
                        output.write(buffer, 0, n)
                    }
                }

                when {
                    safeName.startsWith("assets/") -> assets++
                    safeName.startsWith("res/") || safeName == "resources.arsc" ||
                        safeName == "AndroidManifest.xml" -> resources++
                    safeName.matches(Regex("(^|.*/)classes(\\d*)\\.dex")) -> dex++
                    safeName.startsWith("lib/") && safeName.endsWith(".so") -> nativeLibs++
                    else -> other++
                }
                if (samples.size < 40) {
                    samples += "$safeName (${formatBytes(copied)})"
                }
                zip.closeEntry()
                val percent = ((count % 100) + 1).coerceAtMost(100)
                runOnUiThread {
                    status.text = "Extrahiere: $safeName"
                    progress.progress = percent
                }
            }
        }
        return ExtractionReport(count, bytes, assets, resources, dex, nativeLibs, other, samples)
    }

    private fun safeEntryName(raw: String): String {
        val normalized = raw.replace('\\', '/').trimStart('/')
        require(normalized.isNotBlank()) { "Leerer Dateiname im Archiv." }
        require(normalized.split('/').none { it == ".." }) { "Pfad-Traversal erkannt." }
        require(!normalized.contains('\u0000')) { "Ungültiger Dateiname." }
        return normalized
    }

    private fun saveZip() {
        val source = lastOutput ?: return
        val zipFile = File(cacheDir, "android-source-extractor.zip")
        status.text = "ZIP wird erstellt …"
        zipButton.isEnabled = false

        executor.execute {
            try {
                val files = source.walkTopDown().filter { it.isFile }.toList()
                ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { out ->
                    files.forEachIndexed { index, file ->
                        val relative = file.relativeTo(source).invariantSeparatorsPath
                        out.putNextEntry(ZipEntry(relative))
                        file.inputStream().buffered().use { it.copyTo(out, BUFFER_SIZE) }
                        out.closeEntry()
                        val pct = ((index + 1) * 100 / files.size.coerceAtLeast(1))
                        runOnUiThread {
                            progress.progress = pct
                            status.text = "ZIP: $relative"
                        }
                    }
                }
                pendingZip = zipFile
                runOnUiThread {
                    zipButton.isEnabled = true
                    status.text = "ZIP bereit. Speicherort auswählen."
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/zip"
                        putExtra(Intent.EXTRA_TITLE, "android-source-extractor.zip")
                    }
                    startActivityForResult(intent, SAVE_ZIP)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    zipButton.isEnabled = true
                    showError("ZIP-Erstellung fehlgeschlagen: ${e.message}")
                }
            }
        }
    }

    private fun showError(message: String) {
        status.text = message
        pickButton.isEnabled = true
        zipButton.isEnabled = lastOutput?.exists() == true
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
