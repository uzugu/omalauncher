package dev.omalauncher.hermes

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Optional live gateway check. Credentials come from the test process, never from the repo. */
class HermesNasTest {
    @Test fun nativeClientStreamsAndResumesTheSameNasSession() {
        val url = System.getenv("HERMES_TEST_URL")
        val key = System.getenv("HERMES_TEST_KEY")
        assumeTrue(!url.isNullOrEmpty() && !key.isNullOrEmpty())
        val api = HermesApi(HermesEndpoint.normalize(url!!), key!!)
        assertTrue(api.json("/v1/capabilities").getJSONObject("features").getBoolean("run_events_sse"))
        val session = "omalauncher_test_${UUID.randomUUID().toString().replace("-", "")}"
        val body = JSONObject().put("session_id", session).put("input", "Remember the code word PHONEOWL for our next turn. Reply with only PHONEOWL. Do not use any tools.")
        val id = UUID.randomUUID().toString()
        val run = api.json("/v1/runs", body, id).getString("run_id")
        // An ambiguous POST retry must return the original run rather than invoke Hermes twice.
        assertEquals(run, api.json("/v1/runs", body, id).getString("run_id"))
        var delta = false
        api.events(run) { if (it.optString("event") == "message.delta") delta = true }
        val first = api.json("/v1/runs/$run")
        assertEquals("completed", first.getString("status"))
        assertTrue(first.optString("output").contains("PHONEOWL"))
        assertTrue(delta)
        val followup = api.json("/v1/runs", JSONObject().put("session_id", session).put("input", "What code word did I ask you to remember? Reply only with the code word. Do not use tools."), UUID.randomUUID().toString()).getString("run_id")
        api.events(followup) { }
        assertTrue(api.json("/v1/runs/$followup").optString("output").contains("PHONEOWL"))
        val history = api.json("/api/sessions/$session/messages?limit=100").getJSONArray("data")
        assertTrue(history.length() >= 4)
    }
}
