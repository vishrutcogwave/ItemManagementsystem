package com.itemmanagement.printbridge

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var config: AppConfig
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusValue: TextView
    private lateinit var keyValue: TextView
    private lateinit var storeValue: TextView
    private lateinit var printersValue: TextView
    private lateinit var syncValue: TextView
    private lateinit var lastResultValue: TextView
    private lateinit var serverUrlInput: EditText
    private lateinit var pairingCodeInput: EditText
    private lateinit var deviceNameInput: EditText
    private lateinit var portInput: EditText
    private lateinit var originInput: EditText
    private lateinit var startOnBootSwitch: Switch
    private lateinit var pairButton: Button

    private val statusUpdater = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig(this)
        setContentView(R.layout.activity_main)
        bindViews()
        populate()
        wireActions()
        requestNotificationPermission()
        PrintBridgeService.start(this)
        handler.postDelayed({ render() }, 400)
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.removeCallbacks(statusUpdater)
        handler.post(statusUpdater)
    }

    override fun onPause() {
        handler.removeCallbacks(statusUpdater)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        statusValue = findViewById(R.id.statusValue)
        keyValue = findViewById(R.id.keyValue)
        storeValue = findViewById(R.id.storeValue)
        printersValue = findViewById(R.id.printersValue)
        syncValue = findViewById(R.id.syncValue)
        lastResultValue = findViewById(R.id.lastResultValue)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        pairingCodeInput = findViewById(R.id.pairingCodeInput)
        deviceNameInput = findViewById(R.id.deviceNameInput)
        portInput = findViewById(R.id.portInput)
        originInput = findViewById(R.id.originInput)
        startOnBootSwitch = findViewById(R.id.startOnBootSwitch)
        pairButton = findViewById(R.id.pairButton)
    }

    private fun populate() {
        keyValue.text = config.bridgeKey
        serverUrlInput.setText(config.serverUrl)
        deviceNameInput.setText(config.deviceName)
        portInput.setText(config.port.toString())
        originInput.setText(config.allowedOrigin)
        startOnBootSwitch.isChecked = config.startOnBoot
        pairingCodeInput.setText("")
    }

    private fun wireActions() {
        findViewById<Button>(R.id.copyKeyButton).setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Print bridge key", config.bridgeKey))
            toast("Bridge key copied. Paste it in Settings → Printers.")
        }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            PrintBridgeService.start(this)
            handler.postDelayed({ render() }, 500)
            toast("Starting the print bridge...")
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            PrintBridgeService.stop(this)
            handler.postDelayed({ render() }, 500)
            toast("Print bridge stopped.")
        }

        pairButton.setOnClickListener { pair() }

        findViewById<Button>(R.id.refreshButton).setOnClickListener { refreshConfig() }

        findViewById<Button>(R.id.saveNetworkButton).setOnClickListener { saveNetworkSettings() }

        startOnBootSwitch.setOnCheckedChangeListener { _, checked ->
            config.startOnBoot = checked
        }

        findViewById<Button>(R.id.batteryButton).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    toast("Open Android battery settings and allow this app to run in the background.")
                }
            }
        }
    }

    private fun pair() {
        val base = serverUrlInput.text.toString().trim()
        val code = pairingCodeInput.text.toString().trim()
        val name = deviceNameInput.text.toString().trim()
        if (base.isBlank()) {
            toast("Enter the store website address first.")
            return
        }
        if (code.isBlank()) {
            toast("Enter the one-time pairing code shown in Settings → Printers.")
            return
        }
        pairButton.isEnabled = false
        pairButton.text = "Pairing..."
        executor.execute {
            val outcome = BackendClient(config).pair(base, code, name)
            runOnUiThread {
                pairButton.isEnabled = true
                pairButton.text = "Pair device"
                toast(outcome.message)
                if (outcome.success) {
                    pairingCodeInput.setText("")
                    serverUrlInput.setText(config.serverUrl)
                    deviceNameInput.setText(config.deviceName)
                    PrintBridgeService.stop(this)
                    PrintBridgeService.start(this)
                    render()
                }
            }
        }
    }

    private fun refreshConfig() {
        if (!config.isPaired) {
            toast("Pair this device with your store first.")
            return
        }
        val button = findViewById<Button>(R.id.refreshButton)
        button.isEnabled = false
        button.text = "Refreshing..."
        executor.execute {
            val outcome = BackendClient(config).fetchConfig()
            runOnUiThread {
                button.isEnabled = true
                button.text = "Refresh printer config"
                toast(outcome.message)
                render()
            }
        }
    }

    private fun saveNetworkSettings() {
        val port = portInput.text.toString().toIntOrNull()
        if (port == null || port !in 1024..65535) {
            toast("Bridge port must be between 1024 and 65535.")
            return
        }
        val origin = originInput.text.toString().trim().trimEnd('/')
        if (origin.isNotEmpty() && !origin.startsWith("http://", true) && !origin.startsWith("https://", true)) {
            toast("Allowed website must start with http:// or https://")
            return
        }
        val portChanged = config.port != port
        config.port = port
        config.allowedOrigin = origin
        config.startOnBoot = startOnBootSwitch.isChecked
        if (portChanged) {
            PrintBridgeService.stop(this)
            PrintBridgeService.start(this)
            toast("Saved. Bridge restarting on port $port. Update the port in Settings → Printers too.")
        } else {
            toast("Network settings saved.")
        }
        render()
    }

    private fun render() {
        keyValue.text = config.bridgeKey
        statusValue.text = if (PrintBridgeService.isRunning) {
            "Running on 127.0.0.1:${config.port}"
        } else {
            "Stopped — tap Start to run the bridge"
        }

        storeValue.text = when {
            config.revoked -> "This bridge was revoked in store settings. Generate a new pairing code and pair again."
            !config.isPaired -> "Not paired yet. Generate a pairing code in the website to connect this device."
            else -> "Store: ${config.storeName}\nBranch: ${config.branchName}\nDevice: ${config.deviceName}"
        }

        val printers = config.printers()
        printersValue.text = if (printers.isEmpty()) {
            "No printers synced yet. Pair the device or tap Refresh printer config."
        } else {
            printers.joinToString("\n") { printer ->
                val mark = if (printer.isActive) "•" else "×"
                "$mark ${printer.name} — ${printer.ipAddress}:${printer.port} · ${printer.paperLabel} · ${printer.purpose}"
            }
        }

        syncValue.text = if (config.lastSync > 0) {
            "Printer config synced ${formatTime(config.lastSync)}"
        } else {
            "Printer config has not been fetched yet."
        }
        lastResultValue.text = "Last bridge result: ${config.lastResult}"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun formatTime(epochMillis: Long): String =
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(epochMillis))
}
