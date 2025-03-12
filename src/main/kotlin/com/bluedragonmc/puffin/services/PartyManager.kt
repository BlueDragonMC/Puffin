package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.PartyListResponseKt.playerEntry
import com.bluedragonmc.api.grpc.PartyServiceGrpcKt
import com.bluedragonmc.api.grpc.PartySvc
import com.bluedragonmc.api.grpc.partyListResponse
import com.bluedragonmc.puffin.app.Puffin
import com.bluedragonmc.puffin.dashboard.ApiService
import com.bluedragonmc.puffin.util.Utils
import com.bluedragonmc.puffin.util.Utils.catchingTimer
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.google.protobuf.Empty
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*

/**
 * Handles creating parties, party chat, invitations, warps, and transfers.
 */
class PartyManager(app: ServiceHolder) : Service(app) {

    private val parties = mutableSetOf<Party>()
    internal fun getParties() = parties.toSet()
    internal fun partyOf(player: UUID) = parties.find { player in it.getMembers() }
    private fun createParty(leader: UUID) =
        Party(this, mutableListOf(leader), mutableMapOf(), leader = leader).also { parties.add(it) }

    /**
     * Returns the username of the UUID, with an (optional) MiniMessage-formatted color prepended.
     */
    private val UUID.name: String
        get() = getUsername(this)

    internal fun getUsername(uuid: UUID) = app.get(DatabaseConnection::class).run {
        val color = getPlayerNameColor(uuid)
        val username = getPlayerName(uuid) ?: uuid.toString()
        return@run "<$color>$username"
    }

    private fun sendInvitationMessage(party: Party, invitee: UUID, inviter: UUID) {
        Utils.sendChatAsync(
            party.getMembers(),
            Utils.surroundWithSeparators("<p2><lang:puffin.party.invite.other:'${inviter.name}':'${invitee.name}'>")
        )
        Utils.sendChatAsync(
            invitee,
            Utils.surroundWithSeparators("<p2><click:run_command:/party accept $inviter><lang:puffin.party.invite.1:'${inviter.name}'>\n<p2><lang:puffin.party.invite.2:'<p2><lang:puffin.party.invite.clickable>'></click>")
        )
    }

    data class Marathon(
        val party: Party,
        val endsAt: Long,
        private val points: MutableMap<UUID, Int>
    ) {

        private var cancelJob: Job

        init {
            cancelJob = Puffin.IO.launch {
                delay(endsAt - System.currentTimeMillis())
                Utils.sendChat(
                    party.getMembers(),
                    Utils.surroundWithSeparators("<yellow><lang:puffin.party.marathon.ended>\n${party.marathon!!.formatLeaderboard()}")
                )
                end()
            }
        }

        fun addPoints(uuid: UUID, amount: Int) {
            points[uuid] = points[uuid]?.plus(amount) ?: amount
            party.sendUpdate()
        }

        fun end() {
            cancelJob.cancel()
            points.clear()
            party.marathon = null
            party.sendUpdate()
        }

        fun getPoints() = points.toMap()

        fun formatLeaderboard(): String {
            if (points.isEmpty()) {
                return "<gray><lang:puffin.party.marathon.current_leaderboard.no_points>"
            }
            val fancyNumbers = "➀➁➂➃➄➅➆➇➈➉"
            var str = points.entries
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
            if (points.size > 10) {
                str += "\n<gray>... (+${points.size - 10} more)"
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
        val invitations: MutableMap<UUID, Timer>,
        val id: String = UUID.randomUUID().toString(),
        var leader: UUID,
        var marathon: Marathon? = null,
    ) {

        init {
            val api = svc.app.get(ApiService::class)
            api.sendUpdate("party", "add", id, api.createJsonObjectForParty(this))
        }

        fun add(player: UUID) {
            members.add(player)
            sendUpdate()
        }

        fun remove(player: UUID) {
            members.remove(player)
            sendUpdate()
        }

        fun getMembers() = members.toList()

        fun update() {
            if (members.size <= 1 && invitations.isEmpty()) {
                // If a party has no members and all invites expires, delete it
                Utils.sendChatAsync(members, "<red><lang:puffin.party.disband.auto>")
                marathon?.end()
                svc.parties.remove(this)
                svc.app.get(ApiService::class).sendUpdate("party", "remove", id, null)
            } else if (svc.app.get(PlayerTracker::class).getPlayer(leader) == null) {
                // If the party leader left, transfer the party to one of the members
                val member = members.first { it != leader }
                val leaderUsername = svc.getUsername(leader)
                Utils.sendChatAsync(
                    members,
                    Utils.surroundWithSeparators(
                        "<yellow><lang:puffin.party.transfer.auto:'${svc.getUsername(member)}':'$leaderUsername'>"
                    )
                )
                leader = member
                sendUpdate()
            }
        }

        internal fun sendUpdate() {
            val api = svc.app.get(ApiService::class)
            api.sendUpdate("party", "update", id, api.createJsonObjectForParty(this))
        }
    }

    override fun initialize() {
        app.get(PlayerTracker::class).onLogout { player ->
            val party = partyOf(player) ?: return@onLogout
            party.remove(player)
            Utils.sendChatAsync(
                party.getMembers(),
                Utils.surroundWithSeparators("<red><lang:puffin.party.player_logged_out:'${player.name}'>")
            )
            party.update()
        }
    }

    inner class PartyService : PartyServiceGrpcKt.PartyServiceCoroutineImplBase() {
        override suspend fun acceptInvitation(request: PartySvc.PartyAcceptInviteRequest): Empty = handleRPC {
            val partyOwner = UUID.fromString(request.partyOwnerUuid)
            val player = UUID.fromString(request.playerUuid)
            val party = partyOf(partyOwner)
            if (party == null) {
                Utils.sendChat(player, "<red><lang:puffin.party.not_found>")
                return Empty.getDefaultInstance()
            }
            if (party.invitations.contains(player)) {
                Utils.sendChat(
                    party.getMembers(),
                    Utils.surroundWithSeparators("<p2><lang:puffin.party.join.other:'${player.name}'>")
                )
                party.invitations.remove(player)
                party.add(player)
                Utils.sendChat(
                    player,
                    Utils.surroundWithSeparators("<p2><lang:puffin.party.join.self:'${partyOwner.name}'>")
                )
            } else {
                Utils.sendChat(player, "<red><lang:puffin.party.join.no_invitation>")
            }
            return Empty.getDefaultInstance()
        }

        override suspend fun inviteToParty(request: PartySvc.PartyInviteRequest): Empty = handleRPC {
            val partyOwner = UUID.fromString(request.partyOwnerUuid)
            val player = UUID.fromString(request.playerUuid)
            if (partyOwner == player) {
                Utils.sendChat(player, "<red><lang:puffin.party.invite.self>")
                return Empty.getDefaultInstance()
            }
            val party = partyOf(partyOwner) ?: createParty(partyOwner)

            if (party.getMembers().contains(player)) {
                Utils.sendChat(partyOwner, "<red><lang:puffin.party.invite.already_in_party>")
                return Empty.getDefaultInstance()
            }

            sendInvitationMessage(party, player, partyOwner)
            val timer = catchingTimer(daemon = true, initialDelay = 60_000, period = 60_000) {
                this.cancel()
                if (party.invitations.contains(player)) {
                    party.invitations.remove(player)
                    party.sendUpdate()
                    Utils.sendChatAsync(
                        player,
                        "<p2><lang:puffin.party.invite.expired:'${partyOwner.name}'>"
                    )
                }
            }
            party.invitations[player] = timer
            party.sendUpdate()
            return Empty.getDefaultInstance()
        }

        override suspend fun partyChat(request: PartySvc.PartyChatRequest): Empty = handleRPC {
            val uuid = UUID.fromString(request.playerUuid)
            val party = partyOf(uuid)
            if (party != null) {
                Utils.sendChatAsync(
                    party.getMembers(),
                    "<p3><lang:puffin.party.chat.prefix> <white>${uuid.name}<gray>: <white>${request.message}"
                )
            } else {
                Utils.sendChatAsync(uuid, "<red><lang:puffin.party.chat.not_found>")
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
                            username = it.name
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
                Utils.sendChat(player, "<red><lang:puffin.party.kick.self>")
            } else {
                val party = partyOf(partyOwner)
                if (party != null) {
                    if (party.getMembers().contains(player)) {
                        party.remove(player)
                        Utils.sendChat(
                            party.getMembers(),
                            Utils.surroundWithSeparators("<p2><lang:puffin.party.kick.success:'${player.name}'>")
                        )
                        Utils.sendChat(player, Utils.surroundWithSeparators("<p2><lang:puffin.party.kick.removed>"))
                        party.update()
                    } else {
                        Utils.sendChat(player, "<red><lang:puffin.party.member_not_found>")
                    }
                } else {
                    Utils.sendChat(player, "<red><lang:puffin.party.not_found>")
                }
            }
            return Empty.getDefaultInstance()
        }

        override suspend fun leaveParty(request: PartySvc.PartyLeaveRequest): Empty {
            val player = UUID.fromString(request.playerUuid)
            val party = partyOf(player)

            if (party != null) {
                party.remove(player)
                Utils.sendChat(player, Utils.surroundWithSeparators("<p2><lang:puffin.party.leave.self>"))
                Utils.sendChat(
                    party.getMembers(),
                    Utils.surroundWithSeparators("<p2><lang:puffin.party.leave.others:'${player.name}'>")
                )
                party.update()
            } else {
                Utils.sendChat(player, "<red><lang:puffin.party.not_found>")
            }

            return Empty.getDefaultInstance()
        }

        override suspend fun transferParty(request: PartySvc.PartyTransferRequest): Empty = handleRPC {

            val oldUuid = UUID.fromString(request.playerUuid)
            val newUuid = UUID.fromString(request.newOwnerUuid)

            val party = partyOf(oldUuid)
            if (party == null) {
                Utils.sendChat(oldUuid, "<red><lang:puffin.party.chat.not_found>")
                return Empty.getDefaultInstance()
            }
            if (party.leader != oldUuid) {
                Utils.sendChat(oldUuid, "<red><lang:puffin.party.transfer.not_leader>")
                return Empty.getDefaultInstance()
            }
            if (!party.getMembers().contains(oldUuid)) {
                Utils.sendChat(oldUuid, "<red><lang:puffin.party.member_not_found>")
                return Empty.getDefaultInstance()
            }
            party.leader = newUuid
            party.sendUpdate()
            Utils.sendChat(
                party.getMembers(),
                Utils.surroundWithSeparators("<p2><lang:puffin.party.transfer.success:'${newUuid.name}'>")
            )

            return Empty.getDefaultInstance()
        }

        override suspend fun warpParty(request: PartySvc.PartyWarpRequest): Empty = handleRPC {

            val tracker = app.get(PlayerTracker::class)
            val uuid = UUID.fromString(request.partyOwnerUuid)
            val party = partyOf(uuid)
            // Make sure the player is the leader of their party
            if (party == null) {
                Utils.sendChat(uuid, "<red><lang:puffin.party.not_found>")
                return Empty.getDefaultInstance()
            }
            if (party.leader != uuid) {
                Utils.sendChat(uuid, "<red><lang:puffin.party.warp.not_leader>")
                return Empty.getDefaultInstance()
            }

            // Make sure the instance is not full
            val gameId = request.instanceUuid
            val playersInInstance = tracker.getPlayersInInstance(gameId)

            val emptySlots = app.get(GameStateManager::class).getEmptySlots(gameId)
            val warpNeeded = party.getMembers().count { member -> !playersInInstance.contains(member) }

            if (warpNeeded > emptySlots) {
                Utils.sendChat(uuid, "<red><lang:puffin.party.warp.not_enough_space>")
                return Empty.getDefaultInstance()
            }

            // Warp every member
            val leaderGameId = tracker.getPlayer(party.leader)?.gameId ?: return@handleRPC Empty.getDefaultInstance()
            val membersToWarp =
                party.getMembers().count { member -> tracker.getPlayer(member)?.gameId != leaderGameId }
            party.getMembers().forEach {
                if (party.leader != it) {
                    Utils.sendPlayerToInstance(it, gameId)
                }
            }
            Utils.sendChat(
                party.getMembers(),
                "<p2><lang:puffin.party.warp.success:'<p1>$membersToWarp':'${party.leader.name}'>"
            )

            return Empty.getDefaultInstance()
        }

        override suspend fun getMarathonLeaderboard(request: PartySvc.MarathonLeaderboardRequest): Empty = handleRPC {
            for (uuidString in request.playerUuidsList) {
                val uuid = UUID.fromString(uuidString)
                val party = partyOf(uuid)
                if (party == null) {
                    if (!request.silent) {
                        Utils.sendChat(uuid, "<red><lang:puffin.party.not_found>")
                    }
                    continue
                }
                val marathon = party.marathon
                if (marathon == null) {
                    if (!request.silent) {
                        Utils.sendChat(uuid, "<red><lang:puffin.party.marathon.not_found>")
                    }
                    continue
                }
                val lb = marathon.formatLeaderboard()
                val duration = (marathon.endsAt - System.currentTimeMillis()) / 1000
                val hours = duration / 3600
                val minutes = (duration / 60) % 60
                val seconds = duration % 60
                Utils.sendChat(
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
                Utils.sendChat(uuid, "<red><lang:puffin.party.not_found>")
                return Empty.getDefaultInstance()
            }

            if (party.marathon != null) {
                Utils.sendChat(uuid, "<red><lang:puffin.party.marathon_already_started>")
                return Empty.getDefaultInstance()
            }

            if (uuid != party.leader) {
                Utils.sendChat(uuid, "<red><lang:puffin.party.marathon.not_leader>")
                return Empty.getDefaultInstance()
            }

            party.marathon = Marathon(party, System.currentTimeMillis() + request.durationMs, mutableMapOf())
            party.sendUpdate()

            val minutes = request.durationMs / 1000 / 60
            Utils.sendChat(
                party.getMembers(),
                Utils.surroundWithSeparators("<yellow><lang:puffin.party.marathon.started:'${getUsername(uuid)}':'<p1><lang:puffin.party.marathon.started.time_period:$minutes>'>")
            )

            return Empty.getDefaultInstance()
        }

        override suspend fun stopMarathon(request: PartySvc.StopMarathonRequest): Empty = handleRPC {

            val uuid = UUID.fromString(request.playerUuid)
            val party = partyOf(uuid)

            if (party == null) {
                Utils.sendChat(uuid, "<red><lang:puffin.party.not_found>")
                return Empty.getDefaultInstance()
            }

            if (party.marathon == null) {
                Utils.sendChat(uuid, "<red><lang:puffin.party.marathon.not_found>")
                return Empty.getDefaultInstance()
            }

            if (uuid != party.leader) {
                Utils.sendChat(uuid, "<red><lang:puffin.party.marathon.not_leader>")
                return Empty.getDefaultInstance()
            }

            Utils.sendChat(
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

            if (party == null || party.marathon == null) {
                return Empty.getDefaultInstance()
            }

            val leaderGameId = app.get(PlayerTracker::class).getPlayer(party.leader)?.gameId
            if (request.gameId != leaderGameId) {
                Utils.sendChat(uuid, "<gray><lang:puffin.party.marathon.outside_points>")
                return Empty.getDefaultInstance()
            }

            party.marathon?.addPoints(uuid, request.coins)

            return Empty.getDefaultInstance()
        }
    }
}