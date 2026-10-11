package dev.omalauncher.hermes

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

data class HermesMessage(val role: String, val text: String)
data class HermesApproval(val id: String, val command: String, val choices: List<String>)

/** Process-wide chat state. Closing the panel keeps the NAS run connected. */
class HermesConnection(context: Context) {
    private val prefs = context.getSharedPreferences("hermes", Context.MODE_PRIVATE)
    private val secret = HermesSecret(context)
    private var key = secret.read()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var endpoint by mutableStateOf(prefs.getString("endpoint", "").orEmpty())
        private set
    var selected by mutableStateOf(prefs.getBoolean("selected", false))
        private set
    val configured get() = endpoint.isNotBlank() && key.isNotBlank()
    var sessionId by mutableStateOf(prefs.getString("session", null) ?: newId())
        private set
    private var pendingPrompt = prefs.getString("pending_prompt", "").orEmpty()
    private var pendingKey = prefs.getString("pending_key", "").orEmpty()
    var runId by mutableStateOf(prefs.getString("run", "").orEmpty())
        private set
    var working by mutableStateOf(false)
        private set
    var missingRun by mutableStateOf(false)
        private set
    val pending get() = runId.isNotEmpty() || pendingPrompt.isNotEmpty()
    var testing by mutableStateOf(false)
        private set
    var status by mutableStateOf(if (pending) "Reconnect to recover the active request" else "Ready")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var partial by mutableStateOf("")
        private set
    val messages = mutableStateListOf<HermesMessage>()
    val approvals = mutableStateListOf<HermesApproval>()

    init {
        prefs.edit().putString("session", sessionId).apply()
        if (!configured) choose(false)
    }

    fun choose(enabled: Boolean) {
        selected = enabled && configured
        prefs.edit().putBoolean("selected", selected).apply()
    }

    fun configure(url: String, newKey: String) {
        val normalized = HermesEndpoint.normalize(url)
        check(!working && (!pending || normalized == endpoint)) { "Finish or stop the active Hermes request before switching servers" }
        val credential = newKey.trim().ifEmpty { key }
        require(credential.length >= 16 && credential.none { it == '\r' || it == '\n' }) { "Enter your Hermes API_SERVER_KEY (at least 16 characters)" }
        secret.write(credential)
        val changed = normalized != endpoint || credential != key
        endpoint = normalized
        key = credential
        prefs.edit().putString("endpoint", endpoint).apply()
        if (changed && !pending) newConversation()
        choose(true)
        error = null
        status = "Connection saved"
    }

    fun test() {
        if (!configured || testing) return
        testing = true
        error = null
        val api = api()
        scope.launch {
            try {
                val capabilities = withContext(Dispatchers.IO) { api.json("/v1/capabilities") }
                val features = capabilities.optJSONObject("features")
                require(features?.optBoolean("run_events_sse") == true && features.optBoolean("run_stop")) { "This Hermes gateway needs an update to support runs" }
                status = "Connected to Hermes"
            } catch (e: Exception) { fail(e) }
            finally { testing = false }
        }
    }

    fun load() {
        if (!configured || working) return
        val api = api()
        val session = sessionId
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { history(api, session) }
                if (sessionId == session && !working) {
                    messages.clear()
                    messages.addAll(loaded)
                }
            } catch (e: Exception) { fail(e) }
        }
    }

    fun send(text: String): Boolean {
        val prompt = text.trim()
        if (!configured || working || pending || prompt.isEmpty()) return false
        pendingPrompt = prompt
        pendingKey = UUID.randomUUID().toString()
        persistRequest()
        messages.add(HermesMessage("user", prompt))
        resume()
        return true
    }

    /** Retry an ambiguous submit with the SAME idempotency key; never send a new prompt. */
    fun resume() {
        if (!configured || working || !pending) return
        working = true
        missingRun = false
        error = null
        status = "Connecting…"
        val api = api()
        scope.launch {
            try {
                val wasAccepted = runId.isNotEmpty()
                if (!wasAccepted) {
                    val reply = withContext(Dispatchers.IO) {
                        api.json("/v1/runs", JSONObject().put("input", pendingPrompt).put("session_id", sessionId), pendingKey)
                    }
                    runId = reply.getString("run_id")
                    persistRequest()
                }
                if (!wasAccepted) {
                    status = "Hermes is working…"
                    try {
                        withContext(Dispatchers.IO) {
                            api.events(runId) { event ->
                                // Await the main thread to preserve event order.
                                kotlinx.coroutines.runBlocking(Dispatchers.Main) { applyEvent(event) }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        status = "Recovering connection…"
                    }
                }
                // SSE EOF and dropped streams are reconciled with the NAS run status.
                while (pending) {
                    val state = withContext(Dispatchers.IO) { api.json("/v1/runs/$runId") }
                    state.optString("session_id").takeIf { it.isNotEmpty() }?.let { sessionId = it }
                    when (state.optString("status")) {
                        "completed" -> finish("Completed", state.optString("output"))
                        "cancelled" -> finish("Stopped")
                        "failed" -> { finish("Failed"); error = "Hermes could not complete this request; check the NAS logs" }
                        "waiting_for_approval" -> {
                            status = "Waiting for your approval"
                            state.optJSONObject("approval")?.let { approval(it) }
                        }
                        "stopping" -> status = "Stopping…"
                        else -> status = "Hermes is working…"
                    }
                    if (pending) delay(1000)
                }
                val loaded = withContext(Dispatchers.IO) { history(api, sessionId) }
                if (loaded.isNotEmpty()) { messages.clear(); messages.addAll(loaded) }
            } catch (e: Exception) {
                if (runId.isNotEmpty() && e is HermesHttpException && e.status == 404) missingRun = true
                // A rejected admission never started work. Permit correcting credentials.
                if (runId.isEmpty() && e is HermesHttpException && e.status in setOf(400, 401, 403, 404)) {
                    pendingPrompt = ""
                    pendingKey = ""
                    persistRequest()
                }
                fail(e)
                if (pending) status = "Connection interrupted — reconnect to recover"
            }
            finally { working = false }
        }
    }

    fun stop() {
        if (runId.isEmpty()) return
        val api = api()
        val run = runId
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api.json("/v1/runs/$run/stop", JSONObject()) }
                status = "Stopping…"
                if (!working) resume()
            } catch (e: Exception) { fail(e) }
        }
    }

    /** Explicit recovery when the NAS reports a known run missing; never resubmit its prompt. */
    fun recoverMissingRun() {
        if (working || !missingRun) return
        partial = ""
        finish("Recovered saved conversation")
        missingRun = false
        error = null
        load()
    }

    fun respond(request: HermesApproval, choice: String) {
        if (choice !in request.choices || runId.isEmpty()) return
        val api = api()
        val run = runId
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api.json("/v1/runs/$run/approval", JSONObject().put("request_id", request.id).put("choice", choice)) }
                approvals.removeAll { it.id == request.id }
            } catch (e: Exception) { fail(e) }
        }
    }

    fun newConversation() {
        check(!working && !pending) { "Finish or stop the active Hermes request first" }
        sessionId = newId()
        prefs.edit().putString("session", sessionId).apply()
        messages.clear()
        partial = ""
        approvals.clear()
        error = null
        status = "Ready"
    }

    private fun applyEvent(event: JSONObject) {
        when (event.optString("event")) {
            "message.delta" -> partial += event.optString("delta")
            "tool.started" -> status = "Running ${event.optString("tool", "tool")}…"
            "tool.completed" -> status = "Hermes is working…"
            "approval.request" -> { approval(event); status = "Waiting for your approval" }
            "approval.responded" -> approvals.removeAll { it.id == event.optString("request_id") }
            // Terminal events are display hints; GET /v1/runs is authoritative.
        }
    }

    private fun approval(event: JSONObject) {
        val id = event.optString("request_id")
        if (id.isEmpty() || approvals.any { it.id == id }) return
        val choices = event.optJSONArray("choices")
        val allowed = (0 until (choices?.length() ?: 0)).map { choices!!.getString(it) }
            .filter { it in setOf("once", "session", "always", "deny") }
        approvals.add(HermesApproval(id, event.optString("command", "Hermes requests permission"), allowed.ifEmpty { listOf("once", "deny") }))
    }

    private fun finish(label: String, output: String = "") {
        val answer = output.ifEmpty { partial }
        if (answer.isNotEmpty()) messages.add(HermesMessage("assistant", answer))
        partial = ""
        runId = ""
        pendingPrompt = ""
        pendingKey = ""
        approvals.clear()
        missingRun = false
        status = label
        persistRequest()
    }

    private fun persistRequest() {
        // Persist before contacting the NAS, including across an abrupt process death.
        check(prefs.edit().putString("session", sessionId).putString("run", runId)
            .putString("pending_prompt", pendingPrompt).putString("pending_key", pendingKey).commit()) { "Could not save Hermes request on this phone" }
    }

    private fun api() = HermesApi(endpoint, key)
    private fun fail(e: Exception) {
        if (e is CancellationException) throw e
        error = when (e) {
            is HermesHttpException, is IllegalArgumentException, is IllegalStateException -> e.message
            else -> "Cannot reach Hermes. Check your URL, Tailscale connection, and NAS gateway."
        }
    }

    private fun history(api: HermesApi, session: String): List<HermesMessage> {
        val reply = try { api.json("/api/sessions/${URLEncoder.encode(session, "UTF-8")}/messages?limit=100&order=latest") }
            catch (e: HermesHttpException) { if (e.status == 404) return emptyList() else throw e }
        val data = reply.getJSONArray("data")
        return (0 until data.length()).map { data.getJSONObject(it) }.mapNotNull {
            val role = it.optString("role")
            val text = it.optString("content")
            if (role in setOf("user", "assistant") && text.isNotBlank() && text != "null") HermesMessage(role, text) else null
        }
    }

    companion object { private fun newId() = "omalauncher_${UUID.randomUUID().toString().replace("-", "")}" }
}
