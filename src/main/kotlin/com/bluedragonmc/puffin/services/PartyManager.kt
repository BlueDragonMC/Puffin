package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.PartyListResponseKt.playerEntry
import com.bluedragonmc.api.grpc.PartyServiceGrpcKt
import com.bluedragonmc.api.grpc.PartySvc
import com.bluedragonmc.api.grpc.partyListResponse
import com.bluedragonmc.puffin.app.ApplicationScope
import com.bluedragonmc.puffin.dashboard.ApiService
import com.bluedragonmc.puffin.util.Utils
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.google.gson.JsonElement
import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.protobuf.Empty
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
    fun onLogout(player: UUID)
    val partyService: PartyManager.PartyService
}

/**
 * Handles creating parties, party chat, invitations, warps, and transfers.
 */
@Singleton
class PartyManager @Inject constructor(
    val databaseConnection: DatabaseConnection,
    val playerTracker: IPlayerTracker,
    val queueService: IQueueService,
    val applicationScope: ApplicationScope,
) : Service(), IPartyManager {

    private val parties = mutableSetOf<Party>()

    /** Used for mutual exclusion when mutating [parties] and each party's members and invitations. */
    private val partyLock = Any()

    override fun getParties() = synchronized(partyLock) { parties.toSet() }
    override fun partyOf(player: UUID) = synchronized(partyLock) { parties.find { player in it.getMembers() } }
    private fun createParty(leader: UUID) = synchronized(partyLock) {
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

    private suspend fun sendInvitationMessage(party: Party, invitee: UUID, inviter: UUID) {
        playerTracker.sendChatAsync(
            party.getMembers(),
            Utils.surroundWithSeparators("<p2><lang:puffin.party.invite.other:'${inviter.name()}':'${invitee.name()}'>")
        )
        playerTracker.sendChatAsync(
            invitee,
            Utils.surroundWithSeparators("<p2><click:run_command:/party accept $inviter><lang:puffin.party.invite.1:'${inviter.name()}'>\n<p2><lang:puffin.party.invite.2:'<p2><lang:puffin.party.invite.clickable>'></click>")
        )
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

    override fun onLogout(player: UUID) {
        synchronized(partyLock) {
            val party = partyOf(player) ?: return
            playerTracker.sendChatAsync(party.getMembers(), message = {
                Utils.surroundWithSeparators("<red><lang:puffin.party.player_logged_out:'${player.name()}'>")
            })
            party.remove(player)
        }
    }

    override val partyService by lazy { PartyService() }

    inner class PartyService : PartyServiceGrpcKt.PartyServiceCoroutineImplBase() {
        override suspend fun acceptInvitation(request: PartySvc.PartyAcceptInviteRequest): Empty = handleRPC {
            val partyOwner = UUID.fromString(request.partyOwnerUuid)
            val player = UUID.fromString(request.playerUuid)
            val party = partyOf(partyOwner)
            if (party == null) {
                playerTracker.sendChat(player, "<red><lang:puffin.party.not_found>")
                return Empty.getDefaultInstance()
            }
            if (party.invitations.contains(player)) {
                playerTracker.sendChat(
                    party.getMembers(),
                    Utils.surroundWithSeparators("<p2><lang:puffin.party.join.other:'${player.name()}'>")
                )
                party.add(player)
                playerTracker.sendChat(
                    player,
                    Utils.surroundWithSeparators("<p2><lang:puffin.party.join.self:'${partyOwner.name()}'>")
                )
            } else {
                playerTracker.sendChat(player, "<red><lang:puffin.party.join.no_invitation>")
            }
            return Empty.getDefaultInstance()
        }

        override suspend fun inviteToParty(request: PartySvc.PartyInviteRequest): Empty = handleRPC {
            val partyOwner = UUID.fromString(request.partyOwnerUuid)
            val player = UUID.fromString(request.playerUuid)
            if (partyOwner == player) {
                playerTracker.sendChat(player, "<red><lang:puffin.party.invite.self>")
                return Empty.getDefaultInstance()
            }
            val party = partyOf(partyOwner) ?: createParty(partyOwner)

            if (party.getMembers().contains(player)) {
                playerTracker.sendChat(partyOwner, "<red><lang:puffin.party.invite.already_in_party>")
                return Empty.getDefaultInstance()
            }

            sendInvitationMessage(party, player, partyOwner)
            val expiryJob = applicationScope.launch {
                delay(60_000)
                if (party.invitations.contains(player)) {
                    party.removeInvitation(player)
                    playerTracker.sendChatAsync(player, message = {
                        "<p2><lang:puffin.party.invite.expired:'${partyOwner.name()}'>"
                    })
                }
            }
            party.addInvitation(player, expiryJob)
            return Empty.getDefaultInstance()
        }

        override suspend fun partyChat(request: PartySvc.PartyChatRequest): Empty = handleRPC {
            val uuid = UUID.fromString(request.playerUuid)
            val party = partyOf(uuid)
            if (party != null) {
                playerTracker.sendChatAsync(
                    party.getMembers(),
                    "<p3><lang:puffin.party.chat.prefix> <white>${uuid.name()}<gray>: <white>${request.message}"
                )
            } else {
                playerTracker.sendChatAsync(uuid, "<red><lang:puffin.party.chat.not_found>")
            }
            return Empty.getDefaultInstance()
        }

        override suspend fun partyList(request: PartySvc.PartyListRequest): PartySvc.PartyListResponse = handleRPC {
            val uuid = UUID.fromString(request.playerUuid)
            val party = partyOf(uuid)
            if (party != null) {
                return partyListResponse {
                    players += party.getMembers().map {
                        playerEntry {
                            this.uuid = it.toString()
                            username = it.name()
                            role = if (party.leader == it) "Leader" else "Member"
                        }
                    }
                }
            }

            return partyListResponse { /* empty response - no party found */ }
        }

        override suspend fun removeFromParty(request: PartySvc.PartyRemoveRequest): Empty = handleRPC {
            val player = UUID.fromString(request.playerUuid)
            val partyOwner = UUID.fromString(request.partyOwnerUuid)

            if (partyOwner == player) {
                playerTracker.sendChat(player, "<red><lang:puffin.party.kick.self>")
            } else {
                val party = partyOf(partyOwner)
                if (party != null) {
                    if (party.getMembers().contains(player)) {
                        party.remove(player)
                        playerTracker.sendChat(
                            party.getMembers(),
                            Utils.surroundWithSeparators("<p2><lang:puffin.party.kick.success:'${player.name()}'>")
                        )
                        playerTracker.sendChat(
                            player,
                            Utils.surroundWithSeparators("<p2><lang:puffin.party.kick.removed>")
                        )
                    } else {
                        playerTracker.sendChat(player, "<red><lang:puffin.party.member_not_found>")
                    }
                } else {
                    playerTracker.sendChat(player, "<red><lang:puffin.party.not_found>")
                }
            }
            return Empty.getDefaultInstance()
        }

        override suspend fun leaveParty(request: PartySvc.PartyLeaveRequest): Empty {
            val player = UUID.fromString(request.playerUuid)
            val party = partyOf(player)

            if (party != null) {
                party.remove(player)
                playerTracker.sendChat(player, Utils.surroundWithSeparators("<p2><lang:puffin.party.leave.self>"))
                playerTracker.sendChat(
                    party.getMembers(),
                    Utils.surroundWithSeparators("<p2><lang:puffin.party.leave.others:'${player.name()}'>")
                )
            } else {
                playerTracker.sendChat(player, "<red><lang:puffin.party.not_found>")
            }

            return Empty.getDefaultInstance()
        }

        override suspend fun transferParty(request: PartySvc.PartyTransferRequest): Empty = handleRPC {

            val oldUuid = UUID.fromString(request.playerUuid)
            val newUuid = UUID.fromString(request.newOwnerUuid)

            val party = partyOf(oldUuid)
            if (party == null) {
                playerTracker.sendChat(oldUuid, "<red><lang:puffin.party.chat.not_found>")
                return Empty.getDefaultInstance()
            }
            if (party.leader != oldUuid) {
                playerTracker.sendChat(oldUuid, "<red><lang:puffin.party.transfer.not_leader>")
                return Empty.getDefaultInstance()
            }
            if (!party.getMembers().contains(newUuid) || playerTracker.getPlayer(newUuid) == null) {
                playerTracker.sendChat(oldUuid, "<red><lang:puffin.party.member_not_found>")
                return Empty.getDefaultInstance()
            }
            party.leader = newUuid
            playerTracker.sendChat(
                party.getMembers(),
                Utils.surroundWithSeparators("<p2><lang:puffin.party.transfer.success:'${newUuid.name()}'>")
            )

            return Empty.getDefaultInstance()
        }

        override suspend fun warpParty(request: PartySvc.PartyWarpRequest): Empty = handleRPC {
            val uuid = UUID.fromString(request.partyOwnerUuid)
            val party = partyOf(uuid)
            // Make sure the player is the leader of their party
            if (party == null) {
                playerTracker.sendChat(uuid, "<red><lang:puffin.party.not_found>")
                return Empty.getDefaultInstance()
            }
            if (party.leader != uuid) {
                playerTracker.sendChat(uuid, "<red><lang:puffin.party.warp.not_leader>")
                return Empty.getDefaultInstance()
            }

            // Make sure the instance is not full
            val gameId = request.instanceUuid
            val playersInInstance = playerTracker.getPlayersInInstance(gameId)

            val emptySlots = queueService.getGame(gameId)?.emptySlots ?: 0
            val warpNeeded = party.getMembers().count { member -> !playersInInstance.contains(member) }

            if (warpNeeded > emptySlots) {
                playerTracker.sendChat(uuid, "<red><lang:puffin.party.warp.not_enough_space>")
                return Empty.getDefaultInstance()
            }

            // Warp every member
            val leaderGameId =
                playerTracker.getPlayer(party.leader)?.gameId ?: return@handleRPC Empty.getDefaultInstance()
            val membersToWarp =
                party.getMembers().count { member -> playerTracker.getPlayer(member)?.gameId != leaderGameId }
            party.getMembers().forEach {
                if (party.leader != it) {
                    queueService.sendPlayerToInstance(it, gameId)
                }
            }
            playerTracker.sendChat(
                party.getMembers(),
                "<p2><lang:puffin.party.warp.success:'<p1>$membersToWarp':'${party.leader.name()}'>"
            )

            return Empty.getDefaultInstance()
        }

        override suspend fun getMarathonLeaderboard(request: PartySvc.MarathonLeaderboardRequest): Empty = handleRPC {
            for (uuidString in request.playerUuidsList) {
                val uuid = UUID.fromString(uuidString)
                val party = partyOf(uuid)
                if (party == null) {
                    if (!request.silent) {
                        playerTracker.sendChat(uuid, "<red><lang:puffin.party.not_found>")
                    }
                    continue
                }
                val marathon = party.marathon
                if (marathon == null) {
                    if (!request.silent) {
                        playerTracker.sendChat(uuid, "<red><lang:puffin.party.marathon.not_found>")
                    }
                    continue
                }
                val lb = marathon.formatLeaderboard()
                val duration = (marathon.endsAt - System.currentTimeMillis()) / 1000
                val hours = duration / 3600
                val minutes = (duration / 60) % 60
                val seconds = duration % 60
                playerTracker.sendChat(
                    uuid,
                    Utils.surroundWithSeparators("<yellow><lang:puffin.party.marathon.current_leaderboard>\n${lb}\n<yellow><lang:puffin.party.marathon.time_remaining:'<p1>$hours':'<p1>$minutes':'<p1>$seconds'>")
                )
            }
            return Empty.getDefaultInstance()
        }

        override suspend fun startMarathon(request: PartySvc.StartMarathonRequest): Empty = handleRPC {

            val uuid = UUID.fromString(request.playerUuid)
            val party = partyOf(uuid)

            if (party == null) {
                playerTracker.sendChat(uuid, "<red><lang:puffin.party.not_found>")
                return Empty.getDefaultInstance()
            }

            if (party.marathon != null) {
                playerTracker.sendChat(uuid, "<red><lang:puffin.party.marathon_already_started>")
                return Empty.getDefaultInstance()
            }

            if (uuid != party.leader) {
                playerTracker.sendChat(uuid, "<red><lang:puffin.party.marathon.not_leader>")
                return Empty.getDefaultInstance()
            }

            party.marathon =
                Marathon(this@PartyManager, party, System.currentTimeMillis() + request.durationMs, ConcurrentHashMap())

            val minutes = request.durationMs / 1000 / 60
            playerTracker.sendChat(
                party.getMembers(),
                Utils.surroundWithSeparators("<yellow><lang:puffin.party.marathon.started:'${getUsername(uuid)}':'<p1><lang:puffin.party.marathon.started.time_period:$minutes>'>")
            )

            return Empty.getDefaultInstance()
        }

        override suspend fun stopMarathon(request: PartySvc.StopMarathonRequest): Empty = handleRPC {

            val uuid = UUID.fromString(request.playerUuid)
            val party = partyOf(uuid)

            if (party == null) {
                playerTracker.sendChat(uuid, "<red><lang:puffin.party.not_found>")
                return Empty.getDefaultInstance()
            }

            if (party.marathon == null) {
                playerTracker.sendChat(uuid, "<red><lang:puffin.party.marathon.not_found>")
                return Empty.getDefaultInstance()
            }

            if (uuid != party.leader) {
                playerTracker.sendChat(uuid, "<red><lang:puffin.party.marathon.not_leader>")
                return Empty.getDefaultInstance()
            }

            playerTracker.sendChat(
                party.getMembers(),
                Utils.surroundWithSeparators("<yellow><lang:puffin.party.marathon.ended_by_player:'${getUsername(uuid)}'>\n${party.marathon!!.formatLeaderboard()}")
            )

            party.marathon!!.end()
            party.marathon = null

            return Empty.getDefaultInstance()
        }

        override suspend fun recordCoinAward(request: PartySvc.RecordCoinAwardRequest): Empty {
            val uuid = UUID.fromString(request.playerUuid)
            val party = partyOf(uuid)

            if (party?.marathon == null) {
                return Empty.getDefaultInstance()
            }

            val leaderGameId = playerTracker.getPlayer(party.leader)?.gameId
            if (request.gameId != leaderGameId) {
                playerTracker.sendChat(uuid, "<gray><lang:puffin.party.marathon.outside_points>")
                return Empty.getDefaultInstance()
            }

            party.marathon?.addPoints(uuid, request.coins)

            return Empty.getDefaultInstance()
        }
    }
}