// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.utils.prefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * MCP servers as plugins. The person adds a server (its web address, and a key if it needs one).
 * The keyboard asks it for its tools, and each tool becomes a "/" command. Nothing is sent to a
 * server until the person taps "Ask" on a command, and then only what was typed after it.
 *
 * Speaks the MCP "Streamable HTTP" transport with plain JSON-RPC, no extra library.
 */
object McpServers {
    const val PREF = "open_keyboard_mcp_servers"

    class Server(val name: String, val url: String, val token: String) {
        val id get() = "mcp:$url"
    }

    class Tool(val server: Server, val name: String, val description: String, val schema: JSONObject?)

    fun load(ctx: Context): List<Server> = runCatching {
        val a = JSONArray(ctx.prefs().getString(PREF, "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Server(o.getString("name"), o.getString("url"), o.optString("token")) } }
    }.getOrDefault(emptyList())

    fun save(ctx: Context, list: List<Server>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("name", it.name).put("url", it.url).put("token", it.token)) }
        ctx.prefs().edit().putString(PREF, a.toString()).apply()
    }

    @Volatile var tools: List<Tool> = emptyList(); private set
    /** Why a server couldn't be reached, by server url, for the settings screen. */
    val problems = ConcurrentHashMap<String, String>()
    private val main = Handler(Looper.getMainLooper())
    private val sessions = ConcurrentHashMap<String, String>()

    /** Asks every server that is switched on for its tools, and turns them into "/" commands. */
    private var lastRefresh = 0L

    fun refresh(ctx: Context, force: Boolean = false, done: (() -> Unit)? = null) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < 30 * 60_000) return
        lastRefresh = now
        val app = ctx.applicationContext
        val servers = load(app).filter { Plugins.isOn(app, "mcp") && Plugins.isOn(app, it.id) }
        if (servers.isEmpty() && tools.isEmpty()) { SlashCommands.setExternal("mcp", emptyList()); done?.invoke(); return }
        thread(name = "mcp-refresh") {
            val found = mutableListOf<Tool>()
            for (s in servers) {
                try {
                    val r = request(s, "tools/list", JSONObject())
                    val list = r.optJSONArray("tools") ?: JSONArray()
                    for (i in 0 until list.length()) {
                        val t = list.getJSONObject(i)
                        found += Tool(s, t.getString("name"), t.optString("description"), t.optJSONObject("inputSchema"))
                    }
                    problems.remove(s.url)
                } catch (e: Exception) {
                    problems[s.url] = e.message ?: e.javaClass.simpleName
                }
            }
            tools = found
            main.post {
                SlashCommands.setExternal("mcp", found.mapNotNull { t ->
                    val name = commandName(t.name).ifEmpty { return@mapNotNull null }
                    SlashCommands.Command(name, ("/$name " + requiredStrings(t.schema).ifEmpty { listOfNotNull(firstStringArg(t.schema)) }.joinToString(" ")).trim(), remote = "mcp") { null }
                })
                done?.invoke()
            }
        }
    }

    fun commandName(tool: String) = tool.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    /** A field that takes text: "string", a list of types with "string" in it, or anyOf/oneOf with a string. */
    private fun isText(p: JSONObject?): Boolean {
        if (p == null) return false
        if (p.optString("type") == "string") return true
        p.optJSONArray("type")?.let { a -> if ((0 until a.length()).any { a.optString(it) == "string" }) return true }
        for (k in listOf("anyOf", "oneOf")) p.optJSONArray(k)?.let { a ->
            if ((0 until a.length()).any { isText(a.optJSONObject(it)) }) return true
        }
        return false
    }

    private fun requiredStrings(schema: JSONObject?): List<String> {
        val props = schema?.optJSONObject("properties") ?: return emptyList()
        val required = schema.optJSONArray("required") ?: return emptyList()
        return (0 until required.length()).map { required.getString(it) }
            .filter { isText(props.optJSONObject(it)) }
    }

    private fun firstStringArg(schema: JSONObject?): String? {
        val props = schema?.optJSONObject("properties") ?: return null
        val required = schema.optJSONArray("required")
        val names = props.keys().asSequence().toList()
        val req = (0 until (required?.length() ?: 0)).map { required!!.getString(it) }
        return (req + names).firstOrNull { isText(props.optJSONObject(it)) }
    }

    /** Turns what was typed after the command into the tool's arguments. */
    private fun arguments(tool: Tool, typed: String): JSONObject {
        val args = JSONObject()
        val pairs = Regex("(\\w+)=(\"[^\"]*\"|\\S+)").findAll(typed).toList()
        val props = tool.schema?.optJSONObject("properties")
        if (pairs.isNotEmpty() && props != null && pairs.all { props.has(it.groupValues[1]) }) {
            pairs.forEach { m ->
                val v = m.groupValues[2].trim('"')
                when (props.optJSONObject(m.groupValues[1])?.optString("type")) {
                    "number" -> v.toDoubleOrNull()?.let { args.put(m.groupValues[1], it) }
                    "integer" -> v.toLongOrNull()?.let { args.put(m.groupValues[1], it) }
                    "boolean" -> args.put(m.groupValues[1], v.equals("true", true) || v == "yes")
                    else -> args.put(m.groupValues[1], v)
                }
            }
        } else if (typed.isNotBlank()) {
            // two required text fields (like "repo" and "question"): the first word fills the first,
            // the rest fills the second, so "/askquestion owner/repo how does it work" just works
            val req = requiredStrings(tool.schema)
            val words = typed.trim().split(Regex("\\s+"), limit = 2)
            if (req.size >= 2 && words.size == 2) { args.put(req[0], words[0]); args.put(req[1], words[1]) }
            else firstStringArg(tool.schema)?.let { args.put(it, typed) }
        }
        return args
    }

    // ---------- answers ----------

    private val answers = ConcurrentHashMap<String, String>()
    private val asking = ConcurrentHashMap.newKeySet<String>()

    /** The answer for this command if it was asked, [SlashCommands.WAITING] while asking, or null. */
    fun cached(name: String, args: String): String? {
        val key = "$name/$args"
        if (key in asking) return SlashCommands.WAITING
        return answers[key]
    }

    /** Sends the command to its server. [onAnswer] runs on the main thread when it's back. */
    fun ask(name: String, args: String, onAnswer: () -> Unit) {
        val key = "$name/$args"
        val tool = tools.firstOrNull { commandName(it.name) == name } ?: return
        if (!asking.add(key)) return
        thread(name = "mcp-call") {
            val text = try {
                val r = request(tool.server, "tools/call", JSONObject().put("name", tool.name).put("arguments", arguments(tool, args)))
                val content = r.optJSONArray("content") ?: JSONArray()
                val parts = (0 until content.length()).map { content.getJSONObject(it) }
                    .filter { it.optString("type") == "text" }.map { it.optString("text") }
                val joined = parts.joinToString("\n").trim()
                if (r.optBoolean("isError")) "Error: $joined" else joined.ifEmpty { "No answer" }
            } catch (e: Exception) {
                "Couldn't reach ${tool.server.name}: ${e.message ?: e.javaClass.simpleName}"
            }
            if (answers.size > 30) answers.clear()
            answers[key] = text.take(5000)
            asking.remove(key)
            main.post(onAnswer)
        }
    }

    // ---------- transport ----------

    private var nextId = 1

    private fun request(s: Server, method: String, params: JSONObject): JSONObject {
        if (sessions[s.url] == null) initialize(s)
        return try {
            rpc(s, method, params)
        } catch (e: SessionExpired) {
            sessions.remove(s.url); initialize(s); rpc(s, method, params)
        }
    }

    private class SessionExpired : Exception()

    private fun initialize(s: Server) {
        rpc(s, "initialize", JSONObject()
            .put("protocolVersion", "2025-06-18")
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "open-keyboard").put("version", "1")))
        post(s, JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), expectReply = false)
    }

    private fun rpc(s: Server, method: String, params: JSONObject): JSONObject {
        val id = synchronized(this) { nextId++ }
        val msg = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val reply = post(s, msg, expectReply = true) ?: throw IllegalStateException("no reply")
        reply.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message", "error")) }
        return reply.optJSONObject("result") ?: JSONObject()
    }

    private fun post(s: Server, body: JSONObject, expectReply: Boolean): JSONObject? {
        val url = URL(s.url)
        require(url.protocol == "https" || url.host == "localhost" || url.host == "127.0.0.1") { "the address must start with https://" }
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 30000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json, text/event-stream")
            c.setRequestProperty("MCP-Protocol-Version", "2025-06-18")
            if (s.token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${s.token}")
            sessions[s.url]?.let { c.setRequestProperty("Mcp-Session-Id", it) }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code == 404 && sessions[s.url] != null) throw SessionExpired()
            if (code == 401 || code == 403) throw IllegalStateException("the server said no (check the key)")
            if (code >= 400) throw IllegalStateException("server error $code")
            c.getHeaderField("Mcp-Session-Id")?.let { sessions[s.url] = it }
            if (!expectReply || code == 202) return null
            val type = c.contentType.orEmpty()
            val text = c.inputStream.bufferedReader().use { r ->
                if (!type.startsWith("text/event-stream")) return@use r.readText()
                // read events until the one that answers our request
                val wanted = body.opt("id")
                var data = StringBuilder()
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    val l = line!!
                    if (l.startsWith("data:")) data.append(l.removePrefix("data:").trim())
                    else if (l.isEmpty() && data.isNotEmpty()) {
                        val o = runCatching { JSONObject(data.toString()) }.getOrNull()
                        data = StringBuilder()
                        if (o != null && o.opt("id")?.toString() == wanted?.toString()) return@use o.toString()
                    }
                }
                data.toString()
            }
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }
}
