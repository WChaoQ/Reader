package com.reader.engine.format.txt

object TxtChapterSplitter {
    private val CHAPTER_REGEX = Regex(
        pattern = """^(?:第[\d一二三四五六七八九十百千零〇两]{1,12}[章节回卷部篇集]|Chapter\s+\d+|CHAPTER\s+\d+)[^\n]{0,40}$""",
        option = RegexOption.MULTILINE,
    )

    private const val MAX_CHAPTER_CHARS = 80_000

    data class ChapterRange(
        val title: String?,
        val start: Int,
        val end: Int,
    )

    fun split(text: String): List<ChapterRange> {
        val matches = CHAPTER_REGEX.findAll(text).toList()
        if (matches.isEmpty()) {
            return virtualSplit(text)
        }
        val ranges = mutableListOf<ChapterRange>()
        if (matches.first().range.first > 0) {
            ranges += ChapterRange(
                title = "前言",
                start = 0,
                end = matches.first().range.first,
            )
        }
        for (i in matches.indices) {
            val start = matches[i].range.first
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            val title = matches[i].value.trim().take(80)
            ranges += ChapterRange(title = title, start = start, end = end)
        }
        return ranges.flatMap { range ->
            val len = range.end - range.start
            if (len <= MAX_CHAPTER_CHARS) listOf(range)
            else virtualSplit(text.substring(range.start, range.end), range.start, range.title)
        }
    }

    private fun virtualSplit(
        text: String,
        baseOffset: Int = 0,
        baseTitle: String? = null,
    ): List<ChapterRange> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<ChapterRange>()
        var i = 0
        var part = 1
        while (i < text.length) {
            var end = (i + MAX_CHAPTER_CHARS).coerceAtMost(text.length)
            if (end < text.length) {
                val nl = text.lastIndexOf('\n', end)
                if (nl > i + MAX_CHAPTER_CHARS / 2) end = nl + 1
            }
            val title = when {
                baseTitle != null && part == 1 -> baseTitle
                baseTitle != null -> "$baseTitle ($part)"
                else -> "第${part}部分"
            }
            result += ChapterRange(title = title, start = baseOffset + i, end = baseOffset + end)
            i = end
            part++
        }
        return result
    }
}
