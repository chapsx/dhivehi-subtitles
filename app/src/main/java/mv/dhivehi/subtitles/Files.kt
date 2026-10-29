package mv.dhivehi.subtitles

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

object Files {
    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i)?.let { return it }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun readText(ctx: Context, uri: Uri): String {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Cannot open file")
        return TextCodec.decode(bytes)
    }

    fun write(ctx: Context, uri: Uri, bytes: ByteArray) {
        val out = ctx.contentResolver.openOutputStream(uri, "wt")
            ?: throw IllegalStateException("Cannot write file")
        out.use { it.write(bytes) }
    }

    /** Keeps access to a picked file after the app restarts. */
    fun keepAccess(ctx: Context, uri: Uri, write: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
            if (write) try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
    }

    fun baseName(name: String) = name.substringBeforeLast('.', name)
}
