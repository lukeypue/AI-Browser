package com.appgate.brain.memory

import com.appgate.brain.json.Json
import com.appgate.brain.model.BetaStat
import com.appgate.brain.model.Binding
import com.appgate.brain.model.FeatureVec
import com.appgate.brain.model.PageType
import com.appgate.brain.model.Role
import com.appgate.brain.model.SiteModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MemoryTest {
    private val day = 86_400_000L

    @Test fun rememberedListingControlsKeepStructureWithoutListingNames() {
        val storage = InMemoryStorage()
        val binding = Binding("fake.market", PageType.RESULTS, Role.RESULT_ITEM, null, "semantic:page",
            FeatureVec(mapOf("tag:a" to 1.0, "w:private-title" to 1.0, "c:private-class" to 0.5)), listOf("Private listing title"), BetaStat(), 1L)
        val legacy = SiteModel(binding.host).apply { bindings[binding.key] = binding }
        storage.write("site/${binding.host}", legacy.toJson().toString())
        val memory = Memory(storage) { 1000L }
        assertFalse("old listing names are removed on load", memory.site(binding.host).toJson().toString().contains("Private listing"))
        memory.recordBinding(binding.host, binding, true)
        val remembered = Memory(storage).site(binding.host).binding(PageType.RESULTS, Role.RESULT_ITEM, null)!!
        assertTrue(remembered.nameHints.isEmpty())
        assertEquals(setOf("tag:a"), remembered.features.values.keys)
        assertEquals(1.0, remembered.stats.successes, 0.0)
    }

    @Test
    fun betaStatsDecayAndCalibrate() {
        var s = BetaStat()
        assertEquals(0.5, s.p, 1e-9)
        repeat(8) { s = s.record(true, 1000L + it) }
        assertTrue(s.p > 0.85)
        val later = s.decayed(1000L + 60 * day)
        assertTrue("decay shrinks evidence", later.n < s.n)
        assertTrue(later.p > 0.7)
        val failed = later.record(false, 1000L + 60 * day + 1)
        assertTrue(failed.p < later.p)
    }

    @Test
    fun jsonRoundTripOfSiteModelAndSkills() {
        val m = SiteModel("cars.ksl.com")
        m.recordPage(PageType.RESULTS, 5L)
        m.recordEdge(PageType.RESULTS, Role.PAGE_NEXT, null, PageType.RESULTS, true, 5L)
        m.facetVocabulary["price to"] = "price"
        val b = Binding("cars.ksl.com", PageType.RESULTS, Role.FACET, "price_max", "v1", FeatureVec(mapOf("tag:select" to 1.0)), listOf("Price to"), BetaStat(3.0, 1.0, 5L), 5L)
        m.bindings[b.key] = b
        m.recordFailure(PageType.RESULTS, Role.SORT, "sort", "unverified", 5L)
        val back = SiteModel.fromJson(Json.parseObject(m.toJson().toString()))
        assertEquals(1, back.pageTypesSeen[PageType.RESULTS])
        assertEquals(1, back.edges.values.first().verified)
        assertEquals("price", back.facetVocabulary["price to"])
        assertEquals(3.0, back.bindings[b.key]!!.stats.successes, 1e-9)
        assertNotNull(back.binding(PageType.RESULTS, Role.FACET, "price_max"))
        assertEquals(1, back.failures.values.first().count)
    }

    @Test
    fun consolidationRetiresShadowsAndQuarantines() {
        val memory = Memory(InMemoryStorage()) { 100 * day }
        val site = memory.site("x.test")
        site.siteVersion = "v2"
        site.bindings["bad"] = Binding("x.test", PageType.RESULTS, Role.SORT, null, "v2", FeatureVec.EMPTY, emptyList(), BetaStat(1.0, 6.0, 99 * day), 99 * day)
        site.bindings["old"] = Binding("x.test", PageType.RESULTS, Role.FACET, "price_max", "v1", FeatureVec.EMPTY, emptyList(), BetaStat(2.0, 2.0, 99 * day), 99 * day)
        site.bindings["good"] = Binding("x.test", PageType.RESULTS, Role.SEARCH_BOX, null, "v2", FeatureVec.EMPTY, emptyList(), BetaStat(9.0, 0.0, 99 * day), 99 * day)
        site.bindings["stale"] = Binding("x.test", PageType.RESULTS, Role.LOAD_MORE, null, "v2", FeatureVec.EMPTY, emptyList(), BetaStat(4.0, 0.0, 10 * day), 10 * day)
        memory.saveSite(site)
        val report = Consolidation(memory).run("x.test")
        val after = memory.site("x.test")
        assertNull("p<0.3 after 5 trials is retired", after.bindings["bad"])
        assertNull("unseen for 60 days is retired", after.bindings["stale"])
        assertTrue("other version is shadowed", after.bindings["old"]!!.shadowed)
        assertFalse(after.bindings["good"]!!.shadowed)
        assertEquals(2, report.bindingsRetired)
        assertFalse(report.quarantined)

        // Poisoned verifier: bad calibration quarantines the host.
        repeat(40) { after.recordCalibration(0.95, false) }
        memory.saveSite(after)
        val r2 = Consolidation(memory).run("x.test")
        assertTrue(r2.quarantined)
        assertTrue(memory.site("x.test").bindings.values.all { it.shadowed })
    }

    @Test
    fun fileStorageIsAtomicAndRoundTrips() {
        val dir = File(System.getProperty("java.io.tmpdir"), "brain-test-" + System.nanoTime())
        val storage = FileBrainStorage(dir)
        storage.write("site/cars.ksl.com", "{\"host\":\"cars.ksl.com\"}")
        storage.write("ledger/abc", "{}")
        assertEquals("{\"host\":\"cars.ksl.com\"}", storage.read("site/cars.ksl.com"))
        assertEquals(listOf("site/cars.ksl.com"), storage.keys("site/"))
        storage.delete("ledger/abc")
        assertNull(storage.read("ledger/abc"))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        dir.deleteRecursively()
    }

    @Test
    fun skillLibraryMergesBuiltinsWithPersistedStats() {
        val storage = InMemoryStorage()
        val memory = Memory(storage) { 1000L }
        memory.skills.recordOutcome("search", "x.test", true)
        memory.skills.recordOutcome("search", "x.test", true)
        val reloaded = Memory(storage)
        val s = reloaded.skills.get("search")
        assertNotNull(s)
        assertEquals(2.0, s!!.stat("x.test").successes, 1e-9)
        assertEquals(com.appgate.brain.skills.BuiltinSkills.search.body.size, s.body.size)
        assertTrue(reloaded.skills.retrieve("apply a price filter").any { it.id == "constrain_numeric" })
    }

    @Test
    fun episodesAreBoundedAndContentFree() {
        val memory = Memory(InMemoryStorage())
        repeat(Memory.MAX_EPISODES_PER_HOST + 20) { i ->
            memory.recordEpisode(Episode(i.toLong(), "x.test", PageType.RESULTS, Role.PAGE_NEXT, null, "CLICK", com.appgate.brain.model.VerifyStatus.VERIFIED, 0.7, PageType.RESULTS, 100, "engine"))
        }
        assertEquals(Memory.MAX_EPISODES_PER_HOST, memory.episodes("x.test").size)
    }
}
