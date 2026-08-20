package com.akhoonzadaholdings.school.relay

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

        binding.btnTestConnection.setOnClickListener { testConnection() }

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
            RelayPrefs.clear(this)
            binding.editEndpoint.setText(RelayPrefs.getEndpoint(this))
            binding.editToken.setText("")
            binding.editLabel.setText("")
            binding.switchEnabled.isChecked = false
            updateStatusText()
            Toast.makeText(this, "Device forgotten — relay stopped", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Relay enabled on this device", Toast.LENGTH_SHORT).show()
        } else {
            RelayForegroundService.stop(this)
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
