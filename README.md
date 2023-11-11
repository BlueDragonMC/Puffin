# Puffin

**Puffin** is BlueDragon's container orchestration service and queue system.
It runs in a Docker container with access to the external Docker runtime to create new containers.

## Usage

- Clone: `git clone https://github.com/BlueDragonMC/Puffin.git`
- Configure: see guide below
- Build: `./gradlew build`
- Run: `java -jar build/libs/Puffin-x.x.x-all.jar`

## Internals

### Services

Puffin is composed of many different services:

| Service Name          | Description                                                                                                                              |
|-----------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| DatabaseConnection    | Connects to MongoDB to fetch player names, UUIDs, colors, etc. Caches responses in memory.                                               |
| GameManager           | Fetches and maintains a list of game servers using the Kubernetes API                                                                    |
| GameStateManager      | Receives messages from game servers to update the states of the servers' games. Stores the states in a map for other services to access. |
| K8sServiceDiscovery   | Uses the Kubernetes API to maintain a set of proxy and game server IP addresses accessible within the cluster.                           |
| MinInstanceService    | Ensures that the network meets a minimum amount of joinable instances for each game. Starts new instances when necessary.                |
| PartyManager          | Handles creating parties, party chat, invitations, warps, and transfers.                                                                 |
| PlayerTracker         | Maintains lists that map each player to the game, server, and proxy they're currently in.                                                |
| PrivateMessageService | Sends private messages (i.e. /msg) to players on other servers.                                                                          |
| Queue                 | Receives add-to-queue requests and sends the player to the game that's soonest to start.                                                 |

## Events

This is not an exhaustive list.

### Initialization

When Puffin starts up, it needs to sync up its state with the rest of the cluster. This involves:

| Service             | Action                                                                |
|---------------------|-----------------------------------------------------------------------|
| GameManager         | Listing `GameServer` Kubernetes objects                               |
| K8sServiceDiscovery | Listing proxies in the cluster and getting player lists from each one |

### When a player logs in to a proxy

| Service       | Action                                    |
|---------------|-------------------------------------------|
| PlayerTracker | Records the player's current proxy server |

### When a player switches games

| Service       | Action                                           |
|---------------|--------------------------------------------------|
| PlayerTracker | Updates the player's current server and instance |

### When a player logs out of a proxy

| Service            | Action                                                                                          |
|--------------------|-------------------------------------------------------------------------------------------------|
| PlayerTracker      | Clears the player's current proxy, game server, and instance                                    |
| PartyManager       | Removes the player from their party. If the leader left, transfers the party to a party member. |
| DatabaseConnection | Evicts any cached information from MongoDB for the player                                       |

### Periodic Syncs

Watching resources allows Puffin to be aware of actions happening in real-time, but this does introduce desync issues.
Puffin attempts to combat this by periodically syncing information in the following services:

| Service            | Rate                    | Task                                                         |
|--------------------|-------------------------|--------------------------------------------------------------|
| GameManager        | When desync is detected | Fetches list of game servers and their ready states.         |
| MinInstanceService | Every 5 seconds         | Ensures the network meets our minimum instance requirements. |
