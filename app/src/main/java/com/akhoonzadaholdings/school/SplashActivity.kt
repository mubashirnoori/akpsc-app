package com.akhoonzadaholdings.school

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AnimationUtils
import androidx.activity.ComponentActivity
import com.akhoonzadaholdings.school.relay.RelaySettingsActivity

class SplashActivity : ComponentActivity() {

    // Not shown anywhere in the app's normal navigation — five taps on the splash
    // logo within 2s opens SMS relay device setup. Anyone can find it by trying,
    // but it does nothing without a device token from the admin panel, so there's
    // no privilege gained by discovering the gesture itself.
    private var tapCount = 0
    private var firstTapTime = 0L
    private var navigatedAway = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val logo = findViewById<android.widget.ImageView>(R.id.splashLogo)
        val anim = AnimationUtils.loadAnimation(this, R.anim.splash_scale_fade)
        logo.startAnimation(anim)

        logo.setOnClickListener { onLogoTapped() }

        // Minimum splash time so the logo animation is visible even if check is instant
        val minSplashTime = 1200L
        val startTime = System.currentTimeMillis()

        UpdateChecker.check(this) {
            val elapsed = System.currentTimeMillis() - startTime
            val remaining = (minSplashTime - elapsed).coerceAtLeast(0)

            Handler(Looper.getMainLooper()).postDelayed({
                goToMain()
            }, remaining)
        }
    }

    private fun onLogoTapped() {
        val now = System.currentTimeMillis()
        if (now - firstTapTime > 2000L) {
            firstTapTime = now
            tapCount = 0
        }
        tapCount++
        if (tapCount >= 5) {
            tapCount = 0
            navigatedAway = true
            startActivity(Intent(this, RelaySettingsActivity::class.java))
            finish()
        }
    }

    private fun goToMain() {
        if (navigatedAway || isFinishing) return
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}