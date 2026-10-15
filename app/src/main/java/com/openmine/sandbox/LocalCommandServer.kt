package com.openmine.sandbox

import org.json.JSONObject
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Small authenticated, loopback-only, newline JSON interface; never an HTTP or LAN listener. */
class LocalCommandServer(
    private val port: Int,
    private val authenticate: (String?) -> Boolean,
    private val dispatch: (JSONObject) -> Reply,
) : Closeable {
    data class Reply(val body: JSONObject, val afterSend: (() -> Unit)? = null)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(4)) { task ->
        Thread(task, "openmine-bridge-client").apply { isDaemon = true }
    }
    @Volatile private var listener: ServerSocket? = null
    val localPort: Int get() = listener?.localPort ?: -1

    @Synchronized fun start() {
        check(listener == null) { "Bridge already started" }
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8)
        } catch (e: Exception) { server.close(); throw e }
        listener = server
        Thread({
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                clients.add(client)
                try { workers.execute { serve(client) } }
                catch (_: java.util.concurrent.RejectedExecutionException) {
                    clients.remove(client); runCatching { client.close() }
                }
            }
        }, "openmine-bridge-accept").apply { isDaemon = true; start() }
    }

    private fun serve(socket: Socket) {
        var afterSend: (() -> Unit)? = null
        try {
            socket.soTimeout = 5000
            val input = socket.getInputStream().buffered()
            val bytes = java.io.ByteArrayOutputStream()
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while (true) {
                val remaining=deadline-System.nanoTime()
                require(remaining>0) { "Request deadline exceeded" }
                socket.soTimeout=TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1,5000).toInt()
                val next = input.read()
                require(next >= 0) { "Incomplete request" }
                if (next == 10) break
                require(bytes.size() < 16384) { "Request too large" }
                bytes.write(next)
            }
            val request = JSONObject(bytes.toString("UTF-8"))
            val reply = if (!authenticate(request.optString("token", ""))) {
                Reply(JSONObject().put("ok", false).put("error", "Unauthorized or bridge disabled"))
            } else dispatch(request)
            afterSend = reply.afterSend
            socket.getOutputStream().write((reply.body.toString() + "\n").toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
        } catch (_: Exception) {
            // Do not echo request content (in particular its credential) into errors or logs.
            runCatching { socket.getOutputStream().write("{\"ok\":false,\"error\":\"Invalid or failed request\"}\n".toByteArray()) }
        } finally {
            clients.remove(socket)
            runCatching { socket.close() }
            afterSend?.invoke()
        }
    }

    @Synchronized override fun close() {
        listener?.close(); listener = null
        clients.forEach { runCatching { it.close() } }; clients.clear()
        workers.shutdownNow()
    }
}
