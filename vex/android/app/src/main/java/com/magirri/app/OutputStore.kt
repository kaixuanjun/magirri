package com.magirri.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 成片的默认去处：**相册 / Magirri**。
 *
 * - Android 10+：走 MediaStore，插到 `DCIM/Magirri`，相册里能直接看到，
 *   不需要任何存储权限；
 * - Android 8/9：那时候还没强制分区存储，直接写 `DCIM/Magirri`，
 *   写完再通知媒体库扫一遍（需要 WRITE_EXTERNAL_STORAGE）。
 *
 * 两个分支都返回一个可写的 Uri，MediaMuxer 通过 `openFileDescriptor` 写进去。
 */
object OutputStore {

    const val FOLDER = "Magirri"
    const val PRETTY_PATH = "相册/$FOLDER"

    fun create(context: Context, displayName: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$FOLDER")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            )
            if (uri != null) return uri
            // 媒体库插入失败就退到应用自己的外部目录（至少不丢文件）
            return fileFallback(context, displayName)
        }
        return legacyFile(displayName) ?: fileFallback(context, displayName)
    }

    /** 导出成功后收尾：清 pending 标记 / 触发媒体扫描，让相册立刻能看到。 */
    fun commit(context: Context, uri: Uri) {
        if (uri.scheme == "content") {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, values, null, null)
            } catch (_: Exception) {
            }
        } else if (uri.scheme == "file" && uri.path != null) {
            try {
                MediaScannerConnection.scanFile(
                    context, arrayOf(uri.path), arrayOf("video/mp4"), null
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun legacyFile(displayName: String): Uri? {
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), FOLDER
            )
            if (!dir.exists() && !dir.mkdirs()) null
            else Uri.fromFile(File(dir, uniqueName(dir, displayName)))
        } catch (e: Exception) {
            null
        }
    }

    private fun fileFallback(context: Context, displayName: String): Uri {
        val dir = File(context.getExternalFilesDir(null), "out")
        if (!dir.exists()) dir.mkdirs()
        return Uri.fromFile(File(dir, uniqueName(dir, displayName)))
    }

    private fun uniqueName(dir: File, name: String): String {
        var f = File(dir, name)
        if (!f.exists()) return name
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "mp4")
        var i = 2
        while (f.exists()) {
            f = File(dir, "${base}_$i.$ext")
            i++
        }
        return f.name
    }

    /** 给界面显示用：这个 uri 最终在哪儿（MediaStore 的 lastPathSegment 是数字 ID，得查显示名） */
    fun describe(context: Context, uri: Uri): String {
        val name = try {
            context.contentResolver
                .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "成片.mp4"
        return "$PRETTY_PATH/$name"
    }
}