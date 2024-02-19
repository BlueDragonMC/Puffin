package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.PartyListResponseKt.playerEntry
import com.bluedragonmc.api.grpc.PartyServiceGrpcKt
import com.bluedragonmc.api.grpc.PartySvc
import com.bluedragonmc.api.grpc.partyListResponse
import com.bluedragonmc.puffin.util.Utils
import com.bluedragonmc.puffin.util.Utils.catchingTimer
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.google.protobuf.Empty
import java.util.*

/**
 * Handles creating parties, party chat, invitations, warps, and transfers.
 */
class PartyManager(app: ServiceHolder) : Service(app) {

    private val parties = mutableSetOf<Party>()
    internal fun partyOf(player: UUID) = parties.find { player in it.members }
    private fun createParty(leader: UUID) =
        Party(this, mutableListOf(leader), mutableMapOf(), leader).also { parties.add(it) }

    /**
     * Returns the username of the UUID, with an (optional) MiniMessage-formatted color prepended.
     */
    private val UUID.name: String
        get() = getUsername(this)

    private fun getUsername(uuid: UUID) = app.get(DatabaseConnection::class).run {
        val color = getPlayerNameColor(uuid)
        val username = getPlayerName(uuid) ?: uuid.toString()
        return@run "<$color>$username"
    }

    private fun sendInvitationMessage(party: Party, invitee: UUID, inviter: UUID) {
        Utils.sendChatAsync(
            party.members,
            Utils.surroundWithSeparators("<p2><lang:puffin.party.invite.other:'${inviter.name}':'${invitee.name}'>")
        )
        Utils.sendChatAsync(
            invitee,
            Utils.surroundWithSeparators("<p2><click:run_command:/party accept $inviter><lang:puffin.party.invite.1:'${inviter.name}'>\n<p2><lang:puffin.party.invite.2:'<p2><lang:puffin.party.invite.clickable>'></click>")
        )
    }

    data class Party(
        val svc: PartyManager,
        /**
         * The party's current members, including the leader
         */
        val members: MutableList<UUID>,
        val invitations: MutableMap<UUID, Timer>,
        var leader: UUID,
    ) {

        fun update() {
            println("inside Party#update() with leader: $leader; members: $members; invitations: $invitations")
            if (members.size <= 1 && invitations.isEmpty()) {
                // If a party has no members and all invites expires, delete it
                Utils.sendChatAsync(members, "<red><lang:puffin.party.disband.auto>")
                svc.parties.remove(this)
            } else if (svc.app.get(PlayerTracker::class).getPlayer(leader) == null) {
                // If the party leader left, transfer the party to one of the members
                val member = members.first { it != leader }
                val leaderUsername = svc.getUsername(leader)
                Utils.sendChatAsync(
                    members,
                    Utils.surroundWithSeparators(
                        "<yellow><lang:puffin.transfer.auto:'${svc.getUsername(member)}':'$leaderUsername'>"
                    )
                )
                leader = member
            }
        }
    }

    override fun initialize() {
        app.get(PlayerTracker::class).onLogout { player ->
            val party = partyOf(player) ?: return@onLogout
            party.members.remove(player)
            Utils.sendChatAsync(
                party.members,
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
                    party.members,
                    Utils.surroundWithSeparators("<p2><lang:puffin.party.join.other:'${player.name}'>")
                )
                party.members.add(player)
                party.invitations.remove(player)
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

            if (party.members.contains(player)) {
                Utils.sendChat(partyOwner, "<red><lang:puffin.party.invite.already_in_party>")
                return Empty.getDefaultInstance()
            }

            sendInvitationMessage(party, player, partyOwner)
            val timer = catchingTimer(daemon = true, initialDelay = 60_000, period = 60_000) {
                this.cancel()
                if (party.invitations.contains(player)) {
                    party.invitations.remove(player)
                    Utils.sendChatAsync(
                        player,
                        "<p2><lang:puffin.party.invite.expired:'${partyOwner.name}'>"
                    )
                }
            }
            party.invitations[player] = timer
            return Empty.getDefaultInstance()
        }

        override suspend fun partyChat(request: PartySvc.PartyChatRequest): Empty = handleRPC {
            val uuid = UUID.fromString(request.playerUuid)
            val party = partyOf(uuid)
            if (party != null) {
                Utils.sendChatAsync(
                    party.members,
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
                    players += party.members.map {
                        playerEntry {
                            username = it.name
                            role = if (party.leader == it) "Leader" else "Member"
                        }
                    }
                }
            } else {
                Utils.sendChat(uuid, "<red><lang:puffin.party.chat.not_found>")
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
                    if (party.members.contains(player)) {
                        party.members.remove(player)
                        Utils.sendChat(
                            party.members,
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
                party.members.remove(player)
                Utils.sendChat(player, Utils.surroundWithSeparators("<p2><lang:puffin.party.leave.self>"))
                Utils.sendChat(
                    party.members,
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
            if (!party.members.contains(oldUuid)) {
                Utils.sendChat(oldUuid, "<red><lang:puffin.party.member_not_found>")
                return Empty.getDefaultInstance()
            }
            party.leader = newUuid
            Utils.sendChat(
                party.members,
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
            val warpNeeded = party.members.count { member -> !playersInInstance.contains(member) }

            if (warpNeeded > emptySlots) {
                Utils.sendChat(uuid, "<red><lang:puffin.party.warp.not_enough_space>")
                return Empty.getDefaultInstance()
            }

            // Warp every member
            val leaderGameId = tracker.getPlayer(party.leader)?.gameId ?: return@handleRPC Empty.getDefaultInstance()
            val membersToWarp =
                party.members.count { member -> tracker.getPlayer(member)?.gameId != leaderGameId }
            party.members.forEach {
                if (party.leader != it) {
                    Utils.sendPlayerToInstance(it, gameId)
                }
            }
            Utils.sendChat(
                party.members,
                "<p2><lang:puffin.party.warp.success:'<p1>$membersToWarp':'${party.leader.name}'>"
            )

            return Empty.getDefaultInstance()
        }
    }
}