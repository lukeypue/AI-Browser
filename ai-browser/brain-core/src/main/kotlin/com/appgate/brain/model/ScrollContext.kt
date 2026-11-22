package com.appgate.brain.model

import com.appgate.brain.json.*

enum class ScrollTarget { ROOT, RESULTS_CONTAINER, UNKNOWN }

/** Ephemeral renderer geometry. The opaque document/surface id is never persisted or sent to a model. */
data class ScrollContext(val kind: ScrollTarget, val id: String, val y: Int, val height: Int, val extent: Int) {
    val valid: Boolean get() = kind != ScrollTarget.UNKNOWN && idPattern.matches(id) &&
        (kind == ScrollTarget.ROOT && id.endsWith(":root") || kind == ScrollTarget.RESULTS_CONTAINER && ":scroll-" in id) &&
        y in 0..LIMIT && height in 1..LIMIT && extent in height..LIMIT && y <= extent-height
    fun publicJson(): JsonObject = JsonObject().put("kind", if(valid)kind.name else "UNKNOWN").also {
        if(valid)it.put("y",y).put("height",height).put("extent",extent)
    }
    companion object {
        private const val LIMIT=10_000_000
        private val idPattern=Regex("[A-Za-z0-9-]{1,64}:(root|scroll-[1-9][0-9]{0,6})")
        val INVALID=ScrollContext(ScrollTarget.UNKNOWN,"",0,0,0)
        fun parse(raw: JsonValue?, rootY:Int, rootHeight:Int, rootExtent:Int):ScrollContext {
            val row=raw as? JsonObject ?: return INVALID
            if(row.keys!=setOf("kind","id","y","height","extent"))return INVALID
            fun metric(key:String):Int?=(row[key] as? JsonNumber)?.value?.takeIf{it.isFinite() && it%1.0==0.0 && it in 0.0..LIMIT.toDouble()}?.toInt()
            val kind=ScrollTarget.values().firstOrNull{it.name==(row["kind"] as? JsonString)?.value} ?: return INVALID
            val id=(row["id"] as? JsonString)?.value ?: return INVALID
            val result=ScrollContext(kind,id,metric("y")?:return INVALID,metric("height")?:return INVALID,metric("extent")?:return INVALID)
            if(!result.valid)return INVALID
            if(kind==ScrollTarget.ROOT && (result.y!=rootY || result.height!=rootHeight || result.extent!=rootExtent.coerceAtLeast(rootHeight)))return INVALID
            return result
        }
    }
}
