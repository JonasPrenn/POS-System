package com.example.vereins_kassensystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Die Mindestversion vergleicht wie AppVersion.CODE: Beta auf ihrer Nummer, Freigabe darüber. */
class MinimumVersionTest {

    @Test
    fun `codes follow the app's version code`() {
        assertEquals(103_000, MinimumVersion.codeOf("1.3.0"))
        assertEquals(102_010, MinimumVersion.codeOf(" 1.2.1 "))
        assertEquals(0, MinimumVersion.codeOf(MinimumVersion.NONE))
        for (bad in listOf("1.3", "1.100.0", "1.3.0-beta", "a.b.c", "")) assertNull(MinimumVersion.codeOf(bad), bad)
    }

    @Test
    fun `a minimum blocks older apps only`() {
        val beta = 103_000          // 1.3.0 Beta
        val release = 103_009       // 1.3.0
        assertFalse(MinimumVersion.blocks("1.3.0", beta), "die Beta einer Mindestversion darf")
        assertFalse(MinimumVersion.blocks("1.3.0", release))
        assertTrue(MinimumVersion.blocks("1.3.1", release))
        assertTrue(MinimumVersion.blocks("1.3.0", 102_019), "1.2.1 ist darunter")
        assertFalse(MinimumVersion.blocks(MinimumVersion.NONE, 1))
        assertFalse(MinimumVersion.blocks("kaputt", 1), "was keine Version ist, sperrt nicht")
        assertFalse(MinimumVersion.blocks(MinimumVersion.NONE), "diese App selbst")
    }
}
