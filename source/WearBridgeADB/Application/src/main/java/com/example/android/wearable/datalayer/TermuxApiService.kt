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
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.ChannelClient
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Optional loopback-only HTTP bridge for a local Termux client. */
class TermuxApiService : Service(), MessageClient.OnMessageReceivedListener, DataClient.OnDataChangedListener {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val resultChunks = ConcurrentHashMap<String, ResultChunks>()
    private val jobs = ConcurrentHashMap<String, ApiJob>()
    private val pendingTransfers = ConcurrentHashMap<String, CompletableDeferred<DataMap>>()
    private val pendingWifiReady = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val pendingStreamReady = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val streamSessions = ConcurrentHashMap<String, ApiStreamSession>()
    private val activeNormalRequests = ConcurrentHashMap.newKeySet<String>()
    private val jobRetentionLock = Any()
    private val listenerRegistrationLock = Any()
    private var messageListenerRegistered = false
    private var dataListenerRegistered = false
    // One worker stays in acceptLoop; the rest handle bounded HTTP/background work.
    // Reject overload explicitly instead of growing an unbounded number of threads.
    private val workers = ThreadPoolExecutor(
        2,
        MAX_WORKER_THREADS,
        WORKER_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(MAX_QUEUED_WORK),
        { task -> Thread(task, "WearBridge-API-${workerThreadIds.incrementAndGet()}") },
        ThreadPoolExecutor.AbortPolicy()
    )
    @Volatile private var running = true
    @Volatile private var server: ServerSocket? = null
    private lateinit var token: ByteArray

    override fun onCreate() {
        super.onCreate()
        token = loadOrCreateToken()
        startForeground(NOTIFICATION_ID, buildNotification())
        loadJobs()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("api_running", true).apply()
        workers.execute {
            try {
                if (!runBlocking { registerMessageListener() }) return@execute
                if (!runBlocking { registerDataListener() }) return@execute
                acceptLoop()
            } catch (e: Exception) {
                if (running) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("api_running", false).putString("api_error", e.message).apply()
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun registerMessageListener(): Boolean {
        val client = Wearable.getMessageClient(this)
        client.addListener(this).await()
        val keep = synchronized(listenerRegistrationLock) {
            if (running) { messageListenerRegistered = true; true } else false
        }
        if (!keep) client.removeListener(this)
        return keep
    }

    private suspend fun registerDataListener(): Boolean {
        val client = Wearable.getDataClient(this)
        client.addListener(this, Uri.parse("wear://*/wearbridge/api-transfer/response"), DataClient.FILTER_PREFIX).await()
        val keep = synchronized(listenerRegistrationLock) {
            if (running) { dataListenerRegistered = true; true } else false
        }
        if (!keep) client.removeListener(this)
        return keep
    }

    private fun unregisterWearListeners() {
        synchronized(listenerRegistrationLock) {
            if (messageListenerRegistered) {
                Wearable.getMessageClient(this).removeListener(this)
                messageListenerRegistered = false
            }
            if (dataListenerRegistered) {
                Wearable.getDataClient(this).removeListener(this)
                dataListenerRegistered = false
            }
        }
    }

    private fun loadOrCreateToken(): ByteArray {
        val file = java.io.File(noBackupFilesDir, TOKEN_FILE)
        if (file.isFile && file.length() >= 40) return file.readBytes()
        // Migrate existing installs without changing the token users already configured.
        val legacyFile = java.io.File(filesDir, TOKEN_FILE)
        if (legacyFile.isFile && legacyFile.length() >= 40) {
            val existing = legacyFile.readBytes()
            file.writeBytes(existing)
            legacyFile.delete()
            return existing
        }
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
                try {
                    workers.execute { handle(client) }
                } catch (_: RejectedExecutionException) {
                    try {
                        client.use { respond(it, 503, JSONObject().put("error", "API busy; retry after active requests finish")) }
                    } catch (_: Exception) {
                        try { client.close() } catch (_: Exception) { }
                    }
                }
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
                while (headerBytes.size() < MAX_HTTP_HEADER_BYTES && matched < 4) {
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
                if (matched != 4) {
                    val code = if (headerBytes.size() >= MAX_HTTP_HEADER_BYTES) 413 else 400
                    return@use respond(client, code, JSONObject().put("error", if (code == 413) "request headers too large" else "incomplete HTTP headers"))
                }
                val lines = headerBytes.toString("ISO-8859-1").split("\r\n")
                val request = lines.firstOrNull()?.split(' ') ?: emptyList()
                if (request.size < 2 || request[0] !in setOf("GET", "POST")) return@use respond(client, 405, JSONObject().put("error", "Use GET or POST"))
                val auth = lines.firstOrNull { it.startsWith("Authorization:", true) }
                    ?.substringAfter(':')?.trim()?.removePrefix("Bearer ") ?: ""
                val provided = auth.toByteArray(StandardCharsets.UTF_8)
                if (!MessageDigest.isEqual(provided, token)) return@use respond(client, 401, JSONObject().put("error", "Unauthorized"))

                val target = request[1]
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))
                if (path == "/v1/stream/open") {
                    if (request[0] != "POST") return@use respond(client, 405, JSONObject().put("error", "Use POST"))
                    val mode = query["mode"] ?: "shell"
                    if (mode !in setOf("shell", "root")) return@use respond(client, 400, JSONObject().put("error", "mode must be shell or root"))
                    try { respond(client, 200, runBlocking { openStreamSession(mode) }) }
                    catch (e: Exception) { respond(client, 502, JSONObject().put("error", e.message ?: e.javaClass.simpleName)) }
                    return@use
                }
                if (path == "/v1/stream/input") {
                    if (request[0] != "POST") return@use respond(client, 405, JSONObject().put("error", "Use POST"))
                    val id = query["sessionId"].orEmpty(); val session = streamSessions[id]
                        ?: return@use respond(client, 404, JSONObject().put("error", "unknown stream session"))
                    val length = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull()
                    if (length == null || length !in 0..MAX_STREAM_INPUT_BYTES) return@use respond(client, 400, JSONObject().put("error", "Content-Length must be 0..$MAX_STREAM_INPUT_BYTES"))
                    try {
                        val bytes = ByteArrayOutputStream(length)
                        copyExactly(client.getInputStream(), bytes, length.toLong())
                    session.lastAccess = System.currentTimeMillis()
                    synchronized(session.outputLock) { session.output.write(bytes.toByteArray()); session.output.flush() }
                        respond(client, 200, JSONObject().put("sessionId", id).put("bytes", length))
                    } catch (e: Exception) { respond(client, 502, JSONObject().put("error", e.message ?: e.javaClass.simpleName)) }
                    return@use
                }
                if (path == "/v1/stream/output") {
                    if (request[0] != "GET") return@use respond(client, 405, JSONObject().put("error", "Use GET"))
                    val id = query["sessionId"].orEmpty(); val cursor = query["cursor"]?.toLongOrNull() ?: 0L
                    val session = streamSessions[id] ?: return@use respond(client, 404, JSONObject().put("error", "unknown stream session"))
                    respond(client, 200, session.outputAfter(cursor))
                    return@use
                }
                if (path == "/v1/stream/resize") {
                    if (request[0] != "POST") return@use respond(client, 405, JSONObject().put("error", "Use POST"))
                    val session = streamSessions[query["sessionId"]]
                        ?: return@use respond(client, 404, JSONObject().put("error", "unknown stream session"))
                    val rows = query["rows"]?.toIntOrNull() ?: 0; val columns = query["columns"]?.toIntOrNull() ?: 0
                    if (rows !in 2..300 || columns !in 20..500) return@use respond(client, 400, JSONObject().put("error", "rows or columns out of range"))
                    try {
                        session.lastAccess = System.currentTimeMillis()
                        val payload = JSONObject().put("rows", rows).put("columns", columns).toString().toByteArray(StandardCharsets.UTF_8)
                        runBlocking { Wearable.getMessageClient(this@TermuxApiService).sendMessage(session.nodeId, "/terminal/resize", payload).await() }
                        respond(client, 200, JSONObject().put("sessionId", session.id).put("rows", rows).put("columns", columns))
                    } catch (e: Exception) { respond(client, 502, JSONObject().put("error", e.message ?: e.javaClass.simpleName)) }
                    return@use
                }
                if (path == "/v1/stream/close") {
                    if (request[0] != "POST") return@use respond(client, 405, JSONObject().put("error", "Use POST"))
                    val id = query["sessionId"].orEmpty(); val session = streamSessions.remove(id)
                        ?: return@use respond(client, 404, JSONObject().put("error", "unknown stream session"))
                    closeStreamSession(session)
                    respond(client, 200, JSONObject().put("sessionId", id).put("closed", true))
                    return@use
                }
                if (path == "/v1/files/push") {
                    if (request[0] != "POST") return@use respond(client, 405, JSONObject().put("error", "Use POST"))
                    val remotePath = query["path"].orEmpty(); val mode = query["mode"] ?: "shell"; val transport = query["transport"] ?: "datalayer"
                    val length = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toLongOrNull()
                    if (!validRemotePath(remotePath) || mode !in setOf("shell", "root") || transport !in setOf("datalayer", "wifi") || length == null || length < 0 || length > MAX_TRANSFER_BYTES)
                        return@use respond(client, 400, JSONObject().put("error", "absolute path, mode=shell|root, transport=datalayer|wifi, and Content-Length from 0 to $MAX_TRANSFER_BYTES are required"))
                    val temp = File(cacheDir, "api-upload-${UUID.randomUUID()}.part")
                    var responseItemId: String? = null
                    try {
                        client.soTimeout = TRANSFER_TIMEOUT_MS.toInt()
                        copyExactly(client.getInputStream(), temp, length)
                        val result = if (transport == "wifi") runBlocking { transferViaWifi("upload", remotePath, mode, temp) }
                                     else runBlocking {
                                         val data = transferViaDataLayer("upload", remotePath, mode, temp, length)
                                         JSONObject().put("id", data.getString("id")).put("ok", data.getBoolean("ok")).put("message", data.getString("message"))
                                     }
                        responseItemId = result.optString("id").takeIf { it.isNotBlank() && it != "null" }
                        val ok = result.getBoolean("ok")
                        val body = JSONObject()
                        body.put("ok", ok); body.put("path", remotePath); body.put("bytes", length); body.put("transport", transport); body.put("result", result.optString("message"))
                        respond(client, if (ok) 200 else 502, body)
                    } catch (e: Exception) { respond(client, 502, JSONObject().put("error", e.message ?: e.javaClass.simpleName)) }
                    finally { temp.delete(); responseItemId?.let { deleteTransferItem("response", it) } }
                    return@use
                }
                if (path == "/v1/files/pull") {
                    if (request[0] != "GET") return@use respond(client, 405, JSONObject().put("error", "Use GET"))
                    val remotePath = query["path"].orEmpty(); val mode = query["mode"] ?: "shell"; val transport = query["transport"] ?: "datalayer"
                    if (!validRemotePath(remotePath) || mode !in setOf("shell", "root") || transport !in setOf("datalayer", "wifi")) return@use respond(client, 400, JSONObject().put("error", "absolute path, mode=shell|root, and transport=datalayer|wifi are required"))
                    var responseItemId: String? = null
                    try {
                        if (transport == "wifi") {
                            val result = runBlocking { transferViaWifi("download", remotePath, mode, null) }
                            if (!result.getBoolean("ok")) return@use respond(client, 502, JSONObject().put("error", result.optString("message", "watch file read failed")).put("transport", transport))
                            val file = File(result.getString("localFile"))
                            try { respondFile(client, file.inputStream(), file.length()) } finally { file.delete() }
                            return@use
                        }
                        val result = runBlocking { transferViaDataLayer("download", remotePath, mode, null) }
                        responseItemId = result.getString("id")
                        if (!result.getBoolean("ok")) return@use respond(client, 502, JSONObject().put("error", result.getString("message") ?: "watch file read failed"))
                        val asset = result.getAsset("asset")
                            ?: return@use respond(client, 502, JSONObject().put("error", "watch returned no file asset"))
                        val assetResponse = runBlocking { Wearable.getDataClient(this@TermuxApiService).getFdForAsset(asset).await() }
                        try {
                            val bytes = assetResponse.getInputStream()
                            respondFile(client, bytes, result.getLong("size"))
                        } finally { assetResponse.release() }
                    } catch (e: Exception) { respond(client, 502, JSONObject().put("error", e.message ?: e.javaClass.simpleName)) }
                    finally { responseItemId?.let { deleteTransferItem("response", it) } }
                    return@use
                }
                when (path) {
                    "/v1/status" -> {
                        val nodes = runBlocking { Wearable.getNodeClient(this@TermuxApiService).connectedNodes.await() }
                        respond(client, 200, JSONObject().put("api", "ready").put("listen", "127.0.0.1:$PORT").put("watchConnected", nodes.isNotEmpty()).put("watch", nodes.firstOrNull()?.displayName ?: ""))
                    }
                    "/v1/jobs/status", "/v1/jobs/output" -> {
                        val job = jobs[query["jobId"]]
                        if (job == null) respond(client, 404, JSONObject().put("error", "unknown job"))
                        else {
                            RequestTrace.record(this@TermuxApiService, job.id, "job_status_read", "state=${job.state} stage=${job.stage} ageMs=${System.currentTimeMillis() - job.createdAt} stageAgeMs=${System.currentTimeMillis() - job.lastActivityAt}")
                            val body = JSONObject().put("jobId", job.id).put("state", job.state)
                                .put("mode", job.mode).put("createdAt", job.createdAt)
                                .put("updatedAt", job.updatedAt).put("stage", job.stage)
                                .put("lastActivityAt", job.lastActivityAt).put("exitCode", job.exitCode)
                                .put("error", job.error).put("truncated", job.truncated)
                            if (path.endsWith("/output")) body.put("output", job.output)
                            respond(client, 200, body)
                        }
                    }
                    "/v1/jobs/cancel" -> {
                        val job = jobs[query["jobId"]]
                        if (job == null) respond(client, 404, JSONObject().put("error", "unknown job"))
                        else {
                            RequestTrace.record(this@TermuxApiService, job.id, "job_cancel_requested", "state=${job.state}")
                            val cancelState = synchronized(job) {
                                when (job.state) {
                                    "queued" -> { job.state = "cancelled"; job.stage = "cancelled_before_start"; job.updatedAt = System.currentTimeMillis(); job.lastActivityAt = job.updatedAt; persistJob(job); "cancelled" }
                                    "running" -> { job.state = "cancel_requested"; job.stage = "cancel_message_pending"; job.updatedAt = System.currentTimeMillis(); job.lastActivityAt = job.updatedAt; persistJob(job); "cancel_requested" }
                                    "cancel_requested" -> "cancel_requested"
                                    else -> job.state
                                }
                            }
                            if (cancelState == "cancel_requested") {
                                try {
                                    workers.execute {
                                        try {
                                            runBlocking { cancelWatchExecution(job.id) }
                                            RequestTrace.record(this@TermuxApiService, job.id, "job_cancel_message_sent")
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (e: Exception) {
                                            RequestTrace.record(this@TermuxApiService, job.id, "job_cancel_message_failed", e.javaClass.simpleName)
                                            job.error = e.message ?: "cancel request failed"
                                            if (job.state == "cancel_requested") job.state = "running"
                                            job.updatedAt = System.currentTimeMillis()
                                            job.lastActivityAt = job.updatedAt
                                            persistJob(job)
                                        }
                                    }
                                } catch (_: RejectedExecutionException) {
                                    synchronized(job) {
                                        if (job.state == "cancel_requested") {
                                            job.state = "running"
                                            job.stage = "cancel_rejected_busy"
                                            job.error = "API busy; cancel request was not sent"
                                            job.updatedAt = System.currentTimeMillis()
                                            job.lastActivityAt = job.updatedAt
                                            persistJob(job)
                                        }
                                    }
                                    return@use respond(client, 503, JSONObject().put("jobId", job.id).put("state", job.state).put("error", "API busy; cancel request was not sent"))
                                }
                                respond(client, 202, JSONObject().put("jobId", job.id).put("state", cancelState))
                            } else if (cancelState == "cancelled") {
                                respond(client, 200, JSONObject().put("jobId", job.id).put("state", cancelState))
                            } else {
                                respond(client, 409, JSONObject().put("jobId", job.id).put("state", cancelState).put("error", "job is already finished"))
                            }
                        }
                    }
                    "/v1/exec/cancel" -> {
                        if (request[0] != "POST") return@use respond(client, 405, JSONObject().put("error", "Use POST"))
                        val id = query["requestId"].orEmpty()
                        if (!id.matches(Regex("[A-Fa-f0-9-]{36}"))) return@use respond(client, 400, JSONObject().put("error", "requestId must be a UUID"))
                        if (!activeNormalRequests.contains(id)) return@use respond(client, 409, JSONObject().put("requestId", id).put("error", "request is no longer active"))
                        try {
                            RequestTrace.record(this@TermuxApiService, id, "cancel_http_received")
                            runBlocking { cancelWatchExecution(id) }
                            RequestTrace.record(this@TermuxApiService, id, "cancel_message_sent", "watchProcessStopped=false")
                            respond(client, 202, JSONObject().put("requestId", id).put("state", "cancel_sent").put("watchProcessStopped", false))
                        } catch (e: Exception) {
                            respond(client, 502, JSONObject().put("requestId", id).put("error", e.message ?: e.javaClass.simpleName).put("watchProcessStopped", false))
                        }
                    }
                    "/v1/exec" -> {
                        val command = query["command"].orEmpty()
                        val mode = query["mode"] ?: "shell"
                        val execution = query["execution"] ?: "normal"
                        val commandBytes = command.toByteArray(StandardCharsets.UTF_8).size
                        if (command.isBlank() || commandBytes > MAX_COMMAND_BYTES) return@use respond(client, 400, JSONObject().put("error", "command is required and limited to $MAX_COMMAND_BYTES UTF-8 bytes"))
                        if (mode != "shell" && mode != "root") return@use respond(client, 400, JSONObject().put("error", "mode must be shell or root"))
                        if (execution != "normal") return@use respond(client, 400, JSONObject().put("error", "single commands use execution=normal; open /v1/stream/open for STREAM"))
                        if (query["async"] == "true") {
                            val job = ApiJob(UUID.randomUUID().toString(), mode, command)
                            jobs[job.id] = job
                            persistJob(job)
                            pruneJobHistory()
                            RequestTrace.record(this@TermuxApiService, job.id, "job_queued", "mode=$mode commandBytes=$commandBytes")
                            try {
                                workers.execute { runBlocking { runJob(job) } }
                            } catch (_: RejectedExecutionException) {
                                jobs.remove(job.id, job)
                                java.io.File(java.io.File(filesDir, JOB_DIR), "${job.id}.json").delete()
                                RequestTrace.record(this@TermuxApiService, job.id, "job_rejected_busy")
                                return@use respond(client, 503, JSONObject().put("error", "API busy; async job was not started"))
                            }
                            return@use respond(client, 202, JSONObject().put("jobId", job.id).put("state", job.state).put("mode", mode))
                        }
                        val requestId = query["requestId"] ?: UUID.randomUUID().toString()
                        if (!requestId.matches(Regex("[A-Fa-f0-9-]{36}"))) return@use respond(client, 400, JSONObject().put("error", "requestId must be a UUID"))
                        if (!activeNormalRequests.add(requestId)) return@use respond(client, 409, JSONObject().put("error", "requestId is already active"))
                        try {
                            RequestTrace.record(this@TermuxApiService, requestId, "api_exec_received", "mode=$mode commandBytes=$commandBytes")
                            val (id, result) = runBlocking { executeOnWatch(command, mode, requestId = requestId) }
                            val exitCode = Regex("(?:^|\\n)exitCode=(-?\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull()
                            val response = JSONObject().put("requestId", id).put("mode", mode).put("result", result)
                            if (exitCode != null) response.put("exitCode", exitCode)
                            RequestTrace.record(this@TermuxApiService, id, "api_exec_http_response", "exitCode=${exitCode ?: "unknown"} responseBytes=${result.toByteArray(StandardCharsets.UTF_8).size}")
                            respond(client, 200, response)
                        } catch (e: java.util.concurrent.TimeoutException) {
                            RequestTrace.record(this@TermuxApiService, requestId, "api_exec_timeout", "state=indeterminate")
                            respond(client, 504, JSONObject().put("error", "watch response timed out; command state is indeterminate").put("indeterminate", true).put("mode", mode))
                        } catch (e: Exception) {
                            RequestTrace.record(this@TermuxApiService, requestId, "api_exec_failed", e.javaClass.simpleName)
                            respond(client, 502, JSONObject().put("error", e.message ?: e.javaClass.simpleName).put("mode", mode))
                        } finally { activeNormalRequests.remove(requestId) }
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

    private fun validRemotePath(path: String) = path.startsWith("/") && path.length > 1 && !path.contains('\u0000') && !path.contains('\n') && !path.contains('\r') && !path.split('/').any { it == ".." || it == "." }

    private fun copyExactly(input: java.io.InputStream, file: java.io.File, length: Long) {
        file.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024); var remaining = length
            while (remaining > 0) { val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); if (n < 0) throw java.io.EOFException("request body ended early"); output.write(buffer, 0, n); remaining -= n }
        }
    }

    private fun copyExactly(input: java.io.InputStream, output: java.io.OutputStream, length: Long) {
        val buffer = ByteArray(8192); var remaining = length
        while (remaining > 0) { val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); if (n < 0) throw java.io.EOFException("request body ended early"); output.write(buffer, 0, n); remaining -= n }
    }

    private fun deleteTransferItem(kind: String, id: String) {
        try { runBlocking { Wearable.getDataClient(this@TermuxApiService).deleteDataItems(Uri.parse("wear://*/wearbridge/api-transfer/$kind/$id"), DataClient.FILTER_LITERAL).await() } } catch (_: Exception) { }
    }

    private suspend fun transferViaDataLayer(operation: String, remotePath: String, mode: String, upload: java.io.File?, uploadSize: Long = 0): DataMap {
        val id = UUID.randomUUID().toString(); val reply = CompletableDeferred<DataMap>(); pendingTransfers[id] = reply
        var descriptor: ParcelFileDescriptor? = null
        try {
        val request = PutDataMapRequest.create("/wearbridge/api-transfer/request/$id").apply {
                dataMap.putString("id", id); dataMap.putString("operation", operation); dataMap.putString("path", remotePath); dataMap.putString("mode", mode)
                if (upload != null) {
                    descriptor = ParcelFileDescriptor.open(upload, ParcelFileDescriptor.MODE_READ_ONLY)
                    dataMap.putLong("size", uploadSize)
                    dataMap.putAsset("asset", com.google.android.gms.wearable.Asset.createFromFd(descriptor!!))
                }
            }
            Wearable.getDataClient(this).putDataItem(request.asPutDataRequest().setUrgent()).await()
            descriptor?.close(); descriptor = null
            return kotlinx.coroutines.withTimeout(TRANSFER_TIMEOUT_MS) { reply.await() }
        } finally {
            try { descriptor?.close() } catch (_: Exception) { }
            pendingTransfers.remove(id)
            try { Wearable.getDataClient(this).deleteDataItems(Uri.parse("wear://*/wearbridge/api-transfer/request/$id"), DataClient.FILTER_LITERAL).await() } catch (_: Exception) { }
        }
    }

    private suspend fun transferViaWifi(operation: String, remotePath: String, mode: String, upload: File?): JSONObject {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Nearby devices permission is required for Wi-Fi transfer; grant it in WearBridge Files first")
        val node = Wearable.getNodeClient(this).connectedNodes.await().firstOrNull()
            ?: throw IllegalStateException("No connected Wear OS watch")
        val id = UUID.randomUUID().toString()
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val ready = CompletableDeferred<JSONObject>()
        pendingWifiReady[id] = ready
        try {
            val request = JSONObject().put("id", id)
                .put("key", Base64.encodeToString(key, Base64.NO_WRAP))
                .put("path", remotePath).put("root", mode == "root")
            Wearable.getMessageClient(this).sendMessage(node.id, "/file-transfer/$operation", request.toString().toByteArray(StandardCharsets.UTF_8)).await()
            val endpoint = withTimeout(WIFI_ENDPOINT_TIMEOUT_MS) { ready.await() }
            if (!endpoint.optBoolean("ok", false)) throw IOException(endpoint.optString("error", "watch Wi-Fi endpoint unavailable"))
            val host = endpoint.optString("host"); val port = endpoint.optInt("port", 0)
            if (host.isBlank() || port !in 1..65535) throw IOException("Watch returned an invalid Wi-Fi endpoint")
            val localFile = if (operation == "download") File(cacheDir, "api-download-$id.part") else null
            val remoteResult = performApiWifiTransfer(operation, host, port, id, key, upload, localFile)
            if (remoteResult.first != 0) {
                localFile?.delete()
                return JSONObject().put("id", id).put("ok", false).put("message", remoteResult.second)
            }
            return JSONObject().put("id", id).put("ok", true).put("message", remoteResult.second)
                .put("localFile", localFile?.absolutePath).put("size", localFile?.length() ?: (upload?.length() ?: 0L))
        } finally {
            pendingWifiReady.remove(id)
        }
    }

    private fun performApiWifiTransfer(operation: String, host: String, port: Int, id: String, key: ByteArray, upload: File?, download: File?): Pair<Int, String> {
        val address = InetAddress.getByName(host)
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: throw IOException("Connectivity service unavailable")
        val wifi = connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                (connectivity.getLinkProperties(network)?.routes?.any { it.matches(address) } == true)
        } ?: throw IOException("No Wi-Fi route to watch $host; connect both devices to the same LAN")
        val endpoint = InetSocketAddress(address, port)
        val socket = try {
            wifi.socketFactory.createSocket().also { s ->
                try { s.connect(endpoint, 15_000) } catch (t: Throwable) { try { s.close() } catch (_: Exception) { }; throw t }
            }
        } catch (boundError: Throwable) {
            val message = boundError.message.orEmpty()
            if (!message.contains("EPERM", true) && !message.contains("Operation not permitted", true)) throw boundError
            Socket().also { fallback ->
                try { fallback.connect(endpoint, 15_000) } catch (fallbackError: Throwable) {
                    try { fallback.close() } catch (_: Exception) { }
                    fallbackError.addSuppressed(boundError)
                    throw IOException("Wi-Fi socket binding was denied and default route could not reach the watch; check VPN and local-network permission: ${fallbackError.message}", fallbackError)
                }
            }
        }
        socket.use { s ->
            s.soTimeout = WIFI_SOCKET_TIMEOUT_MS
            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            WifiFileProtocol.authenticateClient(input, output, key, id)
            val writer = WifiFileProtocol.writer(output, key, id)
            if (operation == "upload") {
                val source = upload ?: throw IOException("Phone upload source missing")
                source.inputStream().use { stream ->
                    val buffer = ByteArray(WifiFileProtocol.CHUNK_SIZE)
                    while (true) { val n = stream.read(buffer); if (n < 0) break; if (n > 0) writer.write(WifiFileProtocol.DATA, buffer.copyOf(n)) }
                    writer.write(WifiFileProtocol.END)
                }
            }
            val target = if (operation == "download") download ?: throw IOException("Phone download staging file missing") else null
            target?.outputStream()?.use { sink ->
                val reader = WifiFileProtocol.reader(input, key, id)
                while (true) {
                    val record = reader.read()
                    when (record.type) {
                        WifiFileProtocol.DATA -> sink.write(record.payload)
                        WifiFileProtocol.RESULT -> return parseWifiResult(record.payload)
                        else -> throw IOException("Unexpected Wi-Fi transfer record")
                    }
                }
            }
            val reader = WifiFileProtocol.reader(input, key, id)
            while (true) {
                val record = reader.read()
                if (record.type == WifiFileProtocol.RESULT) return parseWifiResult(record.payload)
                if (record.type != WifiFileProtocol.DATA) throw IOException("Unexpected Wi-Fi transfer record")
            }
        }
    }

    private fun parseWifiResult(payload: ByteArray): Pair<Int, String> {
        if (payload.size < 4) throw IOException("Invalid Wi-Fi transfer result")
        return java.nio.ByteBuffer.wrap(payload).int to payload.copyOfRange(4, payload.size).toString(StandardCharsets.UTF_8)
    }

    private suspend fun openStreamSession(mode: String): JSONObject {
        pruneClosedStreamSessions()
        if (streamSessions.values.any { !it.closed.get() }) throw IllegalStateException("A Termux STREAM session is already active")
        val node = Wearable.getNodeClient(this).connectedNodes.await().firstOrNull() ?: throw IllegalStateException("No connected Wear OS watch")
        val id = UUID.randomUUID().toString()
        val ready = CompletableDeferred<JSONObject>()
        pendingStreamReady[id] = ready
        val client = Wearable.getChannelClient(this)
        var channel: ChannelClient.Channel? = null
        var input: java.io.InputStream? = null
        var output: java.io.OutputStream? = null
        var openedSession: ApiStreamSession? = null
        try {
            val path = if (mode == "root") "/terminal/root/api/$id" else "/terminal/api/$id"
            channel = client.openChannel(node.id, path).await() ?: throw IOException("Could not open terminal channel")
            input = client.getInputStream(channel).await()
            output = client.getOutputStream(channel).await()
            val readyResult = withTimeout(STREAM_OPEN_TIMEOUT_MS) { ready.await() }
            if (!readyResult.optBoolean("ok", false)) throw IOException(readyResult.optString("error", "watch rejected STREAM session"))
            val session = ApiStreamSession(id, mode, node.id, client, channel, input, output)
            openedSession = session
            streamSessions[id] = session
            workers.execute { readStreamOutput(session) }
            workers.execute { watchStreamHeartbeat(session) }
            return JSONObject().put("sessionId", id).put("state", "connected").put("mode", mode)
        } catch (t: Throwable) {
            val opened = openedSession
            if (opened != null) {
                streamSessions.remove(id, opened)
                closeStreamSession(opened)
            } else {
                try { input?.close() } catch (_: Exception) { }
                try { output?.close() } catch (_: Exception) { }
                if (channel != null) try { client.close(channel).await() } catch (_: Exception) { }
            }
            throw t
        } finally { pendingStreamReady.remove(id) }
    }

    private fun readStreamOutput(session: ApiStreamSession) {
        val buffer = ByteArray(8192)
        try {
            while (!session.closed.get()) {
                val count = session.input.read(buffer)
                if (count < 0) break
                if (count > 0) session.append(buffer, count)
            }
        } catch (e: Exception) { if (!session.closed.get()) session.error = e.message ?: e.javaClass.simpleName }
        finally {
            session.closed.set(true)
            closeStreamSession(session)
        }
    }

    private fun watchStreamHeartbeat(session: ApiStreamSession) {
        while (!session.closed.get()) {
            try { Thread.sleep(STREAM_HEARTBEAT_CHECK_MS) } catch (_: InterruptedException) { return }
            if (System.currentTimeMillis() - session.lastAccess > STREAM_IDLE_TIMEOUT_MS) {
                session.error = "STREAM client stopped polling; session closed after idle timeout"
                closeStreamSession(session)
                return
            }
        }
    }

    private fun closeStreamSession(session: ApiStreamSession) {
        if (session.disposed.compareAndSet(false, true)) {
            session.closed.set(true)
            try { session.input.close() } catch (_: Exception) { }
            try { session.output.close() } catch (_: Exception) { }
            synchronized(session.outputMonitor) { session.outputMonitor.notifyAll() }
            try { runBlocking { session.client.close(session.channel).await() } } catch (_: Exception) { }
        }
    }

    /** Keep a small tail of completed STREAM output so callers can drain it after disconnect. */
    private fun pruneClosedStreamSessions() {
        val completed = streamSessions.values
            .filter { it.closed.get() }
            .sortedByDescending { it.lastAccess }
        completed.drop(MAX_RETAINED_CLOSED_STREAM_SESSIONS - 1).forEach { session ->
            if (streamSessions.remove(session.id, session)) closeStreamSession(session)
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        try { events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            val path = event.dataItem.uri.path ?: return@forEach
            if (!path.startsWith("/wearbridge/api-transfer/response/")) return@forEach
            val data = DataMapItem.fromDataItem(event.dataItem).dataMap
            val id = data.getString("id") ?: path.substringAfterLast('/')
            val waiter = pendingTransfers[id]
            if (waiter != null) waiter.complete(data)
            else try { Wearable.getDataClient(this@TermuxApiService).deleteDataItems(event.dataItem.uri, DataClient.FILTER_LITERAL) } catch (_: Exception) { }
        } } finally { events.release() }
    }

    private fun respondFile(socket: java.net.Socket, input: java.io.InputStream, length: Long) {
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: $length\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
        val output = socket.getOutputStream(); output.write(head.toByteArray(StandardCharsets.US_ASCII))
        val buffer = ByteArray(64 * 1024); var remaining = length
        while (remaining > 0) { val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); if (n < 0) throw java.io.EOFException("asset ended early"); output.write(buffer, 0, n); remaining -= n }
        output.flush()
    }

    private suspend fun runJob(job: ApiJob) {
        val start = synchronized(job) {
            if (job.state == "cancel_requested" || job.state == "cancelled") {
                job.state = "cancelled"
                job.stage = "cancelled_before_start"
                job.updatedAt = System.currentTimeMillis()
                job.lastActivityAt = job.updatedAt
                persistJob(job)
                false
            } else {
            job.state = "running"; job.stage = "dispatching"; job.updatedAt = System.currentTimeMillis(); job.lastActivityAt = job.updatedAt; persistJob(job)
                true
            }
        }
        if (!start) return
        RequestTrace.record(this, job.id, "job_started", "mode=${job.mode}")
        try {
            val (_, result) = executeOnWatch(job.command, job.mode, waitMillis = null, requestId = job.id)
            job.output = result.take(MAX_JOB_OUTPUT_CHARS)
            job.truncated = result.length > MAX_JOB_OUTPUT_CHARS || result.contains("[WearBridge output truncated at 2 MiB]")
            job.exitCode = Regex("(?:^|\\n)exitCode=(-?\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull()
            job.state = if (job.state == "cancel_requested" && Regex("(?:^|\\n)exitCode=(130|137|143)(?:\\n|$)").containsMatchIn(result)) "cancelled" else "completed"
            job.stage = if (job.state == "cancelled") "cancelled" else "completed"
            RequestTrace.record(this, job.id, "job_completed", "state=${job.state} exitCode=${job.exitCode ?: "unknown"} outputBytes=${result.toByteArray(StandardCharsets.UTF_8).size}")
        } catch (cancelled: CancellationException) {
            job.error = "API worker stopped"
            job.state = "interrupted"
            job.stage = "interrupted"
            throw cancelled
        } catch (e: Exception) {
            job.error = e.message ?: e.javaClass.simpleName
            job.state = if (job.state == "cancel_requested") "interrupted" else "failed"
            job.stage = job.state
            RequestTrace.record(this, job.id, "job_failed", e.javaClass.simpleName)
        } finally {
            job.updatedAt = System.currentTimeMillis(); job.lastActivityAt = job.updatedAt; persistJob(job)
            pruneJobHistory()
        }
    }

    private fun loadJobs() {
        val dir = java.io.File(filesDir, JOB_DIR)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        files.drop(MAX_RETAINED_JOBS).forEach { it.delete() }
        var remainingOutputChars = MAX_RETAINED_JOB_OUTPUT_CHARS
        files.take(MAX_RETAINED_JOBS).forEach { file ->
            try {
                val json = JSONObject(file.readText())
                val job = ApiJob(json.getString("jobId"), json.optString("mode", "shell"), json.optString("command"))
                job.state = json.optString("state", "interrupted").let { if (it == "running" || it == "queued" || it == "cancel_requested") "interrupted" else it }
                job.createdAt = json.optLong("createdAt", file.lastModified()); job.updatedAt = json.optLong("updatedAt", file.lastModified())
                job.stage = json.optString("stage", job.state); job.lastActivityAt = json.optLong("lastActivityAt", job.updatedAt)
                val storedOutput = json.optString("output")
                job.output = storedOutput.takeLast(remainingOutputChars.coerceAtLeast(0))
                if (job.output.length < storedOutput.length) job.truncated = true
                remainingOutputChars = (remainingOutputChars - job.output.length).coerceAtLeast(0)
                job.exitCode = if (json.isNull("exitCode")) null else json.optInt("exitCode")
                job.error = json.optString("error").takeIf { it.isNotBlank() && it != "null" }
                job.truncated = json.optBoolean("truncated") || storedOutput.length > job.output.length
                jobs[job.id] = job
            } catch (_: Exception) { file.delete() }
        }
        dir.listFiles { f -> f.isFile && f.name.endsWith(".tmp") }?.forEach { it.delete() }
        pruneJobHistory()
    }

    /** Keep completed async results useful without retaining every command and output forever. */
    private fun pruneJobHistory() {
        synchronized(jobRetentionLock) {
            val terminalJobs = jobs.values
                .filter { it.state in TERMINAL_JOB_STATES }
                .sortedBy { it.createdAt }
            var retainedOutputChars = terminalJobs.sumOf { it.output.length.toLong() }
            var retainedCount = terminalJobs.size
            for (job in terminalJobs) {
                if (retainedCount <= MAX_RETAINED_JOBS && retainedOutputChars <= MAX_RETAINED_JOB_OUTPUT_CHARS) break
                if (jobs.remove(job.id, job)) {
                    java.io.File(java.io.File(filesDir, JOB_DIR), "${job.id}.json").delete()
                    retainedCount--
                    retainedOutputChars -= job.output.length
                }
            }
        }
    }

    private fun persistJob(job: ApiJob) {
        try {
            val dir = java.io.File(filesDir, JOB_DIR).apply { mkdirs() }
            val json = JSONObject().put("jobId", job.id).put("mode", job.mode).put("command", job.command)
                .put("state", job.state).put("stage", job.stage).put("createdAt", job.createdAt)
                .put("updatedAt", job.updatedAt).put("lastActivityAt", job.lastActivityAt)
                .put("exitCode", job.exitCode).put("error", job.error).put("output", job.output).put("truncated", job.truncated)
            val temp = java.io.File(dir, "${job.id}.tmp"); temp.writeText(json.toString())
            temp.renameTo(java.io.File(dir, "${job.id}.json"))
        } catch (_: Exception) { }
    }

    private suspend fun cancelWatchExecution(id: String) {
        RequestTrace.record(this, id, "cancel_transport_wait_start")
        val node = Wearable.getNodeClient(this).connectedNodes.await().firstOrNull() ?: throw IllegalStateException("Watch is disconnected; cancellation was not delivered")
        Wearable.getMessageClient(this).sendMessage(node.id, "/exec/cancel", id.toByteArray(StandardCharsets.UTF_8)).await()
        RequestTrace.record(this, id, "cancel_transport_ack", "node=${node.displayName}")
    }

    private suspend fun executeOnWatch(command: String, mode: String, waitMillis: Long? = 120_000, requestId: String = UUID.randomUUID().toString()): Pair<String, String> {
        RequestTrace.record(this, requestId, "watch_node_lookup_start", "mode=$mode")
        val node = Wearable.getNodeClient(this).connectedNodes.await().firstOrNull()
            ?: throw IllegalStateException("No connected Wear OS watch")
        RequestTrace.record(this, requestId, "watch_node_found", "node=${node.displayName}")
        setJobStage(requestId, "sending")
        val id = requestId
        val reply = CompletableDeferred<String>()
        pending[id] = reply
        try {
            val payload = "$id\n$mode\n$command".toByteArray(StandardCharsets.UTF_8)
            RequestTrace.record(this, id, "data_layer_send_start", "path=/exec/api payloadBytes=${payload.size}")
            Wearable.getMessageClient(this).sendMessage(node.id, "/exec/api", payload).await()
            RequestTrace.record(this, id, "data_layer_send_ack", "node=${node.displayName}")
            setJobStage(id, "waiting_for_watch_result")
            val result = if (waitMillis == null) reply.await() else try {
                withTimeout(waitMillis) { reply.await() }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                RequestTrace.record(this, id, "watch_result_timeout", "waitMs=$waitMillis")
                setJobStage(id, "timed_out_indeterminate")
                try { cancelWatchExecution(id) } catch (e: Exception) { RequestTrace.record(this, id, "timeout_cancel_failed", e.javaClass.simpleName) }
                throw java.util.concurrent.TimeoutException("watch response timed out; cancellation was sent if the watch remained connected, but command state is indeterminate")
            }
            RequestTrace.record(this, id, "watch_result_delivered", "responseBytes=${result.toByteArray(StandardCharsets.UTF_8).size}")
            setJobStage(id, "result_received")
            return id to result
        } finally { pending.remove(id); resultChunks.remove(id) }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == "/stream/api-ready" || event.path == "/stream/api-error") {
            try {
                val message = JSONObject(String(event.data, StandardCharsets.UTF_8))
                pendingStreamReady[message.optString("sessionId")]?.complete(message)
            } catch (_: Exception) { }
            return
        }
        if (event.path == "/file-transfer/ready") {
            try {
                val message = JSONObject(String(event.data, StandardCharsets.UTF_8))
                pendingWifiReady[message.optString("id")]?.complete(message)
            } catch (_: Exception) { }
            return
        }
        if (event.path == "/result/chunk") { receiveResultChunk(event.data); return }
        if (event.path != "/result") return
        val payload = String(event.data, StandardCharsets.UTF_8)
        val first = payload.substringBefore('\n')
        val id = first.removePrefix("[").removeSuffix("]")
        RequestTrace.record(this, id, "data_layer_result_received", "payloadBytes=${event.data.size}")
        pending[id]?.complete(payload.substringAfter('\n', ""))
    }

    private fun receiveResultChunk(data: ByteArray) {
        val first = findByte(data, 10, 0); if (first <= 0) return
        val second = findByte(data, 10, first + 1); if (second < 0) return
        val third = findByte(data, 10, second + 1); if (third < 0) return
        val id = String(data, 0, first, StandardCharsets.US_ASCII)
        if (pending[id] == null) return
        val index = String(data, first + 1, second - first - 1, StandardCharsets.US_ASCII).toIntOrNull() ?: return
        val total = String(data, second + 1, third - second - 1, StandardCharsets.US_ASCII).toIntOrNull() ?: return
        if (total !in 1..MAX_RESULT_CHUNKS || index !in 0 until total) return
        RequestTrace.record(this, id, "data_layer_result_chunk", "index=$index total=$total bytes=${data.size}")
        val assembled = resultChunks.computeIfAbsent(id) { ResultChunks(total) }.add(index, total, data.copyOfRange(third + 1, data.size)) ?: return
        resultChunks.remove(id)
        RequestTrace.record(this, id, "data_layer_chunks_assembled", "chunks=$total bytes=${assembled.size}")
        pending[id]?.complete(String(assembled, StandardCharsets.UTF_8))
    }

    private fun respond(socket: java.net.Socket, code: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        val phrase = when (code) { 200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"; 413 -> "Payload Too Large"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"; 504 -> "Gateway Timeout"; else -> "Error" }
        val head = "HTTP/1.1 $code $phrase\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
        socket.getOutputStream().apply { write(head.toByteArray(StandardCharsets.US_ASCII)); write(bytes); flush() }
    }

    private fun findByte(bytes: ByteArray, value: Int, from: Int): Int {
        for (i in from until bytes.size) if (bytes[i].toInt() == value) return i
        return -1
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
        unregisterWearListeners()
        workers.shutdownNow()
        pending.values.forEach { it.completeExceptionally(IllegalStateException("API stopped")) }
        resultChunks.clear()
        pendingTransfers.values.forEach { it.completeExceptionally(IllegalStateException("API stopped")) }
        pendingWifiReady.values.forEach { it.completeExceptionally(IllegalStateException("API stopped")) }
        pendingStreamReady.values.forEach { it.completeExceptionally(IllegalStateException("API stopped")) }
        streamSessions.values.forEach { closeStreamSession(it) }
        streamSessions.clear()
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
        private const val MAX_HTTP_HEADER_BYTES = 256 * 1024
        // Leave room for request id, mode and framing under MessageClient's ~100 KB limit.
        private const val MAX_COMMAND_BYTES = 64 * 1024
        private const val MAX_JOB_OUTPUT_CHARS = 4 * 1024 * 1024
        private const val MAX_RETAINED_JOBS = 25
        private const val MAX_RETAINED_JOB_OUTPUT_CHARS = 8 * 1024 * 1024
        private const val MAX_WORKER_THREADS = 8
        private const val MAX_QUEUED_WORK = 32
        private const val WORKER_KEEP_ALIVE_SECONDS = 30L
        private val workerThreadIds = java.util.concurrent.atomic.AtomicInteger()
        private val TERMINAL_JOB_STATES = setOf("completed", "failed", "cancelled", "interrupted")
        private const val JOB_DIR = "termux-api-jobs"
        private const val MAX_TRANSFER_BYTES = 1024L * 1024L * 1024L
        private const val TRANSFER_TIMEOUT_MS = 10L * 60L * 1000L
        private const val WIFI_ENDPOINT_TIMEOUT_MS = 120_000L
        private const val WIFI_SOCKET_TIMEOUT_MS = 300_000
        private const val STREAM_OPEN_TIMEOUT_MS = 30_000L
        private const val STREAM_IDLE_TIMEOUT_MS = 120_000L
        private const val STREAM_HEARTBEAT_CHECK_MS = 10_000L
        private const val MAX_STREAM_OUTPUT_BYTES = 1024 * 1024
        private const val MAX_STREAM_OUTPUT_CHUNK = 24 * 1024
        private const val MAX_RETAINED_CLOSED_STREAM_SESSIONS = 2
        private const val MAX_STREAM_INPUT_BYTES = 64 * 1024
        private const val MAX_RESULT_CHUNKS = 512
    }

    private class ApiStreamSession(
        val id: String,
        val mode: String,
        val nodeId: String,
        val client: ChannelClient,
        val channel: ChannelClient.Channel,
        val input: java.io.InputStream,
        val output: java.io.OutputStream
    ) {
        val closed = AtomicBoolean(false)
        val disposed = AtomicBoolean(false)
        val outputMonitor = Object()
        private val bytes = ByteArrayOutputStream()
        private var baseOffset = 0L
        private var endOffset = 0L
        @Volatile var lastAccess = System.currentTimeMillis()
        @Volatile var error: String? = null
        val outputLock = Object()

        fun append(source: ByteArray, count: Int) {
            synchronized(outputMonitor) {
                bytes.write(source, 0, count); endOffset += count
                if (bytes.size() > MAX_STREAM_OUTPUT_BYTES) {
                    val all = bytes.toByteArray(); val discard = all.size - MAX_STREAM_OUTPUT_BYTES
                    bytes.reset(); bytes.write(all, discard, all.size - discard); baseOffset += discard
                }
                outputMonitor.notifyAll()
            }
        }

        fun outputAfter(requestedOffset: Long): JSONObject {
            lastAccess = System.currentTimeMillis()
            synchronized(outputMonitor) {
                var cursor = requestedOffset.coerceAtLeast(0L)
                if (cursor >= endOffset && !closed.get()) try { outputMonitor.wait(250L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                val all = bytes.toByteArray()
                val dropped = cursor < baseOffset
                if (dropped) cursor = baseOffset
                val start = (cursor - baseOffset).coerceIn(0L, all.size.toLong()).toInt()
                val length = minOf(MAX_STREAM_OUTPUT_CHUNK, all.size - start)
                val chunk = if (length > 0) all.copyOfRange(start, start + length) else byteArrayOf()
                val next = cursor + chunk.size
                return JSONObject().put("sessionId", id).put("data", Base64.encodeToString(chunk, Base64.NO_WRAP))
                    .put("cursor", next).put("dropped", dropped).put("closed", closed.get())
                    .put("error", error)
            }
        }
    }

    private fun setJobStage(id: String, stage: String) {
        val job = jobs[id] ?: return
        synchronized(job) {
            job.stage = stage
            job.lastActivityAt = System.currentTimeMillis()
            persistJob(job)
        }
    }

    private class ApiJob(val id: String, val mode: String, val command: String) {
        @Volatile var state = "queued"
        @Volatile var stage = "queued"
        @Volatile var createdAt = System.currentTimeMillis()
        @Volatile var updatedAt = createdAt
        @Volatile var lastActivityAt = createdAt
        @Volatile var exitCode: Int? = null
        @Volatile var error: String? = null
        @Volatile var output: String = ""
        @Volatile var truncated = false
    }

    private class ResultChunks(private val expectedTotal: Int) {
        private val chunks = arrayOfNulls<ByteArray>(expectedTotal)
        private var received = 0
        @Synchronized fun add(index: Int, total: Int, chunk: ByteArray): ByteArray? {
            if (total != expectedTotal || chunk.size > RESULT_CHUNK_BYTES) return null
            if (chunks[index] == null) { chunks[index] = chunk; received++ }
            if (received != expectedTotal) return null
            val size = chunks.sumOf { it?.size ?: 0 }
            val output = ByteArray(size); var offset = 0
            for (part in chunks) { val bytes = part ?: return null; bytes.copyInto(output, offset); offset += bytes.size }
            return output
        }
        companion object { const val RESULT_CHUNK_BYTES = 48 * 1024 }
    }
}
