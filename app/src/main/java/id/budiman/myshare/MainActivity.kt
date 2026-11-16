package id.budiman.myshare

import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import android.os.Environment
import android.provider.OpenableColumns
import android.view.View
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import id.budiman.myshare.databinding.ActivityMainBinding
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var server: ShareServer? = null
    private var targetUrl: String? = null
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var dotIndex = 0
    private var dotLabel = ""
    private val dotRunner = object : Runnable {
        override fun run() {
            val dots = listOf("●○○", "●●○", "●●●", "○●●", "○○●", "○○○")
            b.statusText.text = "$dotLabel  ${dots[dotIndex % dots.size]}"
            dotIndex++
            handler.postDelayed(this, 300)
        }
    }

    private fun startDots(label: String) {
        handler.removeCallbacks(dotRunner)
        dotLabel = label
        dotIndex = 0
        handler.post(dotRunner)
    }

    private fun stopDots(message: String) {
        handler.removeCallbacks(dotRunner)
        b.statusText.text = message
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach {
                try {
                    contentResolver.takePersistableUriPermission(
                        it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
            }
            sendFiles(uris)
        }
    }
    private val scanQr = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank() && contents.startsWith("http://")) {
            targetUrl = contents.trimEnd('/')
            getSharedPreferences("myshare", MODE_PRIVATE)
                .edit().putString("target_url", targetUrl).apply()
            b.instructionText.text = "Perangkat penerima terhubung. Pilih file yang akan dikirim."
            b.addressText.text = targetUrl
            pickFiles.launch(arrayOf("*/*"))
        } else if (contents != null) toast("QR tidak dikenali. Pindai QR MY SHARE.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        targetUrl = getSharedPreferences("myshare", MODE_PRIVATE)
            .getString("target_url", null)

        if (!targetUrl.isNullOrBlank()) {
            b.addressText.text = targetUrl
            b.instructionText.text = "Penerima tersimpan. Pilih file untuk mengirim."
        }

        b.sendButton.setOnClickListener { connectOrPickFiles() }
        b.sendButton.setOnLongClickListener {
            scanReceiver()
            true
        }
        b.receiveButton.setOnClickListener { startReceiving() }
        showHistory()
    }

    private fun connectOrPickFiles() {
        if (!targetUrl.isNullOrBlank()) {
            b.addressText.text = targetUrl
            pickFiles.launch(arrayOf("*/*"))
        } else {
            scanReceiver()
        }
    }

    private fun scanReceiver() {
        val options = ScanOptions().setPrompt("Pindai QR Code dari perangkat penerima")
            .setBeepEnabled(false).setOrientationLocked(true)
            .setCaptureActivity(CaptureActivityPortrait::class.java)
        scanQr.launch(options)
    }

    private fun startReceiving() {
        try {
            if (server == null) {
                val ip = findLocalIp() ?: "192.168.43.1"
                server = ShareServer(
                    8989,
                    getReceiveFolder(),
                    onReceiving = {
                        runOnUiThread { startDots("Menerima file") }
                    },
                    onReceived = { name ->
                    runOnUiThread {
                        stopDots("✓ File berhasil diterima")
                        addHistory("Diterima", name, "")

                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("File Diterima!")
                            .setMessage("$name\n\nFile berhasil disimpan. Buka sekarang?")
                            .setPositiveButton("BUKA SEKARANG") { _, _ ->
                                openReceivedFile(name)
                            }
                            .setNegativeButton("NANTI", null)
                            .show()
                    }
                    },
                    onError = { error ->
                        runOnUiThread { stopDots("Transfer gagal: $error") }
                    }
                ).also { it.start() }
                val address = "http://$ip:8989"
                b.qrImage.setImageBitmap(QRCodeWriter().encode(address, BarcodeFormat.QR_CODE, 600, 600).toBitmap())
                b.qrImage.visibility = View.VISIBLE
                b.addressText.text = address
                b.instructionText.text = "Perangkat lain harus terhubung ke Wi-Fi/hotspot yang sama, lalu pindai QR ini."
                b.statusText.text = "Perangkat terhubung • Menunggu file"
                b.statusText.setTextColor(0xFF16845B.toInt())
            } else {
                b.instructionText.text = "Server penerimaan aktif. Pindai QR dari perangkat pengirim."
            }
        } catch (e: Exception) { toast("Gagal memulai penerimaan: ${e.message}") }
    }

    private fun findLocalIp(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val a = addresses.nextElement()
                    if (!a.isLoopbackAddress && a is java.net.Inet4Address) {
                        val ip = a.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) return ip
                    }
                }
            }
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val raw = wm.connectionInfo.ipAddress
            if (raw == 0) null else "${raw and 0xff}.${raw shr 8 and 0xff}.${raw shr 16 and 0xff}.${raw shr 24 and 0xff}"
        } catch (_: Exception) { null }
    }

    private fun getReceiveFolder(): File {
        val base = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        return File(base, "MyShare").apply { mkdirs() }
    }

    private fun sendFiles(uris: List<Uri>) {
        val base = targetUrl ?: run {
            toast("Pindai QR penerima terlebih dahulu.")
            return
        }

        b.progressBar.visibility = View.VISIBLE
        b.progressBar.isIndeterminate = false
        b.progressBar.progress = 0
        b.progressText.text = "Menyiapkan ${uris.size} file..."
        startDots("Mengirim file")

        io.execute {
            var success = 0

            uris.forEachIndexed { index, uri ->
                val name = getFileName(uri)
                val total = getFileSize(uri)

                runOnUiThread {
                    b.progressBar.isIndeterminate = total <= 0
                    b.progressBar.progress = 0
                    b.progressText.text =
                        "File ${index + 1}/${uris.size}\n$name\nMenyiapkan transfer..."
                }

                try {
                    uploadOne(base, uri, name) { sent, fileSize, speed, remaining ->
                        runOnUiThread {
                            if (fileSize > 0) {
                                val percent = ((sent * 100) / fileSize)
                                    .coerceIn(0, 100).toInt()
                                b.progressBar.isIndeterminate = false
                                b.progressBar.progress = percent
                                b.progressText.text =
                                    "File ${index + 1}/${uris.size} • $name\n" +
                                    "$percent% • ${formatBytes(sent)} / ${formatBytes(fileSize)}\n" +
                                    "Kecepatan: ${formatSpeed(speed)} • Sisa: ${formatDuration(remaining)}"
                            } else {
                                b.progressBar.isIndeterminate = true
                                b.progressText.text =
                                    "File ${index + 1}/${uris.size} • $name\n" +
                                    "${formatBytes(sent)} terkirim\n" +
                                    "Kecepatan: ${formatSpeed(speed)}"
                            }
                        }
                    }

                    success++
                    runOnUiThread {
                        addHistory("Terkirim", name, uri.toString())
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        b.progressText.text = "Gagal mengirim $name\n${e.message}"
                    }
                }
            }

            runOnUiThread {
                b.progressBar.isIndeterminate = false
                b.progressBar.progress = 100
                stopDots("✓ Selesai • $success/${uris.size} file terkirim")
                b.progressText.text =
                    "Transfer selesai.\nBerhasil: $success dari ${uris.size} file."
                showHistory()
                toast("Transfer selesai: $success/${uris.size} file")
            }
        }
    }

    private fun uploadOne(
        base: String,
        uri: Uri,
        name: String,
        onProgress: (Long, Long, Double, Long) -> Unit
    ) {
        val boundary = "----MyShare${System.currentTimeMillis()}"
        val url = URL("$base/upload")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 120000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        val totalBytes = getFileSize(uri)
        val startedAt = System.currentTimeMillis()
        var lastUpdate = 0L
        var sentBytes = 0L
        val safeName = name.replace("\"", "_").replace("\r", "_").replace("\n", "_")

        try {
            conn.outputStream.use { out ->
                out.write((
                    "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n"
                ).toByteArray())

                val input = contentResolver.openInputStream(uri)
                    ?: error("File tidak dapat dibaca")

                input.use {
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = it.read(buffer)
                        if (count == -1) break

                        out.write(buffer, 0, count)
                        sentBytes += count

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 150 || (totalBytes > 0 && sentBytes >= totalBytes)) {
                            val elapsed = ((now - startedAt).coerceAtLeast(1)) / 1000.0
                            val speed = sentBytes / elapsed
                            val remaining = if (totalBytes > sentBytes && speed > 0) {
                                ((totalBytes - sentBytes) / speed).toLong()
                            } else {
                                0L
                            }

                            onProgress(sentBytes, totalBytes, speed, remaining)
                            lastUpdate = now
                        }
                    }
                }

                out.write("\r\n--$boundary--\r\n".toByteArray())
                out.flush()
            }

            val code = conn.responseCode
            val response = (
                if (code in 200..299) conn.inputStream else conn.errorStream
            )?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                error("Server menolak file ($code): $response")
            }

            val elapsed = ((System.currentTimeMillis() - startedAt).coerceAtLeast(1)) / 1000.0
            onProgress(sentBytes, totalBytes, sentBytes / elapsed, 0L)
        } finally {
            conn.disconnect()
        }
    }

    private fun getFileSize(uri: Uri): Long {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (column >= 0 && cursor.moveToFirst()) {
                    val size = cursor.getLong(column)
                    if (size >= 0) return size
                }
            }

            contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                it.length
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun formatBytes(bytes: Long): String {
        val mb = 1024.0 * 1024.0
        return when {
            bytes >= mb -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / mb)
            bytes >= 1024 -> String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun formatSpeed(bytesPerSecond: Double): String {
        val mb = 1024.0 * 1024.0
        return String.format(
            java.util.Locale.getDefault(),
            "%.2f MB/s",
            bytesPerSecond / mb
        )
    }

    private fun formatDuration(seconds: Long): String {
        return when {
            seconds < 60 -> "$seconds detik"
            seconds < 3600 -> "${seconds / 60} m ${seconds % 60} d"
            else -> "${seconds / 3600} j ${(seconds % 3600) / 60} m"
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "file_${System.currentTimeMillis()}"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
        }
        return name
    }

    private fun addHistory(direction: String, name: String, uri: String) {
        val prefs = getSharedPreferences("myshare", MODE_PRIVATE)
        val old = try {
            JSONArray(prefs.getString("history_items", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val item = JSONObject().apply {
            put("direction", direction)
            put("name", name)
            put("uri", uri)
            put("time", java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date()))
        }
        val updated = JSONArray().apply {
            put(item)
            for (i in 0 until minOf(old.length(), 19)) put(old.getJSONObject(i))
        }
        prefs.edit().putString("history_items", updated.toString()).apply()
        showHistory()
    }

    private fun openHistoryFile(direction: String, name: String, uriText: String) {
        try {
            val uri = if (direction == "Diterima") {
                val file = File(getReceiveFolder(), name)
                if (!file.exists()) {
                    toast("File sudah tidak ditemukan.")
                    return
                }
                FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            } else {
                Uri.parse(uriText)
            }

            val mime = contentResolver.getType(uri)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    name.substringAfterLast('.', "").lowercase()
                ) ?: "*/*"

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Buka file dengan"))
        } catch (e: Exception) {
            toast("File tidak dapat dibuka: ${e.message}")
        }
    }

    private fun showHistory() {
        val list = b.historyList
        list.removeAllViews()

        val prefs = getSharedPreferences("myshare", MODE_PRIVATE)
        val items = try {
            JSONArray(prefs.getString("history_items", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }

        if (items.length() == 0) {
            val empty = TextView(this).apply {
                text = prefs.getString("history", "Belum ada transfer.")
                textSize = 13f
                setTextColor(0xFF60708A.toInt())
                setPadding(0, 12, 0, 12)
            }
            list.addView(empty)
            return
        }

        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val direction = item.optString("direction")
            val name = item.optString("name")
            val uri = item.optString("uri")
            val time = item.optString("time")

            val row = TextView(this).apply {
                text = "📄  $name\n      $direction • $time     ›"
                textSize = 14f
                setTextColor(0xFF1457D9.toInt())
                setPadding(12, 14, 12, 14)
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                setOnClickListener {
                    openHistoryFile(direction, name, uri)
                }
            }
            list.addView(row)
            val divider = View(this).apply {
                setBackgroundColor(0xFFE1E7F0.toInt())
            }
            list.addView(divider, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ))
        }
    }

    private fun openReceivedFile(name: String) {
        try {
            val file = File(getReceiveFolder(), name)
            if (!file.exists()) {
                toast("File tidak ditemukan.")
                return
            }

            val extension = file.extension.lowercase()
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(extension) ?: "*/*"

            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, "Buka file dengan"))
        } catch (e: Exception) {
            toast("Tidak ada aplikasi yang dapat membuka file ini: ${e.message}")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() { handler.removeCallbacks(dotRunner); server?.stop(); io.shutdown(); super.onDestroy() }
}
