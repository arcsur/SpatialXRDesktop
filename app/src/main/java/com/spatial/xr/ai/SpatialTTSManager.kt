package com.spatial.xr.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class SpatialTTSManager(
    private val context: Context? = null
) : TextToSpeech.OnInitListener {

    data class TextCard3D(
        var text: String = "",
        var isVisible: Boolean = false,
        var positionX: Float = 0f,
        var positionY: Float = 0f, // Eye level
        var positionZ: Float = -1.5f, // 1.5m ahead
        var opacity: Float = 0.85f,
        var spawnTimestampMs: Long = 0L
    )

    companion object {
        const val AUTO_DISMISS_DELAY_MS = 10000L // 10 seconds
    }

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val activeCard = TextCard3D()

    fun initializeTts(): Boolean {
        if (context != null) {
            tts = TextToSpeech(context, this)
        }
        return true
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            isTtsReady = true
        }
    }

    fun speakToTempleSpeakers(text: String): Boolean {
        if (isTtsReady && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "SpatialAgentTTS")
            return true
        }
        return false
    }

    fun spawnTextCard(initialText: String, nowMs: Long = System.currentTimeMillis()): TextCard3D {
        activeCard.text = initialText
        activeCard.isVisible = true
        activeCard.spawnTimestampMs = nowMs
        return activeCard
    }

    fun appendTokenToCard(token: String) {
        if (activeCard.isVisible) {
            activeCard.text += token
        }
    }

    fun dismissCard() {
        activeCard.isVisible = false
        activeCard.text = ""
    }

    fun checkAutoDismiss(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (activeCard.isVisible && (nowMs - activeCard.spawnTimestampMs >= AUTO_DISMISS_DELAY_MS)) {
            dismissCard()
            return true
        }
        return false
    }

    fun getActiveCard(): TextCard3D = activeCard

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
    }
}
