# Puffin
**Puffin** is BlueDragon's container orchestration service and queue system.
It runs in a Docker container with access to the external Docker runtime to create new containers.

## Usage
- Clone: `git clone https://github.com/BlueDragonMC/Puffin.git`
- Build: `./gradlew build`

## Configuration
Puffin is configured using two files: `assets/puffin.json` and `assets/secrets.json`.

`secrets.json`:
* `githubToken`: (String) A GitHub personal access token for downloading private repos
* `velocitySecret`: (String) A long, random string of text used for Velocity modern forwarding

`puffin.json`:
* `worldsFolder`: (String) The complete, absolute path to the parent directory of all worlds. Used for checking validity of map names.
* `puffinNetworkName`: (String) The name of Puffin's internal Docker bridge network
* `mongoHostname`: (String) The hostname of a MongoDB instance
* `mongoPort`: (Int) The port number of the MongoDB instance
* `amqpHostname`: (String) The hostname of a RabbitMQ instance
* `amqpPort`: (Int) The port number of the RabbitMQ instance
* `versions`: (Object) These versions are updated automatically, so manual configuration is typically not necessary.
  * `latestVersions`: (Object)
    * Key: Repository name (i.e. `bluedragonmc/server`)
    * Value: Latest Git commit hash of the repo for which a Docker image exists on the machine
* `containers`: (Array)
  * *Each container can have the following properties:*
  * `type`: (String) One of: `docker_hub`, `github`
  * `minimum`: (Int) The minimum number of containers of this type that must be running
  * `priority`: (Int) The priority of the container. Containers with higher priorities are created first. Containers of the same priority are created at the same time.
  * `networks`: (String Array) List of Docker networks to connect the container to
  * `exposedPorts`: (String Array) Ports for the container to expose in the format: `<port>/<protocol>`, where `<protocol>` is either `tcp` or `udp` and `port` is a number from 0-65535.
  * `portBindings`: (String Array) Ports that will be bound to the host machine, in the format: `<ip>:<hostPort>:<containerPort>`, where `ip` is the IP address to bind on the host machine, `hostPort` is the port to bind on the host machine, and `containerPort` is an exposed port as described above.
  * `mounts`: (Array)
    * *Each mount can have the following properties:*
    * `type`: (String) One of: `BIND`, `VOLUME`, `TMPFS`, `NPIPE`
    * `target`: (String) The absolute path on the container
    * `source`: (String) The absolute path on the host machine
    * `readOnly`: (Boolean) Whether the container can write to the mounted files or not
    * `env`: (Object)
      * Key: Environment variable name (should be all uppercase)
      * Value: Environment variable value
    * `containerUser`: (String) The name of the user that the container runs as. This typically can be left to the default, which does not change the user.