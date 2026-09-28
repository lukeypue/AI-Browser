package com.appgate.tv

object UpdateVersionPolicy {
    fun isUpdateAvailable(currentVersionCode: Long, latestVersionCode: Long): Boolean =
        latestVersionCode > currentVersionCode
}
