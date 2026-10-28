package com.appgate.brain.lab

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class FacetLearningClinicTest {
    @Test fun actualFailuresAndNewLessonsSurviveDurableReloadWithoutTeacher() {
        val root=Files.createTempDirectory("synthetic-facet-clinic-").toFile()
        try {
            val report=FacetLearningClinic().experiment(root)
            assertEquals("JVM_SYNTHETIC_ENGINE",report.optString("source"))
            assertFalse(report.optBoolean("real_world_validated"))
            val rows=report.optArray("cases")!!.objects()
            assertEquals(listOf("choice","numeric","no-op","side-effect","human"),rows.map { it.optString("case_id") })
            for(row in rows.take(2)) {
                assertTrue(row.toString(),row.optBoolean("failure_recorded"))
                assertTrue(row.toString(),row.optInt("acquisition_first_attempts")>0)
                assertEquals(row.toString(),0,row.optInt("replay_first_attempts"))
                assertTrue(row.toString(),row.optBoolean("physical_constraint_applied"))
                assertTrue(row.toString(),row.optBoolean("verified_lesson"))
                assertEquals(row.toString(),"DONE",row.optString("replay_status"))
                assertTrue(row.toString(),row.optBoolean("replay_verified"))
                assertTrue(row.toString(),row.optBoolean("hold_retained_after_reload"))
                assertTrue(row.toString(),row.optBoolean("lesson_retained_after_reload"))
                assertTrue(row.toString(),row.optBoolean("lesson_retained_before_replay"))
                assertEquals(row.toString(),1,row.optInt("new_lessons_total"))
                assertTrue(row.toString(),row.optInt("ablated_first_attempts")>0)
                assertTrue(row.toString(),row.optBoolean("ablated_hold_restored"))
                assertTrue(row.toString(),row.optBoolean("ablation_source_unchanged"))
            }
            for(row in rows)assertEquals(0,row.optInt("teacher_calls"))
            for(row in rows.drop(2)) {
                assertFalse(row.toString(),row.optBoolean("verified_lesson"))
                assertEquals(0,row.optInt("new_lessons_total"))
            }
            assertFalse(rows[2].optBoolean("physical_constraint_applied"))
            assertTrue(rows[2].optInt("acquisition_second_attempts")>0)
            assertTrue(rows[2].optBoolean("second_hold_retained"))
            assertEquals(0,rows[2].optInt("replay_actions"))
            assertTrue(rows[3].optBoolean("acquisition_physical_constraint_applied"))
            assertFalse(rows[3].optBoolean("replay_verified"))
            assertFalse(rows.last().optBoolean("physical_constraint_applied"))
            assertEquals(0,rows.last().optInt("actions"))
        } finally { root.deleteRecursively() }
    }
}

