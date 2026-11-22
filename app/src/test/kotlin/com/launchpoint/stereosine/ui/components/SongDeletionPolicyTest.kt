package com.launchpoint.stereosine.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI-READABILITY-03D: row-level deletion keeps Track Details' eligibility: Android 11+ and a real song id. No pre-11 path. */
class SongDeletionPolicyTest {
    @Test fun android_11_and_later_with_a_real_song_id_is_eligible() {
        assertTrue(SongDeletionPolicy.isEligible(songId = 42L, sdkInt = 30))
        assertTrue(SongDeletionPolicy.isEligible(songId = 1L, sdkInt = 36))
    }

    @Test fun android_10_and_earlier_is_never_eligible() {
        assertFalse(SongDeletionPolicy.isEligible(songId = 42L, sdkInt = 29))
        assertFalse(SongDeletionPolicy.isEligible(songId = 42L, sdkInt = 26))
    }

    @Test fun the_invalid_song_id_sentinel_is_never_eligible() {
        assertFalse(SongDeletionPolicy.isEligible(songId = Long.MIN_VALUE, sdkInt = 34))
    }
}
