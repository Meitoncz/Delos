package com.limelight.shell.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogoSizeTest {
    @Test fun aTallLogoIsFittedByItsHeight() {
        assertEquals(150 to 180, logoSize(1796, 2161, 900f, 180f))
    }

    @Test fun aWideLogoIsFittedByItsWidth() {
        assertEquals(900 to 135, logoSize(3000, 450, 900f, 180f))
    }

    @Test fun aLogoThatFitsIsKept() {
        assertNull(logoSize(600, 150, 900f, 180f))
        assertNull(logoSize(0, 0, 900f, 180f))
    }

    @Test fun subsamplingNeverGoesBelowTheTarget() {
        assertEquals(8, sampleSize(1796, 2161, 150, 180))
        assertEquals(1, sampleSize(1796, 2161, 1000, 1000))
        assertEquals(2, sampleSize(3000, 450, 900, 135))
    }
}
