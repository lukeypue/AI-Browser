package com.appgate.tv

object UpdateCompatibility {
    fun sameSigner(installedSha256: String, candidateSha256: String): Boolean {
        val installed = normalize(installedSha256)
        val candidate = normalize(candidateSha256)
        return installed.isNotEmpty() && candidate.isNotEmpty() && installed == candidate
    }

    private fun normalize(value: String): String = value
        .replace(":", "")
        .trim()
        .lowercase()
}
