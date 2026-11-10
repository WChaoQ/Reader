package com.reader.engine.format.epub

/**
 * Resolves an EPUB resource reference against the document that contains it.
 *
 * The result is always a package-relative path. Attempts to walk above the
 * package root are clamped at the root, and URL fragments/queries are excluded
 * because they identify a location or request variant rather than a ZIP entry.
 */
internal object EpubPathResolver {

    fun resolve(baseDocumentPath: String, href: String): String {
        val cleanHref = href.substringBefore('#').substringBefore('?')
        val normalizedBase = baseDocumentPath.replace('\\', '/')
        val normalizedHref = cleanHref.replace('\\', '/')

        if (normalizedHref.startsWith('/')) {
            return normalizeSegments(normalizedHref.trimStart('/'))
        }

        val baseDirectory = normalizedBase.substringBeforeLast('/', missingDelimiterValue = "")
        val segments = buildList {
            if (baseDirectory.isNotEmpty()) addAll(baseDirectory.split('/'))
            addAll(normalizedHref.split('/'))
        }
        return normalizeSegments(segments.joinToString("/"))
    }

    private fun normalizeSegments(path: String): String {
        val result = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (result.isNotEmpty()) result.removeLast()
                else -> result.addLast(segment)
            }
        }
        return result.joinToString("/")
    }
}
