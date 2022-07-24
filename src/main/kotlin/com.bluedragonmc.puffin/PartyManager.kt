package com.bluedragonmc.puffin

import com.bluedragonmc.messages.*
import com.bluedragonmc.messagingsystem.AMQPClient
import com.bluedragonmc.puffin.DatabaseConnection.getUsername
import java.util.Timer
import java.util.UUID
import kotlin.concurrent.timer

object PartyManager {

    private lateinit var client: AMQPClient
    private val parties = mutableSetOf<Party>()
    private fun partyOf(player: UUID) = parties.find { player in it.members }
    private fun createParty(leader: UUID) =
        Party(mutableListOf(leader), mutableMapOf(), leader).also { parties.add(it) }

    private fun sendInvitationMessage(player: UUID, partyOwner: UUID) {
        client.publish(
            SendChatMessage(
                player,
                "\n<click:run_command:/party accept $partyOwner><aqua>${partyOwner.getUsername()}<yellow> has invited you to join their party!\n<red>Click here<yellow> to accept the invitation.</click>\n"
            )
        )
    }

    fun start(client: AMQPClient) {
        this.client = client
        client.subscribe(InvitePlayerToPartyMessage::class) { message ->
            val party = partyOf(message.partyOwner) ?: createParty(message.partyOwner)
            sendInvitationMessage(message.player, message.partyOwner)
            val timer = timer(daemon = true, initialDelay = 60_000, period = 60_000) {
                this.cancel()
                if(party.invitations.contains(message.player)) {
                    party.invitations.remove(message.player)
                    client.publish(
                        SendChatMessage(
                            message.player, "\n<yellow>The invitation to <aqua>${message.partyOwner.getUsername()}<yellow>'s party\n<yellow>has expired.\n"
                        )
                    )
                }
            }
            party.invitations[message.player] = timer
        }
        client.subscribe(AcceptPartyInvitationMessage::class) { message ->
            val party = partyOf(message.partyOwner)
            if (party == null) {
                client.publish(SendChatMessage(message.player, "<red>That party does not exist!"))
            } else {
                if (party.invitations.contains(message.player)) {
                    party.members.add(message.player)
                    party.invitations.remove(message.player)
                    client.publish(
                        SendChatMessage(
                            message.player, "\n<yellow>You have joined <aqua>${message.partyOwner.getUsername()}<yellow>'s party!\n"
                        )
                    )
                } else {
                    client.publish(SendChatMessage(message.player, "<red>You don't have an invitation to that party!"))
                }
            }
        }
        client.subscribe(RemovePlayerFromPartyMessage::class) { message ->
            if (message.partyOwner == message.player) {
                client.publish(SendChatMessage(message.player, "<red>You can't kick yourself from your own party!"))
            } else {
                val party = partyOf(message.partyOwner)
                if (party != null) {
                    if (party.members.contains(message.player)) {
                        party.members.remove(message.player)
                        party.members.forEach {
                            client.publish(
                                SendChatMessage(
                                    it, "\n<aqua>${message.player.getUsername()}<yellow> has been removed from the party.\n"
                                )
                            )
                        }
                        client.publish(
                            SendChatMessage(
                                message.player, "\n<yellow>You have been <red>removed<yellow> from the party.\n"
                            )
                        )
                    } else {
                        client.publish(
                            SendChatMessage(
                                message.player,
                                "<red>That player is not in the party! Make sure you typed their name correctly."
                            )
                        )
                    }
                } else {
                    client.publish(SendChatMessage(message.player, "<red>That party does not exist!"))
                }
            }
        }

        client.subscribe(PartyTransferMessage::class) { message ->
            val party = partyOf(message.oldOwner)
            if (party == null || party.leader != message.oldOwner) {
                client.publish(SendChatMessage(message.oldOwner, "<red>You must be the leader of a party to do this."))
                return@subscribe
            }
            if (!party.members.contains(message.newOwner)) {
                client.publish(
                    SendChatMessage(
                        message.oldOwner,
                        "<red>That player is not in the party! Make sure you typed their name correctly."
                    )
                )
                return@subscribe
            }
            party.leader = message.newOwner
            party.members.forEach {
                client.publish(
                    SendChatMessage(
                        it, "\n<yellow>Party ownership has been transferred to <aqua>${message.newOwner.getUsername()}<yellow>.\n"
                    )
                )
            }
        }

        client.subscribe(PartyWarpMessage::class) { message ->
            val emptySlots = GameStateManager.getEmptySlots(message.instanceId)
            val party = partyOf(message.partyOwner) ?: return@subscribe
            // TODO account for party members besides the leader already being in the game. currently this is not possible
            if (party.members.size-1 > emptySlots) {
                client.publish(
                    SendChatMessage(
                        message.partyOwner, "\n<red>There is not enough space in this game for your entire party!</red>\n"
                    )
                )
                return@subscribe
            }
            party.members.forEach {
                if (party.leader != it) client.publish(SendPlayerToInstanceMessage(it, message.instanceId))
            }
        }

        client.subscribe(PartyChatMessage::class) { message ->
            val party = partyOf(message.player)
            if(party != null) {
                party.members.forEach {
                    client.publish(SendChatMessage(it, "<blue>Party > <white>${message.player.getUsername()}<gray>: <white>${message.message}"))
                }
            } else {
                client.publish(SendChatMessage(message.player, "<red>You are not in a party."))
            }
        }
    }

    data class Party(val members: MutableList<UUID>, val invitations: MutableMap<UUID, Timer>, var leader: UUID)

}