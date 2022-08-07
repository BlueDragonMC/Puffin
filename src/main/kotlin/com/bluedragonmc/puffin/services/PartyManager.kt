package com.bluedragonmc.puffin.services

import com.bluedragonmc.messages.*
import com.bluedragonmc.puffin.util.Utils
import com.bluedragonmc.puffin.util.Utils.catchingTimer
import kotlinx.coroutines.runBlocking
import java.util.*

class PartyManager(app: ServiceHolder) : Service(app) {

    private val parties = mutableSetOf<Party>()
    private fun partyOf(player: UUID) = parties.find { player in it.members }
    private fun createParty(leader: UUID) =
        Party(mutableListOf(leader), mutableMapOf(), leader).also { parties.add(it) }

    private val UUID.username: String
        get() {
            return runBlocking {
                app.get(DatabaseConnection::class).getPlayerName(this@username) ?: this.toString()
            }
        }

    private fun sendInvitationMessage(player: UUID, partyOwner: UUID) {
        Utils.sendChat(player,
            "\n<click:run_command:/party accept $partyOwner><aqua>${partyOwner.username}<yellow> has invited you to join their party!\n<red>Click here<yellow> to accept the invitation.</click>\n")
    }

    data class Party(val members: MutableList<UUID>, val invitations: MutableMap<UUID, Timer>, var leader: UUID)

    override fun initialize() {
        val client = app.get(MessagingService::class).client
        val playerTracker = app.get(PlayerTracker::class)
        val gameStateManager = app.get(GameStateManager::class)

        client.subscribe(InvitePlayerToPartyMessage::class) { message ->
            val party = partyOf(message.partyOwner) ?: createParty(message.partyOwner)
            sendInvitationMessage(message.player, message.partyOwner)
            val timer = catchingTimer(daemon = true, initialDelay = 60_000, period = 60_000) {
                this.cancel()
                if (party.invitations.contains(message.player)) {
                    party.invitations.remove(message.player)
                    Utils.sendChat(message.player,
                        "\n<yellow>The invitation to <aqua>${message.partyOwner.username}<yellow>'s party\n<yellow>has expired.\n")
                }
            }
            party.invitations[message.player] = timer
        }

        client.subscribe(AcceptPartyInvitationMessage::class) { message ->
            val party = partyOf(message.partyOwner)
            if (party == null) {
                Utils.sendChat(message.player, "<red>That party does not exist!")
            } else {
                if (party.invitations.contains(message.player)) {
                    party.members.add(message.player)
                    party.invitations.remove(message.player)
                    Utils.sendChat(message.player,
                        "\n<yellow>You have joined <aqua>${message.partyOwner.username}<yellow>'s party!\n")
                } else {
                    Utils.sendChat(message.player, "<red>You don't have an invitation to that party!")
                }
            }
        }

        client.subscribe(RemovePlayerFromPartyMessage::class) { message ->
            if (message.partyOwner == message.player) {
                Utils.sendChat(message.player, "<red>You can't kick yourself from your own party!")
            } else {
                val party = partyOf(message.partyOwner)
                if (party != null) {
                    if (party.members.contains(message.player)) {
                        party.members.remove(message.player)
                        party.members.forEach {
                            Utils.sendChat(it,
                                "\n<aqua>${message.player.username}<yellow> has been removed from the party.\n")
                        }
                        Utils.sendChat(message.player, "\n<yellow>You have been <red>removed<yellow> from the party.\n")
                    } else {
                        Utils.sendChat(message.player,
                            "<red>That player is not in the party! Make sure you typed their name correctly.")
                    }
                } else {
                    Utils.sendChat(message.player, "<red>That party does not exist!")
                }
            }
        }

        client.subscribe(PartyTransferMessage::class) { message ->
            val party = partyOf(message.oldOwner)
            if (party == null || party.leader != message.oldOwner) {
                Utils.sendChat(message.oldOwner, "<red>You must be the leader of a party to do this.")
                return@subscribe
            }
            if (!party.members.contains(message.newOwner)) {
                Utils.sendChat(message.oldOwner,
                    "<red>That player is not in the party! Make sure you typed their name correctly.")
                return@subscribe
            }
            party.leader = message.newOwner
            party.members.forEach {
                Utils.sendChat(it,
                    "\n<yellow>The party has been transferred to <aqua>${message.newOwner.username}<yellow>.\n")
            }
        }

        client.subscribe(PartyWarpMessage::class) { message ->
            val party = partyOf(message.partyOwner) ?: return@subscribe
            val emptySlots =
                gameStateManager.getEmptySlots(message.instanceId) + playerTracker.getPlayersInInstance(message.instanceId)
                    .count { party.members.contains(it) }
            if (party.members.size - 1 > emptySlots) {
                Utils.sendChat(message.partyOwner,
                    "\n<red>There is not enough space in this game for your entire party!</red>\n")
                return@subscribe
            }
            party.members.forEach {
                if (party.leader != it) client.publish(SendPlayerToInstanceMessage(it, message.instanceId))
            }
        }

        client.subscribe(PartyChatMessage::class) { message ->
            val party = partyOf(message.player)
            if (party != null) {
                party.members.forEach {
                    Utils.sendChat(it,
                        "<blue>Party > <white>${message.player.username}<gray>: <white>${message.message}")
                }
            } else {
                Utils.sendChat(message.player, "<red>You are not in a party.")
            }
        }
    }

    override fun close() {

    }
}