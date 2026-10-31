package zai.mobile.protocol

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Fayl əlavələri — spec §1/#11, §4, §12.
 * SAF (ACTION_OPEN_DOCUMENT) → heç bir storage icazəsi lazım deyil.
 * 60MB limit oxumadan ƏVVƏL yoxlanılır; şəkillər >2048px/>1MB endirilir.
 */
data class PickedFile(val uri: Uri, val name: String, val mime: String, val size: Long)

/** Yükləməyə hazır (yəqin ki, endirilmiş) fayl. */
class PreparedFile(val name: String, val mime: String, val bytes: ByteArray, val kind: String)

data class UploadResult(
    val fileId: String,
    val filename: String,
    val kind: String,
    val directUrl: String,
) {
    /** /api/v2 body-sindəki content hissəsinə çevrilir. */
    fun toPart(): AttachPart {
        val k = when (kind) {
            "image" -> "image_url"
            "video" -> "video_url"
            else -> "file_url"
        }
        return AttachPart(k, directUrl.ifBlank { fileId })
    }
}

class Attachments(private val resolver: ContentResolver) {

    /** SAF uri → ad/mime/ölçü (yuxarıda heç bir fayl oxunmur). */
    fun probe(uri: Uri): PickedFile? {
        var name: String? = null
        var size: Long = -1
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val iName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (iName >= 0) name = c.getString(iName)
                    val iSize = c.getColumnIndex(OpenableColumns.SIZE)
                    if (iSize >= 0 && !c.isNull(iSize)) size = c.getLong(iSize)
                }
            }
        }
        if (size < 0) {
            runCatching {
                resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    size = afd.declaredLength
                }
            }
        }
        val mime = resolver.getType(uri) ?: guessMimeFromName(name)
        val n = name ?: uri.lastPathSegment ?: "file"
        if (mime.isNullOrBlank() && size < 0) return null
        return PickedFile(uri, n, mime ?: "application/octet-stream", size)
    }

    fun classify(mime: String, name: String): String {
        val m = mime.lowercase()
        if (m.startsWith("image/")) return "image"
        if (m.startsWith("video/")) return "video"
        val lower = name.lowercase()
        return when {
            lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp") -> "image"
            lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".avi") ||
                lower.endsWith(".mkv") || lower.endsWith(".webm") -> "video"
            else -> "doc"
        }
    }

    fun guessMimeFromName(name: String?): String? = when (name?.lowercase()?.substringAfterLast('.', "")) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        else -> null
    }

    /**
     * Oxu + 60MB qoruması + (şəkil üçün) endirmə.
     * Yekun fayl: original, yaxud (endirilmişsə) JPEG.
     */
    fun prepare(p: PickedFile): PreparedFile? {
        if (p.size > MAX_BYTES) return null
        val raw = resolver.openInputStream(p.uri)?.use { it.readBytes() } ?: return null
        val kind = classify(p.mime, p.name)
        if (kind != "image") {
            return PreparedFile(p.name, p.mime, raw, kind)
        }
        val isGif = p.mime.lowercase() == "image/gif" || p.name.lowercase().endsWith(".gif")
        if (isGif || raw.size <= DOWNSCALE_MIN_BYTES) {
            return PreparedFile(p.name, p.mime, raw, kind)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return PreparedFile(p.name, p.mime, raw, kind)
        }
        if (bounds.outWidth <= MAX_SIDE && bounds.outHeight <= MAX_SIDE) {
            return PreparedFile(p.name, p.mime, raw, kind)
        }
        val scaled = downscale(raw, bounds.outWidth, bounds.outHeight) ?: return PreparedFile(p.name, p.mime, raw, kind)
        if (scaled.size >= raw.size) {
            return PreparedFile(p.name, p.mime, raw, kind)
        }
        val newName = p.name.substringBeforeLast('.') + ".jpg"
        return PreparedFile(newName, "image/jpeg", scaled, kind)
    }

    private fun downscale(raw: ByteArray, w: Int, h: Int): ByteArray? {
        var sample = 1
        while (w / (sample * 2) >= MAX_SIDE / 2 && h / (sample * 2) >= MAX_SIDE / 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return null
        val ratio = minOf(
            MAX_SIDE.toFloat() / bmp.width,
            MAX_SIDE.toFloat() / bmp.height,
        ).coerceAtMost(1f)
        val out: Bitmap = if (ratio < 1f) {
            Bitmap.createScaledBitmap(
                bmp, (bmp.width * ratio).toInt().coerceAtLeast(1), (bmp.height * ratio).toInt().coerceAtLeast(1), true,
            )
        } else bmp
        val bos = ByteArrayOutputStream()
        if (!out.compress(Bitmap.CompressFormat.JPEG, 85, bos)) return null
        return bos.toByteArray()
    }

    companion object {
        const val MAX_BYTES = 60L * 1024 * 1024
        const val MAX_SIDE = 2048
        const val DOWNSCALE_MIN_BYTES = 1024 * 1024
    }
}

/** POST /api/v1/storage/pfs/files/upload — multipart, tək "file" hissəsi. */
class UploadApi(private val http: ZaiHttp) {

    fun upload(token: String, file: PreparedFile): UploadResult {
        val boundary = "----ZaiMobile${System.currentTimeMillis()}"
        val head = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name.replace('"', '\'')}\"\r\n")
            append("Content-Type: ").append(file.mime).append("\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
        val tail = ("\r\n--$boundary--\r\n").toByteArray(Charsets.UTF_8)
        val body = ByteArray(head.size + file.bytes.size + tail.size)
        var off = 0
        head.copyInto(body, off); off += head.size
        file.bytes.copyInto(body, off); off += file.bytes.size
        tail.copyInto(body, off)

        val out = http.json(
            attempts = UPLOAD_ATTEMPTS,
            method = "POST",
            path = { "/api/v1/storage/pfs/files/upload" },
            headers = {
                listOf(
                    "Authorization" to "Bearer $token",
                    "Accept" to "application/json",
                    "Content-Type" to "multipart/form-data; boundary=$boundary",
                )
            },
            body = { body },
        )
        if (out.code !in 200..299) {
            throw ZaiException.fromHttp(out.code, String(out.bytes, Charsets.UTF_8))
        }
        val root = String(out.bytes, Charsets.UTF_8).toJsonObject()
            ?: throw ZaiException.Bug("upload: non-JSON 200 response")
        if (root.optInt("code", -1) != 0) {
            throw ZaiException.Http(out.code, ZaiException.extractDetail(String(out.bytes, Charsets.UTF_8)))
        }
        val f = root.optJSONObject("data")?.optJSONObject("file")
            ?: throw ZaiException.Bug("upload: no data.file")
        val fileId = f.optString("id", f.optString("file_id", ""))
        val directUrl = f.optString("direct_url", "")
        if (fileId.isBlank()) throw ZaiException.Bug("upload: no file id")
        return UploadResult(
            fileId = fileId,
            filename = f.optString("filename", file.name),
            kind = file.kind,
            directUrl = directUrl,
        )
    }

    companion object {
        const val UPLOAD_ATTEMPTS = 4
    }
}
