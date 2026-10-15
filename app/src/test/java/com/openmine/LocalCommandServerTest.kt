package com.openmine

import com.openmine.sandbox.LocalCommandServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LocalCommandServerTest {
    private val token = "a".repeat(64)
    private fun request(port: Int, input: String): JSONObject = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 3000
        socket.getOutputStream().write((input + "\n").toByteArray())
        JSONObject(socket.getInputStream().bufferedReader().readLine())
    }

    @Test fun authenticatesBeforeDispatchAndDoesNotEchoSecrets() {
        val calls = AtomicInteger()
        LocalCommandServer(0, { it == token }) { request ->
            calls.incrementAndGet()
            LocalCommandServer.Reply(JSONObject().put("ok", true).put("action", request.getString("action")))
        }.use { server ->
            server.start()
            val rejected = request(server.localPort, JSONObject().put("token", "wrong-secret").put("action", "shell").toString())
            assertFalse(rejected.getBoolean("ok"))
            assertFalse(rejected.toString().contains("wrong-secret"))
            assertEquals(0, calls.get())
            val accepted = request(server.localPort, JSONObject().put("token", token).put("action", "status").toString())
            assertTrue(accepted.getBoolean("ok"))
            assertEquals("status", accepted.getString("action"))
            assertEquals(1, calls.get())
        }
    }

    @Test fun rejectsOversizeAndMalformedRequestsWithoutDispatch() {
        val calls = AtomicInteger()
        LocalCommandServer(0, { true }) {
            calls.incrementAndGet(); LocalCommandServer.Reply(JSONObject().put("ok", true))
        }.use { server ->
            server.start()
            assertFalse(request(server.localPort, "not-json").getBoolean("ok"))
            assertFalse(request(server.localPort, "x".repeat(16385)).getBoolean("ok"))
            assertEquals(0, calls.get())
        }
    }

    @Test fun stopAcknowledgementIsSentBeforeShutdownCallback() {
        val stopped = CountDownLatch(1)
        LocalCommandServer(0, { it == token }) {
            LocalCommandServer.Reply(JSONObject().put("ok", true), { stopped.countDown() })
        }.use { server ->
            server.start()
            assertTrue(request(server.localPort, JSONObject().put("token", token).toString()).getBoolean("ok"))
            assertTrue(stopped.await(1, TimeUnit.SECONDS))
        }
    }

    @Test fun revokedCredentialCannotDispatchOnExistingConnection() {
        var enabled = true
        val calls = AtomicInteger()
        LocalCommandServer(0, { enabled && it == token }) {
            calls.incrementAndGet(); LocalCommandServer.Reply(JSONObject().put("ok", true))
        }.use { server ->
            server.start()
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 3000
                enabled = false
                socket.getOutputStream().write((JSONObject().put("token", token).toString() + "\n").toByteArray())
                assertFalse(JSONObject(socket.getInputStream().bufferedReader().readLine()).getBoolean("ok"))
                assertEquals(0, calls.get())
            }
        }
    }
}
