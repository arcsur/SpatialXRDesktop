package com.spatial.xr

import com.spatial.xr.ai.GeminiSpatialAgent
import com.spatial.xr.ai.SpatialTTSManager
import com.spatial.xr.ai.VisualQAPipeline
import com.spatial.xr.window.SpatialWindowManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase7AssistantTest {

    @Test
    fun testWakeTriggers() {
        val agent = GeminiSpatialAgent()
        assertTrue(agent.isWakeTriggerValid(GeminiSpatialAgent.WakeTrigger.KEYWORD_HEY_GEMINI, "Hey Gemini, organize windows"))
        assertFalse(agent.isWakeTriggerValid(GeminiSpatialAgent.WakeTrigger.KEYWORD_HEY_GEMINI, "Hello Google"))
        assertTrue(agent.isWakeTriggerValid(GeminiSpatialAgent.WakeTrigger.FRAME_BUTTON_PRESS))
        assertTrue(agent.isWakeTriggerValid(GeminiSpatialAgent.WakeTrigger.THREE_FINGER_TAP))
    }

    @Test
    fun testToolRouterAndFunctionCalling() {
        val wm = SpatialWindowManager()
        val agent = GeminiSpatialAgent(wm)

        // Verify tool schema declarations
        val tools = agent.getToolDeclarationsJson()
        assertTrue(tools.length() >= 3)

        // Execute launch_app tool call
        val launchCall = JSONObject().apply {
            put("name", "launch_app")
            put("args", JSONObject().put("package_name", "com.google.android.youtube"))
        }

        val success = agent.parseAndExecuteToolCall(launchCall)
        assertTrue(success)
        assertEquals(1, wm.getAllWindows().size)
        assertEquals("com.google.android.youtube", wm.getAllWindows().first().packageName)
    }

    @Test
    fun testVisualQAPayloadPackaging() {
        val pipeline = VisualQAPipeline()
        val dummyBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46) // 'RIFF' header
        val payload = pipeline.createLookAndAskPayload(
            userQuestion = "What model of monitor is in front of me?",
            imageBytes = dummyBytes,
            mimeType = "image/webp"
        )

        assertNotNull(payload.base64Data)
        assertTrue(payload.promptText.contains("What model of monitor"))
        assertTrue(payload.promptText.contains(VisualQAPipeline.SYSTEM_PROMPT_PREFIX))

        val root = payload.jsonPayload
        val contents = root.getJSONArray("contents")
        assertEquals(1, contents.length())
        val parts = contents.getJSONObject(0).getJSONArray("parts")
        assertEquals(2, parts.length()) // text part + inline_data part

        val inlineData = parts.getJSONObject(1).getJSONObject("inline_data")
        assertEquals("image/webp", inlineData.getString("mime_type"))
        assertEquals(payload.base64Data, inlineData.getString("data"))
    }

    @Test
    fun testSpatialTTSCardLifecycle() {
        val ttsManager = SpatialTTSManager()

        val card = ttsManager.spawnTextCard("Looking at ", nowMs = 1000L)
        assertTrue(card.isVisible)
        assertEquals(0f, card.positionY, 1e-4f)      // Eye level
        assertEquals(-1.5f, card.positionZ, 1e-4f)  // 1.5m ahead

        // Stream tokens
        ttsManager.appendTokenToCard("VITURE glasses")
        assertEquals("Looking at VITURE glasses", card.text)

        // 5s later: should NOT auto dismiss (threshold is 10s)
        val dismissedEarly = ttsManager.checkAutoDismiss(nowMs = 6000L)
        assertFalse(dismissedEarly)
        assertTrue(card.isVisible)

        // 11s later: auto dismiss triggers
        val autoDismissed = ttsManager.checkAutoDismiss(nowMs = 12000L)
        assertTrue(autoDismissed)
        assertFalse(card.isVisible)
    }

    private fun assertNotNull(obj: Any?) {
        org.junit.Assert.assertNotNull(obj)
    }
}
