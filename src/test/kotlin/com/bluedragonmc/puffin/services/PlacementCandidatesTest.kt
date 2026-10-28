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

    @Test
    fun `not-ready servers are not candidates`() {
        val ready = QueueServer("ready", emptyList(), ready = true)
        val starting = QueueServer("starting", emptyList(), ready = false)

        assertEquals(listOf(ready), placementCandidates(listOf(starting, ready)))
    }

    @Test
    fun `terminating servers are never candidates`() {
        val draining = QueueServer("draining", emptyList(), draining = true)
        val terminating = QueueServer("terminating", emptyList(), draining = true, terminating = true)

        assertEquals(listOf(draining), placementCandidates(listOf(terminating, draining)))
    }

    @Test
    fun `not-ready draining servers are not used as a fallback`() {
        val draining = QueueServer("draining", emptyList(), draining = true)
        val notReady = QueueServer("not-ready", emptyList(), ready = false, draining = true)

        assertEquals(listOf(draining), placementCandidates(listOf(notReady, draining)))
    }
}
