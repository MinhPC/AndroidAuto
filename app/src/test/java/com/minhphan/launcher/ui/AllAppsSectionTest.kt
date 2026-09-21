package com.minhphan.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AllAppsSectionTest {
    @Test
    fun plainLettersAreUppercased() {
        assertEquals("M", sectionOf("Maps"))
        assertEquals("Y", sectionOf("yt music"))
    }

    @Test
    fun accentsAreIgnoredAndDStrokeCountsAsD() {
        assertEquals("A", sectionOf("Ảnh"))
        assertEquals("D", sectionOf("Điện thoại"))
        assertEquals("D", sectionOf("điều khiển"))
        assertEquals("Z", sectionOf("Zalo"))
    }

    @Test
    fun digitsSymbolsAndEmptyLabelsGoUnderHash() {
        assertEquals("#", sectionOf("1Password"))
        assertEquals("#", sectionOf("@Home"))
        assertEquals("#", sectionOf("   "))
        assertEquals("#", sectionOf(""))
    }

    @Test
    fun leadingSpacesDoNotHideTheLetter() {
        assertEquals("C", sectionOf("  Chrome"))
    }
}
