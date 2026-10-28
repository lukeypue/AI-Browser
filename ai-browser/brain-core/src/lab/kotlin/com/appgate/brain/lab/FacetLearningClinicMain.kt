package com.appgate.brain.lab

import java.io.File
import java.nio.file.Files

/** An additional City quality gate; legacy exam manifests and export stay unchanged. */
fun main(args:Array<String>) {
    val root=Files.createTempDirectory("synthetic-facet-acquisition-").toFile()
    try {
        val report=FacetLearningClinic().experiment(root)
        val rows=report.optArray("cases")!!.objects()
        require(rows.size==5 && rows.all { it.optInt("teacher_calls")==0 })
        require(rows.take(2).all { row -> row.optBoolean("failure_recorded") && row.optInt("acquisition_first_attempts")>0 &&
            row.optInt("replay_first_attempts")==0 && row.optBoolean("physical_constraint_applied") &&
            row.optBoolean("verified_lesson") && row.optString("replay_status")=="DONE" && row.optBoolean("replay_verified") && row.optBoolean("hold_retained_after_reload") &&
            row.optBoolean("lesson_retained_after_reload") && row.optBoolean("lesson_retained_before_replay") && row.optInt("new_lessons_total")==1 &&
            row.optInt("ablated_first_attempts")>0 && row.optBoolean("ablated_hold_restored") && row.optBoolean("ablation_source_unchanged") })
        require(rows.drop(2).all { !it.optBoolean("verified_lesson") && it.optInt("new_lessons_total")==0 })
        require(!rows[2].optBoolean("physical_constraint_applied") && rows[2].optInt("acquisition_second_attempts")>0 && rows[2].optBoolean("second_hold_retained") && rows[2].optInt("replay_actions")==0)
        require(rows[3].optBoolean("acquisition_physical_constraint_applied") && !rows[3].optBoolean("replay_verified"))
        require(!rows.last().optBoolean("physical_constraint_applied"))
        require(rows.last().optInt("actions")==0)
        val target=File(args.firstOrNull()?:"build/reports/brain/facet-clinic.json")
        target.parentFile.mkdirs();target.writeText(report.toString())
        println("Synthetic direct-facet acquisition gate passed: 2 newly verified lessons, durable reload, failure ablation, no-op, side-effect and human negatives; zero teacher.")
    } finally { root.deleteRecursively() }
}

