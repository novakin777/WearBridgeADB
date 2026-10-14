package dev.wearbrdgeadb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Optional loopback-only HTTP bridge for a local Termux client. */
class TermuxApiService : Service(), MessageClient.OnMessageReceivedListener {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val workers = Executors.newCachedThreadPool()
    @Volatile private var running = true
    @Volatile private var server: ServerSocket? = null
    private lateinit var token: ByteArray

    override fun onCreate() {
        super.onCreate()
        token = loadOrCreateToken()
        startForeground(NOTIFICATION_ID, buildNotification())
        Wearable.getMessageClient(this).addListener(this)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("api_running", true).apply()
        workers.execute { acceptLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun loadOrCreateToken(): ByteArray {
        val file = java.io.File(filesDir, TOKEN_FILE)
        if (file.isFile && file.length() >= 40) return file.readBytes()
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        file.writeText(encoded)
        return encoded.toByteArray(StandardCharsets.UTF_8)
    }

    private fun acceptLoop() {
        try {
            val listener = ServerSocket()
            listener.reuseAddress = true
            listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 8)
            server = listener
            while (running) {
                val client = listener.accept()
                workers.execute { handle(client) }
            }
        } catch (e: Exception) {
            if (running) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("api_running", false).putString("api_error", e.message).apply()
                stopSelf()
            }
        }
    }

    private fun handle(socket: java.net.Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 130_000
                val headerBytes = ByteArrayOutputStream()
                var matched = 0
                while (headerBytes.size() < 65536 && matched < 4) {
                    val b = client.getInputStream().read()
                    if (b < 0) break
                    headerBytes.write(b)
                    matched = when {
                        matched == 0 && b == 13 -> 1
                        matched == 1 && b == 10 -> 2
                        matched == 2 && b == 13 -> 3
                        matched == 3 && b == 10 -> 4
                        b == 13 -> 1
                        else -> 0
                    }
                }
                val lines = headerBytes.toString("ISO-8859-1").split("\r\n")
                val request = lines.firstOrNull()?.split(' ') ?: emptyList()
                if (request.size < 2 || request[0] != "GET") return@use respond(client, 405, JSONObject().put("error", "Use GET"))
                val auth = lines.firstOrNull { it.startsWith("Authorization:", true) }
                    ?.substringAfter(':')?.trim()?.removePrefix("Bearer ") ?: ""
                val provided = auth.toByteArray(StandardCharsets.UTF_8)
                if (!MessageDigest.isEqual(provided, token)) return@use respond(client, 401, JSONObject().put("error", "Unauthorized"))

                val target = request[1]
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))
                when (path) {
                    "/v1/status" -> {
                        val nodes = runBlocking { Wearable.getNodeClient(this@TermuxApiService).connectedNodes.await() }
                        respond(client, 200, JSONObject().put("api", "ready").put("listen", "127.0.0.1:$PORT").put("watchConnected", nodes.isNotEmpty()).put("watch", nodes.firstOrNull()?.displayName ?: ""))
                    }
                    "/v1/exec" -> {
                        val command = query["command"].orEmpty()
                        val mode = query["mode"] ?: "shell"
                        if (command.isBlank() || command.length > MAX_COMMAND) return@use respond(client, 400, JSONObject().put("error", "command is required and limited to $MAX_COMMAND characters"))
                        if (mode != "shell" && mode != "root") return@use respond(client, 400, JSONObject().put("error", "mode must be shell or root"))
                        try {
                            val (id, result) = runBlocking { executeOnWatch(command, mode) }
                            val exitCode = Regex("(?:^|\\n)exitCode=(-?\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull()
                            val response = JSONObject().put("requestId", id).put("mode", mode).put("result", result)
                            if (exitCode != null) response.put("exitCode", exitCode)
                            respond(client, 200, response)
                        } catch (e: java.util.concurrent.TimeoutException) {
                            respond(client, 504, JSONObject().put("error", "watch response timed out").put("mode", mode))
                        } catch (e: Exception) {
                            respond(client, 502, JSONObject().put("error", e.message ?: e.javaClass.simpleName).put("mode", mode))
                        }
                    }
                    else -> respond(client, 404, JSONObject().put("error", "unknown endpoint"))
                }
            } catch (_: Exception) {
                try { respond(client, 400, JSONObject().put("error", "invalid request")) } catch (_: Exception) { }
            }
        }
    }

    private fun parseQuery(query: String): Map<String, String> = query.split('&').filter { it.isNotEmpty() }.associate {
        val key = URLDecoder.decode(it.substringBefore('='), "UTF-8")
        val value = URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        key to value
    }

    private suspend fun executeOnWatch(command: String, mode: String): Pair<String, String> {
        val node = Wearable.getNodeClient(this).connectedNodes.await().firstOrNull()
            ?: throw IllegalStateException("No connected Wear OS watch")
        val id = UUID.randomUUID().toString()
        val reply = CompletableDeferred<String>()
        pending[id] = reply
        try {
            Wearable.getMessageClient(this).sendMessage(node.id, "/exec", "$id\n$mode\n$command".toByteArray(StandardCharsets.UTF_8)).await()
            return id to withTimeout(120_000) { reply.await() }
        } finally { pending.remove(id) }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != "/result") return
        val payload = String(event.data, StandardCharsets.UTF_8)
        val first = payload.substringBefore('\n')
        val id = first.removePrefix("[").removeSuffix("]")
        pending[id]?.complete(payload.substringAfter('\n', ""))
    }

    private fun respond(socket: java.net.Socket, code: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        val phrase = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 502 -> "Bad Gateway"; 504 -> "Gateway Timeout"; else -> "Error" }
        val head = "HTTP/1.1 $code $phrase\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
        socket.getOutputStream().apply { write(head.toByteArray(StandardCharsets.US_ASCII)); write(bytes); flush() }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Termux local API", NotificationManager.IMPORTANCE_LOW))
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("WearBridgeADB API")
            .setContentText("Local Termux API · 127.0.0.1:$PORT").setOngoing(true).build()
    }

    override fun onDestroy() {
        running = false
        try { server?.close() } catch (_: Exception) { }
        Wearable.getMessageClient(this).removeListener(this)
        workers.shutdownNow()
        pending.values.forEach { it.completeExceptionally(IllegalStateException("API stopped")) }
        stopForeground(true)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("api_running", false).apply()
        super.onDestroy()
    }

    companion object {
        const val PREFS = "wearbridge_settings"
        const val TOKEN_FILE = "termux-api.token"
        const val PORT = 18765
        private const val CHANNEL = "wearbridge-termux-api"
        private const val NOTIFICATION_ID = 18765
        private const val MAX_COMMAND = 4096
    }
}
