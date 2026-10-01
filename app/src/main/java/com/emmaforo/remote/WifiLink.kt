package com.emmaforo.remote

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object WifiLink {
    fun send(store: LampStore, command: String, blink: Boolean, level: Int): String? {
        for (endpoint in candidates(store)) {
            if (postLed(endpoint, command, blink, level)) {
                store.stationUrl = endpoint
                return endpoint
            }
        }
        return null
    }

    fun resolve(store: LampStore): String? {
        for (endpoint in candidates(store)) {
            if (ping(endpoint)) {
                store.stationUrl = endpoint
                return endpoint
            }
        }
        return null
    }

    fun battery(endpoint: String): BatteryReading? {
        val connection = open("$endpoint/api/battery", "GET") ?: return null
        return try {
            if (connection.responseCode != 200) return null
            val text = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(text)
            BatteryReading(
                percent = json.optInt("percentage").coerceIn(0, 100),
                millivolts = json.optInt("voltage_mv"),
                charging = json.optBoolean("charging"),
                chargeState = if (json.has("charge_status")) json.optInt("charge_status") else null,
                fault = json.optInt("fault"),
                valid = if (json.has("estimated")) json.optBoolean("estimated") else true,
            )
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun candidates(store: LampStore): List<String> {
        val saved = store.stationUrl.trim()
        val local = "http://emmaforo.local"
        if (saved.isEmpty() || saved == local) return listOf(local)
        return listOf(saved, local)
    }

    private fun postLed(endpoint: String, command: String, blink: Boolean, level: Int): Boolean {
        val query = buildString {
            append("command=")
            append(URLEncoder.encode(command, "UTF-8"))
            append("&blink=")
            append(if (blink) "true" else "false")
            append("&interval=1000&level=")
            append(level.coerceIn(0, 255))
        }
        val connection = open("${endpoint.trimEnd('/')}/api/led?$query", "POST") ?: return false
        return try {
            connection.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun ping(endpoint: String): Boolean {
        val connection = open(endpoint, "GET") ?: return false
        return try {
            connection.responseCode < 500
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun open(address: String, method: String): HttpURLConnection? {
        return try {
            (URL(address).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 4000
                readTimeout = 4000
                instanceFollowRedirects = false
            }
        } catch (_: Exception) {
            null
        }
    }
}
