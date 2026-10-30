package com.volleylive.app

import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView

/** Logo ECVB + « ECVB LIVE » + barre de chargement (layout loading_content) : police et apparition. */
object LoadingScreen {

    /** Applique la police du live et lance l'apparition (logo qui grandit en fondu, puis le titre). */
    fun show(root: View) {
        runCatching { Typeface.createFromAsset(root.context.assets, "fonts/BebasNeue-Regular.ttf") }.getOrNull()?.let { bebas ->
            listOf(R.id.splashEcvb, R.id.splashLive).forEach { root.findViewById<TextView>(it).typeface = bebas }
        }
        val logo = root.findViewById<View>(R.id.splashLogo)
        val title = root.findViewById<View>(R.id.splashTitle)
        val progress = root.findViewById<View>(R.id.splashProgress)
        logo.alpha = 0f
        logo.scaleX = 0.88f
        logo.scaleY = 0.88f
        title.alpha = 0f
        progress.alpha = 0f
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
        title.animate().alpha(1f).setStartDelay(110).setDuration(220).start()
        progress.animate().alpha(1f).setStartDelay(160).setDuration(200).start()
    }
}
