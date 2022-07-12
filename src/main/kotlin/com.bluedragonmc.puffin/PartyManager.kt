package com.bluedragonmc.puffin

import com.bluedragonmc.messages.*
import com.bluedragonmc.messagingsystem.AMQPClient
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
                "\n<click:run_command:/party accept $partyOwner><aqua>$partyOwner<yellow> has invited you to join their party!\n<red>Click here<yellow> to accept the invitation.</click>\n"
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
                            message.player, "\n<yellow>The invitation to <aqua>${message.partyOwner}<yellow>'s party\n<yellow>has expired.\n"
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
                            message.player, "\n<yellow>You have joined <aqua>${message.partyOwner}<yellow>'s party!\n"
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
                                    it, "\n<aqua>${message.player}<yellow> has been removed from the party.\n"
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
        client.subscribe(PartyChatMessage::class) { message ->
            val party = partyOf(message.player)
            if(party != null) {
                party.members.forEach {
                    client.publish(SendChatMessage(it, "<blue>Party > <white>${message.player}<gray>: <white>${message.message}"))
                }
            } else {
                client.publish(SendChatMessage(message.player, "<red>You are not in a party."))
            }
        }
    }

    data class Party(val members: MutableList<UUID>, val invitations: MutableMap<UUID, Timer>, val leader: UUID)

}