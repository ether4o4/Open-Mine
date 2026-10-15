package com.openmine.sandbox

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable

class RuntimeBridgeServer(context: Context, private val runtime: OpenMineRuntime, private val stop: () -> Unit) : Closeable {
    private val settings = RuntimeBridgeSettings(context)
    private val server = LocalCommandServer(8766, settings::authenticates, ::dispatch)
    fun start() = server.start()
    override fun close() = server.close()

    private fun dispatch(request: JSONObject): LocalCommandServer.Reply {
        var afterSend: (() -> Unit)? = null
        val body = try {
            when (request.optString("action")) {
                "status" -> runtime.controlStatus().put("ok", true)
                "shell" -> {
                    val command = request.getString("command")
                    val timeout = request.optLong("timeout_seconds", 30)
                    require(timeout in 1..1800) { "Timeout must be between 1 and 1800 seconds" }
                    val result = runBlocking { runtime.controlCommand(command, timeout) }
                    JSONObject().put("ok", true).put("result", JSONObject(result))
                }
                "process_start" -> {
                    val process = runBlocking { runtime.startManagedProcess(request.getString("command")) }
                    JSONObject().put("ok", true).put("process", process.toJson())
                }
                "process_stop" -> {
                    check(runtime.stopManagedProcess(request.getString("id"))) { "Unknown process" }
                    JSONObject().put("ok", true)
                }
                "process_logs" -> {
                    val process=runtime.managedProcess(request.getString("id")) ?: error("Unknown process")
                    JSONObject().put("ok",true).put("process",process.toJson())
                }
                "stop" -> {
                    afterSend = stop
                    JSONObject().put("ok", true).put("status", "stopping")
                }
                else -> JSONObject().put("ok", false).put("error", "Unsupported action")
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "Runtime operation failed")
        }
        return LocalCommandServer.Reply(body, afterSend)
    }
}

internal fun RuntimeProcessManager.ProcessInfo.toJson(includeOutput:Boolean=true): JSONObject = JSONObject()
    .put("id", id).put("command", if(includeOutput)command else command.take(512)).put("state", state)
    .put("exit_code", exitCode ?: JSONObject.NULL).also { if(includeOutput)it.put("stdout",stdout).put("stderr",stderr) }

internal fun List<RuntimeProcessManager.ProcessInfo>.toJson(includeOutput:Boolean=true): JSONArray = JSONArray().also { array -> forEach { array.put(it.toJson(includeOutput)) } }
