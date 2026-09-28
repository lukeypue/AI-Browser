package com.appgate.brain.engine

import org.junit.Assert.*
import org.junit.Test

class BridgeRequestsTest {
    @Test fun oldDocumentCannotCompleteOrCancelNewDocumentRequest() {
        val requests = BridgeRequests<Any, String>()
        val old = Any(); val current = Any()
        requests.put(1, old, "old")
        requests.put(2, current, "new")
        assertNull(requests.take(2, old))
        assertEquals(listOf("old"), requests.removeOwner(old))
        assertEquals("new", requests.take(2, current))
        assertTrue(requests.drain().isEmpty())
    }
    @Test fun cancelledRequestCannotReceiveLateReply() {
        val requests = BridgeRequests<Any, String>(); val port = Any()
        requests.put(1, port, "value")
        requests.remove(1)
        assertNull(requests.take(1, port))
    }
}
