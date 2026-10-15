package com.appgate.brain.lab

/** Additional frozen developer-owned stress fixtures; never replace the legacy exams. */
object ChallengeCity {
 fun cases(): List<Scenario> = (0..23).flatMap { district -> (1..9).flatMap { level ->
  SimulationPagination.entries.map { pagination ->
   val group=district%8
   val fault=when(group) {
    2->SimulationFault.STALE_ONCE
    3->SimulationFault.LOADING_ONCE
    4->SimulationFault.NOOP_SEARCH
    5->SimulationFault.AUTH
    6->SimulationFault.CHALLENGE
    7->when(level%4){1->SimulationFault.BLANK_ALWAYS;2->SimulationFault.ERROR_ALWAYS;3->SimulationFault.LOADING_ALWAYS;else->SimulationFault.NONE}
    else->SimulationFault.NONE
   }
   Scenario(100000+district*1000+level*10+pagination.ordinal,level,host="challenge-exam-$district.sim.invalid",
    twoStepSearch=district%2==0,missingMileage=(district+level)%3==0,missingRareEvidence=(district+level)%4==0,
    fault=fault,emptyCatalog=group==7 && level%4==0,catalogSize=listOf(64,96,128)[district%3],
    pageSize=listOf(1,2,4,8)[district%4],pagination=pagination,queryVariant=(district/4)%4)
  }
 } }
}
