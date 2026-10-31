package com.itemmanagement.printbridge

import android.content.Context
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

data class PrinterInfo(
    val id: Long,
    val name: String,
    val ipAddress: String,
    val port: Int,
    val paperWidth: String,
    val purpose: String,
    val status: String,
) {
    val isActive: Boolean get() = status.equals("ACTIVE", ignoreCase = true)
    val isReceipt: Boolean get() = purpose.equals("RECEIPT", true) || purpose.equals("ALL", true)
    val paperLabel: String get() = if (paperWidth == "MM58") "58 mm" else "80 mm"

    fun matches(ip: String, targetPort: Int): Boolean =
        ipAddress.equals(ip.trim(), ignoreCase = true) && port == targetPort

    companion object {
        fun from(json: JSONObject): PrinterInfo = PrinterInfo(
            id = json.optLong("id"),
            name = json.optString("printerName", "Printer"),
            ipAddress = json.optString("ipAddress"),
            port = json.optInt("port", 9100),
            paperWidth = json.optString("paperWidth", "MM80"),
            purpose = json.optString("purpose", "RECEIPT"),
            status = json.optString("status", "ACTIVE"),
        )

        fun listFrom(raw: String): List<PrinterInfo> {
            if (raw.isBlank()) return emptyList()
            return try {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let(::from)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun toJson(printers: List<PrinterInfo>): String {
            val array = JSONArray()
            printers.forEach { printer ->
                array.put(
                    JSONObject()
                        .put("id", printer.id)
                        .put("printerName", printer.name)
                        .put("ipAddress", printer.ipAddress)
                        .put("port", printer.port)
                        .put("paperWidth", printer.paperWidth)
                        .put("purpose", printer.purpose)
                        .put("status", printer.status),
                )
            }
            return array.toString()
        }
    }
}

class AppConfig(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("print_bridge", Context.MODE_PRIVATE)

    var bridgeKey: String
        get() {
            val current = prefs.getString(KEY_BRIDGE_KEY, "").orEmpty()
            if (current.length >= 24) return current
            val generated = generateKey()
            prefs.edit().putString(KEY_BRIDGE_KEY, generated).apply()
            return generated
        }
        set(value) = prefs.edit().putString(KEY_BRIDGE_KEY, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT).coerceIn(1024, 65535)
        set(value) = prefs.edit().putInt(KEY_PORT, value.coerceIn(1024, 65535)).apply()

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var deviceToken: String
        get() = prefs.getString(KEY_DEVICE_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_DEVICE_TOKEN, value).apply()

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, defaultDeviceName()).orEmpty()
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    var storeName: String
        get() = prefs.getString(KEY_STORE_NAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_STORE_NAME, value).apply()

    var branchName: String
        get() = prefs.getString(KEY_BRANCH_NAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_BRANCH_NAME, value).apply()

    var allowedOrigin: String
        get() = prefs.getString(KEY_ALLOWED_ORIGIN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ALLOWED_ORIGIN, value.trim().trimEnd('/')).apply()

    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_START_ON_BOOT, true)
        set(value) = prefs.edit().putBoolean(KEY_START_ON_BOOT, value).apply()

    var revoked: Boolean
        get() = prefs.getBoolean(KEY_REVOKED, false)
        set(value) = prefs.edit().putBoolean(KEY_REVOKED, value).apply()

    var lastSync: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    var lastResult: String
        get() = prefs.getString(KEY_LAST_RESULT, "No print jobs yet.").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_RESULT, value).apply()

    val isPaired: Boolean get() = deviceToken.isNotBlank()

    fun printers(): List<PrinterInfo> = PrinterInfo.listFrom(prefs.getString(KEY_PRINTERS, "").orEmpty())

    fun savePrinters(printers: List<PrinterInfo>) {
        prefs.edit().putString(KEY_PRINTERS, PrinterInfo.toJson(printers)).apply()
    }

    fun defaultReceiptPrinter(): PrinterInfo? =
        printers().firstOrNull { it.isActive && it.isReceipt } ?: printers().firstOrNull { it.isActive }

    fun clearPairing() {
        prefs.edit()
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_STORE_NAME)
            .remove(KEY_BRANCH_NAME)
            .remove(KEY_PRINTERS)
            .remove(KEY_LAST_SYNC)
            .putBoolean(KEY_REVOKED, false)
            .apply()
    }

    private fun defaultDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val name = when {
            model.isEmpty() -> "Android device"
            manufacturer.isEmpty() || model.lowercase().startsWith(manufacturer.lowercase()) -> model
            else -> "$manufacturer $model"
        }
        return name.take(80)
    }

    private fun generateKey(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        const val DEFAULT_PORT = 17778
        private const val KEY_BRIDGE_KEY = "bridge_key"
        private const val KEY_PORT = "port"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_STORE_NAME = "store_name"
        private const val KEY_BRANCH_NAME = "branch_name"
        private const val KEY_PRINTERS = "printers"
        private const val KEY_ALLOWED_ORIGIN = "allowed_origin"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_REVOKED = "revoked"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_LAST_RESULT = "last_result"
    }
}
