package com.bluedragonmc.puffin.services

import com.bluedragonmc.messages.*
import com.bluedragonmc.puffin.util.Utils
import com.bluedragonmc.puffin.util.Utils.catchingTimer
import java.util.*

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
        if (color != null)
            "<$color>$username"
        else
            username
    }

    private fun sendInvitationMessage(party: Party, invitee: UUID, inviter: UUID) {
        Utils.sendChat(party.members,
            "\n<p2><lang:puffin.party.invite.other:'${inviter.name}':'${invitee.name}'>")
        Utils.sendChat(invitee,
            "\n<p2><click:run_command:/party accept $inviter><lang:puffin.party.invite.1:'${inviter.name}'>\n<p2><lang:puffin.party.invite.2:'<p2><lang:puffin.party.invite.clickable>'></click>\n")
    }

    data class Party(
        val svc: PartyManager,
        val members: MutableList<UUID>,
        val invitations: MutableMap<UUID, Timer>,
        var leader: UUID,
    ) {

        fun update() {
            if (members.size <= 1 && invitations.isEmpty()) {
                // If a party has no members and all invites expires, delete it
                Utils.sendChat(leader, "<red><lang:puffin.party.disband.auto>")
                members.clear()
                leader = UUID(0L, 0L)
                svc.parties.remove(this)
            } else if(svc.app.get(PlayerTracker::class).getInstanceOfPlayer(leader) == null) {
                // If the party leader left, transfer the party to one of the members
                val member = members.first { it != leader }
                Utils.sendChat(members, "<yellow><lang:puffin.transfer.auto:'${svc.getUsername(member)}':'${svc.getUsername(leader)}'>")
                leader = member
            }
        }
    }

    override fun initialize() {
        val client = app.get(MessagingService::class).client
        val playerTracker = app.get(PlayerTracker::class)
        val gameStateManager = app.get(GameStateManager::class)

        client.subscribe(InvitePlayerToPartyMessage::class) { message ->
            if (message.partyOwner == message.player) {
                Utils.sendChat(message.player, "<red><lang:puffin.party.invite.self>")
                return@subscribe
            }
            val party = partyOf(message.partyOwner) ?: createParty(message.partyOwner)
            sendInvitationMessage(party, message.player, message.partyOwner)
            val timer = catchingTimer(daemon = true, initialDelay = 60_000, period = 60_000) {
                this.cancel()
                if (party.invitations.contains(message.player)) {
                    party.invitations.remove(message.player)
                    Utils.sendChat(message.player,
                        "<p2><lang:puffin.party.invite.expired:'${message.partyOwner.name}'>")
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
                Utils.sendChat(party.members, "\n<p2><lang:puffin.party.join.other:'${message.player.name}'>")
                party.members.add(message.player)
                party.invitations.remove(message.player)
                Utils.sendChat(message.player,
                    "\n<p2><lang:puffin.party.join.self:'${message.partyOwner.name}'>\n")
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
                            "\n<p2><lang:puffin.party.kick.success:'${message.player.name}'>\n")
                        Utils.sendChat(message.player, "\n<p2><lang:puffin.party.kick.removed>\n")
                        party.update()
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
                "\n<p2><lang:puffin.party.transfer.success:'${message.newOwner.name}'>\n")
        }

        client.subscribe(PartyWarpMessage::class) { message ->
            val party = partyOf(message.partyOwner)
            if (party == null) {
                Utils.sendChat(message.partyOwner, "<red><lang:puffin.party.not_found>")
                return@subscribe
            }
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
                    "<p2><lang:puffin.party.warp.success:'<p1>$membersToWarp':'${party.leader.name}'>")
            }
        }

        client.subscribe(PartyChatMessage::class) { message ->
            val party = partyOf(message.player)
            if (party != null) {
                Utils.sendChat(party.members,
                    "<p3><lang:puffin.party.chat.prefix> <white>${message.player.name}<gray>: <white>${message.message}")
            } else {
                Utils.sendChat(message.player, "<red><lang:puffin.party.chat.not_found>")
            }
        }

        client.subscribe(PartyListMessage::class) { message ->
            val party = partyOf(message.player)
            if (party != null) {
                val members = party.members.filter { it != party.leader }
                    .joinToString(separator = "<p2>, ") { it.name }
                val leader = party.leader.name
                Utils.sendChat(message.player,
                    "\n<p2><lang:puffin.party.list.leader:'$leader'>\n<p2><lang:puffin.party.list.members:'${party.members.size - 1}':'$members'>\n")
            } else {
                Utils.sendChat(message.player, "<red><lang:puffin.party.chat.not_found>")
            }
        }

        client.subscribe(PlayerLogoutMessage::class) { message ->
            val party = partyOf(message.player) ?: return@subscribe
            party.members.remove(message.player)
            party.members.forEach {
                Utils.sendChat(it, "\n<red><lang:puffin.party.player_logged_out:'${message.player.name}'>\n")
            }
            party.update()
        }
    }

    override fun close() {

    }
}