package com.itemmanagement.printbridge

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class BackendOutcome(val success: Boolean, val message: String)

class BackendClient(private val config: AppConfig) {

    fun pair(baseUrl: String, code: String, deviceName: String): BackendOutcome {
        val base = normalize(baseUrl)
            ?: return BackendOutcome(false, "Enter the store website address, for example https://store.example.com")
        val cleanCode = code.uppercase().replace(" ", "").replace("-", "").trim()
        if (cleanCode.length !in 6..12) {
            return BackendOutcome(false, "Enter the 8-character pairing code shown in Settings → Printers.")
        }
        val name = deviceName.trim().ifBlank { "Android device" }.take(100)
        if (name.length < 2) {
            return BackendOutcome(false, "Enter a device name with at least 2 characters.")
        }

        val body = JSONObject()
            .put("code", cleanCode)
            .put("deviceName", name)
            .put("platform", "ANDROID")
            .toString()

        val response = request("$base/api/printing/devices/pair", "POST", body, null)
        if (!response.success) return BackendOutcome(false, response.message)
        val payload = response.payload
            ?: return BackendOutcome(false, "The store returned an empty pairing response.")

        val token = payload.optString("deviceToken").trim()
        if (token.isBlank()) {
            return BackendOutcome(false, "The store did not return a device token. Generate a new pairing code.")
        }
        config.serverUrl = base
        config.deviceToken = token
        config.deviceName = name
        config.storeName = payload.optString("storeName")
        config.branchName = payload.optString("branchName")
        config.savePrinters(printerList(payload.optJSONArray("printers")))
        config.revoked = false
        config.lastSync = System.currentTimeMillis()
        return BackendOutcome(true, "Paired with ${config.storeName} · ${config.branchName}")
    }

    fun fetchConfig(): BackendOutcome {
        val base = normalize(config.serverUrl)
            ?: return BackendOutcome(false, "Pair this device with your store first.")
        val token = config.deviceToken
        if (token.isBlank()) return BackendOutcome(false, "Pair this device with your store first.")

        val response = request("$base/api/printing/device/config", "GET", null, token)
        if (!response.success) {
            if (response.unauthorized) {
                config.revoked = true
                return BackendOutcome(false, response.message.ifBlank {
                    "This print bridge was revoked in store settings. Generate a new pairing code and pair it again."
                })
            }
            return BackendOutcome(false, response.message)
        }
        val payload = response.payload ?: return BackendOutcome(false, "The store returned an empty configuration.")
        config.storeName = payload.optString("storeName")
        config.branchName = payload.optString("branchName")
        config.savePrinters(printerList(payload.optJSONArray("printers")))
        config.revoked = false
        config.lastSync = System.currentTimeMillis()
        val count = config.printers().size
        return BackendOutcome(true, "Synced $count printer${if (count == 1) "" else "s"} for ${config.branchName}.")
    }

    private data class Response(
        val success: Boolean,
        val message: String,
        val payload: JSONObject? = null,
        val unauthorized: Boolean = false,
    )

    private fun request(url: String, method: String, body: String?, token: String?): Response {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("Accept", "application/json")
                token?.let { setRequestProperty("X-Device-Token", it) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    setFixedLengthStreamingMode(bytes.size)
                    outputStream.use { it.write(bytes) }
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
            }.orEmpty()
            val json = try {
                if (text.isBlank()) null else JSONObject(text)
            } catch (_: Exception) {
                null
            }
            if (status in 200..299) {
                Response(true, "OK", json)
            } else {
                val message = json?.optString("message").orEmpty()
                    .ifBlank { json?.optString("error").orEmpty() }
                    .ifBlank { "The store returned error $status." }
                Response(false, message, json, status == 401)
            }
        } catch (ex: Exception) {
            Response(false, "Cannot reach the store server. Check the server address and internet/Wi-Fi connection. ${ex.message ?: ""}".trim())
        } finally {
            connection?.disconnect()
        }
    }

    private fun normalize(raw: String): String? {
        var value = raw.trim()
        if (value.isBlank()) return null
        if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) {
            value = "http://$value"
        }
        value = value.trimEnd('/')
        if (value.endsWith("/api", true)) value = value.dropLast(4).trimEnd('/')
        return try {
            val url = URL(value)
            if (url.host.isNullOrBlank()) null else value
        } catch (_: Exception) {
            null
        }
    }

    private fun printerList(array: JSONArray?): List<PrinterInfo> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(PrinterInfo::from)
        }
    }
}
