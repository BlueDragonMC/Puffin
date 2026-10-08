package com.bluedragonmc.puffin.services

import com.bluedragonmc.puffin.services.QueueService.GameServer
import kotlin.test.Test
import kotlin.test.assertEquals

class PlacementCandidatesTest {

    @Test
    fun `up-to-date servers are preferred over draining ones`() {
        val current = GameServer("current", emptyList(), draining = false)
        val outdated = GameServer("outdated", emptyList(), draining = true)

        assertEquals(listOf(current), placementCandidates(listOf(outdated, current)))
    }

    @Test
    fun `draining servers are used when every server is draining`() {
        val outdated = GameServer("outdated", emptyList(), draining = true)
        val older = GameServer("older", emptyList(), draining = true)

        assertEquals(listOf(outdated, older), placementCandidates(listOf(outdated, older)))
    }

    @Test
    fun `no servers yields no candidates`() {
        assertEquals(emptyList(), placementCandidates(emptyList()))
    }
}
