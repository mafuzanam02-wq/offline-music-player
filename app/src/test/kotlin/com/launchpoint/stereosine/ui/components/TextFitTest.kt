package com.launchpoint.stereosine.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFitTest {
    @Test fun wordsOf_splits_on_any_whitespace_and_ignores_empties() {
        assertEquals(listOf("Played", "Tracks"), wordsOf("Played Tracks"))
        assertEquals(listOf("126", "h", "40", "min"), wordsOf("  126 h\t40  min "))
        assertEquals(emptyList<String>(), wordsOf("   "))
        assertEquals(listOf("com.launchpoint.stereosine.debug"), wordsOf("com.launchpoint.stereosine.debug"))
    }

    private val pads = listOf(4f, 2f, 0f)

    @Test fun navigation_slots_stay_equal_while_the_widest_label_fits_an_equal_share() {
        val w = navigationSlotWidths(listOf(36f, 40f, 46f, 52f), total = 392f, paddingCandidates = pads, minSlot = 48f)
        assertEquals(listOf(98f, 98f, 98f, 98f), w)
    }

    @Test fun navigation_slots_follow_label_widths_when_an_equal_share_is_too_narrow() {
        val labels = listOf(64f, 67f, 74f, 86f)
        val w = navigationSlotWidths(labels, total = 368f, paddingCandidates = pads, minSlot = 48f)
        assertEquals(368f, w.sum(), 0.01f)
        labels.forEachIndexed { i, l -> assertTrue("slot $i ${w[i]} must hold label $l plus 8 padding", w[i] >= l + 8f) }
    }

    @Test fun navigation_slots_tighten_padding_before_shrinking_a_label() {
        val labels = listOf(64f, 67f, 74f, 86f) // 291 total: 4 dp padding needs 323, 2 dp needs 307, 0 needs 291
        val w = navigationSlotWidths(labels, total = 310f, paddingCandidates = pads, minSlot = 48f)
        assertEquals(310f, w.sum(), 0.01f)
        labels.forEachIndexed { i, l -> assertTrue("slot $i ${w[i]} must hold label $l plus 4 padding", w[i] >= l + 4f - 0.01f) }
    }

    @Test fun navigation_slots_only_shrink_below_a_label_when_the_bar_is_narrower_than_the_labels() {
        val labels = listOf(64f, 67f, 74f, 86f)
        val w = navigationSlotWidths(labels, total = 280f, paddingCandidates = pads, minSlot = 48f)
        assertEquals(280f, w.sum(), 0.01f)
        assertTrue(w[3] < 86f)
    }

    @Test fun navigation_slots_never_go_below_the_touch_minimum_when_there_is_room() {
        val w = navigationSlotWidths(listOf(10f, 10f, 10f, 100f), total = 270f, paddingCandidates = pads, minSlot = 48f)
        assertTrue(w.all { it >= 48f })
        assertEquals(270f, w.sum(), 0.01f)
    }
}
