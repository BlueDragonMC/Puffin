package com.bluedragonmc.puffin.grpc

import com.bluedragonmc.api.grpc.PartyListResponseKt.playerEntry
import com.bluedragonmc.api.grpc.PartyServiceGrpcKt
import com.bluedragonmc.api.grpc.PartySvc
import com.bluedragonmc.api.grpc.partyListResponse
import com.bluedragonmc.puffin.app.ApplicationScope
import com.bluedragonmc.puffin.services.IPlayerTracker
import com.bluedragonmc.puffin.services.IQueueService
import com.bluedragonmc.puffin.services.Party
import com.bluedragonmc.puffin.services.PartyManager
import com.bluedragonmc.puffin.util.Utils
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.protobuf.Empty
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * gRPC adapter for parties.
 */
@Singleton
class PartyGrpcService @Inject constructor(
    private val partyManager: PartyManager,
    private val playerTracker: IPlayerTracker,
    private val queueService: IQueueService,
    private val applicationScope: ApplicationScope,
) : PartyServiceGrpcKt.PartyServiceCoroutineImplBase() {

    private suspend fun sendInvitationMessage(party: Party, invitee: UUID, inviter: UUID) {
        playerTracker.sendChatAsync(
            partyManager.getMembers(party),
            Utils.surroundWithSeparators("<p2><lang:puffin.party.invite.other:'${partyManager.getUsername(inviter)}':'${partyManager.getUsername(invitee)}'>")
        )
        playerTracker.sendChatAsync(
            invitee,
            Utils.surroundWithSeparators("<p2><click:run_command:/party accept $inviter><lang:puffin.party.invite.1:'${partyManager.getUsername(inviter)}'>\n<p2><lang:puffin.party.invite.2:'<p2><lang:puffin.party.invite.clickable>'></click>")
        )
    }

    override suspend fun acceptInvitation(request: PartySvc.PartyAcceptInviteRequest): Empty = handleRPC {
        val partyOwner = UUID.fromString(request.partyOwnerUuid)
        val player = UUID.fromString(request.playerUuid)
        val party = partyManager.partyOf(partyOwner)
        if (party == null) {
            playerTracker.sendChat(player, "<red><lang:puffin.party.not_found>")
            return Empty.getDefaultInstance()
        }
        if (partyManager.hasInvitation(party, player)) {
            playerTracker.sendChat(
                partyManager.getMembers(party),
                Utils.surroundWithSeparators("<p2><lang:puffin.party.join.other:'${partyManager.getUsername(player)}'>")
            )
            partyManager.addMember(party, player)
            playerTracker.sendChat(
                player,
                Utils.surroundWithSeparators("<p2><lang:puffin.party.join.self:'${partyManager.getUsername(partyOwner)}'>")
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
        val party = partyManager.partyOf(partyOwner) ?: partyManager.createParty(partyOwner)

        if (partyManager.getMembers(party).contains(player)) {
            playerTracker.sendChat(partyOwner, "<red><lang:puffin.party.invite.already_in_party>")
            return Empty.getDefaultInstance()
        }

        sendInvitationMessage(party, player, partyOwner)
        val expiryJob = applicationScope.launch {
            delay(60_000)
            if (partyManager.hasInvitation(party, player)) {
                partyManager.removeInvitation(party, player)
                playerTracker.sendChatAsync(player, message = {
                    "<p2><lang:puffin.party.invite.expired:'${partyManager.getUsername(partyOwner)}'>"
                })
            }
        }
        partyManager.addInvitation(party, player, expiryJob)
        return Empty.getDefaultInstance()
    }

    override suspend fun partyChat(request: PartySvc.PartyChatRequest): Empty = handleRPC {
        val uuid = UUID.fromString(request.playerUuid)
        val party = partyManager.partyOf(uuid)
        if (party != null) {
            playerTracker.sendChatAsync(
                partyManager.getMembers(party),
                "<p3><lang:puffin.party.chat.prefix> <white>${partyManager.getUsername(uuid)}<gray>: <white>${request.message}"
            )
        } else {
            playerTracker.sendChatAsync(uuid, "<red><lang:puffin.party.chat.not_found>")
        }
        return Empty.getDefaultInstance()
    }

    override suspend fun partyList(request: PartySvc.PartyListRequest): PartySvc.PartyListResponse = handleRPC {
        val uuid = UUID.fromString(request.playerUuid)
        val party = partyManager.partyOf(uuid)
        if (party != null) {
            return partyListResponse {
                players += partyManager.getMembers(party).map {
                    playerEntry {
                        this.uuid = it.toString()
                        username = partyManager.getUsername(it)
                        role = if (partyManager.getLeader(party) == it) "Leader" else "Member"
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
            val party = partyManager.partyOf(partyOwner)
            if (party != null) {
                if (partyManager.getMembers(party).contains(player)) {
                    partyManager.removeMember(party, player)
                    playerTracker.sendChat(
                        partyManager.getMembers(party),
                        Utils.surroundWithSeparators("<p2><lang:puffin.party.kick.success:'${partyManager.getUsername(player)}'>")
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
        val party = partyManager.partyOf(player)

        if (party != null) {
            partyManager.removeMember(party, player)
            playerTracker.sendChat(player, Utils.surroundWithSeparators("<p2><lang:puffin.party.leave.self>"))
            playerTracker.sendChat(
                partyManager.getMembers(party),
                Utils.surroundWithSeparators("<p2><lang:puffin.party.leave.others:'${partyManager.getUsername(player)}'>")
            )
        } else {
            playerTracker.sendChat(player, "<red><lang:puffin.party.not_found>")
        }

        return Empty.getDefaultInstance()
    }

    override suspend fun transferParty(request: PartySvc.PartyTransferRequest): Empty = handleRPC {

        val oldUuid = UUID.fromString(request.playerUuid)
        val newUuid = UUID.fromString(request.newOwnerUuid)

        val party = partyManager.partyOf(oldUuid)
        if (party == null) {
            playerTracker.sendChat(oldUuid, "<red><lang:puffin.party.chat.not_found>")
            return Empty.getDefaultInstance()
        }
        if (partyManager.getLeader(party) != oldUuid) {
            playerTracker.sendChat(oldUuid, "<red><lang:puffin.party.transfer.not_leader>")
            return Empty.getDefaultInstance()
        }
        if (!partyManager.getMembers(party).contains(newUuid) || playerTracker.getPlayer(newUuid) == null) {
            playerTracker.sendChat(oldUuid, "<red><lang:puffin.party.member_not_found>")
            return Empty.getDefaultInstance()
        }
        partyManager.setLeader(party, newUuid)
        playerTracker.sendChat(
            partyManager.getMembers(party),
            Utils.surroundWithSeparators("<p2><lang:puffin.party.transfer.success:'${partyManager.getUsername(newUuid)}'>")
        )

        return Empty.getDefaultInstance()
    }

    override suspend fun warpParty(request: PartySvc.PartyWarpRequest): Empty = handleRPC {
        val uuid = UUID.fromString(request.partyOwnerUuid)
        val party = partyManager.partyOf(uuid)
        // Make sure the player is the leader of their party
        if (party == null) {
            playerTracker.sendChat(uuid, "<red><lang:puffin.party.not_found>")
            return Empty.getDefaultInstance()
        }
        if (partyManager.getLeader(party) != uuid) {
            playerTracker.sendChat(uuid, "<red><lang:puffin.party.warp.not_leader>")
            return Empty.getDefaultInstance()
        }

        // Make sure the instance is not full
        val gameId = request.instanceUuid
        val playersInInstance = playerTracker.getPlayersInInstance(gameId)

        val emptySlots = queueService.getGame(gameId)?.emptySlots ?: 0
        val warpNeeded = partyManager.getMembers(party).count { member -> !playersInInstance.contains(member) }

        if (warpNeeded > emptySlots) {
            playerTracker.sendChat(uuid, "<red><lang:puffin.party.warp.not_enough_space>")
            return Empty.getDefaultInstance()
        }

        // Warp every member
        val leaderGameId =
            playerTracker.getPlayer(partyManager.getLeader(party))?.gameId ?: return@handleRPC Empty.getDefaultInstance()
        val membersToWarp =
            partyManager.getMembers(party).count { member -> playerTracker.getPlayer(member)?.gameId != leaderGameId }
        partyManager.getMembers(party).forEach {
            if (partyManager.getLeader(party) != it) {
                queueService.sendPlayerToInstance(it, gameId)
            }
        }
        playerTracker.sendChat(
            partyManager.getMembers(party),
            "<p2><lang:puffin.party.warp.success:'<p1>$membersToWarp':'${partyManager.getUsername(partyManager.getLeader(party))}'>"
        )

        return Empty.getDefaultInstance()
    }

    override suspend fun getMarathonLeaderboard(request: PartySvc.MarathonLeaderboardRequest): Empty = handleRPC {
        for (uuidString in request.playerUuidsList) {
            val uuid = UUID.fromString(uuidString)
            val party = partyManager.partyOf(uuid)
            if (party == null) {
                if (!request.silent) {
                    playerTracker.sendChat(uuid, "<red><lang:puffin.party.not_found>")
                }
                continue
            }
            val marathon = partyManager.getMarathon(party)
            if (marathon == null) {
                if (!request.silent) {
                    playerTracker.sendChat(uuid, "<red><lang:puffin.party.marathon.not_found>")
                }
                continue
            }
            val lb = partyManager.formatMarathonLeaderboard(marathon)
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
        val party = partyManager.partyOf(uuid)

        if (party == null) {
            playerTracker.sendChat(uuid, "<red><lang:puffin.party.not_found>")
            return Empty.getDefaultInstance()
        }

        if (partyManager.getMarathon(party) != null) {
            playerTracker.sendChat(uuid, "<red><lang:puffin.party.marathon_already_started>")
            return Empty.getDefaultInstance()
        }

        if (uuid != partyManager.getLeader(party)) {
            playerTracker.sendChat(uuid, "<red><lang:puffin.party.marathon.not_leader>")
            return Empty.getDefaultInstance()
        }

        partyManager.startMarathon(party, request.durationMs.toLong())

        val minutes = request.durationMs / 1000 / 60
        playerTracker.sendChat(
            partyManager.getMembers(party),
            Utils.surroundWithSeparators("<yellow><lang:puffin.party.marathon.started:'${partyManager.getUsername(uuid)}':'<p1><lang:puffin.party.marathon.started.time_period:$minutes>'>")
        )

        return Empty.getDefaultInstance()
    }

    override suspend fun stopMarathon(request: PartySvc.StopMarathonRequest): Empty = handleRPC {

        val uuid = UUID.fromString(request.playerUuid)
        val party = partyManager.partyOf(uuid)

        if (party == null) {
            playerTracker.sendChat(uuid, "<red><lang:puffin.party.not_found>")
            return Empty.getDefaultInstance()
        }

        if (partyManager.getMarathon(party) == null) {
            playerTracker.sendChat(uuid, "<red><lang:puffin.party.marathon.not_found>")
            return Empty.getDefaultInstance()
        }

        if (uuid != partyManager.getLeader(party)) {
            playerTracker.sendChat(uuid, "<red><lang:puffin.party.marathon.not_leader>")
            return Empty.getDefaultInstance()
        }

        playerTracker.sendChat(
            partyManager.getMembers(party),
            Utils.surroundWithSeparators("<yellow><lang:puffin.party.marathon.ended_by_player:'${partyManager.getUsername(uuid)}'>\n${partyManager.formatMarathonLeaderboard(partyManager.getMarathon(party)!!)}")
        )

        partyManager.endMarathon(party)

        return Empty.getDefaultInstance()
    }

    override suspend fun recordCoinAward(request: PartySvc.RecordCoinAwardRequest): Empty {
        val uuid = UUID.fromString(request.playerUuid)
        val party = partyManager.partyOf(uuid)

        if (party == null || partyManager.getMarathon(party) == null) {
            return Empty.getDefaultInstance()
        }

        val leaderGameId = playerTracker.getPlayer(partyManager.getLeader(party))?.gameId
        if (request.gameId != leaderGameId) {
            playerTracker.sendChat(uuid, "<gray><lang:puffin.party.marathon.outside_points>")
            return Empty.getDefaultInstance()
        }

        partyManager.addMarathonPoints(party, uuid, request.coins)

        return Empty.getDefaultInstance()
    }
}
