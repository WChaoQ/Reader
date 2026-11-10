package com.reader.engine.format.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubPathResolverTest {

    @Test
    fun resolvesNestedRelativeResourcesAgainstTheContainingDocument() {
        assertEquals(
            "OPS/Images/cover%20art.jpg",
            EpubPathResolver.resolve("OPS/Text/chapter-1.xhtml", "../Images/cover%20art.jpg"),
        )
    }

    @Test
    fun removesFragmentAndQueryBeforeResolvingResourcePath() {
        assertEquals(
            "OPS/Text/chapter-2.xhtml",
            EpubPathResolver.resolve("OPS/Text/chapter-1.xhtml", "chapter-2.xhtml?mode=read#section-3"),
        )
    }

    @Test
    fun resolvesPackageRootReferences() {
        assertEquals(
            "Images/cover.jpg",
            EpubPathResolver.resolve("OPS/Text/chapter.xhtml", "/Images/cover.jpg"),
        )
    }

    @Test
    fun clampsParentTraversalAtPackageRoot() {
        assertEquals(
            "Images/cover.jpg",
            EpubPathResolver.resolve("chapter.xhtml", "../../Images/cover.jpg"),
        )
    }

    @Test
    fun normalizesDotSegmentsAndSeparators() {
        assertEquals(
            "OPS/Images/cover.jpg",
            EpubPathResolver.resolve("OPS/Text/chapter.xhtml", """..\\Images\\./cover.jpg"""),
        )
    }
}
