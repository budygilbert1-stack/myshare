package id.budiman.myshare

import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.View
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

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) sendFiles(uris)
    }
    private val scanQr = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank() && contents.startsWith("http://")) {
            targetUrl = contents.trimEnd('/')
            b.instructionText.text = "Perangkat penerima terhubung. Pilih file yang akan dikirim."
            b.addressText.text = targetUrl
            pickFiles.launch(arrayOf("*/*"))
        } else if (contents != null) toast("QR tidak dikenali. Pindai QR MY SHARE.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.sendButton.setOnClickListener { scanReceiver() }
        b.receiveButton.setOnClickListener { startReceiving() }
        showHistory()
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
                server = ShareServer(8989, getReceiveFolder()) { name ->
                    runOnUiThread {
                        addHistory("Diterima: $name")
                        b.statusText.text = "File berhasil diterima"
                    }
                }.also { it.start() }
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
        val base = targetUrl ?: run { toast("Pindai QR penerima terlebih dahulu."); return }
        b.progressBar.visibility = View.VISIBLE
        b.progressBar.progress = 0
        b.progressText.text = "Menyiapkan ${uris.size} file..."
        b.statusText.text = "Sedang mengirim"
        io.execute {
            var success = 0
            uris.forEachIndexed { index, uri ->
                try {
                    val name = getFileName(uri)
                    uploadOne(base, uri, name)
                    success++
                    runOnUiThread { addHistory("Terkirim: $name") }
                } catch (e: Exception) {
                    runOnUiThread { b.progressText.text = "Gagal: ${e.message}" }
                }
                runOnUiThread {
                    b.progressBar.progress = ((index + 1) * 100 / uris.size)
                    b.progressText.text = "${index + 1}/${uris.size} file diproses"
                }
            }
            runOnUiThread {
                b.statusText.text = "Selesai • $success/${uris.size} file terkirim"
                b.progressText.text = "Transfer selesai."
                showHistory()
                toast("Transfer selesai: $success/${uris.size} file")
            }
        }
    }

    private fun uploadOne(base: String, uri: Uri, name: String) {
        val boundary = "----MyShare${System.currentTimeMillis()}"
        val url = URL("$base/upload")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 120000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        val safeName = name.replace("\"", "_").replace("\r", "_").replace("\n", "_")
        conn.outputStream.use { out ->
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            contentResolver.openInputStream(uri)?.use { input -> input.copyTo(out, 64 * 1024) } ?: error("File tidak dapat dibaca")
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val code = conn.responseCode
        val response = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) error("Server menolak file ($code): $response")
    }

    private fun getFileName(uri: Uri): String {
        var name = "file_${System.currentTimeMillis()}"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
        }
        return name
    }

    private fun addHistory(line: String) {
        val prefs = getSharedPreferences("myshare", MODE_PRIVATE)
        val old = prefs.getString("history", "").orEmpty().lines().filter { it.isNotBlank() }
        prefs.edit().putString("history", (listOf("${java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}  $line") + old).take(12).joinToString("\n")).apply()
        showHistory()
    }

    private fun showHistory() { b.historyText.text = getSharedPreferences("myshare", MODE_PRIVATE).getString("history", "Belum ada transfer.") }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() { server?.stop(); io.shutdown(); super.onDestroy() }
}
