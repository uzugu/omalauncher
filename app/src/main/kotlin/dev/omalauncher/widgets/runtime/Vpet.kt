package dev.omalauncher.widgets.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.WidgetError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn

/** Local Messenger connection. The emulator owns timing/saves; OWL receives only theme-neutral pixels. */
class Vpet(private val context: Context, scope: CoroutineScope) {
    private var remote: Messenger? = null
    private var reply: Messenger? = null
    private var current: Value = VpetFrame.unavailable("Connecting to V-pet…")
    fun value() = current

    val updates = callbackFlow<Value> {
        val main = Handler(Looper.getMainLooper())
        var closed = false
        var bound = false
        lateinit var connection: ServiceConnection
        lateinit var connect: Runnable
        lateinit var handshakeTimeout: Runnable

        fun emit(value: Value) {
            current = value
            trySend(value)
        }
        fun unbind() {
            remote = null
            if (bound) {
                try { context.unbindService(connection) } catch (_: IllegalArgumentException) {}
                bound = false
            }
        }
        fun retry(status: String) {
            main.removeCallbacks(handshakeTimeout)
            unbind()
            emit(VpetFrame.unavailable(status))
            main.removeCallbacks(connect)
            if (!closed) main.postDelayed(connect, 3000)
        }

        val receiver = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (closed) return
                when (msg.what) {
                    FRAME -> {
                        val frame = msg.data
                        main.removeCallbacks(handshakeTimeout)
                        if (frame.getInt("version") != VERSION) {
                            retry("Update both apps for the OWL bridge")
                            return
                        }
                        try {
                            emit(VpetFrame.decode(frame.getInt("width"), frame.getInt("height"),
                                frame.getStringArrayList("rows").orEmpty(), frame.getBoolean("ready"), frame.getBoolean("frozen")))
                        } catch (_: IllegalArgumentException) {
                            retry("Invalid V-pet display; update both apps")
                            return
                        }
                        // Frames are tiny and conflated; acknowledge acceptance rather than queueing a backlog.
                        try {
                            remote?.send(Message.obtain(null, ACK).apply {
                                replyTo = this@Vpet.reply
                                data = Bundle().apply { putLong("sequence", frame.getLong("sequence")) }
                            })
                        } catch (_: RemoteException) { retry("Reconnecting to V-pet…") }
                    }
                    ERROR -> retry(msg.data.getString("error") ?: "Update the V-pet app")
                }
            }
        })
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (closed) return
                remote = Messenger(service)
                reply = receiver
                try {
                    remote!!.send(Message.obtain(null, SUBSCRIBE, VERSION, 0).apply { replyTo = receiver })
                    main.postDelayed(handshakeTimeout, 5000)
                } catch (_: RemoteException) { retry("Reconnecting to V-pet…") }
            }
            override fun onServiceDisconnected(name: ComponentName) = retry("Reconnecting to V-pet…")
            override fun onBindingDied(name: ComponentName) = retry("Reconnecting to V-pet…")
            override fun onNullBinding(name: ComponentName) = retry("Update V-pet to enable the OWL bridge")
        }
        connect = Runnable {
            if (closed) return@Runnable
            emit(VpetFrame.unavailable("Connecting to V-pet…"))
            try {
                val installed = context.packageManager.getPackageInfo(PACKAGE, 0)
                if (installed.longVersionCode < 4) {
                    retry("Update V-pet to enable the OWL bridge")
                    return@Runnable
                }
                bound = context.bindService(
                    Intent(ACTION_BIND).setComponent(ComponentName(PACKAGE, SERVICE)),
                    connection, Context.BIND_AUTO_CREATE,
                )
                if (!bound) retry("Install the V-pet OWL update")
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) { retry("Install the V-pet OWL update") }
            catch (_: SecurityException) { retry("V-pet connection unavailable") }
        }
        handshakeTimeout = Runnable { if (!closed) retry("Update V-pet to enable the OWL bridge") }
        connect.run()
        awaitClose {
            closed = true
            main.removeCallbacks(connect)
            main.removeCallbacks(handshakeTimeout)
            try { remote?.send(Message.obtain(null, UNSUBSCRIBE).apply { replyTo = receiver }) } catch (_: RemoteException) {}
            unbind()
            reply = null
            current = VpetFrame.unavailable("Connecting to V-pet…")
        }
    }.buffer(Channel.CONFLATED).shareIn(scope, SharingStarted.WhileSubscribed(0, 0), replay = 1)

    fun control(key: String) {
        if (key !in setOf("a", "b", "c")) throw WidgetError("vpet takes a, b or c")
        val service = remote ?: throw WidgetError("Open the V-pet app and load a ROM first")
        try {
            service.send(Message.obtain(null, BUTTON).apply {
                replyTo = reply
                data = Bundle().apply { putString("button", key) }
            })
        } catch (_: RemoteException) { throw WidgetError("V-pet disconnected; reconnecting") }
    }

    companion object {
        const val VERSION = 1
        const val ACTION_BIND = "com.digimon.glyph.OWL_BIND"
        const val PACKAGE = "com.digimon.glyph"
        const val SERVICE = "com.digimon.glyph.DigimonGlyphToyService"
        const val SUBSCRIBE = 1
        const val UNSUBSCRIBE = 2
        const val BUTTON = 3
        const val ACK = 4
        const val FRAME = 10
        const val ERROR = 11
    }
}
