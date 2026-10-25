package com.volleylive.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Court écran de chargement au lancement de l'appli : logo ECVB et « ECVB LIVE » au centre,
 * puis la page des réglages s'affiche en fondu.
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        val bebas = runCatching { Typeface.createFromAsset(assets, "fonts/BebasNeue-Regular.ttf") }.getOrNull()
        listOf(R.id.splashEcvb, R.id.splashLive).forEach { id -> bebas?.let { findViewById<TextView>(id).typeface = it } }

        // Apparition : le logo grandit légèrement en fondu, puis le titre
        val logo = findViewById<View>(R.id.splashLogo)
        val title = findViewById<View>(R.id.splashTitle)
        val progress = findViewById<View>(R.id.splashProgress)
        logo.alpha = 0f
        logo.scaleX = 0.85f
        logo.scaleY = 0.85f
        title.alpha = 0f
        progress.alpha = 0f
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(DecelerateInterpolator()).start()
        title.animate().alpha(1f).setStartDelay(180).setDuration(320).start()
        progress.animate().alpha(1f).setStartDelay(300).setDuration(300).start()

        handler.postDelayed({ openSettings() }, DURATION_MS)
    }

    private fun openSettings() {
        if (isFinishing || isDestroyed) return
        startActivity(Intent(this, MainActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val DURATION_MS = 1_300L
    }
}
