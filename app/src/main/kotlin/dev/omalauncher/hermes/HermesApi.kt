package dev.omalauncher.hermes

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Hermes gateway API, not the dashboard on port 9119. Profile prefixes are supported. */
object HermesEndpoint {
    fun normalize(value: String): String {
        val uri = try { URI(value.trim().trimEnd('/').removeSuffix("/v1")) }
            catch (_: Exception) { throw IllegalArgumentException("Enter a valid Hermes gateway URL") }
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) { "Use an http:// or https:// gateway URL" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "Enter the gateway URL without credentials, query, or fragment" }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid gateway port" }
        require(uri.path.isNullOrEmpty() || Regex("/p/[A-Za-z0-9_-]+").matches(uri.path)) { "Use the gateway origin, optionally /p/profile or /v1" }
        if (uri.scheme == "http") require(isPrivateHost(uri.host)) { "Use HTTPS for public hosts, or a private LAN/Tailscale address" }
        return uri.toASCIIString().trimEnd('/')
    }

    private fun isPrivateHost(value: String): Boolean {
        val host = value.lowercase()
        if (host == "localhost" || host in setOf("[::1]", "::1") || host.endsWith(".ts.net") || host.endsWith(".local")) return true
        val octets = host.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 100 && octets[1] in 64..127)
    }
}

class HermesHttpException(val status: Int) : Exception(when (status) {
    401, 403 -> "Hermes rejected the API key"
    404 -> "Hermes endpoint or run not found; use the gateway API, usually port 8642"
    409 -> "Hermes is busy or this request is no longer active"
    429 -> "Hermes has reached its request limit; try again later"
    else -> "Hermes returned HTTP $status"
})

/** SSE parsing is independent of Android so framing and disconnect behavior can be tested. */
object HermesEvents {
    fun read(reader: BufferedReader, receive: (JSONObject) -> Unit) {
        var name = ""
        val data = StringBuilder()
        fun emit() {
            if (data.isNotEmpty()) {
                val body = data.toString().trimEnd('\n')
                if (body != "[DONE]") {
                    val event = JSONObject(body)
                    if (!event.has("event") && name.isNotEmpty()) event.put("event", name)
                    receive(event)
                }
            }
            data.setLength(0)
            name = ""
        }
        while (true) {
            val line = reader.readLine() ?: break
            when {
                line.isEmpty() -> emit()
                line.startsWith("event:") -> name = line.substringAfter(':').removePrefix(" ")
                line.startsWith("data:") -> {
                    require(data.length + line.length <= 1_048_576) { "Hermes event is too large" }
                    data.append(line.substringAfter(':').removePrefix(" ")).append('\n')
                }
            }
        }
        // EOF is not completion. The caller checks the run's authoritative status.
    }
}

class HermesApi(private val endpoint: String, private val key: String) {
    fun json(path: String, body: JSONObject? = null, idempotencyKey: String? = null): JSONObject {
        val connection = open(path, body, idempotencyKey)
        try {
            checkStatus(connection)
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { connection.disconnect() }
    }

    fun events(run: String, receive: (JSONObject) -> Unit) {
        val connection = open("/v1/runs/$run/events")
        connection.setRequestProperty("Accept", "text/event-stream")
        try {
            checkStatus(connection)
            require(connection.contentType?.startsWith("text/event-stream") == true) { "Hermes did not return an event stream" }
            connection.inputStream.bufferedReader().use { HermesEvents.read(it, receive) }
        } finally { connection.disconnect() }
    }

    private fun open(path: String, body: JSONObject? = null, idempotencyKey: String? = null): HttpURLConnection {
        require(path.startsWith('/') && !path.startsWith("//"))
        return (URL(endpoint + path).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false // Never forward an API key to a redirect target.
            connectTimeout = 10_000
            readTimeout = 45_000 // Hermes emits SSE keepalives every 30 seconds.
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Accept", "application/json")
            if (idempotencyKey != null) setRequestProperty("Idempotency-Key", idempotencyKey)
            if (body != null) {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                setFixedLengthStreamingMode(bytes.size)
                outputStream.use { it.write(bytes) }
            }
        }
    }

    private fun checkStatus(connection: HttpURLConnection) {
        if (connection.responseCode !in 200..299) throw HermesHttpException(connection.responseCode)
    }
}
