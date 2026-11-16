package id.budiman.myshare

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

class ShareServer(port: Int, private val folder: File, private val onReceived: (String) -> Unit) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.GET && session.uri == "/") {
            return newFixedLengthResponse("MY SHARE READY")
        }
        if (session.method == Method.POST && session.uri == "/upload") {
            return try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val tempPath = files["file"] ?: files["postData"]
                    ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "File tidak ditemukan")
                val original = session.parameters["file"]?.firstOrNull()
                    ?: session.parameters["filename"]?.firstOrNull() ?: ""
                val source = File(tempPath)
                val name = safeName(if (original.isNotBlank()) original else source.name.substringAfterLast("/"))
                var dest = File(folder, name)
                var n = 1
                while (dest.exists()) {
                    val dot = name.lastIndexOf('.')
                    dest = if (dot > 0) File(folder, "${name.substring(0, dot)} ($n)${name.substring(dot)}") else File(folder, "$name ($n)")
                    n++
                }
                FileInputStream(source).use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
                onReceived(dest.name)
                newFixedLengthResponse(Response.Status.OK, "text/plain", "OK:${dest.name}")
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "ERROR:${e.message}")
            }
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
    }

    private fun safeName(input: String): String {
        val cleaned = input.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._() -]"), "_").trim().take(150)
        return cleaned.ifBlank { "received_${System.currentTimeMillis()}" }
    }
}
