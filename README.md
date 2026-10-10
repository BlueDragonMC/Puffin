# Puffin

**Puffin** is BlueDragon's game server orchestration service and queue system.
It runs in a container inside a Kubernetes cluster and uses the Agones API to discover game servers, manage the
instances running on them, and route players to games.

## Usage

Puffin expects an Agones-enabled Kubernetes cluster, MongoDB, the LuckPerms REST API, and a populated worlds folder.
See the [deployment guides](https://developer.bluedragonmc.com/deployment/kubernetes/) for details.

- Clone: `git clone https://github.com/BlueDragonMC/Puffin.git`
- Configure: see guide below
- Build: `./gradlew build`
- Run: `java -jar build/libs/Puffin-x.x.x-all.jar`

Puffin is built and run with JDK 25.

## Configuration

Environment variables:

| Name                              | Description                                                                                                 | Default                    |
|-----------------------------------|-------------------------------------------------------------------------------------------------------------|----------------------------|
| `PUFFIN_GRPC_PORT`                | The port that Puffin uses for its gRPC server.                                                              | 50051                      |
| `PUFFIN_K8S_NAMESPACE`            | The Kubernetes namespace used in all API requests.                                                          | default                    |
| `PUFFIN_WORLD_FOLDER`             | The worlds folder, as described in the [docs](https://developer.bluedragonmc.com/reference/worlds-folder/). | /puffin/worlds/            |
| `PUFFIN_MONGO_CONNECTION_STRING`  | A MongoDB connection string.                                                                                | mongodb://mongo:27017      |
| `PUFFIN_LUCKPERMS_URL`            | The base URL used to interact with the LuckPerms REST API.                                                  | http://luckperms:8080      |
| `PUFFIN_DRAIN_OUTDATED_SERVERS`   | When true, game servers running an out-of-date version are drained (no new games are created on them).      | true                       |
| `PUFFIN_LOBBY_GAME_NAME`          | The name of the game type used for lobbies.                                                                 | Lobby                      |
| `PUFFIN_GS_SYNC_PERIOD_MS`        | The amount of milliseconds in between game server syncs                                                     | 10000                      |
| `PUFFIN_K8S_SYNC_PERIOD_MS`       | The amount of milliseconds in between proxy syncs                                                           | 10000                      |
| `PUFFIN_GAMESERVER_GRPC_PORT`     | The port used to create gRPC channels to game servers                                                       | 50051                      |
| `PUFFIN_PROXY_GRPC_PORT`          | The port used to create gRPC channels to proxy servers                                                      | 50051                      |
| `PUFFIN_API_PORT`                 | The port used for the dashboard WebSocket API.                                                              | 8080                       |
| `PUFFIN_MAPS_PORT`                | The port that the map service listens on for map data.                                                      | 8082                       |
| `PUFFIN_SERVICE_HOST`             | The hostname used to build map download URLs (set automatically by Kubernetes).                             | The local machine hostname |

> [!TIP]
> If you are running Puffin on the same machine as a proxy or game server without containers or VMs, you will have
> to change the `PUFFIN_GAMESERVER_GRPC_PORT` and `PUFFIN_PROXY_GRPC_PORT` environment variables to avoid port
> conflicts.

## Internals

### Services

Puffin is composed of many different services:

| Service Name          | Description                                                                                                               |
|-----------------------|---------------------------------------------------------------------------------------------------------------------------|
| ApiService            | Streams players, parties, servers, and instances to the dashboard over a WebSocket.                                       |
| DatabaseConnection    | Connects to MongoDB and the LuckPerms API to fetch player names, UUIDs, colors, and map data. Caches responses in memory. |
| GameServerManager     | Watches Agones game servers and keeps track of the instances running on each one.                                         |
| JukeboxService        | Saves players' song queues while they transfer between servers.                                                           |
| K8sServiceDiscovery   | Uses the Kubernetes API to maintain a set of proxy and game server IP addresses accessible within the cluster.            |
| MapService            | Serves map data and configs to game servers over HTTP and gRPC.                                                           |
| PartyManager          | Handles creating parties, party chat, invitations, warps, transfers, and marathons.                                       |
| PlayerTracker         | Maintains a map of players' UUIDs to their current games, servers, and proxies.                                           |
| PrivateMessageService | Sends private messages (i.e. /msg) to players on other servers.                                                           |
| QueueService          | Manages games and instances, and sends queued players to the best available game.                                         |
| ServerVersionResolver | Determines whether a game server is running an outdated version so that it can be drained.                                |

## Events

This is not an exhaustive list.

### Initialization

When Puffin starts up, it needs to sync up its state with the rest of the cluster. This involves:

| Service             | Action                                                                                     |
|---------------------|--------------------------------------------------------------------------------------------|
| GameServerManager   | Listing `GameServer` objects and loading the current image of each Agones Fleet            |
| K8sServiceDiscovery | Listing proxies in the cluster and getting player lists from each one                      |

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
| QueueService       | Removes the player from the queue                                                               |
| DatabaseConnection | Evicts any cached information from MongoDB for the player                                       |

### Periodic Syncs

Watching resources allows Puffin to be aware of actions happening in real-time, but this does introduce desync issues.
Puffin attempts to combat this by periodically syncing information in the following services:

| Service             | Rate                                     | Task                                                                     |
|---------------------|------------------------------------------|--------------------------------------------------------------------------|
| GameServerManager   | Every 10 seconds, or when desync occurs  | Fetches list of game servers, their ready states, and current instances. |
| K8sServiceDiscovery | Every 10 seconds                         | Refreshes the list of proxies and the players connected to them.         |
| PlayerTracker       | Every 10 seconds                         | Clears stale player state that no longer matches a known server.         |
