package com.bluedragonmc.puffin.services

import com.bluedragonmc.puffin.app.ApplicationScope
import com.bluedragonmc.puffin.dashboard.ApiService
import com.bluedragonmc.puffin.util.Utils
import com.google.gson.JsonElement
import com.google.inject.Inject
import com.google.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

interface IPartyManager {
    fun getParties(): Set<PartyManager.Party>
    fun partyOf(player: UUID): PartyManager.Party?
    fun registerPartyUpdateCallback(cb: (action: String, id: String, updated: JsonElement?) -> Unit)
}

/**
 * Handles creating parties, party chat, invitations, warps, and transfers.
 */
@Singleton
class PartyManager @Inject constructor(
    val databaseConnection: DatabaseConnection,
    val playerTracker: IPlayerTracker,
    val applicationScope: ApplicationScope,
) : Service(), IPartyManager {

    private val parties = mutableSetOf<Party>()

    /** Used for mutual exclusion when mutating [parties] and each party's members and invitations. */
    private val partyLock = Any()

    init {
        // React to logouts through PlayerTracker's callback rather than depending on it directly.
        playerTracker.registerLogoutCallback { onLogout(it) }
    }

    override fun getParties() = synchronized(partyLock) { parties.toSet() }
    override fun partyOf(player: UUID) = synchronized(partyLock) { parties.find { player in it.getMembers() } }
    internal fun createParty(leader: UUID) = synchronized(partyLock) {
        Party(this, mutableListOf(leader), mutableMapOf(), _leader = leader).also { parties.add(it) }
    }

    /**
     * Returns the username of the UUID, with an (optional) MiniMessage-formatted color prepended.
     */
    private suspend fun UUID.name(): String = getUsername(this)

    internal suspend fun getUsername(uuid: UUID): String {
        val color = databaseConnection.getPlayerNameColor(uuid)
        val username = databaseConnection.getPlayerName(uuid) ?: uuid.toString()
        return "<$color>$username"
    }

    private val partyUpdateCallbacks = mutableListOf<(action: String, id: String, updated: JsonElement?) -> Unit>()

    override fun registerPartyUpdateCallback(cb: (action: String, id: String, updated: JsonElement?) -> Unit) {
        partyUpdateCallbacks.add(cb)
    }

    data class Marathon(
        private val svc: PartyManager,
        val party: Party,
        val endsAt: Long,
        private val points: ConcurrentHashMap<UUID, Int>
    ) {

        private var cancelJob: Job

        init {
            cancelJob = svc.applicationScope.launch {
                delay((endsAt - System.currentTimeMillis()).milliseconds)
                svc.playerTracker.sendChat(
                    party.getMembers(),
                    Utils.surroundWithSeparators("<yellow><lang:puffin.party.marathon.ended>\n${party.marathon!!.formatLeaderboard()}")
                )
                end()
            }
        }

        fun addPoints(uuid: UUID, amount: Int) {
            points.merge(uuid, amount, Int::plus)
            party.update()
        }

        fun end() {
            cancelJob.cancel()
            points.clear()
            party.marathon = null
            party.update()
        }

        fun getPoints() = points.toMap()

        suspend fun formatLeaderboard(): String {
            val snapshot = points.toMap()
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
                    return@mapIndexed "<$color>${fancyNumbers[index]} ${party.svc.getUsername(uuid)}<p1>: <yellow>${points}"
                }
                .joinToString("\n")
            if (snapshot.size > 10) {
                str += "\n<gray>... (+${snapshot.size - 10} more)"
            }
            return str
        }
    }

    data class Party(
        val svc: PartyManager,
        /**
         * The party's current members, including the leader
         */
        private val members: MutableList<UUID>,
        private val _invitations: MutableMap<UUID, Job>,
        val id: String = UUID.randomUUID().toString(),
        private var _leader: UUID,
        private var _marathon: Marathon? = null,
    ) {
        val invitations: Map<UUID, Job> get() = synchronized(svc.partyLock) { _invitations.toMap() }
        var marathon: Marathon?
            get() = _marathon
            set(value) {
                synchronized(svc.partyLock) {
                    _marathon = value
                    update()
                }
            }

        var leader: UUID
            get() = _leader
            set(value) {
                synchronized(svc.partyLock) {
                    val changed = _leader != value
                    _leader = value
                    if (changed) update()
                }
            }

        init {
            svc.partyUpdateCallbacks.forEach { it("add", id, ApiService.createJsonObjectForParty(this)) }
        }

        fun add(player: UUID) {
            synchronized(svc.partyLock) {
                members.add(player)
                removeInvitation(player)
            }
        }

        fun remove(player: UUID) {
            synchronized(svc.partyLock) {
                members.remove(player)
                update()
            }
        }

        fun getMembers() = synchronized(svc.partyLock) { members.toList() }

        /**
         * Re-evaluates the party against its invariants and must be called after every mutation:
         *  - Parties always have at least one member besides the leader, unless there are outgoing
         *    invitations (in which case a leader-only party may live until they expire).
         *  - All members are online.
         *  - The leader is always a member; if they leave or go offline, leadership is transferred.
         */
        fun update() = synchronized(svc.partyLock) {
            // Prune members who have left the server (safety net if a logout was never reported).
            val onlineMembers = members.filter { svc.playerTracker.getPlayer(it) != null }.distinct()
            if (onlineMembers.size != members.size) {
                val removed = members - onlineMembers
                members.retainAll(onlineMembers)
                val recipients = members.toList()
                removed.forEach {
                    svc.playerTracker.sendChatAsync(recipients, message = {
                        Utils.surroundWithSeparators(
                            "<red><lang:puffin.party.player_logged_out:'${svc.getUsername(it)}'>"
                        )
                    })
                }
            }

            // If the leader left or is offline, transfer leadership to another member.
            if (leader !in members) {
                val newLeader = members.firstOrNull { it != leader }
                if (newLeader != null && canSurvive()) {
                    val recipients = members.toList()
                    val oldLeader = leader
                    svc.playerTracker.sendChatAsync(recipients, message = {
                        Utils.surroundWithSeparators(
                            "<yellow><lang:puffin.party.transfer.auto:'${svc.getUsername(newLeader)}':'${svc.getUsername(oldLeader)}'>"
                        )
                    })
                    _leader = newLeader
                }
            }

            if (canSurvive()) {
                sendUpdate()
            } else {
                dissolve()
            }
        }

        /**
         * A party is valid if it has at least one member besides the leader, or if the leader is alone
         * but there are still outgoing invitations (it may live until those invitations expire).
         */
        private fun canSurvive() = members.size >= 2 || (members.size == 1 && invitations.isNotEmpty())

        private fun dissolve() {
            if (this !in svc.parties) return
            svc.parties.remove(this)
            svc.playerTracker.sendChatAsync(members, "<red><lang:puffin.party.disband.auto>")
            marathon?.end()
            _invitations.values.forEach { it.cancel() }
            _invitations.clear()
            svc.partyUpdateCallbacks.forEach { it("remove", id, null) }
        }

        private fun sendUpdate() {
            svc.partyUpdateCallbacks.forEach { it("update", id, ApiService.createJsonObjectForParty(this)) }
        }

        fun removeInvitation(player: UUID) {
            synchronized(svc.partyLock) {
                _invitations.remove(player)?.cancel()
                update()
            }
        }

        fun addInvitation(player: UUID, job: Job) {
            synchronized(svc.partyLock) {
                _invitations[player]?.cancel()
                _invitations[player] = job
                update()
            }
        }
    }

    private fun onLogout(player: UUID) {
        synchronized(partyLock) {
            val party = partyOf(player) ?: return
            playerTracker.sendChatAsync(party.getMembers(), message = {
                Utils.surroundWithSeparators("<red><lang:puffin.party.player_logged_out:'${player.name()}'>")
            })
            party.remove(player)
        }
    }

}