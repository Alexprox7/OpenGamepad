package org.opengamepad.amiibo

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Amiibo dumps kept in the app's private storage: `amiibo/<group>/<name>.bin`, or directly in
 * `amiibo/` for no group. Files come from the system picker (single files or a whole folder,
 * whose sub-folders become groups), so no storage permission is needed.
 */
class AmiiboLibrary(filesDir: File) {
    private val root = File(filesDir, "amiibo")

    class Entry(val name: String, val group: String, val file: File, val identity: AmiiboIdentity) {
        /** Path relative to the library, used as the key in menus. */
        val key: String get() = if (group.isEmpty()) name else "$group/$name"
    }

    class ImportResult(val added: Int, val skipped: Int)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    suspend fun load() = withContext(Dispatchers.IO) { _entries.value = scan() }

    fun find(key: String): Entry? = _entries.value.firstOrNull { it.key == key }

    suspend fun importFiles(resolver: ContentResolver, uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        var added = 0
        var skipped = 0
        for (uri in uris) {
            if (store(resolver, uri, displayName(resolver, uri), "")) added++ else skipped++
        }
        _entries.value = scan()
        ImportResult(added, skipped)
    }

    /** A folder picked with the system tree picker; its sub-folders (any depth) become groups. */
    suspend fun importTree(resolver: ContentResolver, tree: Uri): ImportResult = withContext(Dispatchers.IO) {
        var added = 0
        var skipped = 0
        fun walk(documentId: String, group: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            resolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < MAX_DEPTH) walk(id, if (group.isEmpty()) name else "$group/$name", depth + 1)
                    } else if (name.endsWith(".bin", ignoreCase = true)) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        if (store(resolver, uri, name, group)) added++ else skipped++
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        _entries.value = scan()
        ImportResult(added, skipped)
    }

    suspend fun delete(entry: Entry) = withContext(Dispatchers.IO) {
        entry.file.delete()
        entry.file.parentFile?.takeIf { it != root && it.list()?.isEmpty() == true }?.delete()
        _entries.value = scan()
    }

    private fun store(resolver: ContentResolver, uri: Uri, displayName: String?, group: String): Boolean {
        // Reads at most MAX_SIZE + 1 bytes: anything longer is not a dump.
        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(AmiiboDump.MAX_SIZE + 1)
                var total = 0
                while (total < buffer.size) {
                    val n = input.read(buffer, total, buffer.size - total)
                    if (n < 0) break
                    total += n
                }
                buffer.copyOf(total)
            }
        }.getOrNull() ?: return false
        if (AmiiboDump.parse(bytes) == null) return false
        val name = clean(displayName?.removeSuffix(".bin")?.removeSuffix(".BIN") ?: "amiibo")
        val dir = if (group.isEmpty()) root else File(root, group.split('/').joinToString("/") { clean(it) })
        dir.mkdirs()
        File(dir, "$name.bin").writeBytes(bytes)
        return true
    }

    private fun scan(): List<Entry> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().maxDepth(MAX_DEPTH + 1)
            .filter { it.isFile && it.name.endsWith(".bin") }
            .mapNotNull { file ->
                val identity = runCatching { AmiiboDump.parse(file.readBytes()) }.getOrNull() ?: return@mapNotNull null
                val group = file.parentFile!!.relativeTo(root).invariantSeparatorsPath
                Entry(file.name.removeSuffix(".bin"), group, file, identity)
            }
            .sortedWith(compareBy({ it.group.lowercase() }, { it.name.lowercase() }))
            .toList()
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** Safe as a file name and short enough for the console's folder names. */
    private fun clean(name: String) =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.').take(48).ifEmpty { "amiibo" }

    private companion object {
        const val MAX_DEPTH = 3
    }
}
