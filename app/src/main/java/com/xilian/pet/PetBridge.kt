package com.xilian.pet

import android.util.Log
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread

class PetBridge(
    private val service: FloatingPetService,
    private val petView: PetView
) {
    private var serverThread: Thread? = null
    private var running = false

    fun start() {
        running = true
        serverThread = thread(name = "pet-bridge") {
            try {
                val server = ServerSocket(BRIDGE_PORT, 8)
                Log.d(TAG, "Bridge listening on $BRIDGE_PORT")
                while (running) {
                    try {
                        val client = server.accept()
                        thread { handleClient(client) }
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "Accept error: ${e.message}")
                    }
                }
                server.close()
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        try { serverThread?.interrupt() } catch (_: Exception) {}
    }

    private fun handleClient(client: java.net.Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val path = parts[1]

            var contentLength = 0
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                line = reader.readLine()
            }

            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                reader.read(buf, 0, contentLength)
                String(buf)
            } else ""

            val response = route(method, path, body)
            val writer = OutputStreamWriter(client.getOutputStream())
            writer.write("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n")
            writer.write("Content-Length: ${response.toByteArray().size}\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.write(response)
            writer.flush()
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun route(method: String, path: String, body: String): String {
        return try {
            when {
                method == "POST" && path == "/say" -> {
                    val json = JSONObject(body)
                    val text = json.optString("text", "")
                    petView.post { petView.speechText = text }
                    ok()
                }
                method == "POST" && path == "/expression" -> {
                    val json = JSONObject(body)
                    val type = json.optString("type", "NEUTRAL")
                    petView.post {
                        try { petView.expression = PetView.Expression.valueOf(type.uppercase()) }
                        catch (_: Exception) {}
                    }
                    ok()
                }
                method == "POST" && path == "/bubble" -> {
                    val json = JSONObject(body)
                    val text = json.optString("text", "")
                    val exprType = json.optString("expression", null)
                    petView.post {
                        if (exprType != null) {
                            try { petView.expression = PetView.Expression.valueOf(exprType.uppercase()) }
                            catch (_: Exception) {}
                        }
                        petView.speechText = text
                    }
                    ok()
                }
                method == "POST" && path == "/chatting" -> {
                    val json = JSONObject(body)
                    val active = json.optBoolean("active", false)
                    petView.post { petView.isChatting = active }
                    ok()
                }
                method == "GET" && path == "/status" -> {
                    """{"status":"alive","port":$BRIDGE_PORT}"""
                }
                else -> """{"error":"unknown"}"""
            }
        } catch (e: Exception) {
            """{"error":"${e.message}"}"""
        }
    }

    private fun ok() = """{"ok":true}"""

    companion object {
        const val BRIDGE_PORT = 28765
        var backendUrl = "http://127.0.0.1:8000"
        private const val TAG = "PetBridge"

        private fun agentBaseUrl(): String = backendUrl.trimEnd('/')

        /**
         * SSE streaming chat — connects to xilian-agent API on port 8000.
         * Same backend as the web UI.
         */
        fun streamChat(
            text: String,
            onToken: ((String) -> Unit)? = null,
            onComplete: ((String) -> Unit)? = null,
            onError: ((String) -> Unit)? = null
        ) {
            thread {
                try {
                    val url = URL("${agentBaseUrl()}/api/chat/stream")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.connectTimeout = 3000
                    conn.readTimeout = 60000

                    val body = JSONObject().apply {
                        put("message", text)
                        put("user_id", "hezi")
                        put("fast", true)
                    }.toString()
                    conn.outputStream.write(body.toByteArray())

                    val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                    val accumulated = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        if (!l.startsWith("data: ")) continue
                        val chunk = l.substring(6)
                        if (chunk == "[DONE]") {
                            onComplete?.invoke(accumulated.toString())
                            return@thread
                        }
                        accumulated.append(chunk.replace("\\n", "\n"))
                        onToken?.invoke(accumulated.toString())
                    }
                    onComplete?.invoke(accumulated.toString())
                } catch (e: Exception) {
                    onError?.invoke("人家现在连不上后端呢…\n${e.message?.take(80) ?: "agent 未启动"}")
                }
            }
        }

        /** Non-streaming fallback */
        fun sendToTermux(text: String, callback: ((String) -> Unit)? = null) {
            thread {
                try {
                    val url = URL("${agentBaseUrl()}/api/chat")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.connectTimeout = 3000
                    conn.readTimeout = 30000

                    val body = JSONObject().apply {
                        put("message", text)
                        put("user_id", "hezi")
                    }.toString()
                    conn.outputStream.write(body.toByteArray())

                    val resp = conn.inputStream.bufferedReader().readText()
                    val reply = JSONObject(resp).optString("reply", resp)
                    callback?.invoke(reply)
                } catch (e: Exception) {
                    callback?.invoke("人家现在连不上后端呢…\n${e.message?.take(60)}")
                }
            }
        }
    }
}
