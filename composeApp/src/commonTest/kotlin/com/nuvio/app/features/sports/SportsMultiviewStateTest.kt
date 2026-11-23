package com.nuvio.app.features.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SportsMultiviewStateTest {
    @Test
    fun addingTwoStreamsKeepsOnlyFocusedAudioEnabled() {
        val state = SportsMultiviewState().add("game-a").add("game-b")
        assertEquals(listOf("game-a", "game-b"), state.streamIds)
        assertFalse(state.canAdd)
        assertTrue(state.shouldPlayAudio("game-a"))
        assertFalse(state.shouldPlayAudio("game-b"))
        val focused = state.focus("game-b")
        assertFalse(focused.shouldPlayAudio("game-a"))
        assertTrue(focused.shouldPlayAudio("game-b"))
    }

    @Test
    fun duplicatesAndThirdStreamAreRejected() {
        val state = SportsMultiviewState().add("game-a").add("game-a").add("game-b").add("game-c")
        assertEquals(listOf("game-a", "game-b"), state.streamIds)
    }

    @Test
    fun removingFocusedStreamMovesAudioToRemainingStream() {
        val state = SportsMultiviewState().add("game-a").add("game-b").focus("game-b").remove("game-b")
        assertEquals(listOf("game-a"), state.streamIds)
        assertTrue(state.shouldPlayAudio("game-a"))
    }
}
