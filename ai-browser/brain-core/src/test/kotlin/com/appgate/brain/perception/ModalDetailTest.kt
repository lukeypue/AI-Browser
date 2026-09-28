package com.appgate.brain.perception

import com.appgate.brain.model.PageType
import com.appgate.brain.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Marketplaces open item details in a modal over the results grid; that must read as a DETAIL page. */
class ModalDetailTest {
    @Test
    fun itemModalOverResultsIsADetailPage() {
        val sps = SpsParser().parse(com.appgate.brain.test.Fixtures.observation("modal_detail_page"))
        assertTrue(sps.dialogOpen)
        assertEquals(PageType.DETAIL, sps.pageType)
        assertTrue(sps.detailText.contains("3.73 rear gears"))
        assertFalse(sps.detailText.contains("801-555"))
        assertTrue(sps.byRole(Role.MESSAGE_SELLER).isNotEmpty())
        assertTrue(sps.byRole(Role.CLOSE).isNotEmpty())
        assertTrue(sps.has(Role.EXPAND_TEXT))
    }
}
