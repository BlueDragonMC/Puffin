package com.bluedragonmc.puffin.services

import kotlin.test.Test
import kotlin.test.assertEquals

class PlacementCandidatesTest {

    @Test
    fun `up-to-date servers are preferred over draining ones`() {
        val current = QueueServer("current", emptyList(), draining = false)
        val outdated = QueueServer("outdated", emptyList(), draining = true)

        assertEquals(listOf(current), placementCandidates(listOf(outdated, current)))
    }

    @Test
    fun `draining servers are used when every server is draining`() {
        val outdated = QueueServer("outdated", emptyList(), draining = true)
        val older = QueueServer("older", emptyList(), draining = true)

        assertEquals(listOf(outdated, older), placementCandidates(listOf(outdated, older)))
    }

    @Test
    fun `no servers yields no candidates`() {
        assertEquals(emptyList(), placementCandidates(emptyList()))
    }
}
