package com.mathnote

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Exposes exactly one temporary PNG to the Android share sheet. */
class PageShareProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.path != "/page.png" || mode != "r") throw FileNotFoundException()
        return ParcelFileDescriptor.open(File(requireNotNull(context).cacheDir, "shared_page.png"), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (uri.path != "/page.png") return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        val row = Array<Any?>(columns.size) { index ->
            when (columns[index]) {
                OpenableColumns.DISPLAY_NAME -> "MathNote page.png"
                OpenableColumns.SIZE -> File(requireNotNull(context).cacheDir, "shared_page.png").length()
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
