package com.volleylive.app

import org.json.JSONObject

/**
 * État du téléphone qui filme (batterie, température, réseau), mis à jour par l'écran caméra
 * et transmis à la télécommande.
 */
object DeviceStatus {
    @Volatile var battery: Int = -1
    @Volatile var charging: Boolean = false
    /** Température de la batterie en °C (indicateur de chauffe du téléphone). */
    @Volatile var temperature: Float = 0f
    @Volatile var live: Boolean = false
    /** Débit réellement envoyé (bits/s). */
    @Volatile var bitrate: Long = 0
    @Volatile var weakNetwork: Boolean = false
    @Volatile var quality: String = "Auto"

    fun toJson(): JSONObject = JSONObject()
        .put("battery", battery)
        .put("charging", charging)
        .put("temperature", temperature.toDouble())
        .put("live", live)
        .put("bitrate", bitrate)
        .put("weak", weakNetwork)
        .put("quality", quality)
}
