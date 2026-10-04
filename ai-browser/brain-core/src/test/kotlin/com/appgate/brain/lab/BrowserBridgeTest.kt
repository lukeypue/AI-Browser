package com.appgate.brain.lab

import com.appgate.brain.engine.RendererTimeout
import com.appgate.brain.json.JsonObject
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BrowserBridgeTest {
    private fun script(code: String) = File.createTempFile("brain-bridge-", ".cjs").apply { writeText(code); deleteOnExit() }
    private fun node() = System.getenv("BRAIN_NODE") ?: "node"

    @Test fun newlineProtocolPreservesValuesAndOwnsItsChild() {
        val file = script("require('readline').createInterface({input:process.stdin}).on('line',s=>{const q=JSON.parse(s);console.log(JSON.stringify({id:q.id,ok:true,result:q.payload}));});")
        JsonLineBridge(listOf(node(), file.path), file.parentFile).use { bridge ->
            val value = bridge.request("echo", JsonObject().put("text", "path with spaces\nsecond line"), 2000)
            assertEquals("path with spaces\nsecond line", value.optString("text"))
            assertTrue(bridge.isAlive())
        }
    }

    @Test fun unresponsiveRendererIsKilledWithinDeadline() {
        val file = script("setInterval(()=>{},1000);")
        val bridge = JsonLineBridge(listOf(node(), file.path), file.parentFile)
        val began = System.currentTimeMillis()
        try { bridge.request("observe", JsonObject(), 150); fail("expected a bounded timeout") }
        catch (_: RendererTimeout) { assertTrue(System.currentTimeMillis() - began < 2000) }
        finally { bridge.close() }
        assertFalse(bridge.isAlive())
    }

    @Test fun mismatchedResponseCannotBeMistakenForAnotherRequest() {
        val file = script("require('readline').createInterface({input:process.stdin}).on('line',s=>console.log(JSON.stringify({id:999,ok:true,result:{}})));")
        JsonLineBridge(listOf(node(), file.path), file.parentFile).use { bridge ->
            assertThrows(IllegalStateException::class.java) { bridge.request("observe", JsonObject(), 2000) }
        }
    }
}
