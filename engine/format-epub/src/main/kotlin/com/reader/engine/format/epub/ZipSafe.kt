package com.reader.engine.format.epub

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Zip Slip protection + size limits (architecture §6.3). */
object ZipSafe {
    private const val MAX_ENTRY_BYTES = 50L * 1024 * 1024

    fun open(file: File): ZipFile = ZipFile(file)

    fun readEntry(zip: ZipFile, name: String): ByteArray {
        val entry = zip.getEntry(name) ?: error("Missing entry: $name")
        require(!entry.name.contains("..")) { "Zip Slip blocked: ${entry.name}" }
        require(entry.size <= MAX_ENTRY_BYTES || entry.size == -1L) {
            "Entry too large: ${entry.name}"
        }
        return zip.getInputStream(entry).use { it.readBytes().also { bytes ->
            require(bytes.size <= MAX_ENTRY_BYTES) { "Entry too large after read" }
        } }
    }

    fun openStream(zip: ZipFile, name: String): InputStream {
        val entry = zip.getEntry(name) ?: error("Missing entry: $name")
        require(!entry.name.contains("..")) { "Zip Slip blocked: ${entry.name}" }
        return zip.getInputStream(entry)
    }

    fun resolveHref(basePath: String, href: String): String {
        val clean = href.substringBefore('#').substringBefore('?')
        if (clean.startsWith("/")) return clean.trimStart('/')
        val baseDir = basePath.substringBeforeLast('/', missingDelimiterValue = "")
        if (baseDir.isEmpty()) return clean
        val parts = (baseDir.split("/") + clean.split("/")).toMutableList()
        val out = mutableListOf<String>()
        for (p in parts) {
            when (p) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                else -> out += p
            }
        }
        return out.joinToString("/")
    }
}
