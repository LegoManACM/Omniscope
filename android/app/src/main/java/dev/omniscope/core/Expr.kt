package dev.omniscope.core

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToLong

class ExprError(message: String) : Exception(message)

/**
 * The small expression language plugin manifests use to pull values out of API responses.
 *
 * - **Path**: `$.a.b`, `$.list[0]`, `$.list[*]`, `$.obj.*` (all values), `$['key:with:colons']`, `$.list[-1]`.
 * - **Pipes**: `$.alt | num | mul:0.3048 | round` transform the value (see [pipe]).
 * - **Fallbacks**: `$.name || $.title` takes the first alternative that has a value.
 * - **Literals**: `'text'` is a fixed string (useful as a fallback).
 * - **Templates**: any other string with `{…}` placeholders, e.g. `"M{$.mag | round1} · {$.place}"`. Empty parts of
 *   " · "-separated templates are dropped; in strict mode (links, URLs) a missing placeholder drops the whole value.
 * - Anything else is returned as literal text.
 */
object Expr {

    /** A value as a display string, or null if it has none. */
    fun string(spec: String?, node: Any?, strict: Boolean = false): String? {
        if (spec.isNullOrBlank()) return null
        val s = spec.trim()
        return when {
            s.startsWith("$") || s.startsWith("'") -> format(evalAlternatives(s, node).firstOrNull())
            s.contains('{') -> template(s, node, strict)
            else -> s
        }
    }

    fun number(spec: String?, node: Any?): Double? {
        if (spec.isNullOrBlank()) return null
        val s = spec.trim()
        if (!s.startsWith("$")) return s.toDoubleOrNull() ?: string(s, node)?.let { toNumber(it) }
        return evalAlternatives(s, node).firstNotNullOfOrNull { toNumber(it) }
    }

    /** Every value an expression yields (for item lists and galleries). */
    fun list(spec: String?, node: Any?): List<Any> {
        if (spec.isNullOrBlank()) return emptyList()
        val s = spec.trim()
        if (s == "$") return listOfNotNull(node)
        return evalAlternatives(s, node, all = true)
    }

    // --------------------------------------------------------------------------------------------------------------

    private fun evalAlternatives(spec: String, node: Any?, all: Boolean = false): List<Any> {
        for (alt in splitTop(spec, "||")) {
            val parts = splitTop(alt, "|").map { it.trim() }
            val source = parts.first()
            var values: List<Any?> = when {
                source.startsWith("$") -> path(source, node)
                source.startsWith("'") && source.endsWith("'") && source.length >= 2 -> listOf(source.substring(1, source.length - 1))
                else -> throw ExprError("Expected a path or 'literal', got \"$source\"")
            }
            if (values.isEmpty()) values = listOf(null)
            for (p in parts.drop(1)) values = pipe(p, values)
            val result = values.filterNotNull().filter { !(it is String && it.isBlank()) }
            if (result.isNotEmpty()) return if (all) result else listOf(result.first())
        }
        return emptyList()
    }

    /** Splits on [sep] but not inside quotes, brackets or braces ("||" is never split as "|"). */
    private fun splitTop(s: String, sep: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0; var quote: Char? = null; var start = 0; var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '[' || c == '{' -> depth++
                c == ']' || c == '}' -> depth--
                depth == 0 && s.startsWith(sep, i) && !(sep == "|" && (s.startsWith("||", i) || (i > 0 && s[i - 1] == '|'))) -> {
                    out += s.substring(start, i); i += sep.length; start = i; continue
                }
            }
            i++
        }
        out += s.substring(start)
        return out
    }

    fun path(p: String, root: Any?): List<Any?> {
        if (!p.startsWith("$")) throw ExprError("Paths start with $: $p")
        var cur: List<Any?> = listOf(root)
        var i = 1
        while (i < p.length) {
            when (p[i]) {
                '.' -> {
                    i++
                    if (i < p.length && p[i] == '*') { cur = cur.flatMap { children(it) }; i++ }
                    else {
                        val start = i
                        while (i < p.length && (p[i].isLetterOrDigit() || p[i] == '_' || p[i] == '-' || p[i] == '@')) i++
                        if (start == i) throw ExprError("Empty name in path $p")
                        val name = p.substring(start, i)
                        cur = cur.map { field(it, name) }
                    }
                }
                '[' -> {
                    val end = p.indexOf(']', i)
                    if (end < 0) throw ExprError("Unclosed [ in path $p")
                    val inner = p.substring(i + 1, end).trim()
                    i = end + 1
                    cur = when {
                        inner == "*" -> cur.flatMap { children(it) }
                        inner.length >= 2 && (inner[0] == '\'' || inner[0] == '"') -> cur.map { field(it, inner.substring(1, inner.length - 1)) }
                        else -> {
                            val n = inner.toIntOrNull() ?: throw ExprError("Bad index [$inner] in path $p")
                            cur.map { (it as? JSONArray)?.let { a -> val k = if (n < 0) a.length() + n else n; if (k in 0 until a.length()) clean(a.opt(k)) else null } }
                        }
                    }
                }
                ' ' -> i++
                else -> throw ExprError("Unexpected '${p[i]}' in path $p")
            }
            cur = cur.filterNotNull()
        }
        return cur
    }

    private fun clean(v: Any?): Any? = if (v == null || v == JSONObject.NULL) null else v
    private fun field(o: Any?, name: String): Any? = (o as? JSONObject)?.let { clean(it.opt(name)) }
    private fun children(o: Any?): List<Any?> = when (o) {
        is JSONArray -> (0 until o.length()).map { clean(o.opt(it)) }
        is JSONObject -> o.keys().asSequence().map { clean(o.opt(it)) }.toList()
        else -> emptyList()
    }

    // --------------------------------------------------------------------------------------------------------------
    // Pipes

    private val compass = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    private fun pipe(spec: String, values: List<Any?>): List<Any?> {
        val name = spec.substringBefore(':').trim()
        // Arguments keep leading spaces ("suffix: m"); quote them to keep trailing ones too ("replace:'_=> '").
        val raw = if (spec.contains(':')) spec.substringAfter(':').trimEnd() else ""
        val arg = if (raw.trim().length >= 2 && raw.trim().startsWith("'") && raw.trim().endsWith("'")) raw.trim().drop(1).dropLast(1) else raw
        // List pipes work on the whole list.
        when (name) {
            "join" -> return listOf(values.filterNotNull().joinToString(arg.ifEmpty { ", " }) { format(it) ?: "" }.ifBlank { null })
            "first" -> return listOf(values.firstOrNull { it != null })
            "count" -> return listOf(values.count { it != null }.toDouble())
            "limit" -> return values.take(arg.trim().toIntOrNull() ?: values.size)
        }
        return values.map { v -> one(name, arg, v) }
    }

    private fun one(name: String, arg: String, v: Any?): Any? {
        fun n() = toNumber(v)
        fun s() = format(v)
        return when (name) {
            "num" -> n()
            "round" -> n()?.roundToLong()?.toDouble()
            "round1" -> n()?.let { "%.1f".format(java.util.Locale.US, it) }
            "mul" -> n()?.times(arg.toDouble())
            "div" -> n()?.div(arg.toDouble())
            "add" -> n()?.plus(arg.toDouble())
            "nonzero" -> n()?.takeIf { it != 0.0 }
            "thousands" -> n()?.let { "%,d".format(java.util.Locale.US, it.roundToLong()) }
            "trim" -> s()?.trim()
            "upper" -> s()?.uppercase()
            "lower" -> s()?.lowercase()
            "cap" -> s()?.replaceFirstChar { it.uppercase() }
            "take" -> s()?.let { t -> val k = arg.trim().toInt(); if (t.length > k) t.take(k).trimEnd() + "…" else t }
            "replace" -> s()?.replace(arg.substringBefore("=>"), arg.substringAfter("=>", ""))
            "removeprefix" -> s()?.removePrefix(arg)
            "removesuffix" -> s()?.removeSuffix(arg)
            "noext" -> s()?.let { t -> if (t.contains('.')) t.substringBeforeLast('.') else t }
            "prefix" -> s()?.let { arg + it }
            "suffix" -> s()?.let { it + arg }
            "default" -> v ?: arg
            "date" -> v?.let { toDate(it) }
            "compass" -> n()?.let { compass[(((it % 360) + 360) % 360 / 45 + 0.5).toInt() % 8] }
            "url" -> s()?.let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
            "wikiurl" -> s()?.let { t ->
                val lang = t.substringBefore(':', "en").takeIf { it.length in 2..3 } ?: "en"
                "https://$lang.wikipedia.org/wiki/" + URLEncoder.encode(t.substringAfter(':').replace(' ', '_'), "UTF-8")
            }
            "enc" -> s()?.let { URLEncoder.encode(it, "UTF-8") }
            else -> throw ExprError("Unknown pipe \"$name\"")
        }
    }

    /** Epoch milliseconds (a number, or all-digit text) or an ISO date/time string to YYYY-MM-DD. */
    private fun toDate(v: Any): String? {
        val ms = when (v) { is Number -> v.toDouble(); is String -> v.trim().takeIf { t -> t.all { it.isDigit() } }?.toDoubleOrNull(); else -> null }
        if (ms != null) return Instant.ofEpochMilli(ms.toLong()).atOffset(ZoneOffset.UTC).toLocalDate().toString()
        return format(v)?.take(10)
    }

    fun toNumber(v: Any?): Double? = when (v) {
        null -> null
        is Number -> v.toDouble().takeIf { !it.isNaN() }
        is String -> Regex("^\\s*-?[0-9]+(\\.[0-9]+)?").find(v)?.value?.trim()?.toDoubleOrNull()
        else -> null
    }

    fun format(v: Any?): String? = when (v) {
        null -> null
        is Double -> if (v.isNaN() || v.isInfinite()) null else BigDecimal(v).let {
            if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else it.round(java.math.MathContext(10)).stripTrailingZeros().toPlainString()
        }
        is Float -> format(v.toDouble())
        is Number -> v.toString()
        is String -> v.trim().ifEmpty { null }
        is Boolean -> if (v) "yes" else "no"
        else -> null // objects and arrays aren't displayable
    }

    // --------------------------------------------------------------------------------------------------------------
    // Templates

    private val placeholder = Regex("\\{([^{}]+)\\}")

    private fun template(t: String, node: Any?, strict: Boolean): String? {
        var missing = false
        val filled = placeholder.replace(t) { m ->
            val v = string(m.groupValues[1], node)
            if (v == null) { missing = true; "" } else v
        }
        if (strict && missing) return null
        val cleaned = filled.split(" · ").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ").trim()
        return cleaned.ifEmpty { null }
    }

    /**
     * Request templates: `{lat}`, `{lon}`, `{radius_m}`, `{radius_km}` (or `{radius_km:3}` for 3 decimals),
     * `{radius_nm}`, `{key}`, `{date}` / `{date:-14}` (today plus N days, UTC). Values are URL-safe.
     */
    fun request(t: String, ctx: Ctx, key: String?): String = placeholder.replace(t) { m ->
        val name = m.groupValues[1].substringBefore(':').trim()
        val arg = m.groupValues[1].substringAfter(':', "").trim()
        when (name) {
            "lat" -> "%.5f".format(java.util.Locale.US, ctx.lat)
            "lon" -> "%.5f".format(java.util.Locale.US, ctx.lon)
            "radius_m" -> ctx.radiusM.roundToLong().toString()
            "radius_km" -> if (arg.isEmpty()) (ctx.radiusM / 1000).roundToLong().coerceAtLeast(1).toString()
                else "%.${arg.toInt()}f".format(java.util.Locale.US, ctx.radiusM / 1000)
            "radius_nm" -> (ctx.radiusM / 1852).roundToLong().coerceAtLeast(1).toString()
            "key" -> URLEncoder.encode(key.orEmpty(), "UTF-8")
            "date" -> LocalDate.now(ZoneOffset.UTC).plusDays(arg.toLongOrNull() ?: 0).toString()
            else -> throw ExprError("Unknown request placeholder {$name}")
        }
    }
}
