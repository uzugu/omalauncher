package dev.omalauncher.widgets

import dev.omalauncher.widgets.runtime.VpetFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VpetFrameTest {
    @Test fun acceptsThemeNeutralPixelsAndFrozenState() {
        val live = VpetFrame.decode(4, 2, listOf(".pid", "pi.."), true, false)
        val frozen = VpetFrame.decode(4, 2, listOf(".pid", "pi.."), true, true)
        assertNotEquals(live, frozen)
        assertEquals(live, VpetFrame.decode(4, 2, listOf(".pid", "pi.."), true, false))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOversizedFrames() {
        VpetFrame.decode(10000, 10000, emptyList(), true, false)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsRaggedFrames() {
        VpetFrame.decode(4, 2, listOf(".pid", "p"), true, false)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownPixels() {
        VpetFrame.decode(4, 1, listOf(".pix"), true, false)
    }
}
