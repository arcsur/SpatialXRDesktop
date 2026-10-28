package com.spatial.xr

import com.spatial.xr.ai.GeminiSpatialAgent
import com.spatial.xr.tutorial.TutorialManager
import com.spatial.xr.tutorial.TutorialManager.TutorialTopic
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorialManagerTest {

    @Test
    fun testFirstLaunchPreferenceTracking() {
        val manager = TutorialManager()
        assertFalse(manager.isTutorialCompleted())

        manager.setTutorialCompleted(true)
        assertTrue(manager.isTutorialCompleted())

        manager.setTutorialCompleted(false)
        assertFalse(manager.isTutorialCompleted())
    }

    @Test
    fun testStepProgressionAndContent() {
        val manager = TutorialManager()
        assertEquals(5, manager.getStepCount())

        manager.startTutorial()
        assertTrue(manager.isVisible())
        assertEquals(0, manager.getCurrentStepIndex())
        assertEquals(TutorialTopic.POINTER_PINCH, manager.getCurrentStep().topic)

        // Step 1 -> 2
        assertTrue(manager.nextStep())
        assertEquals(1, manager.getCurrentStepIndex())
        assertEquals(TutorialTopic.RESIZE_MOVE, manager.getCurrentStep().topic)

        // Step 2 -> 3
        assertTrue(manager.nextStep())
        assertEquals(2, manager.getCurrentStepIndex())
        assertEquals(TutorialTopic.PALM_DOCK, manager.getCurrentStep().topic)

        // Step 3 -> 4
        assertTrue(manager.nextStep())
        assertEquals(3, manager.getCurrentStepIndex())
        assertEquals(TutorialTopic.AIR_SWIPE_CINEMA, manager.getCurrentStep().topic)

        // Step 4 -> 5
        assertTrue(manager.nextStep())
        assertEquals(4, manager.getCurrentStepIndex())
        assertEquals(TutorialTopic.GEMINI_VOICE, manager.getCurrentStep().topic)

        // Step 5 next completes tutorial
        val hasMore = manager.nextStep()
        assertFalse(hasMore)
        assertFalse(manager.isVisible())
        assertTrue(manager.isTutorialCompleted())
    }

    @Test
    fun testTopicQueryShortcuts() {
        val manager = TutorialManager()

        manager.startTutorialForQuery("resize")
        assertEquals(1, manager.getCurrentStepIndex())
        assertEquals(TutorialTopic.RESIZE_MOVE, manager.getCurrentStep().topic)

        manager.startTutorialForQuery("dock")
        assertEquals(2, manager.getCurrentStepIndex())
        assertEquals(TutorialTopic.PALM_DOCK, manager.getCurrentStep().topic)

        manager.startTutorialForQuery("swipe")
        assertEquals(3, manager.getCurrentStepIndex())
        assertEquals(TutorialTopic.AIR_SWIPE_CINEMA, manager.getCurrentStep().topic)

        manager.startTutorialForQuery("gemini")
        assertEquals(4, manager.getCurrentStepIndex())
        assertEquals(TutorialTopic.GEMINI_VOICE, manager.getCurrentStep().topic)
    }

    @Test
    fun testGeminiTutorialIntegration() {
        val tutorialManager = TutorialManager()
        val agent = GeminiSpatialAgent(tutorialManager = tutorialManager)

        // 1. Verify show_usage_tutorial schema in tools
        val tools = agent.getToolDeclarationsJson()
        var foundTutorialTool = false
        for (i in 0 until tools.length()) {
            if (tools.getJSONObject(i).getString("name") == "show_usage_tutorial") {
                foundTutorialTool = true
                break
            }
        }
        assertTrue(foundTutorialTool)

        // 2. Direct tool invocation
        val toolCall = JSONObject().apply {
            put("name", "show_usage_tutorial")
            put("args", JSONObject().put("topic", "dock"))
        }
        val executed = agent.parseAndExecuteToolCall(toolCall)
        assertTrue(executed)
        assertTrue(tutorialManager.isVisible())
        assertEquals(TutorialTopic.PALM_DOCK, tutorialManager.getCurrentStep().topic)

        // 3. Natural language voice query intent detection
        val response = agent.processVoiceIntent("Hey Gemini, can you help me understand how to use this?")
        assertNotNull(response.textResponse)
        assertEquals(1, response.executedToolCalls.size)
        assertEquals("show_usage_tutorial", response.executedToolCalls[0].name)
        assertTrue(tutorialManager.isVisible())

        // Specific topic query
        val resizeResponse = agent.processVoiceIntent("How do I resize windows in XR?")
        assertEquals("resize", resizeResponse.executedToolCalls[0].args.getString("topic"))
        assertEquals(TutorialTopic.RESIZE_MOVE, tutorialManager.getCurrentStep().topic)
    }
}
