package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.omalauncher.hermes.HermesConnection

@Composable
fun HermesSettingsScreen(connection: HermesConnection, onDismiss: () -> Unit, onChat: () -> Unit) {
    val network = rememberHermesNetworkAccess()
    var url by remember { mutableStateOf(connection.endpoint) }
    var key by remember { mutableStateOf("") }
    var validation by remember { mutableStateOf<String?>(null) }
    OverlayPanel("Hermes connection", onDismiss) {
        Column(Modifier.weight(1f).imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText("Connect to the Hermes gateway on your NAS. Use Tailscale on your phone when away from home.", style = Mono.copy(color = Palette.muted))
            BasicText("gateway URL", style = Mono.copy(color = Palette.accent))
            HermesField(url, { url = it }, "http://your-nas:8642")
            BasicText("API key", style = Mono.copy(color = Palette.accent))
            HermesField(key, { key = it }, if (connection.configured) "saved · leave blank to keep" else "API_SERVER_KEY", password = true)
            BasicText("The API key is stored encrypted on this phone. The gateway runs Hermes with your NAS tools and configuration.", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            (validation ?: connection.error)?.let { BasicText(it, style = Mono.copy(color = Palette.red)) }
            BasicText(connection.status, style = Mono.copy(color = Palette.muted))
            if (!connection.working) {
                BracketAction("save connection", Palette.accent) {
                    try { connection.configure(url, key); key = ""; validation = null; url = connection.endpoint }
                    catch (e: Exception) { validation = e.message ?: "Could not save connection" }
                }
            } else BasicText("Finish or stop the active request before changing the connection.", style = Mono.copy(color = Palette.muted))
            if (connection.configured) {
                BracketAction(if (connection.testing) "testing…" else "test connection", Palette.accent) { network { connection.test() } }
                BracketAction("open Hermes chat", Palette.accent, onChat)
                BracketAction(if (connection.selected) "use local agents in the bar" else "use Hermes in the bar", Palette.muted) { connection.choose(!connection.selected) }
            }
        }
    }
}

@Composable
fun HermesScreen(connection: HermesConnection, onDismiss: () -> Unit, onSettings: () -> Unit) {
    val network = rememberHermesNetworkAccess()
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { network { connection.load() } }
    LaunchedEffect(connection.messages.size, connection.partial.length, connection.approvals.size) {
        if (list.layoutInfo.totalItemsCount > 0) list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1)
    }
    OverlayPanel("Hermes · NAS", onDismiss, actions = {
        BracketAction("connection", Palette.muted, onSettings)
    }) {
        Column(Modifier.weight(1f).imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BasicText(connection.status, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                if (!connection.working && !connection.pending) BracketAction("new chat", Palette.muted) { connection.newConversation() }
                if (connection.runId.isNotEmpty()) BracketAction("stop", Palette.red) { network { connection.stop() } }
            }
            connection.error?.let { BasicText(it, style = Mono.copy(color = Palette.red)) }
            if (connection.pending && !connection.working) BracketAction("reconnect", Palette.accent) { network { connection.resume() } }
            if (connection.missingRun && !connection.working) {
                BasicText("The NAS no longer retains this run. You can return to the saved conversation; the interrupted prompt will not be sent again.", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                BracketAction("return to saved conversation", Palette.accent) { network { connection.recoverMissingRun() } }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (connection.messages.isEmpty() && connection.partial.isEmpty()) item {
                    BasicText("Ask Hermes here or from the home screen bar. Your conversation stays on the NAS.", style = Mono.copy(color = Palette.muted))
                }
                items(connection.messages) { message ->
                    Column(Modifier.fillMaxWidth().background(Palette.surface).padding(12.dp)) {
                        BasicText(if (message.role == "user") "you" else "Hermes", style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small))
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            BasicText(message.text, style = Mono)
                        }
                    }
                }
                if (connection.partial.isNotEmpty()) item {
                    Column(Modifier.fillMaxWidth().background(Palette.surface).padding(12.dp)) {
                        BasicText("Hermes", style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small))
                        BasicText(connection.partial, style = Mono)
                    }
                }
                items(connection.approvals, key = { it.id }) { request ->
                    Column(Modifier.fillMaxWidth().frame(1.dp, Palette.accent).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BasicText("Hermes requests permission", style = Mono.copy(color = Palette.accent))
                        BasicText(request.command, style = Mono)
                        for (choice in request.choices) BracketAction(when (choice) {
                            "once" -> "allow once"
                            "session" -> "allow for this session"
                            "always" -> "always allow"
                            else -> "deny"
                        }, if (choice == "deny") Palette.red else Palette.accent) { network { connection.respond(request, choice) } }
                    }
                }
            }
            HermesField(text, { text = it }, "ask Hermes…", multiline = true)
            if (!connection.working && !connection.pending && text.isNotBlank()) BracketAction("send", Palette.accent) {
                network { if (connection.send(text)) text = "" }
            }
        }
    }
}

@Composable
private fun HermesField(value: String, onChange: (String) -> Unit, placeholder: String, password: Boolean = false, multiline: Boolean = false) {
    BasicTextField(value, onChange,
        modifier = Modifier.fillMaxWidth().background(Palette.surface).padding(12.dp),
        textStyle = Mono,
        cursorBrush = SolidColor(Palette.accent),
        singleLine = !multiline,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text),
        maxLines = if (multiline) 5 else 1,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        decorationBox = { inner ->
            androidx.compose.foundation.layout.Box {
                if (value.isEmpty()) BasicText(placeholder, style = Mono.copy(color = Palette.muted))
                inner()
            }
        })
}
