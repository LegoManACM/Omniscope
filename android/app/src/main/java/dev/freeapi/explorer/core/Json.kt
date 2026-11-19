package dev.freeapi.explorer.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** org.json returns "null" and NaN for missing values; these return Kotlin nulls instead. */
fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).trim().takeIf { it.isNotEmpty() }
fun JSONObject.num(k: String): Double? = if (isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }
fun JSONObject.obj(k: String): JSONObject? = optJSONObject(k)
fun JSONObject.arr(k: String): JSONArray = optJSONArray(k) ?: JSONArray()
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
