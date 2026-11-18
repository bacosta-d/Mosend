package com.filetransfer.coui.transfer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

/**
 * 文件相关工具：URI 复制到缓存、按 MIME 打开接收到的文件。
 */
object FileUtils {

    /** 把 content:// 复制到缓存目录，返回本地 File。失败返回 null。 */
    fun copyUriToCache(context: Context, uri: Uri): File? {
        return try {
            var name = "file_${System.currentTimeMillis()}"
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
            }
            val dir = File(context.cacheDir, "outgoing").apply { mkdirs() }
            var target = File(dir, name)
            if (target.exists()) {
                val dot = name.lastIndexOf('.')
                val base = if (dot > 0) name.substring(0, dot) else name
                val ext = if (dot > 0) name.substring(dot) else ""
                var i = 1
                while (target.exists()) { target = File(dir, "${base}_$i$ext"); i++ }
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.exists() && target.length() > 0) target else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 用系统合适应用打开文件。成功返回 true。
     * location 支持普通文件路径或 content:// URI（SAF 保存目录）。
     */
    fun open(context: Context, location: String?, mimeType: String?): Boolean {
        if (location.isNullOrBlank()) return false
        return try {
            val uri = toUri(context, location) ?: return false
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType ?: guessMime(location))
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun exists(context: Context, location: String?): Boolean {
        if (location.isNullOrBlank()) return false
        return try {
            if (location.startsWith("content://")) {
                context.contentResolver.openInputStream(android.net.Uri.parse(location))?.use { true } ?: false
            } else {
                File(location).exists()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun toUri(context: Context, location: String): android.net.Uri? {
        return if (location.startsWith("content://")) {
            android.net.Uri.parse(location)
        } else {
            val f = File(location)
            if (!f.exists()) return null
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        }
    }

    fun share(context: Context, file: File?, mimeType: String?): Boolean {
        if (file == null || !file.exists()) return false
        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mimeType ?: guessMime(file.name)
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "分享").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "zip" -> "application/zip"
        "rar" -> "application/x-rar-compressed"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }
}
