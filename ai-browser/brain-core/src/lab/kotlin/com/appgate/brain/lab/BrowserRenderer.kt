package com.appgate.brain.lab

import com.appgate.brain.engine.*
import com.appgate.brain.json.*
import java.io.File

class BrowserRenderer(node: String, script: File) : Renderer, AutoCloseable {
    private val bridge=JsonLineBridge(listOf(node,script.absolutePath),script.parentFile)
    private var url=""
    private var documentId=""
    private val shutdownHook=Thread { bridge.close() }
    init { Runtime.getRuntime().addShutdownHook(shutdownHook) }
    fun reset(scenario: JsonObject) { val result=bridge.request("reset",scenario,30000);url=result.optString("url");documentId="" }
    fun truth(): JsonObject=bridge.request("truth",JsonObject(),5000)
    override fun observe(timeoutMs: Long): String {
        val result=bridge.request("observe",JsonObject(),timeoutMs)
        url=result.optString("url");documentId=result.optString("documentId")
        return result.toString()
    }
    private fun action(op:String,payload:JsonObject,timeoutMs:Long):RendererResult {
        val result=bridge.request(op,payload,timeoutMs)
        result.optStringOrNull("url")?.let { url=it }
        return RendererResult(result.optBoolean("ok"),result.optString("detail"),result)
    }
    override fun act(command:JsonObject,timeoutMs:Long)=action("act",Json.parseObject(command.toString()).put("expectedDocumentId",documentId).put("expectedUrl",url),timeoutMs)
    override fun navigate(url:String,timeoutMs:Long)=action("navigate",JsonObject().put("url",url),timeoutMs)
    override fun back(timeoutMs:Long)=action("back",JsonObject(),timeoutMs)
    override fun waitSettle(timeoutMs:Long)=action("settle",JsonObject(),timeoutMs)
    override fun currentUrl()=url
    override fun setNetworkMode(mode:String,allowlist:List<String>,commitEndpoints:List<String>)=action("network",JsonObject(),5000)
    override fun recover()=action("recover",JsonObject(),5000)
    override fun isAlive()=bridge.isAlive()
    override fun close(){
        if(bridge.isAlive())runCatching { bridge.request("close",JsonObject(),5000) }
        bridge.close();runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }
}
