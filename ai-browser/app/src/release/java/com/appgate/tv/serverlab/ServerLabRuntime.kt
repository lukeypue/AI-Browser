package com.appgate.tv.serverlab
import com.appgate.brain.json.JsonObject
import com.appgate.brain.profile.SiteProfile
/** Release cannot install a lab observer or select synthetic profiles. */
object ServerLabRuntime {
    val active: Boolean get() = false
    fun profiles(default: List<SiteProfile>): List<SiteProfile> = default
    fun observe(report: JsonObject) {}
}
