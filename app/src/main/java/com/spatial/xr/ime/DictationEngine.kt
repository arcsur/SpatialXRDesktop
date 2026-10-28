package com.spatial.xr.ime

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.inputmethod.InputConnection

class DictationEngine(
    private val context: Context? = null
) {

    interface DictationListener {
        fun onTranscriptionInterim(interimText: String)
        fun onTranscriptionCommitted(committedText: String)
        fun onRmsChanged(rmsdB: Float)
        fun onError(errorCode: Int)
    }

    companion object {
        const val PAUSE_COMMIT_THRESHOLD_MS = 1500L // 1.5s pause
    }

    private var listener: DictationListener? = null
    private var isListening = false
    private var lastSpokenTimestampMs = 0L
    private var currentComposingText = ""

    fun setListener(listener: DictationListener?) {
        this.listener = listener
    }

    fun startListening(): Boolean {
        isListening = true
        currentComposingText = ""
        lastSpokenTimestampMs = SystemClock.uptimeMillis()
        return true
    }

    fun stopListening() {
        if (isListening && currentComposingText.isNotEmpty()) {
            commitComposingText(null)
        }
        isListening = false
    }

    fun isListening(): Boolean = isListening

    /**
     * Process incoming token from speech recognizer.
     * Injects composing text or commits upon sentence boundaries.
     */
    fun processIncomingSpeech(
        token: String,
        inputConnection: InputConnection?,
        timestampMs: Long = SystemClock.uptimeMillis()
    ): String {
        lastSpokenTimestampMs = timestampMs

        val isSentenceBoundary = token.endsWith(".") || token.endsWith("?") ||
                token.endsWith("!") || token.endsWith("\n")

        if (isSentenceBoundary) {
            val fullText = if (currentComposingText.isEmpty()) token else "$currentComposingText $token"
            inputConnection?.commitText(fullText, 1)
            listener?.onTranscriptionCommitted(fullText)
            currentComposingText = ""
            return fullText
        } else {
            currentComposingText = if (currentComposingText.isEmpty()) token else "$currentComposingText $token"
            inputConnection?.setComposingText(currentComposingText, 1)
            listener?.onTranscriptionInterim(currentComposingText)
            return currentComposingText
        }
    }

    /**
     * Check silence pause timeout (1.5 seconds) to commit any pending composing text.
     */
    fun checkSilenceTimeout(nowMs: Long = SystemClock.uptimeMillis(), inputConnection: InputConnection?): Boolean {
        if (isListening && currentComposingText.isNotEmpty()) {
            if (nowMs - lastSpokenTimestampMs >= PAUSE_COMMIT_THRESHOLD_MS) {
                commitComposingText(inputConnection)
                return true
            }
        }
        return false
    }

    fun commitComposingText(inputConnection: InputConnection?) {
        if (currentComposingText.isNotEmpty()) {
            val textToCommit = currentComposingText
            inputConnection?.commitText(textToCommit, 1)
            listener?.onTranscriptionCommitted(textToCommit)
            currentComposingText = ""
        }
    }

    fun notifyRmsChanged(rmsdB: Float) {
        listener?.onRmsChanged(rmsdB)
    }

    fun getCurrentComposingText(): String = currentComposingText
}
