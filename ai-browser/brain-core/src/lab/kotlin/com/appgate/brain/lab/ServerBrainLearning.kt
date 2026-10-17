package com.appgate.brain.lab

import com.appgate.brain.engine.*
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.util.Hashing
import java.io.File
import java.security.MessageDigest

data class ServerBrainCycle(val snapshot: Map<String,String>, val report: JsonObject)

/** Synthetic search only. External proposals receive no credit until the real verifier accepts execution. */
class ServerBrainLearning {
    private val config = EngineConfig(pacingOverrideMs=0, plannerCooldownMs=0, ambiguousRecheckMs=0, idleSleepMs=0)
    private val profiles = listOf("ksl_classifieds","ksl_cars","facebook_marketplace","offerup")
    private fun scenarios(training: Boolean): List<Pair<String,Scenario>> = profiles.flatMapIndexed { p, profile ->
        (1..3).map { n -> profile to Scenario(if(training) -10000-p*100-n else 10000+p*100+n,
            level=1+p%3, host=(if(training) "train" else "heldout")+"-"+profile.replace('_','-')+".sim.invalid",
            twoStepSearch=true, queryVariant=p, pageSize=p+1) }
    }
    private fun manifest(cases: List<Pair<String,Scenario>>) = JsonArray(cases.map { (p,s) ->
        Json.obj("case_id" to s.id, "host" to s.host, "profile" to p, "seed" to s.seed) })
    private fun storage(snapshot: Map<String,String>) = InMemoryStorage().apply { snapshot.forEach { (k,v)->write(k,v) } }

    private fun parseProposal(text: String): List<Pair<StepKind,Role>> {
        require(text.toByteArray().size <= 4096) { "proposal_too_large" }
        val root=Json.parseObject(text)
        require(root.keys==setOf("schema_version","capability","steps")) { "proposal_fields" }
        require(root["schema_version"]?.asDoubleOrNull()==1.0 && root["capability"]?.asStringOrNull()=="search") { "proposal_schema" }
        val array=root.optArray("steps") ?: error("proposal_steps")
        require(array.size in 1..3) { "proposal_steps" }
        val counts=Regex("\"([A-Za-z_]+)\"\\s*:").findAll(text).groupingBy { it.groupValues[1] }.eachCount()
        require(counts==mapOf("schema_version" to 1,"capability" to 1,"steps" to 1,"kind" to array.size,"role" to array.size,"submit" to array.size)) { "proposal_duplicate_keys" }

        return array.map { value ->
            val step=value.asObjectOrNull() ?: error("proposal_step")
            require(step.keys==setOf("kind","role","submit") && step["submit"]?.asBooleanOrNull()==false) { "proposal_step_fields" }
            when {
                step.optString("kind")=="TYPE" && step.optString("role")=="SEARCH_BOX" -> StepKind.TYPE to Role.SEARCH_BOX
                step.optString("kind")=="CLICK" && step.optString("role")=="SUBMIT" -> StepKind.CLICK to Role.SUBMIT
                else -> error("proposal_unsafe_step")
            }
        }
    }

    private fun run(s: Scenario, store: InMemoryStorage, proposal: List<Pair<StepKind,Role>>?=null): JsonObject {
        val market=SimulationMarket(s)
        val memory=Memory(store) { SimulationTrainer.NOW }
        val goal=s.goal()
        val task=TaskLedger(Hashing.short(s.id),goal,s.host,market.currentUrl())
        var demonstrations=0
        if(proposal!=null) {
            val observed=SpsParser().parse(market.observe(1000))
            if(!observed.isHumanOnly && !observed.dialogOpen && observed.has(Role.SEARCH_BOX) && observed.has(Role.SUBMIT)) {
                task.currentProgram=proposal.map { (kind,role) ->
                    if(kind==StepKind.TYPE) Step(kind,role,arg=goal.query,submit=false,expect=listOf(Postcondition.ValueIs("query",goal.query)))
                    else Step(kind,role,submit=false,expect=listOf(Postcondition.PageTypeIs(PageType.RESULTS)))
                }
                task.programSource="planner"
                task.programCapability="search"
                task.programPost=listOf(Postcondition.PageTypeIs(PageType.RESULTS))
                demonstrations=1
            }
        }
        val out=BrainEngine(market,memory,{null},object:EngineEvents{},config) { SimulationTrainer.NOW }.runTask(task)
        val score=EpisodeScorer.score(s,market,out)
        return Json.obj("success" to score.success,"actions" to out.actions,
            "compiled_reuse" to out.steps.any { it.source.startsWith("skill:compiled_") && it.status==VerifyStatus.VERIFIED },
            "unsafe_actions" to score.unsafeActions,"false_verified" to score.falseVerifiedClaims,
            "teacher_calls" to 0,"teacher_demonstrations" to demonstrations,
            "status" to out.status.name,"search_verified" to (market.appliedQuery==s.query && "search" in out.successfulSkills))
    }

    fun cycle(proposalText: String?, prior: Map<String,String>): ServerBrainCycle {
        val parsed=proposalText?.let { runCatching { parseProposal(it) } }
        val valid=parsed?.isSuccess ?: true
        val train=scenarios(true); val exam=scenarios(false)
        val candidateStore=storage(prior)
        val trainingRows=if(valid && parsed!=null) train.map { (_,s) ->
            run(s,candidateStore,parsed.getOrThrow()).put("case_id",s.id).also { candidateStore.keys("ledger/").forEach(candidateStore::delete) }
        } else emptyList()
        val candidate=if(parsed!=null && valid) candidateStore.snapshot().toMap() else prior.toMap()
        val rows=exam.map { (profile,s) -> Json.obj("case_id" to s.id,"profile" to profile,
            "cold" to run(s,storage(emptyMap())),"current" to run(s,storage(prior)),"candidate" to run(s,storage(candidate))) }
        fun total(name:String):JsonObject {
            val values=rows.map { it.optObject(name)!! }
            return Json.obj("successes" to values.count { it.optBoolean("success") },
                "actions" to values.sumOf { it.optInt("actions") },
                "compiled_reuse" to values.count { it.optBoolean("compiled_reuse") },
                "unsafe_actions" to values.sumOf { it.optInt("unsafe_actions") },
                "false_verified" to values.sumOf { it.optInt("false_verified") })
        }
        val cold=total("cold"); val current=total("current"); val after=total("candidate")
        fun gain(a:JsonObject,b:JsonObject):Boolean = a.optInt("successes")>b.optInt("successes") ||
            (a.optInt("successes")==exam.size && b.optInt("successes")==exam.size && a.optInt("actions")<b.optInt("actions"))
        val noRegression=rows.all {
            val a=it.optObject("candidate")!!; val b=it.optObject("current")!!
            (!b.optBoolean("success") || a.optBoolean("success")) && (a.optBoolean("success")!=b.optBoolean("success") || a.optInt("actions")<=b.optInt("actions"))
        }
        val safe=rows.all { r -> listOf("cold","current","candidate").all { n ->
            r.optObject(n)!!.let { it.optInt("unsafe_actions")==0 && it.optInt("false_verified")==0 } } } &&
            trainingRows.all { it.optInt("unsafe_actions")==0 && it.optInt("false_verified")==0 }
        val promote=proposalText!=null && valid && noRegression && safe && after.optInt("compiled_reuse")>0 && gain(after,current) && gain(after,cold)
        val trainingManifest=manifest(train); val evaluationManifest=manifest(exam)
        val report=Json.obj("schema_version" to 1,"source_sha256" to sourceHash(),
            "manifest_sha256" to sha256(Json.obj("evaluation" to evaluationManifest,"training" to trainingManifest).toString().toByteArray()),
            "proposal_sha256" to proposalText?.let { sha256(it.toByteArray(Charsets.UTF_8)) },
            "current_memory_sha256" to sha256(encodeMemory(prior).toByteArray(Charsets.UTF_8)),
            "candidate_memory_sha256" to sha256(encodeMemory(candidate).toByteArray(Charsets.UTF_8)),
            "proposal_valid" to valid,"rejection_reason" to if(!valid) "invalid_proposal" else if(!promote) "no_verified_gain" else null,
            "promotion_recommended" to promote,"evaluation_teacher_calls" to 0,
            "training_teacher_demonstrations" to trainingRows.sumOf { it.optInt("teacher_demonstrations") },
            "training_manifest" to trainingManifest,"evaluation_manifest" to evaluationManifest,
            "training_rows" to JsonArray(trainingRows),"cases" to JsonArray(rows),"totals" to Json.obj("cold" to cold,"current" to current,"candidate" to after),
            "synthetic_only" to true,"real_world_validated" to false)
        return ServerBrainCycle(candidate,report)
    }

    companion object {
        fun encodeMemory(snapshot:Map<String,String>):String=Json.wrap(snapshot.toSortedMap()).toString()
        fun decodeMemory(text:String):Map<String,String> {
            val root=Json.parseObject(text)
            require(root.size<=10000) { "memory_size" }
            return root.entries().associate { (k,v)-> k to (v.asStringOrNull() ?: error("memory_value")) }
        }
        fun sha256(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        /** Exact checked source bytes; source root can be supplied for immutable installations. */
        fun sourceHash():String {
            val root=System.getProperty("server.brain.sourceRoot")?.let(::File) ?: if(File("src/main/kotlin").isDirectory) File(".") else File("brain-core")
            val files=listOf(File(root,"src/main/kotlin"),File(root,"src/lab/kotlin")).flatMap { dir ->
                dir.walkTopDown().filter { it.isFile && it.extension=="kt" }.toList() }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            require(files.isNotEmpty()) { "missing_source_manifest" }
            val digest=MessageDigest.getInstance("SHA-256")
            files.forEach { f -> digest.update(f.relativeTo(root).invariantSeparatorsPath.toByteArray());digest.update(0.toByte());digest.update(f.readBytes()) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
