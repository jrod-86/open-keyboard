// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.databinding.ClipboardSuggestionBinding
import helium314.keyboard.latin.utils.ToolbarKey
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.random.Random

/**
 * "/" commands: type /calc 12*7, =12*7, /date, /time, /convert 5 km to mi, /shrug, /flip, /roll,
 * /random 1 100 and the answer appears in the suggestion bar. Tap it to replace the command.
 * Built as a small registry so plugins can add commands later.
 */
object SlashCommands {
    class Command(val name: String, val usage: String, val remote: String? = null, val run: (args: String) -> String?)

    /** Shown while a plugin app works on an answer. */
    const val WAITING = "\u2026"

    private val builtIn = mutableListOf<Command>()
    private val external = java.util.concurrent.ConcurrentHashMap<String, List<Command>>()
    private val commands: List<Command> get() {
        val out = builtIn.toMutableList()
        external.values.flatten().forEach { e -> if (out.none { it.name == e.name }) out += e }
        return out
    }

    /** Commands from plugin apps ([CommandPlugins]) or MCP servers ([McpServers]), by source. */
    fun setExternal(source: String, list: List<Command>) { external[source] = list }

    /** Lets other parts of the app (and later plugins) add a command. */
    fun register(command: Command) { builtIn.removeAll { it.name == command.name }; builtIn.add(command) }

    init {
        register(Command("calc", "/calc 12*7") { a -> calc(a)?.let { format(it) } })
        register(Command("date", "/date") { DateFormat.getDateInstance(DateFormat.FULL, Locale.getDefault()).format(Date()) })
        register(Command("time", "/time") { DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date()) })
        register(Command("convert", "/convert 5 km to mi") { a -> convert(a) })
        register(Command("shrug", "/shrug") { "¯\\_(ツ)_/¯" })
        register(Command("tableflip", "/tableflip") { "(╯°□°)╯︵ ┻━┻" })
        register(Command("lenny", "/lenny") { "( ͡° ͜ʖ ͡°)" })
        register(Command("flip", "/flip (a coin)") { if (Random.nextBoolean()) "Heads" else "Tails" })
        register(Command("roll", "/roll (a dice)") { "🎲 ${Random.nextInt(1, 7)}" })
        register(Command("random", "/random 1 100") { a ->
            val n = Regex("-?\\d+").findAll(a).map { it.value.toLong() }.toList()
            val (lo, hi) = when (n.size) { 0 -> 1L to 100L; 1 -> 1L to n[0]; else -> minOf(n[0], n[1]) to maxOf(n[0], n[1]) }
            if (hi < lo) null else Random.nextLong(lo, hi + 1).toString()
        })
    }

    /** What is being typed: the text to replace, what the chip shows, and what tapping inserts (null = hint only). */
    class Match(val replaceLength: Int, val shown: String, val insert: String?, val ask: (() -> kotlin.Unit)? = null)

    // a stray ` before the command (copied from a chat) is allowed
    private val slash = Regex("(?:^|[\\s`])(/([a-z]*)(?:\\s+(.*))?)$")
    private val equals = Regex("(?:^|\\s)(=\\s*([0-9.,+\\-*/x×÷^%() ]*[0-9)]))$")

    /** Whether the cursor is inside a command, so autocorrect leaves it alone. */
    fun inCommand(textBeforeCursor: CharSequence?): Boolean {
        if (!Plugins.isOn("slash")) return false
        val line = textBeforeCursor?.toString()?.substringAfterLast('\n') ?: return false
        val m = slash.find(line) ?: return equals.containsMatchIn(line) && match(line) != null
        val name = m.groupValues[2]
        return commands.any { it.name.startsWith(name) }
    }

    fun match(textBeforeCursor: CharSequence?, ime: LatinIME? = null): Match? {
        val line = textBeforeCursor?.toString()?.substringAfterLast('\n') ?: return null
        equals.find(line)?.takeIf { m -> m.groupValues[2].trim().drop(1).any { it in "+-*/x×÷^%" } }?.let { m ->
            val result = calc(m.groupValues[2]) ?: return null
            val shown = format(result)
            return Match(m.groupValues[1].length, "= $shown", shown)
        }
        val m = slash.find(line) ?: return null
        val name = m.groupValues[2]
        val args = m.groupValues[3].trimEnd('`').replace('“', '"').replace('”', '"')
        val exact = commands.firstOrNull { it.name == name }
        if (exact != null && (args.isNotBlank() || exact.usage == "/${exact.name}" || exact.usage.contains("("))) {
            if (exact.remote == "mcp") {
                val typed = args.trim()
                val out = McpServers.cached(name, typed)
                if (out == WAITING) return Match(0, WAITING, null)
                if (out != null) return Match(m.groupValues[1].length, out, out)
                if (typed.isEmpty() && exact.usage.trim().contains(' ')) return Match(0, exact.usage, null)
                return Match(0, "Ask: /$name $typed".trim(), null,
                    ask = ime?.let { i -> { McpServers.ask(name, typed) { i.setNeutralSuggestionStrip() }; i.setNeutralSuggestionStrip(); kotlin.Unit } })
            }
            val out = if (exact.remote != null) {
                if (ime == null || args.isBlank() && exact.usage.contains(' ')) return Match(0, exact.usage, null)
                CommandPlugins.answer(ime, exact.remote, exact.name, args.trim()) { ime.setNeutralSuggestionStrip() }
            } else runCatching { exact.run(args.trim()) }.getOrNull()
            if (out == WAITING) return Match(0, WAITING, null)
            if (out == null) return Match(0, exact.usage, null)
            return Match(m.groupValues[1].length, out, out)
        }
        val options = commands.filter { it.name.startsWith(name) }
        if (options.isEmpty()) return null
        if (options.size == 1 && !line.endsWith(" "))
            return Match(m.groupValues[1].length, options[0].usage, "/${options[0].name} ")
        return Match(0, options.joinToString("   ") { "/" + it.name }, null)
    }

    /** The chip for the suggestion bar, or null if no command is being typed. */
    fun chip(ime: LatinIME, parent: ViewGroup): View? {
        if (!Plugins.isOn(ime, "slash")) return null
        val ic = ime.currentInputConnection ?: return null
        val match = match(ic.getTextBeforeCursor(200, 0), ime) ?: return null
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(ime), parent, false)
        val textView = binding.clipboardSuggestionText
        textView.text = if (match.insert != null) "↵  ${match.shown}" else match.shown
        val colors = ime.mSettings.current.mColors
        textView.setTextColor(colors.get(ColorType.KEY_TEXT))
        colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)
        binding.clipboardSuggestionClose.visibility = View.GONE
        if (match.insert != null) textView.setOnClickListener { apply(ime, match) }
        else match.ask?.let { ask -> textView.text = "↗  ${match.shown}"; textView.setOnClickListener { ask() } }
        return binding.root
    }

    private fun apply(ime: LatinIME, match: Match) {
        val ic = ime.currentInputConnection ?: return
        ime.mInputLogic.finishInput()
        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.deleteSurroundingText(match.replaceLength, 0)
        ic.commitText(match.insert, 1)
        ic.endBatchEdit()
        val ex = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        if (ex != null && ex.selectionStart >= 0)
            ime.mInputLogic.resyncAfterVoiceInput(ex.startOffset + ex.selectionStart, ex.startOffset + ex.selectionEnd)
        KeyboardSwitcher.getInstance().updateShiftState(ime.currentAutoCapsState, ime.currentRecapitalizeState)
        ime.setNeutralSuggestionStrip()
    }

    // ---------- calculator ----------

    private fun format(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "∞"
        val bd = BigDecimal(v).round(MathContext(12, RoundingMode.HALF_UP)).stripTrailingZeros()
        return if (bd.scale() <= 0) bd.toBigInteger().toString() else bd.toPlainString()
    }

    /** Small calculator: + - * / x × ÷ ^ % and brackets. */
    fun calc(input: String): Double? {
        val s = input.replace(" ", "").replace(',', '.').replace('x', '*').replace('×', '*').replace('÷', '/')
        if (s.isEmpty() || s.none { it.isDigit() }) return null
        var pos = 0
        fun peek() = if (pos < s.length) s[pos] else '\u0000'
        lateinit var expr: () -> Double
        fun number(): Double {
            val start = pos
            while (peek().isDigit() || peek() == '.') pos++
            if (start == pos) throw IllegalArgumentException()
            return s.substring(start, pos).toDouble()
        }
        fun factor(): Double {
            val v = when (peek()) {
                '-' -> { pos++; -factor() }
                '+' -> { pos++; factor() }
                '(' -> { pos++; val r = expr(); if (peek() == ')') pos++; r }
                else -> number()
            }
            var r = v
            if (peek() == '%') { pos++; r /= 100 }
            if (peek() == '^') { pos++; r = r.pow(factor()) }
            return r
        }
        fun term(): Double {
            var v = factor()
            while (peek() == '*' || peek() == '/') { val op = s[pos++]; val f = factor(); v = if (op == '*') v * f else v / f }
            return v
        }
        expr = {
            var v = term()
            while (peek() == '+' || peek() == '-') { val op = s[pos++]; val t = term(); v = if (op == '+') v + t else v - t }
            v
        }
        return try { val r = expr(); if (pos != s.length) null else r } catch (_: Exception) { null }
    }

    // ---------- unit conversion ----------

    private class Unit(val names: List<String>, val group: String, val toBase: Double, val label: String)
    private val units = listOf(
        Unit(listOf("mm", "millimetre", "millimetres", "millimeter", "millimeters"), "len", 0.001, "mm"),
        Unit(listOf("cm", "centimetre", "centimetres", "centimeter", "centimeters"), "len", 0.01, "cm"),
        Unit(listOf("m", "metre", "metres", "meter", "meters"), "len", 1.0, "m"),
        Unit(listOf("km", "kilometre", "kilometres", "kilometer", "kilometers", "k"), "len", 1000.0, "km"),
        Unit(listOf("in", "inch", "inches", "\""), "len", 0.0254, "in"),
        Unit(listOf("ft", "foot", "feet", "'"), "len", 0.3048, "ft"),
        Unit(listOf("yd", "yard", "yards"), "len", 0.9144, "yd"),
        Unit(listOf("mi", "mile", "miles"), "len", 1609.344, "mi"),
        Unit(listOf("g", "gram", "grams"), "mass", 1.0, "g"),
        Unit(listOf("kg", "kilo", "kilos", "kilogram", "kilograms"), "mass", 1000.0, "kg"),
        Unit(listOf("oz", "ounce", "ounces"), "mass", 28.349523125, "oz"),
        Unit(listOf("lb", "lbs", "pound", "pounds"), "mass", 453.59237, "lb"),
        Unit(listOf("st", "stone", "stones"), "mass", 6350.29318, "st"),
        Unit(listOf("ml", "millilitre", "millilitres", "milliliter", "milliliters"), "vol", 1.0, "ml"),
        Unit(listOf("l", "litre", "litres", "liter", "liters"), "vol", 1000.0, "L"),
        Unit(listOf("tsp", "teaspoon", "teaspoons"), "vol", 5.0, "tsp"),
        Unit(listOf("tbsp", "tablespoon", "tablespoons"), "vol", 20.0, "tbsp"), // Australian tablespoon
        Unit(listOf("cup", "cups"), "vol", 250.0, "cups"), // metric cup
        Unit(listOf("floz", "fl.oz"), "vol", 29.5735, "fl oz"),
        Unit(listOf("gal", "gallon", "gallons"), "vol", 3785.41, "gal"),
        Unit(listOf("kmh", "km/h", "kph"), "speed", 1.0, "km/h"),
        Unit(listOf("mph"), "speed", 1.609344, "mph"),
        Unit(listOf("ms", "m/s"), "speed", 3.6, "m/s"),
        Unit(listOf("knot", "knots", "kn"), "speed", 1.852, "knots"),
        Unit(listOf("ha", "hectare", "hectares"), "area", 10000.0, "ha"),
        Unit(listOf("acre", "acres"), "area", 4046.8564224, "acres"),
        Unit(listOf("c", "°c", "celsius"), "temp", 0.0, "°C"),
        Unit(listOf("f", "°f", "fahrenheit"), "temp", 0.0, "°F"),
        Unit(listOf("k", "kelvin"), "temp", 0.0, "K"),
    )
    private fun unit(name: String, group: String? = null) = name.lowercase().trim().let { n ->
        units.firstOrNull { n in it.names && (group == null || it.group == group) }
    }

    fun convert(args: String): String? {
        val m = Regex("(-?[0-9.,]+)\\s*([^\\s0-9]+)\\s*(?:to|in|into|->)?\\s*([^\\s0-9]+)?", RegexOption.IGNORE_CASE).find(args.trim()) ?: return null
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val fromName = m.groupValues[2]
        val toName = m.groupValues[3]
        // "k" means km for lengths but kelvin for temperatures: decide by the other unit
        val to0 = unit(toName)
        val from = unit(fromName, to0?.group) ?: unit(fromName) ?: return null
        val to = unit(toName, from.group) ?: defaultTarget(from) ?: return null
        if (from.group != to.group) return null
        val result = if (from.group == "temp") {
            val c = when (from.label) { "°F" -> (value - 32) * 5 / 9; "K" -> value - 273.15; else -> value }
            when (to.label) { "°F" -> c * 9 / 5 + 32; "K" -> c + 273.15; else -> c }
        } else value * from.toBase / to.toBase
        val rounded = BigDecimal(result).round(MathContext(4, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()
        return "$rounded ${to.label}"
    }

    private fun defaultTarget(from: Unit): Unit? = when (from.label) {
        "km" -> unit("mi"); "mi" -> unit("km"); "m" -> unit("ft"); "ft" -> unit("m"); "cm" -> unit("in"); "in" -> unit("cm")
        "kg" -> unit("lb"); "lb" -> unit("kg"); "g" -> unit("oz"); "oz" -> unit("g"); "st" -> unit("kg")
        "°C" -> unit("f"); "°F" -> unit("c"); "L" -> unit("gal"); "gal" -> unit("l"); "km/h" -> unit("mph"); "mph" -> unit("kmh")
        else -> null
    }
}
