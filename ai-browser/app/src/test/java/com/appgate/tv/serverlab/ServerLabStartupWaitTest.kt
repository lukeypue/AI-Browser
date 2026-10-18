package com.appgate.tv.serverlab

import org.junit.Assert.*
import org.junit.Test

class ServerLabStartupWaitTest {
    private class Clock {
        var now = 0L
        val sleeps = mutableListOf<Long>()
        fun sleep(ms: Long) { sleeps += ms; now += ms }
    }

    @Test fun immediateBoundAndReadyMakesOneRequestWithoutSleeping() {
        val clock = Clock(); val stages = mutableListOf<ServerLabStartupWait.Stage>()
        val wait = ServerLabStartupWait({ clock.now }, clock::sleep, stages::add)
        var requests = 0; var awaits = 0
        assertTrue(wait.awaitBinding({ requests++; true }, { awaits++; true }))
        assertTrue(wait.awaitEngineReady { true })
        assertEquals(1, requests); assertEquals(1, awaits); assertTrue(clock.sleeps.isEmpty())
        assertEquals(ServerLabStartupWait.BindOutcome.CONNECTED, wait.bindOutcome)
        assertEquals(0L, wait.startupWaitMs); assertFalse(wait.legacyBind30sExceeded)
        assertEquals(listOf(ServerLabStartupWait.Stage.BIND_REQUESTED, ServerLabStartupWait.Stage.WAIT_BOUND,
            ServerLabStartupWait.Stage.ENGINE_READY), stages)
    }

    @Test fun rejectedBindingNeverWaitsOrRetries() {
        val clock = Clock(); val stages = mutableListOf<ServerLabStartupWait.Stage>()
        val wait = ServerLabStartupWait({ clock.now }, clock::sleep, stages::add)
        var requests = 0
        assertFalse(wait.awaitBinding({ requests++; false }, { fail("rejected binding must not await"); true }))
        assertEquals(1, requests); assertTrue(clock.sleeps.isEmpty())
        assertFalse(wait.bindAccepted); assertEquals(ServerLabStartupWait.BindOutcome.REJECTED, wait.bindOutcome)
        assertEquals(ServerLabStartupWait.Stage.BIND_REJECTED, stages.last())
    }

    @Test fun interruptedBindingRetainsClosedOutcomeAndMeasuredElapsed() {
        val clock = Clock(); val wait = ServerLabStartupWait({ clock.now }, clock::sleep)
        try {
            wait.awaitBinding({ true }, { clock.now = 700L; throw InterruptedException() })
            fail("interrupted wait must remain interrupted")
        } catch (_: InterruptedException) { }
        assertEquals(ServerLabStartupWait.BindOutcome.INTERRUPTED, wait.bindOutcome)
        assertEquals(700L, wait.bindWaitMs); assertEquals(700L, wait.startupWaitMs)
        assertTrue(clock.sleeps.isEmpty())
    }

    private fun connectAt(clock: Clock, at: Long, budget: Long): Boolean = if (at - clock.now <= budget) {
        clock.now = maxOf(clock.now, at); true
    } else {
        clock.now += budget; false
    }

    @Test fun fortyFiveSecondBindingAndSeventyFiveSecondEngineShareTheOriginalTotalBudget() {
        val clock = Clock(); val wait = ServerLabStartupWait({ clock.now }, clock::sleep)
        var requests = 0; var awaits = 0
        assertTrue("unused engine time must be available while binding", wait.awaitBinding({ requests++; true }, {
            awaits++; connectAt(clock, 45_000L, it)
        }))
        assertTrue(wait.awaitEngineReady { clock.now >= 120_000L })
        assertEquals(1, requests); assertEquals(1, awaits)
        assertEquals(45_000L, wait.bindWaitMs); assertEquals(75_000L, wait.engineWaitMs)
        assertEquals(120_000L, clock.now); assertEquals(120_000L, wait.startupWaitMs)
        assertTrue(wait.legacyBind30sExceeded)
    }

    @Test fun engineCannotResetDeadlineAfterRequestAndBindingUseFortyFiveSeconds() {
        val clock = Clock(); val stages = mutableListOf<ServerLabStartupWait.Stage>()
        val wait = ServerLabStartupWait({ clock.now }, clock::sleep, stages::add)
        assertTrue(wait.awaitBinding({ clock.now = 20_000L; true }, { connectAt(clock, 45_000L, it) }))
        assertFalse(wait.awaitEngineReady { false })
        assertEquals("the ninety-second phase allowance must not reset the total", 120_000L, clock.now)
        assertEquals(25_000L, wait.bindWaitMs); assertEquals(75_000L, wait.engineWaitMs)
        assertFalse(wait.legacyBind30sExceeded)
        assertEquals(ServerLabStartupWait.Stage.ENGINE_READY_TIMEOUT, stages.last())
    }

    @Test fun exhaustedRequestDeadlineDoesNotCallAwaitWithZeroOrRetryTheRequest() {
        val clock = Clock(); val stages = mutableListOf<ServerLabStartupWait.Stage>()
        val wait = ServerLabStartupWait({ clock.now }, clock::sleep, stages::add)
        var requests = 0; var awaits = 0
        assertFalse(wait.awaitBinding({ requests++; clock.now = 120_000L; true }, { awaits++; false }))
        assertEquals(1, requests); assertEquals("zero-budget await must not be invoked", 0, awaits)
        assertEquals(ServerLabStartupWait.BindOutcome.TIMED_OUT, wait.bindOutcome)
        assertEquals(ServerLabStartupWait.Stage.BIND_WAIT_TIMEOUT, stages.last())
        assertEquals(0L, wait.bindWaitMs); assertTrue(clock.sleeps.isEmpty())
    }

    @Test fun missingCallbackExhaustsOneTotalDeadlineWithoutExtraAttempts() {
        val clock = Clock(); val wait = ServerLabStartupWait({ clock.now }, clock::sleep)
        var requests = 0; var awaits = 0
        assertFalse(wait.awaitBinding({ requests++; true }, { awaits++; clock.now += it; false }))
        assertEquals(1, requests); assertEquals(1, awaits)
        assertEquals(120_000L, clock.now); assertEquals(120_000L, wait.bindWaitMs)
        assertEquals(ServerLabStartupWait.BindOutcome.TIMED_OUT, wait.bindOutcome)
        assertTrue(clock.sleeps.isEmpty())
    }

    @Test fun engineReadinessJustBeyondTotalDeadlineRemainsRejected() {
        val clock = Clock(); val wait = ServerLabStartupWait({ clock.now }, clock::sleep)
        assertTrue(wait.awaitBinding({ clock.now = 20_000L; true }, { true }))
        assertFalse(wait.awaitEngineReady { clock.now >= 120_001L })
        assertEquals(120_000L, clock.now)
        assertEquals(100_000L, wait.engineWaitMs)
        assertTrue(clock.sleeps.all { it in 1L..100L })
    }
}
