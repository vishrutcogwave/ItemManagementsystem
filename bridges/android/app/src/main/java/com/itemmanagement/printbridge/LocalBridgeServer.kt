package com.itemmanagement.printbridge

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class LocalBridgeServer(
    private val config: AppConfig,
    private val onResult: (String) -> Unit,
) {
    private val workers = Executors.newCachedThreadPool()
    private val printed = ConcurrentHashMap<String, Long>()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile var lastError: String? = null
        private set

    fun start(port: Int): Boolean {
        if (running) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16)
            serverSocket = socket
            running = true
            lastError = null
            thread(start = true, isDaemon = true, name = "print-bridge-accept") { acceptLoop(socket) }
            report("Listening on 127.0.0.1:$port")
            true
        } catch (ex: Exception) {
            lastError = "Could not start the bridge on 127.0.0.1:$port: ${ex.message}"
            report(lastError!!)
            false
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        workers.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (ex: SocketException) {
                if (running) report("Bridge stopped: ${ex.message}")
                break
            } catch (ex: Exception) {
                if (running) report("Bridge accept error: ${ex.message}")
                continue
            }
            try {
                workers.execute { handle(client) }
            } catch (_: Exception) {
                try {
                    client.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 15000
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()
            val requestLine = readLine(input, 4096) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                respondJson(output, 400, errorJson("Invalid request."))
                return
            }
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')

            var headerSize = 0
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input, 8192) ?: break
                if (line.isEmpty()) break
                headerSize += line.length + 2
                if (headerSize > 16384) {
                    respondJson(output, 431, errorJson("Request headers are too large."))
                    return
                }
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
            }

            val origin = headers["origin"].orEmpty()
            val allowedOrigin = originAllowed(origin)

            if (method == "OPTIONS") {
                if (!allowedOrigin) {
                    respondJson(output, 403, errorJson("This website is not allowed to use the print bridge."))
                    return
                }
                respondEmpty(output, 204, corsHeaders(origin, privateNetwork = true))
                return
            }

            if (!allowedOrigin) {
                respondJson(output, 403, errorJson("This website is not allowed to use the print bridge. Update the allowed website in the bridge app."))
                return
            }

            if (!keyMatches(headers["x-bridge-key"].orEmpty(), config.bridgeKey)) {
                respondJson(output, 401, errorJson("The bridge key is invalid."), corsHeaders(origin))
                return
            }

            if (method == "GET" && path == "/api/bridge/status") {
                respondJson(output, 200, statusJson(), corsHeaders(origin))
                return
            }

            if (method == "POST" && (path == "/api/bridge/print" || path == "/api/bridge/test")) {
                val body = try {
                    readBody(input, headers)
                } catch (ex: IllegalArgumentException) {
                    respondJson(output, 413, errorJson(ex.message ?: "The request is too large."), corsHeaders(origin))
                    return
                }
                if (body == null) {
                    respondJson(output, 411, errorJson("The request body length is required."), corsHeaders(origin))
                    return
                }
                if (path == "/api/bridge/test") {
                    respondJson(output, 200, handleTest(body), corsHeaders(origin))
                } else {
                    respondJson(output, 200, handlePrint(body), corsHeaders(origin))
                }
                return
            }

            respondJson(output, 404, errorJson("Not found."), corsHeaders(origin))
        }
    }

    private fun statusJson(): String {
        val printer = config.defaultReceiptPrinter()
        return JSONObject()
            .put("ok", true)
            .put("platform", "ANDROID")
            .put("version", APP_VERSION)
            .put("deviceName", config.deviceName)
            .put("defaultPrinter", printer?.name)
            .put("paired", config.isPaired && !config.revoked)
            .put("storeName", config.storeName)
            .put("branchName", config.branchName)
            .put("printers", config.printers().size)
            .put("lastResult", config.lastResult)
            .toString()
    }

    private fun handleTest(body: String): String {
        guardPaired()?.let { return errorJson(it) }
        val json = try {
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } catch (_: Exception) {
            return errorJson("Invalid request body.")
        }
        val requested = json.optJSONObject("printer")
        val paper = requested?.optString("paperWidth").takeUnless { it.isNullOrBlank() } ?: "MM80"
        val target = if (requested != null) {
            val ip = requested.optString("ipAddress").trim()
            val port = requested.optInt("port", 9100)
            if (ip.isBlank()) return errorJson("The printer IP address is empty.")
            if (port !in 1..65535) return errorJson("The printer port is invalid.")
            PrinterTarget(ip, port, paper)
        } else {
            val printer = config.defaultReceiptPrinter()
                ?: return errorJson("No receipt printer is configured for this branch. Add one in Settings → Printers, then refresh this app.")
            PrinterTarget(printer.ipAddress, printer.port, printer.paperWidth, printer.name)
        }

        val error = PrinterClient.send(target, EscPos.testSlip(target.label, target.paperWidth))
        if (error != null) {
            report("Test failed: $error")
            return JSONObject()
                .put("success", false)
                .put("message", error)
                .put("defaultPrinter", target.label)
                .toString()
        }
        val message = "Test slip sent to ${target.label}."
        report(message)
        return JSONObject()
            .put("success", true)
            .put("message", message)
            .put("defaultPrinter", target.label)
            .toString()
    }

    private fun handlePrint(body: String): String {
        guardPaired()?.let { return errorJson(it) }
        val payload = try {
            JSONObject(body)
        } catch (_: Exception) {
            return errorJson("Invalid request body.")
        }
        validateJob(payload)?.let { return errorJson(it) }

        val jobId = payload.optLong("jobId")
        val jobKey = payload.optString("jobKey")
        val paper = payload.optString("paperWidth")
        val document = payload.getJSONObject("document")
        val lineArray = document.getJSONArray("lines")
        val lines = (0 until lineArray.length()).map { index ->
            val line = lineArray.getJSONObject(index)
            ThermalLine(
                text = line.optString("text"),
                bold = line.optBoolean("bold"),
                align = line.optString("align").takeIf { it.isNotBlank() },
                doubleSize = line.optBoolean("doubleSize"),
            )
        }

        val printerJson = payload.getJSONObject("printer")
        val ip = printerJson.optString("ipAddress").trim()
        val port = printerJson.optInt("port", 9100)
        val configured = config.printers().filter { it.isActive && it.isReceipt }
        if (configured.isNotEmpty() && configured.none { it.matches(ip, port) }) {
            return errorJson("The printer $ip:$port is not configured for this branch. Refresh the bridge configuration.")
        }
        val printerName = configured.firstOrNull { it.matches(ip, port) }?.name
        val target = PrinterTarget(ip, port, paper, printerName)
        val cacheKey = "$jobId:$jobKey"
        val now = System.currentTimeMillis()

        synchronized(printed) {
            printed.entries.removeAll { it.value < now - 30 * 60 * 1000 }
            if (printed.containsKey(cacheKey)) {
                report("Ignored duplicate receipt $jobKey.")
                return JSONObject()
                    .put("success", true)
                    .put("duplicate", true)
                    .put("message", "This receipt was already printed.")
                    .put("printer", target.label)
                    .toString()
            }

            val error = PrinterClient.send(target, EscPos.build(lines, paper))
            if (error != null) {
                report("Print failed: $error")
                return JSONObject()
                    .put("success", false)
                    .put("message", error)
                    .put("printer", target.label)
                    .toString()
            }
            printed[cacheKey] = now
        }

        val message = "Receipt sent to ${target.label}."
        report(message)
        return JSONObject()
            .put("success", true)
            .put("message", message)
            .put("printer", target.label)
            .toString()
    }

    private fun guardPaired(): String? {
        if (!config.isPaired) {
            return "Pair this device with your store in the bridge app before printing."
        }
        if (config.revoked) {
            return "This print bridge was revoked in store settings. Generate a new pairing code and pair it again."
        }
        return null
    }

    private fun validateJob(payload: JSONObject): String? {
        if (payload.optLong("jobId", 0) <= 0) return "The print job id is invalid."
        if (payload.optString("jobKey").isBlank()) return "The print job key is missing."
        if (!payload.optString("platform").equals("ANDROID", ignoreCase = true)) {
            return "This bridge only prints Android jobs."
        }
        val paper = payload.optString("paperWidth")
        if (paper != "MM58" && paper != "MM80") return "Paper width must be MM58 or MM80."
        val printer = payload.optJSONObject("printer") ?: return "No Android printer was supplied with the job."
        if (printer.optString("ipAddress").isBlank()) return "The printer IP address is empty."
        if (printer.optInt("port", 0) !in 1..65535) return "The printer port is invalid."
        val document = payload.optJSONObject("document") ?: return "The document is missing."
        if (!document.optString("type").equals("SALE_RECEIPT", ignoreCase = true)) {
            return "Unsupported document type."
        }
        val lines = document.optJSONArray("lines") ?: return "The receipt has no lines."
        if (lines.length() == 0 || lines.length() > 300) return "The receipt line count is invalid."
        var total = 0
        for (index in 0 until lines.length()) {
            val line = lines.optJSONObject(index) ?: return "The receipt contains an invalid line."
            val text = line.optString("text")
            if (text.length > 200) return "A receipt line is too long."
            val align = line.optString("align")
            if (align.isNotEmpty() && align !in listOf("left", "center", "right")) {
                return "A receipt line has an invalid alignment."
            }
            total += text.length
        }
        if (total > 20000) return "The receipt content is too large."
        return null
    }

    private fun originAllowed(origin: String): Boolean {
        if (origin.isBlank()) return true
        val allowed = config.allowedOrigin
        if (allowed.isBlank()) return true
        return allowed.equals(origin.trimEnd('/'), ignoreCase = true)
    }

    private fun keyMatches(provided: String, expected: String): Boolean {
        if (provided.isEmpty() || expected.isEmpty()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        val left = digest.digest(provided.toByteArray(Charsets.UTF_8))
        val right = digest.digest(expected.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(left, right)
    }

    private fun corsHeaders(origin: String, privateNetwork: Boolean = false): Map<String, String> {
        if (origin.isBlank()) return emptyMap()
        val headers = mutableMapOf(
            "Access-Control-Allow-Origin" to origin,
            "Vary" to "Origin",
            "Access-Control-Allow-Headers" to "Content-Type, X-Bridge-Key",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Max-Age" to "600",
        )
        if (privateNetwork) headers["Access-Control-Allow-Private-Network"] = "true"
        return headers
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): String? {
        val lengthValue = headers["content-length"] ?: return null
        val length = lengthValue.toIntOrNull() ?: return null
        if (length < 0) return ""
        if (length > 512 * 1024) throw IllegalArgumentException("The request body is too large.")
        if (length == 0) return ""
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return String(bytes, 0, offset, Charsets.UTF_8)
    }

    private fun readLine(input: InputStream, max: Int): String? {
        val builder = StringBuilder()
        var previous = -1
        while (true) {
            val value = input.read()
            if (value == -1) return if (builder.isEmpty()) null else builder.toString()
            if (value == '\n'.code) {
                if (builder.isNotEmpty() && builder.last() == '\r') builder.setLength(builder.length - 1)
                return builder.toString()
            }
            builder.append(value.toChar())
            if (builder.length > max) return builder.substring(0, max)
            previous = value
        }
    }

    private fun respondJson(
        output: OutputStream,
        status: Int,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = extraHeaders.toMutableMap()
        headers["Content-Type"] = "application/json; charset=utf-8"
        respond(output, status, bytes, headers)
    }

    private fun respondEmpty(output: OutputStream, status: Int, extraHeaders: Map<String, String>) {
        respond(output, status, ByteArray(0), extraHeaders)
    }

    private fun respond(output: OutputStream, status: Int, body: ByteArray, headers: Map<String, String>) {
        try {
            val reason = when (status) {
                200 -> "OK"
                204 -> "No Content"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                403 -> "Forbidden"
                404 -> "Not Found"
                411 -> "Length Required"
                413 -> "Payload Too Large"
                431 -> "Request Header Fields Too Large"
                else -> "Error"
            }
            val builder = StringBuilder()
            builder.append("HTTP/1.1 $status $reason\r\n")
            builder.append("Content-Length: ${body.size}\r\n")
            builder.append("Connection: close\r\n")
            headers.forEach { (key, value) -> builder.append("$key: $value\r\n") }
            builder.append("\r\n")
            output.write(builder.toString().toByteArray(Charsets.UTF_8))
            if (body.isNotEmpty()) output.write(body)
            output.flush()
        } catch (_: Exception) {
        }
    }

    private fun errorJson(message: String): String =
        JSONObject().put("success", false).put("message", message).toString()

    private fun report(message: String) {
        try {
            onResult(message)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val APP_VERSION = "1.0.0"
    }
}
