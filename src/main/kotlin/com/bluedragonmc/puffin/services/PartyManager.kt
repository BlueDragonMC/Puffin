package com.bluedragonmc.puffin.services

import com.bluedragonmc.puffin.app.ApplicationScope
import com.bluedragonmc.puffin.util.Utils
import com.google.inject.Inject
import com.google.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

interface IPartyManager {
    fun getParties(): Set<Party>
    fun partyOf(player: UUID): Party?
    fun registerPartyUpdateCallback(cb: (action: String, id: String, party: Party?) -> Unit)
    fun createParty(leader: UUID): Party
    suspend fun getUsername(uuid: UUID): String
    fun getMembers(party: Party): List<UUID>
    fun getLeader(party: Party): UUID
    fun getInvitations(party: Party): Map<UUID, Job>
    fun getMarathon(party: Party): Marathon?
    fun hasInvitation(party: Party, player: UUID): Boolean
    fun addMember(party: Party, player: UUID)
    fun removeMember(party: Party, player: UUID)
    fun setLeader(party: Party, newLeader: UUID)
    fun addInvitation(party: Party, player: UUID, job: Job)
    fun removeInvitation(party: Party, player: UUID)
    fun startMarathon(party: Party, durationMs: Long)
    fun endMarathon(party: Party)
    fun addMarathonPoints(party: Party, uuid: UUID, amount: Int)
    suspend fun formatMarathonLeaderboard(marathon: Marathon): String
}

/**
 * Handles creating parties, party chat, invitations, warps, and transfers.
 */
@Singleton
class PartyManager @Inject constructor(
    private val databaseConnection: DatabaseConnection,
    private val playerTracker: IPlayerTracker,
    private val applicationScope: ApplicationScope,
) : Service(), IPartyManager {

    private val parties = mutableSetOf<Party>()

    /** Guards [parties] and every party's members, invitations, leader, and marathon. */
    private val partyLock = Any()

    private val partyUpdateCallbacks = mutableListOf<(action: String, id: String, party: Party?) -> Unit>()

    override fun start() {
        // React to logouts through PlayerTracker's callback rather than depending on it directly.
        playerTracker.registerLogoutCallback { onLogout(it) }
    }

    override fun getParties() = synchronized(partyLock) { parties.toSet() }
    override fun partyOf(player: UUID) = synchronized(partyLock) { parties.find { player in it.members } }

    override fun createParty(leader: UUID) = synchronized(partyLock) {
        val party = Party(mutableListOf(leader), mutableMapOf(), leader)
        parties.add(party)
        notifyPartyUpdate("add", party.id, party)
        return@synchronized party
    }

    override fun registerPartyUpdateCallback(cb: (action: String, id: String, party: Party?) -> Unit) {
        partyUpdateCallbacks.add(cb)
    }

    /**
     * Returns the username of the UUID, with an (optional) MiniMessage-formatted color prepended.
     */
    override suspend fun getUsername(uuid: UUID): String {
        val color = databaseConnection.getPlayerNameColor(uuid)
        val username = databaseConnection.getPlayerName(uuid) ?: uuid.toString()
        return "<$color>$username"
    }

    private suspend fun UUID.name(): String = getUsername(this)

    override fun getMembers(party: Party) = synchronized(partyLock) { party.members.toList() }
    override fun getLeader(party: Party) = synchronized(partyLock) { party.leader }
    override fun getMarathon(party: Party) = synchronized(partyLock) { party.marathon }
    override fun getInvitations(party: Party) = synchronized(partyLock) { party.invitations.toMap() }
    override fun hasInvitation(party: Party, player: UUID) = synchronized(partyLock) { party.invitations.containsKey(player) }

    override fun addMember(party: Party, player: UUID) = synchronized(partyLock) {
        party.members.add(player)
        party.invitations.remove(player)?.cancel()
        update(party)
    }

    override fun removeMember(party: Party, player: UUID) = synchronized(partyLock) {
        party.members.remove(player)
        update(party)
    }

    override fun setLeader(party: Party, newLeader: UUID) = synchronized(partyLock) {
        val changed = party.leader != newLeader
        party.leader = newLeader
        if (changed) update(party)
    }

    override fun addInvitation(party: Party, player: UUID, job: Job) = synchronized(partyLock) {
        party.invitations[player]?.cancel()
        party.invitations[player] = job
        update(party)
    }

    override fun removeInvitation(party: Party, player: UUID) = synchronized(partyLock) {
        party.invitations.remove(player)?.cancel()
        update(party)
    }

    override fun startMarathon(party: Party, durationMs: Long) = synchronized(partyLock) {
        val endsAt = System.currentTimeMillis() + durationMs
        val marathon = Marathon(endsAt, ConcurrentHashMap())
        party.marathon = marathon
        marathon.cancelJob = applicationScope.launch {
            delay((endsAt - System.currentTimeMillis()).milliseconds)
            playerTracker.sendChat(
                getMembers(party),
                Utils.surroundWithSeparators("<yellow><lang:puffin.party.marathon.ended>\n${formatMarathonLeaderboard(marathon)}")
            )
            endMarathon(party)
        }
        update(party)
    }

    override fun endMarathon(party: Party) = synchronized(partyLock) {
        clearMarathon(party)
        update(party)
    }

    override fun addMarathonPoints(party: Party, uuid: UUID, amount: Int) = synchronized(partyLock) {
        party.marathon?.points?.merge(uuid, amount, Int::plus)
        update(party)
    }

    override suspend fun formatMarathonLeaderboard(marathon: Marathon): String {
        val snapshot = marathon.points.toMap()
        if (snapshot.isEmpty()) {
            return "<gray><lang:puffin.party.marathon.current_leaderboard.no_points>"
        }
        val fancyNumbers = "➀➁➂➃➄➅➆➇➈➉"
        var str = snapshot.entries
            .sortedByDescending { (_, points) -> points }
            .take(10)
            .mapIndexed { index, (uuid, points) ->
                val color = when (index) {
                    0 -> "#cfa959"
                    1 -> "#c0c0c0"
                    2 -> "#cd7f32"
                    else -> "gray"
                }
                return@mapIndexed "<$color>${fancyNumbers[index]} ${getUsername(uuid)}<p1>: <yellow>${points}"
            }
            .joinToString("\n")
        if (snapshot.size > 10) {
            str += "\n<gray>... (+${snapshot.size - 10} more)"
        }
        return str
    }

    /**
     * Re-evaluates [party] against its invariants and must be called after every mutation:
     *  - Parties always have at least one member besides the leader, unless there are outgoing
     *    invitations (in which case a leader-only party may live until they expire).
     *  - All members are online.
     *  - The leader is always a member; if they leave or go offline, leadership is transferred.
     */
    private fun update(party: Party) {
        // Prune members who have left the server (safety net if a logout was never reported).
        val onlineMembers = party.members.filter { playerTracker.getPlayer(it) != null }.distinct()
        if (onlineMembers.size != party.members.size) {
            val removed = party.members - onlineMembers
            party.members.retainAll(onlineMembers)
            val recipients = party.members.toList()
            removed.forEach {
                playerTracker.sendChatAsync(recipients, message = {
                    Utils.surroundWithSeparators(
                        "<red><lang:puffin.party.player_logged_out:'${getUsername(it)}'>"
                    )
                })
            }
        }

        // If the leader left or is offline, transfer leadership to another member.
        if (party.leader !in party.members) {
            val newLeader = party.members.firstOrNull { it != party.leader }
            if (newLeader != null && canSurvive(party)) {
                val recipients = party.members.toList()
                val oldLeader = party.leader
                playerTracker.sendChatAsync(recipients, message = {
                    Utils.surroundWithSeparators(
                        "<yellow><lang:puffin.party.transfer.auto:'${getUsername(newLeader)}':'${getUsername(oldLeader)}'>"
                    )
                })
                party.leader = newLeader
            }
        }

        if (canSurvive(party)) {
            notifyPartyUpdate("update", party.id, party)
        } else {
            dissolve(party)
        }
    }

    /**
     * A party is valid if it has at least one member besides the leader, or if the leader is alone
     * but there are still outgoing invitations (it may live until those invitations expire).
     */
    private fun canSurvive(party: Party) =
        party.members.size >= 2 || (party.members.size == 1 && party.invitations.isNotEmpty())

    private fun dissolve(party: Party) {
        if (party !in parties) return
        parties.remove(party)
        playerTracker.sendChatAsync(party.members.toList(), "<red><lang:puffin.party.disband.auto>")
        clearMarathon(party)
        party.invitations.values.forEach { it.cancel() }
        party.invitations.clear()
        notifyPartyUpdate("remove", party.id, null)
    }

    private fun clearMarathon(party: Party) {
        val marathon = party.marathon ?: return
        marathon.cancelJob?.cancel()
        marathon.points.clear()
        party.marathon = null
    }

    private fun notifyPartyUpdate(action: String, id: String, party: Party?) {
        partyUpdateCallbacks.forEach { it(action, id, party) }
    }

    private fun onLogout(player: UUID) {
        synchronized(partyLock) {
            val party = partyOf(player) ?: return
            playerTracker.sendChatAsync(party.members.toList(), message = {
                Utils.surroundWithSeparators("<red><lang:puffin.party.player_logged_out:'${player.name()}'>")
            })
            party.members.remove(player)
            update(party)
        }
    }
}
