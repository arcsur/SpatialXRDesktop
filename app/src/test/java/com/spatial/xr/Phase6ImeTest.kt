package com.spatial.xr

import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.SurroundingText
import com.spatial.xr.ime.DictationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase6ImeTest {

    class MockInputConnection : InputConnection {
        var lastComposingText: String? = null
        var lastCommittedText: String? = null
        var performedAction: Int? = null

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun getSelectedText(flags: Int): CharSequence = ""
        override fun getCursorCapsMode(reqModes: Int): Int = 0
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = true
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = true

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            lastComposingText = text?.toString()
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean = true

        override fun finishComposingText(): Boolean {
            lastComposingText = null
            return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            lastCommittedText = text?.toString()
            lastComposingText = null
            return true
        }

        override fun commitCompletion(text: CompletionInfo?): Boolean = true
        override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = true
        override fun setSelection(start: Int, end: Int): Boolean = true
        override fun performEditorAction(editorAction: Int): Boolean {
            performedAction = editorAction
            return true
        }
        override fun performContextMenuAction(id: Int): Boolean = true
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun sendKeyEvent(event: KeyEvent?): Boolean = true
        override fun clearMetaKeyStates(states: Int): Boolean = true
        override fun reportFullscreenMode(enabled: Boolean): Boolean = true
        override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean = true
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = true
        override fun getHandler(): android.os.Handler? = null
        override fun closeConnection() {}
        override fun commitContent(inputContentInfo: android.view.inputmethod.InputContentInfo, flags: Int, opts: android.os.Bundle?): Boolean = true
    }

    @Test
    fun testStreamingDictationAndInterimTokens() {
        val engine = DictationEngine()
        val mockIc = MockInputConnection()

        engine.startListening()
        assertTrue(engine.isListening())

        // Stream interim word
        engine.processIncomingSpeech("Open", mockIc)
        assertEquals("Open", mockIc.lastComposingText)

        engine.processIncomingSpeech("YouTube", mockIc)
        assertEquals("Open YouTube", mockIc.lastComposingText)

        // Stream word with sentence boundary punctuation
        engine.processIncomingSpeech("fullscreen.", mockIc)
        // Should immediately commit "Open YouTube fullscreen."
        assertEquals("Open YouTube fullscreen.", mockIc.lastCommittedText)
        assertEquals(null, mockIc.lastComposingText)
    }

    @Test
    fun testSilenceTimeoutCommit() {
        val engine = DictationEngine()
        val mockIc = MockInputConnection()

        engine.startListening()
        val now = 10000L
        engine.processIncomingSpeech("Search for XR articles", mockIc, now)
        assertEquals("Search for XR articles", mockIc.lastComposingText)
        val timedOutEarly = engine.checkSilenceTimeout(now + 500L, mockIc)
        assertFalse(timedOutEarly)
        assertEquals(null, mockIc.lastCommittedText)

        // 1600ms later: silence threshold reached -> commits text
        val timedOut = engine.checkSilenceTimeout(now + 1600L, mockIc)
        assertTrue(timedOut)
        assertEquals("Search for XR articles", mockIc.lastCommittedText)
    }
}
