package com.volleylive.app

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * Adresses IPv4 locales du téléphone (Wi-Fi, ou point d'accès si le téléphone partage sa connexion).
     * Le Wi-Fi est placé en premier.
     */
    fun localIpAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .map { it.hostAddress ?: "" }
            }
            .filter { it.isNotEmpty() }
            .distinct()
    } catch (e: Exception) {
        emptyList()
    }

    fun qrCode(content: String, size: Int = 600): Bitmap {
        val matrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1)
        )
        val pixels = IntArray(size * size) { i ->
            if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
