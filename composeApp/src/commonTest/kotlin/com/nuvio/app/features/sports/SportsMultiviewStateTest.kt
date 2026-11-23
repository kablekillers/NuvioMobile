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
    @Test
    fun fourGameModeKeepsExactlyOneAudibleStream() {
        val state = SportsMultiviewState().withLayout(4)
            .add("a").add("b").add("c").add("d").add("e").focus("d")
        assertEquals(listOf("a", "b", "c", "d"), state.streamIds)
        assertFalse(state.canAdd)
        assertEquals(listOf("d"), state.streamIds.filter { state.shouldPlayAudio(it) })
    }

    @Test
    fun switchingFromFourToTwoKeepsValidFocus() {
        val state = SportsMultiviewState().withLayout(4)
            .add("a").add("b").add("c").add("d").focus("d").withLayout(2)
        assertEquals(listOf("a", "b"), state.streamIds)
        assertTrue(state.shouldPlayAudio("a"))
        assertFalse(state.canAdd)
    }

}
