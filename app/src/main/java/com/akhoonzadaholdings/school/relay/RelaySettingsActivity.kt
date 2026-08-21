package com.akhoonzadaholdings.school.relay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.akhoonzadaholdings.school.databinding.ActivityRelaySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reached only via the hidden long-press gesture on the splash screen (see SplashActivity).
 * Not linked from anywhere in the normal app UI — a parent/student/staff member who never
 * needs to configure a relay device will never stumble into this screen by accident.
 *
 * Configuring a device here does nothing server-side by itself: the token pasted in must
 * already exist in Settings > SMS Gateway Devices (admin panel), which is also what
 * decides which staff member's messages (if any) route to this specific phone.
 */
class RelaySettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRelaySettingsBinding

    private val requestSmsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            saveAndMaybeStart()
        } else {
            Toast.makeText(this, "SMS permission is required to run the relay on this device", Toast.LENGTH_LONG).show()
            binding.switchEnabled.isChecked = false
        }
    }

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* not fatal if denied on Android 13+ — service still runs, just less visibly */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRelaySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = "SMS Relay Device Setup"

        binding.editEndpoint.setText(RelayPrefs.getEndpoint(this))
        binding.editToken.setText(RelayPrefs.getToken(this))
        binding.editLabel.setText(RelayPrefs.getLabel(this))
        binding.switchEnabled.isChecked = RelayPrefs.isEnabled(this)
        updateStatusText()
        updateBatteryStatusText()

        binding.btnTestConnection.setOnClickListener { testConnection() }

        binding.btnBatteryOptimization.setOnClickListener { requestIgnoreBatteryOptimizations() }

        binding.btnSave.setOnClickListener {
            if (binding.editToken.text.toString().isBlank()) {
                Toast.makeText(this, "Paste the device token from the admin panel first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (binding.switchEnabled.isChecked) {
                ensureNotificationPermission()
                ensureSmsPermissionThenSave()
            } else {
                saveAndMaybeStart()
            }
        }

        binding.btnForgetDevice.setOnClickListener {
            RelayForegroundService.stop(this)
            RelayWatchdog.cancel(this)
            RelayPrefs.clear(this)
            binding.editEndpoint.setText(RelayPrefs.getEndpoint(this))
            binding.editToken.setText("")
            binding.editLabel.setText("")
            binding.switchEnabled.isChecked = false
            updateStatusText()
            Toast.makeText(this, "Device forgotten — relay stopped", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh after returning from the system battery-optimization dialog, which is a
        // separate screen this activity doesn't get a normal result callback from.
        updateBatteryStatusText()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (isIgnoringBatteryOptimizations()) {
            Toast.makeText(this, "Already exempted — this device can run in the background", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Some OEMs (MIUI in particular) block this dialog outright — fall back to the
            // general battery-settings screen so the user can find the equivalent toggle
            // manually (often under a separate "Autostart"/"No restrictions" entry there).
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Couldn't open battery settings — check your phone's battery/autostart settings manually", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateBatteryStatusText() {
        binding.textBatteryStatus.text = if (isIgnoringBatteryOptimizations()) {
            "✓ Exempted — this device can run in the background"
        } else {
            "✗ Not exempted yet — the relay may get killed after a while until this is granted"
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun ensureSmsPermissionThenSave() {
        val hasSms = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED
        if (hasSms) {
            saveAndMaybeStart()
        } else {
            requestSmsPermission.launch(Manifest.permission.SEND_SMS)
        }
    }

    private fun saveAndMaybeStart() {
        val endpoint = binding.editEndpoint.text.toString().trim()
        val token = binding.editToken.text.toString().trim()
        val label = binding.editLabel.text.toString().trim()
        val enabled = binding.switchEnabled.isChecked

        RelayPrefs.save(this, endpoint, token, label, enabled)

        if (enabled) {
            RelayForegroundService.start(this)
            RelayWatchdog.schedule(this)
            Toast.makeText(this, "Relay enabled on this device", Toast.LENGTH_SHORT).show()
        } else {
            RelayForegroundService.stop(this)
            RelayWatchdog.cancel(this)
            Toast.makeText(this, "Relay disabled", Toast.LENGTH_SHORT).show()
        }
        updateStatusText()
    }

    private fun testConnection() {
        val endpoint = binding.editEndpoint.text.toString().trim()
        val token = binding.editToken.text.toString().trim()
        if (token.isBlank()) {
            Toast.makeText(this, "Enter a token first", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnTestConnection.isEnabled = false
        binding.textStatus.text = "Testing…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    GatewayApi.healthCheck(endpoint, token)
                    "ok" to null
                } catch (e: GatewayApi.GatewayException) {
                    null to (e.message ?: "Failed (HTTP ${e.httpCode})")
                } catch (e: Exception) {
                    null to (e.message ?: "Connection failed")
                }
            }
            binding.btnTestConnection.isEnabled = true
            if (result.first != null) {
                binding.textStatus.text = "✓ Connected — device recognized by server"
            } else {
                binding.textStatus.text = "✗ ${result.second}"
            }
        }
    }

    private fun updateStatusText() {
        binding.textStatus.text = if (RelayPrefs.isConfigured(this) && RelayPrefs.isEnabled(this)) {
            "Relay is currently ON for this device"
        } else if (RelayPrefs.isConfigured(this)) {
            "Device configured but relay is OFF"
        } else {
            "No device token set"
        }
    }
}
