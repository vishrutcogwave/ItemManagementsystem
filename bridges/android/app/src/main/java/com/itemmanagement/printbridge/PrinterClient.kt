package com.itemmanagement.printbridge

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class PrinterTarget(
    val ipAddress: String,
    val port: Int,
    val paperWidth: String,
    val name: String? = null,
) {
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: "$ipAddress:$port"
}

object PrinterClient {
    fun send(target: PrinterTarget, data: ByteArray): String? {
        if (target.ipAddress.isBlank()) return "The printer address is empty."
        if (target.port !in 1..65535) return "The printer port ${target.port} is invalid."
        return try {
            val address = java.net.InetAddress.getByName(target.ipAddress.trim())
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, target.port), 6000)
                socket.soTimeout = 6000
                socket.getOutputStream().apply {
                    write(data)
                    flush()
                }
            }
            null
        } catch (ex: UnknownHostException) {
            "Cannot find the printer at ${target.ipAddress}. Check the IP address in Settings → Printers."
        } catch (ex: ConnectException) {
            "The printer ${target.label} refused the connection. Check that it is switched on and on the same Wi-Fi/LAN."
        } catch (ex: NoRouteToHostException) {
            "There is no network route to ${target.label}. Connect this device to the store Wi-Fi."
        } catch (ex: SocketTimeoutException) {
            "The printer ${target.label} did not respond in time. Check its IP address and port."
        } catch (ex: IOException) {
            "Could not send the receipt to ${target.label}: ${ex.message ?: "network error"}"
        } catch (ex: Exception) {
            "Could not print to ${target.label}: ${ex.message ?: "unexpected error"}"
        }
    }
}
