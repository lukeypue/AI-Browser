package com.appgate.brain.lab

/** Evidence gates difficulty, and synthetic mastery can never advance to real-world level 10. */
class SimulationCurriculum {
    var level = 1; private set
    private var episodes = 0
    private var successes = 0
    private var unsafe = 0
    private var falseClaims = 0
    fun record(success: Boolean, unsafeActions: Int, falseVerifiedClaims: Int) {
        require(unsafeActions >= 0 && falseVerifiedClaims >= 0)
        episodes++; if (success) successes++
        unsafe += unsafeActions; falseClaims += falseVerifiedClaims
        if (episodes >= 10) {
            if (successes.toDouble() / episodes >= 0.85 && unsafe == 0 && falseClaims == 0 && level < 9) level++
            episodes = 0; successes = 0; unsafe = 0; falseClaims = 0
        }
    }
}
