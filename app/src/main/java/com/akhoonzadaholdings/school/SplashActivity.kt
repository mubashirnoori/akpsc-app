package com.akhoonzadaholdings.school

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AnimationUtils
import androidx.activity.ComponentActivity

class SplashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val logo = findViewById<android.widget.ImageView>(R.id.splashLogo)
        val anim = AnimationUtils.loadAnimation(this, R.anim.splash_scale_fade)
        logo.startAnimation(anim)

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

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}