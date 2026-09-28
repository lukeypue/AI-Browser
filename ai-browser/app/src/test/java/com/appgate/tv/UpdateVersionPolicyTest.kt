package com.appgate.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionPolicyTest {
    @Test fun newerBuildIsAvailable() {
        assertTrue(UpdateVersionPolicy.isUpdateAvailable(20, 21))
    }

    @Test fun sameBuildIsAlreadyLatest() {
        assertFalse(UpdateVersionPolicy.isUpdateAvailable(21, 21))
    }

    @Test fun olderPublishedBuildIsNotOffered() {
        assertFalse(UpdateVersionPolicy.isUpdateAvailable(22, 21))
    }
}
