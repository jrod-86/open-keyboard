// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import helium314.keyboard.latin.utils.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Open Keyboard (test 226, Jarrod: "the keyboard as the phone's AI hub"): an MCP server, so AI apps on this phone can
 * use the keyboard's on-phone AI (write a message, rewrite text in a style, see which models are downloaded).
 * - Off until switched on in Settings, Plugins.
 * - Only apps on this phone can reach it (it listens on 127.0.0.1, never the network), and only with the key.
 * - Light: one sleeping thread while nobody asks. The writing itself runs in Chat's process ([WriterService]).
 * Speaks MCP's Streamable HTTP transport with plain JSON answers, at http://127.0.0.1:[PORT]/mcp.
 */
object KeyboardMcpServer {
    private const val TAG = "KeyboardMcp"
    const val PORT = 8765
    const val ADDRESS = "http://127.0.0.1:$PORT/mcp"
    private const val PREFS = "open_keyboard_mcp_server"
    private const val PROTOCOL = "2025-06-18"

    @Volatile private var socket: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(2)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("on", false)
    val running get() = socket?.isClosed == false

    /** The key apps must send ("Authorization: Bearer <key>"). Made once, new one with [newKey]. */
    fun key(ctx: Context): String = prefs(ctx).getString("key", null) ?: newKey(ctx)
    fun newKey(ctx: Context): String {
        val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
        val k = bytes.joinToString("") { "%02x".format(it) }
        prefs(ctx).edit().putString("key", k).apply()
        return k
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("on", on).apply()
        if (on) start(ctx) else stop()
    }

    /** Starts listening if switched on (called when the keyboard starts). */
    fun startIfEnabled(ctx: Context) { if (isEnabled(ctx)) start(ctx) }

    @Synchronized private fun start(ctx: Context) {
        if (running) return
        val app = ctx.applicationContext
        val server = runCatching { ServerSocket(PORT, 4, InetAddress.getLoopbackAddress()) }
            .onFailure { Log.w(TAG, "couldn't start on port $PORT", it) }.getOrNull() ?: return
        socket = server
        thread(name = "keyboard-mcp", isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                workers.execute { runCatching { client.use { handle(app, it) } }.onFailure { Log.w(TAG, "request failed", it) } }
            }
        }
        Log.i(TAG, "MCP server on $ADDRESS")
    }

    @Synchronized fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    // ---------- HTTP ----------

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun handle(ctx: Context, s: Socket) {
        s.soTimeout = 15_000
        val input = BufferedInputStream(s.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val (method, path) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        fun reply(code: Int, status: String, body: String = "", type: String = "application/json") {
            val bytes = body.toByteArray()
            val head = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            s.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
        }
        if (path.substringBefore('?') != "/mcp") return reply(404, "Not Found")
        // only apps that know the key
        if (headers["authorization"] != "Bearer ${key(ctx)}") return reply(401, "Unauthorized", """{"error":"Wrong or missing key. Copy it from O-Board's Settings, Open Hub."}""")
        if (method == "GET") return reply(405, "Method Not Allowed") // no server-sent events
        if (method == "DELETE") return reply(200, "OK")
        if (method != "POST") return reply(405, "Method Not Allowed")
        val length = headers["content-length"]?.toIntOrNull() ?: return reply(411, "Length Required")
        if (length > 200_000) return reply(413, "Too Large")
        val body = ByteArray(length)
        var read = 0
        while (read < length) { val n = input.read(body, read, length - read); if (n < 0) break; read += n }
        s.soTimeout = 0
        val msg = runCatching { JSONObject(String(body, 0, read)) }.getOrNull()
            ?: return reply(400, "Bad Request", error(null, -32700, "Parse error").toString())
        val answer = rpc(ctx, msg) ?: return reply(202, "Accepted") // a notification: nothing to answer
        reply(200, "OK", answer.toString())
    }

    // ---------- MCP ----------

    private fun error(id: Any?, code: Int, message: String) = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))

    private fun result(id: Any?, r: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", r)

    private fun rpc(ctx: Context, msg: JSONObject): JSONObject? {
        val id = msg.opt("id")
        val method = msg.optString("method")
        if (id == null || id == JSONObject.NULL) return null
        return when (method) {
            "initialize" -> result(id, JSONObject()
                .put("protocolVersion", PROTOCOL)
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject().put("name", "open-keyboard").put("version", "1"))
                .put("instructions", "O-Board's on-phone AI. Everything runs on this phone; nothing is sent anywhere."))
            "ping" -> result(id, JSONObject())
            "tools/list" -> result(id, JSONObject().put("tools", tools()))
            "tools/call" -> {
                val p = msg.optJSONObject("params") ?: JSONObject()
                val (text, isError) = call(ctx, p.optString("name"), p.optJSONObject("arguments") ?: JSONObject())
                result(id, JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))).put("isError", isError))
            }
            else -> error(id, -32601, "Method not found: $method")
        }
    }

    private fun schema(vararg props: Triple<String, String, Boolean>, extra: (JSONObject) -> Unit = {}) = JSONObject().apply {
        put("type", "object")
        val o = JSONObject()
        props.forEach { (name, desc, _) -> o.put(name, JSONObject().put("type", "string").put("description", desc)) }
        extra(o)
        put("properties", o)
        put("required", JSONArray(props.filter { it.third }.map { it.first }))
    }

    private fun tools() = JSONArray()
        .put(JSONObject().put("name", "write")
            .put("description", "Write a message, reply, caption, poem and so on from a short request, with the on-phone writing model.")
            .put("inputSchema", schema(Triple("request", "What to write, e.g. \"a happy birthday message for my wife\"", true))))
        .put(JSONObject().put("name", "rewrite")
            .put("description", "Rewrite text in a style: " + WritingTools.styleNames.joinToString(", ") + ".")
            .put("inputSchema", schema(Triple("text", "The text to rewrite", true), Triple("style", "One of the styles", true)) { o ->
                o.getJSONObject("style").put("enum", JSONArray(WritingTools.styleNames))
            }))
        .put(JSONObject().put("name", "list_models")
            .put("description", "Which AI models are downloaded on this phone, their sizes and what uses them.")
            .put("inputSchema", schema()))

    private fun call(ctx: Context, name: String, args: JSONObject): Pair<String, Boolean> = runCatching {
        when (name) {
            "write" -> {
                val request = args.optString("request").trim()
                if (request.isEmpty()) return "Say what to write in \"request\"." to true
                if (!WritingTools.anyDownloaded(ctx)) return "No writing model is downloaded. Get the Writing pack in O-Board's Settings, Open Hub." to true
                val out = runBlocking { withTimeoutOrNull(180_000) { AiWriter.write(ctx, request) } }
                (out ?: "Couldn't write that, please try again.") to (out == null)
            }
            "rewrite" -> {
                val text = args.optString("text").trim()
                val style = args.optString("style").trim()
                if (text.isEmpty()) return "Give the text to rewrite in \"text\"." to true
                if (WritingTools.styleNames.none { it.equals(style, true) }) return "Unknown style. Use one of: " + WritingTools.styleNames.joinToString(", ") to true
                if (!WritingTools.anyDownloaded(ctx)) return "No writing model is downloaded. Get the Writing pack in O-Board's Settings, Open Hub." to true
                val out = runBlocking { withTimeoutOrNull(180_000) { WritingTools.rewrite(ctx, text, style) } }
                (out ?: "Couldn't rewrite that, please try again.") to (out == null)
            }
            "list_models" -> ModelManager.list(ctx).filter { !it.userMade }
                .joinToString("\n") { "${it.name}: ${ModelManager.sizeText(it.bytes)}, used by ${it.usedBy}" }
                .ifEmpty { "No AI models downloaded yet." } to false
            else -> "Unknown tool: $name" to true
        }
    }.getOrElse { ("Failed: " + (it.message ?: it.javaClass.simpleName)) to true }
}
