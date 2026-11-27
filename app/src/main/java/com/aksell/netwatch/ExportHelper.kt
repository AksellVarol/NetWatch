package com.aksell.netwatch

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale

// v2.9.6 — ONE export path for every REPORT export (LAB JSON+PDF,
// MainActivity CSV/JSON/PDF): Internal Storage/Download/NetWatch/.
// - API 29+: MediaStore Downloads with RELATIVE_PATH "Download/NetWatch"
//   (no storage permission needed; the file is visible in File Manager
//   and Downloads as soon as IS_PENDING is cleared).
// - API 21-28: the public Downloads/NetWatch folder, created with mkdirs
//   (needs WRITE_EXTERNAL_STORAGE, maxSdkVersion 28 — see the manifest).
// - NEVER auto-delete, NEVER overwrite: every name is timestamped
//   (NETWATCH_Report_2026-11-10_054500.pdf) and _2/_3 is appended on a
//   collision, so an earlier export is always preserved.
// - Backup/restore files are NOT exports: they stay in the app-private
//   dir (History.backup/restore) — a restore file in Downloads would be
//   user-visible and editable, which must never happen.
// - Zero new dependencies: there is no androidx FileProvider in this
//   project, so legacy files are shared through the minimal read-only
//   ExportFileProvider below (openFile + query + getType only).
object ExportHelper {
    const val FOLDER_LABEL = "Downloads/NetWatch"
    const val AUTHORITY = "com.aksell.netwatch.exports"
    private const val RELATIVE_PATH = "Download/NetWatch"

    data class ExportResult(
        val ok: Boolean,
        val uri: Uri?,
        val file: File?,
        val displayName: String,
        val folderLabel: String = FOLDER_LABEL,
        val error: String = ""
    )

    fun timestampedName(base: String, ext: String, now: Long = System.currentTimeMillis()): String {
        val ts = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(java.util.Date(now))
        return "${base}_$ts.$ext"
    }

    private fun numberedName(base: String, ext: String, n: Int, now: Long = System.currentTimeMillis()): String {
        val ts = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(java.util.Date(now))
        return if (n <= 1) "${base}_$ts.$ext" else "${base}_${ts}_$n.$ext"
    }

    fun mimeFor(displayName: String): String = when (displayName.substringAfterLast('.', "").lowercase(Locale.US)) {
        "pdf" -> "application/pdf"
        "json" -> "application/json"
        "csv" -> "text/csv"
        else -> "application/octet-stream"
    }

    // Write the report bytes through `write` and return where it landed.
    // Any failure deletes the partial file and reports the EXACT cause in
    // ExportResult.error — never a silent Toast with a private path.
    fun save(ctx: Context, base: String, ext: String, mime: String, write: (OutputStream) -> Unit): ExportResult {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveMediaStore(ctx, base, ext, mime, write)
            else saveLegacy(ctx, base, ext, write)
        } catch (e: Exception) {
            ExportResult(false, null, null, timestampedName(base, ext), error = e.message ?: e.javaClass.simpleName)
        }
    }

    // API 29+ only — kept in its own method so the MediaStore.Downloads
    // references are never loaded on older devices.
    private fun saveMediaStore(ctx: Context, base: String, ext: String, mime: String, write: (OutputStream) -> Unit): ExportResult {
        val resolver = ctx.contentResolver
        var n = 1
        var name = numberedName(base, ext, n)
        while (mediaStoreNameTaken(ctx, name)) { n++; name = numberedName(base, ext, n) }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return ExportResult(false, null, null, name, error = "MediaStore: درج فایل در Downloads ممکن نشد")
        return try {
            resolver.openOutputStream(uri)?.use { write(it) }
                ?: throw IOException("MediaStore: بازکردن فایل برای نوشتن ممکن نشد")
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            ExportResult(true, uri, null, name)
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            ExportResult(false, null, null, name, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun mediaStoreNameTaken(ctx: Context, name: String): Boolean {
        return try {
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(name, "$RELATIVE_PATH/"), null
            )?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) { false }
    }

    // API 21-28 only — public Downloads folder. WRITE_EXTERNAL_STORAGE is
    // a runtime permission on API 23-28; without it the save fails with
    // the exact cause instead of a fake success.
    @Suppress("DEPRECATION")
    private fun saveLegacy(ctx: Context, base: String, ext: String, write: (OutputStream) -> Unit): ExportResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            return ExportResult(false, null, null, timestampedName(base, ext),
                error = "دسترسی WRITE_EXTERNAL_STORAGE داده نشده است (اندروید ۶ تا ۹: از تنظیمات برنامه اجازه ذخیره‌سازی را بده)")
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NetWatch")
        if (!dir.exists() && !dir.mkdirs()) {
            return ExportResult(false, null, null, timestampedName(base, ext),
                error = "ساخت پوشه Downloads/NetWatch ممکن نشد")
        }
        var n = 1
        var name = numberedName(base, ext, n)
        var f = File(dir, name)
        while (f.exists()) { n++; name = numberedName(base, ext, n); f = File(dir, name) }
        return try {
            FileOutputStream(f).use { write(it) }
            ExportResult(true, contentUriFor(f), f, name)
        } catch (e: Exception) {
            try { f.delete() } catch (_: Exception) { }
            ExportResult(false, null, f, name, error = e.message ?: e.javaClass.simpleName)
        }
    }

    fun contentUriFor(file: File): Uri =
        Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(file.name).build()

    // Does the exported file still exist (used by the on-device LAB export
    // test: a second export must never delete or overwrite the first one)?
    fun exists(ctx: Context, result: ExportResult): Boolean {
        val uri = result.uri
        if (uri != null && uri.scheme == "content" && uri.authority == AUTHORITY) {
            return result.file?.exists() ?: false
        }
        if (uri != null && uri.scheme == "content") {
            return try { ctx.contentResolver.openInputStream(uri)?.use { true } ?: false } catch (e: Exception) { false }
        }
        return result.file?.exists() ?: false
    }

    // Delete an exported file (used by the on-device LAB export test to
    // clean up its own test files — never called for user exports).
    fun delete(ctx: Context, result: ExportResult): Boolean {
        val uri = result.uri
        if (uri != null && uri.scheme == "content" && uri.authority == AUTHORITY) {
            return try { result.file?.delete() ?: false } catch (e: Exception) { false }
        }
        if (uri != null && uri.scheme == "content") {
            return try { ctx.contentResolver.delete(uri, null, null) > 0 } catch (e: Exception) { false }
        }
        return try { result.file?.delete() ?: false } catch (e: Exception) { false }
    }

    fun openFolderIntent(): Intent {
        val docUri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Download/NetWatch")
        return Intent(Intent.ACTION_VIEW).setData(docUri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // One result dialog for every export button (LAB JSON+PDF, Main CSV /
    // JSON / PDF): success/failure, the EXACT filenames, the storage path,
    // "Open folder" (best-effort DocumentsUI intent, Toast fallback),
    // Share per file (ACTION_SEND with the content Uri), and the EXACT
    // error cause when a save fails. Runs on the main thread; the caller
    // does the export itself on a background thread first.
    fun showExportDialog(activity: Activity, title: String, items: List<Pair<String, ExportResult>>) {
        val okItems = items.filter { it.second.ok }
        val failed = items.filter { !it.second.ok }
        val msg = StringBuilder()
        if (okItems.isNotEmpty()) {
            msg.append("گزارش‌ها با موفقیت ذخیره شدند.\n")
            for ((label, r) in okItems) msg.append("$label: ${r.displayName}\n")
            msg.append("محل ذخیره: $FOLDER_LABEL/")
        }
        if (failed.isNotEmpty()) {
            if (msg.isNotEmpty()) msg.append("\n\n")
            msg.append("ذخیره ناموفق:\n")
            for ((label, r) in failed) {
                msg.append("$label: ${if (r.error.isBlank()) "خطای نامشخص" else r.error}\n")
            }
        }
        if (okItems.isEmpty() && failed.isEmpty()) msg.append("گزارشی ساخته نشد.")
        val actions = mutableListOf<String>()
        actions.add("باز کردن پوشه")
        for ((label, _) in okItems) actions.add("اشتراک‌گذاری $label")
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(msg.toString().trim())
            .setItems(actions.toTypedArray()) { _, which ->
                if (which == 0) {
                    try { activity.startActivity(openFolderIntent()) } catch (e: Exception) {
                        Toast.makeText(activity, "پوشه در $FOLDER_LABEL/ است — با فایل‌منیجر بازش کن.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    val (_, r) = okItems[which - 1]
                    val uri = r.uri ?: return@setItems
                    val send = Intent(Intent.ACTION_SEND).setType(mimeFor(r.displayName))
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try { activity.startActivity(Intent.createChooser(send, "اشتراک‌گذاری ${r.displayName}")) } catch (e: Exception) {
                        Toast.makeText(activity, "اشتراک‌گذاری ممکن نشد: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("بستن", null)
            .show()
    }
}

// Minimal read-only ContentProvider for legacy (API 21-28) export files:
// the project has zero dependencies, so androidx FileProvider is NOT
// available. Serves ONLY files inside Downloads/NetWatch, read-only,
// never outside that folder (canonical-path check). API 29+ shares the
// MediaStore content Uri directly and never touches this provider.
class ExportFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    companion object {
        /** Builds the content Uri for a Share copy in the cache dir.
         *  (This project has no androidx FileProvider, so there is no
         *  getUriForFile — building the Uri is the equivalent.) */
        fun cacheShareUri(fileName: String): Uri =
            Uri.Builder().scheme("content").authority(ExportHelper.AUTHORITY)
                .appendPath("cache").appendPath(fileName).build()
    }

    @Suppress("DEPRECATION")
    private fun fileFor(uri: Uri): File? {
        if (uri.authority != ExportHelper.AUTHORITY) return null
        val segments = uri.pathSegments
        if (segments.isEmpty()) return null
        // 2.10.1: "cache/<name>" serves the Share copy in the app's cache
        // dir — the only path guaranteed File-readable on API 29+ (the
        // Download/NetWatch file is MediaStore-owned and may not be
        // File-readable on API 34). The Download root below is unchanged.
        val dir: File
        val name: String
        if (segments.size == 2 && segments[0] == "cache") {
            val ctx = context ?: return null
            dir = File(ctx.cacheDir, "netwatch-share")
            name = segments[1]
        } else if (segments.size == 1) {
            dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NetWatch")
            name = segments[0]
        } else return null
        if (name.contains("/") || name.contains("..")) return null
        return try {
            val f = File(dir, name)
            if (f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) f else null
        } catch (e: Exception) { null }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileFor(uri) ?: throw FileNotFoundException("Export not found: $uri")
        if (mode.contains("w")) throw FileNotFoundException("Exports are read-only")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        val f = fileFor(uri) ?: return null
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        c.addRow(arrayOf<Any>(f.name, f.length()))
        return c
    }

    override fun getType(uri: Uri): String? = ExportHelper.mimeFor(uri.lastPathSegment ?: "")

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Exports are read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException("Exports are read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException("Exports are read-only")
}
