package com.bluedragonmc.puffin.services

import com.bluedragonmc.messages.*
import com.bluedragonmc.puffin.util.Utils
import com.bluedragonmc.puffin.util.Utils.catchingTimer
import java.util.*

class PartyManager(app: ServiceHolder) : Service(app) {

    private val parties = mutableSetOf<Party>()
    private fun partyOf(player: UUID) = parties.find { player in it.members }
    private fun createParty(leader: UUID) =
        Party(mutableListOf(leader), mutableMapOf(), leader).also { parties.add(it) }

    private val UUID.username: String
        get() = app.get(DatabaseConnection::class).getPlayerName(this@username) ?: this.toString()

    private fun sendInvitationMessage(party: Party, invitee: UUID, inviter: UUID) {
        Utils.sendChat(party.members,
            "\n<p2><lang:puffin.party.invite.other:'<p1>${inviter.username}':'<p1>${invitee.username}'>")
        Utils.sendChat(invitee,
            "\n<p2><click:run_command:/party accept $inviter><lang:puffin.party.invite.1:'<p1>${inviter.username}'>\n<p2><lang:puffin.party.invite.2:'<p2><lang:puffin.party.invite.clickable>'></click>\n")
    }

    data class Party(val members: MutableList<UUID>, val invitations: MutableMap<UUID, Timer>, var leader: UUID)

    override fun initialize() {
        val client = app.get(MessagingService::class).client
        val playerTracker = app.get(PlayerTracker::class)
        val gameStateManager = app.get(GameStateManager::class)

        client.subscribe(InvitePlayerToPartyMessage::class) { message ->
            val party = partyOf(message.partyOwner) ?: createParty(message.partyOwner)
            sendInvitationMessage(party, message.player, message.partyOwner)
            val timer = catchingTimer(daemon = true, initialDelay = 60_000, period = 60_000) {
                this.cancel()
                if (party.invitations.contains(message.player)) {
                    party.invitations.remove(message.player)
                    Utils.sendChat(message.player,
                        "<p2><lang:puffin.party.invite.expired:'<p1>${message.partyOwner.username}'>")
                }
            }
            party.invitations[message.player] = timer
        }

        client.subscribe(AcceptPartyInvitationMessage::class) { message ->
            val party = partyOf(message.partyOwner)
            if (party == null) {
                Utils.sendChat(message.player, "<red><lang:puffin.party.not_found>")
                return@subscribe
            }
            if (party.invitations.contains(message.player)) {
                Utils.sendChat(party.members, "\n<p2><lang:puffin.party.join.other:'<p1>${message.player.username}'>")
                party.members.add(message.player)
                party.invitations.remove(message.player)
                Utils.sendChat(message.player,
                    "\n<p2><lang:puffin.party.join.self:'<p1>${message.partyOwner.username}'>\n")
            } else {
                Utils.sendChat(message.player, "<red><lang:puffin.party.join.no_invitation>")
            }
        }

        client.subscribe(RemovePlayerFromPartyMessage::class) { message ->
            if (message.partyOwner == message.player) {
                Utils.sendChat(message.player, "<red><lang:puffin.party.kick.self>")
            } else {
                val party = partyOf(message.partyOwner)
                if (party != null) {
                    if (party.members.contains(message.player)) {
                        party.members.remove(message.player)
                        Utils.sendChat(party.members,
                            "\n<p2><lang:puffin.party.kick.success:'<p1>${message.player.username}'>\n")
                        Utils.sendChat(message.player, "\n<p2><lang:puffin.party.kick.removed>\n")
                    } else {
                        Utils.sendChat(message.player, "<red><lang:puffin.party.member_not_found>")
                    }
                } else {
                    Utils.sendChat(message.player, "<red><lang:puffin.party.not_found>")
                }
            }
        }

        client.subscribe(PartyTransferMessage::class) { message ->
            val party = partyOf(message.oldOwner)
            if (party == null) {
                Utils.sendChat(message.oldOwner, "<red><lang:puffin.party.chat.not_found>")
                return@subscribe
            }
            if (party.leader != message.oldOwner) {
                Utils.sendChat(message.oldOwner, "<red><lang:puffin.party.transfer.not_leader>")
                return@subscribe
            }
            if (!party.members.contains(message.newOwner)) {
                Utils.sendChat(message.oldOwner, "<red><lang:puffin.party.member_not_found>")
                return@subscribe
            }
            party.leader = message.newOwner
            Utils.sendChat(party.members,
                "\n<p2><lang:puffin.party.transfer.success:'<p1>${message.newOwner.username}'>\n")
        }

        client.subscribe(PartyWarpMessage::class) { message ->
            val party = partyOf(message.partyOwner) ?: return@subscribe
            val emptySlots =
                gameStateManager.getEmptySlots(message.instanceId) + playerTracker.getPlayersInInstance(message.instanceId)
                    .count { party.members.contains(it) }
            if (party.members.size - 1 > emptySlots) {
                Utils.sendChat(message.partyOwner,
                    "\n<red><lang:puffin.party.warp.not_enough_space>\n")
                return@subscribe
            }
            val tracker = app.get(PlayerTracker::class)
            val membersToWarp =
                party.members.count { tracker.getInstanceOfPlayer(it) != tracker.getInstanceOfPlayer(party.leader) }
            party.members.forEach {
                if (party.leader != it) client.publish(SendPlayerToInstanceMessage(it, message.instanceId))
                Utils.sendChat(it,
                    "<p2><lang:puffin.party.warp.success:'<p1>$membersToWarp':'<p1>${party.leader.username}'>")
            }
        }

        client.subscribe(PartyChatMessage::class) { message ->
            val party = partyOf(message.player)
            if (party != null) {
                Utils.sendChat(party.members,
                    "<p3><lang:puffin.party.chat.prefix> <white>${message.player.username}<gray>: <white>${message.message}")
            } else {
                Utils.sendChat(message.player, "<red><lang:puffin.party.chat.not_found>")
            }
        }

        client.subscribe(PartyListMessage::class) { message ->
            val party = partyOf(message.player)
            if (party != null) {
                val members = party.members.filter { it != party.leader }
                    .joinToString(prefix = "<p1>", separator = "<p2>, <p1>") { it.username }
                val leader = party.leader.username
                Utils.sendChat(message.player,
                    "\n<p2><lang:puffin.party.list.leader:'<p1>$leader'>\n<p2><lang:puffin.party.list.members:'${party.members.size - 1}':'$members'>\n")
            } else {
                Utils.sendChat(message.player, "<red><lang:puffin.party.chat.not_found>")
            }
        }
    }

    override fun close() {

    }
}