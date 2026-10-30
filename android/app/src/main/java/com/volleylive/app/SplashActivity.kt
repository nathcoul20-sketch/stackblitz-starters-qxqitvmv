package com.volleylive.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
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
        LoadingScreen.show(findViewById<View>(android.R.id.content))

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
        private const val DURATION_MS = 800L
    }
}
