package dev.omalauncher.hermes

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.net.InetSocketAddress

class HermesApiTest {
    @Test fun gatewayUrlsSupportPrivateNetworksAndProfiles() {
        assertEquals("http://100.76.101.70:8642", HermesEndpoint.normalize(" http://100.76.101.70:8642/v1/ "))
        assertEquals("https://nas.example/p/personal", HermesEndpoint.normalize("https://nas.example/p/personal/"))
        for (url in listOf("http://public.example", "https://user:secret@nas.example", "https://nas.example?token=secret", "file:///tmp/key", "https://nas.example/chat", "http://100.128.1.1")) {
            assertThrows("Must reject $url", IllegalArgumentException::class.java) { HermesEndpoint.normalize(url) }
        }
    }

    @Test fun sseHandlesKeepalivesMultilineDataAndTerminalEvents() {
        val events = mutableListOf<JSONObject>()
        val stream = ": keepalive\r\n\r\nevent: message.delta\r\ndata: {\r\ndata: \"delta\": \"hello 🦉\"}\r\n\r\ndata: {\"event\":\"run.completed\",\"output\":\"hello 🦉\"}\n\ndata: [DONE]\n\n"
        HermesEvents.read(StringReader(stream).buffered(), events::add)
        assertEquals(2, events.size)
        assertEquals("message.delta", events[0].getString("event"))
        assertEquals("hello 🦉", events[0].getString("delta"))
        assertEquals("run.completed", events[1].getString("event"))
    }

    @Test fun truncatedStreamDoesNotInventCompletion() {
        val events = mutableListOf<JSONObject>()
        HermesEvents.read(StringReader("data: {\"event\":\"run.completed\"}").buffered(), events::add)
        assertTrue(events.isEmpty())
    }

    @Test fun transportSendsAuthenticationAndIdempotencyWithoutFollowingRedirects() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var forwarded = false
        var auth = ""
        var retry = ""
        var prompt = ""
        server.createContext("/v1/runs") { request ->
            auth = request.requestHeaders.getFirst("Authorization")
            retry = request.requestHeaders.getFirst("Idempotency-Key")
            prompt = JSONObject(request.requestBody.bufferedReader().readText()).getString("input")
            val reply = "{\"run_id\":\"run_test\"}".toByteArray()
            request.sendResponseHeaders(202, reply.size.toLong())
            request.responseBody.use { it.write(reply) }
        }
        server.createContext("/redirect") { request ->
            request.responseHeaders.add("Location", "/target")
            request.sendResponseHeaders(302, -1)
            request.close()
        }
        server.createContext("/target") { request -> forwarded = true; request.sendResponseHeaders(200, -1); request.close() }
        server.createContext("/denied") { request ->
            val body = "SECRET ERROR BODY".toByteArray()
            request.sendResponseHeaders(401, body.size.toLong())
            request.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val api = HermesApi("http://127.0.0.1:${server.address.port}", "test-api-key")
            assertEquals("run_test", api.json("/v1/runs", JSONObject().put("input", "hello"), "same-request").getString("run_id"))
            assertEquals("Bearer test-api-key", auth)
            assertEquals("same-request", retry)
            assertEquals("hello", prompt)
            assertThrows(HermesHttpException::class.java) { api.json("/redirect") }
            assertFalse(forwarded)
            val error = assertThrows(HermesHttpException::class.java) { api.json("/denied") }
            assertEquals(401, error.status)
            assertFalse(error.message!!.contains("SECRET"))
        } finally { server.stop(0) }
    }
}
