package com.bluedragonmc.puffin

import com.bluedragonmc.messagingsystem.AMQPClient
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.model.*
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.io.IOUtils
import org.apache.commons.lang3.RandomStringUtils
import org.slf4j.LoggerFactory
import java.io.*
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.util.*
import kotlin.concurrent.fixedRateTimer

object DockerContainerManager {

    private val logger = LoggerFactory.getLogger(DockerContainerManager::class.java)

    private lateinit var docker: DockerClient
    private val http = HttpClient.newHttpClient()

    private const val GITHUB_USER = "BlueDragonMC"

    private const val PUFFIN_NETWORK_NAME = "puffin_network"

    /**
     * A list of Docker image names whose containers must be connected to the Puffin network.
     */
    private val requiredNetworkAccess = listOf("mongo", "rabbitmq", "puffin")

    /**
     * A list of repositories whose versions are checked on startup if they aren't saved.
     */
    private val repositoriesToVersionCheck = listOf("Server", "Komodo").associateWith { "main" }

    private lateinit var puffinNetworkId: String

    private val worldsFolder = File("worlds") //TODO this does not work while running in a Docker container; the absolute path must be specified, probably with an env variable.

    /**
     * The number of containers to maintain per repository.
     * If there are not enough containers of this type, more will
     * be created every 10 seconds until the threshold is reached.
     */
    private var numContainersByLabel = listOf(
        ContainerMeta(
            "Komodo",
            minimum = 1,
            // expose port 25565 to the host system
            portBindings = listOf(PortBinding(Ports.Binding.bindIpAndPort("0.0.0.0", 25565), ExposedPort.tcp(25565)))
        ),
        ContainerMeta(
            "Server",
            minimum = 2,
            // expose port 25565 to other containers
            exposedPorts = listOf(ExposedPort.tcp(25565)),
            // mount the `worlds` folder in the container so maps can be used
            // Note: read-only mode can't be used because Hephaistos relies on a RandomAccessFile which is initialized with write privileges.
            mounts = listOf(Mount().withSource(worldsFolder.absolutePath).withTarget("/server/worlds/").withType(MountType.BIND))
        )
    )

    data class ContainerMeta(
        val repo: String,
        val minimum: Int,
        val portBindings: List<PortBinding> = emptyList(),
        val exposedPorts: List<ExposedPort> = emptyList(),
        val mounts: List<Mount> = emptyList()
    )

    /**
     * Get the Docker image tag for a specific repository and version
     */
    private fun getTag(repo: String, version: String) = "$GITHUB_USER/$repo:$version".lowercase()

    /**
     * Get the Docker image tag for a specific repository using the latest version
     */
    private fun getMostRecentTag(repo: String) = getTag(repo, getMostRecentVersion(repo) ?: "latest")

    /**
     * Get the latest version of a repository from the properties file.
     * Note that this can return a null value. If this happens, the version should be fetched from GitHub.
     */
    private fun getMostRecentVersion(repo: String): String? {
        return SavedProperties.getString("latest_version_$repo")
    }
    private fun setMostRecentVersion(repo: String, commit: String) {
        SavedProperties.setString("latest_version_$repo", commit)
        SavedProperties.save()
        logger.info("Latest version of $repo was updated to: $commit (Properties file saved)")
    }

    /**
     * Get the label that is added to a Docker image when it is built to specify the version of the repository it is running.
     */
    private fun getVersionLabel(repository: String) = "com.bluedragonmc.${repository.lowercase()}.version"

    fun start(client: AMQPClient) {

        // Connect to Docker via Unix Sockets to create and manage containers
        val config = DefaultDockerClientConfig.Builder().withDockerHost("unix:///var/run/docker.sock").build()
        val httpClient = ApacheDockerHttpClient.Builder().dockerHost(config.dockerHost).build()
        docker = DockerClientImpl.getInstance(config, httpClient)
        logger.info("Connected to Docker.")

        for((repo, defaultBranch) in repositoriesToVersionCheck) {
            if (getMostRecentVersion(repo).isNullOrBlank()) {
                logger.warn("No latest version information for $repo was found. Gathering latest version...")
                fetchLatestVersion(repo, defaultBranch)
            }
        }

        fixedRateTimer("Docker Container Monitoring", daemon = true, period = 10_000) {

            val containers = docker.listContainersCmd().exec()

            // Make sure the Puffin network exists and all containers are connected to it
            val puffinNetworks = docker.listNetworksCmd().withNameFilter(PUFFIN_NETWORK_NAME).exec()
            val puffinNetworkExists = puffinNetworks.isNotEmpty()
            if (!puffinNetworkExists) {
                // Create a new network
                logger.warn("Puffin network does not exist, creating...")
                val response = docker.createNetworkCmd().withName(PUFFIN_NETWORK_NAME).withDriver("bridge").exec()
                puffinNetworkId = response.id
                response.warnings?.forEach { logger.warn(it) }
                logger.warn("Puffin network created with ID: ${response.id}")
                // Connect all running containers to it
                containers.forEach {
                    docker.connectToNetworkCmd().withContainerId(it.id).withNetworkId(response.id).exec()
                }
                logger.warn("Connected ${containers.size} containers to the Puffin network.")
            } else {
                puffinNetworkId = puffinNetworks.first().id
            }

            containers.forEach { container ->
                if(requiredNetworkAccess.contains(container.image)) {
                    if(container.networkSettings?.networks?.containsKey(PUFFIN_NETWORK_NAME) == false) {
                        logger.info("Connecting container ${container.image}/${container.id} to network $PUFFIN_NETWORK_NAME (id: $puffinNetworkId).")
                        docker.connectToNetworkCmd().withContainerId(container.id).withNetworkId(puffinNetworkId).exec()
                    }
                }
            }

            // Make sure there are enough of each container type to satisfy the minimums from [numContainersByLabel]
            for(containerType in numContainersByLabel) {
                val requiredLabel = getVersionLabel(containerType.repo)
                val serverContainers = containers.count {
                    it.labels.containsKey(requiredLabel)
                }
                if (serverContainers < containerType.minimum) {
                    logger.info("There are only $serverContainers containers with label $requiredLabel, but ${containerType.minimum} are required. Starting another.")
                    startContainer(containerType, UUID.randomUUID())
                    return@fixedRateTimer // Only start one container per cycle, no matter the type
                }
            }
        }
    }

    /**
     * Get a [Container] from its [containerId]. This method can return null,
     * which most likely means the container does not exist or is not running.
     */
    fun getRunningContainer(containerId: UUID): Container? {
        val containers = docker.listContainersCmd().withNameFilter(listOf(containerId.toString())).exec()
        return containers.firstOrNull()
    }

    /**
     * Start a container using the most recent tag for [repository],
     * giving the container an environment variable called `container_id`
     * with the string contents of [containerId].
     * The container is immediately connected to the Puffin network
     * and is named after the [containerId].
     * The container is started immediately after it is created.
     */
    private fun startContainer(containerMeta: ContainerMeta, containerId: UUID) {
        val repository = containerMeta.repo
        logger.info("Starting new container with containerId=$containerId")
        var velocitySecret = SavedProperties.getString("velocity_secret")
        if(velocitySecret == null) {
            logger.error("No velocity secret was found in puffin.properties, creating a random one...")
            velocitySecret = RandomStringUtils.randomAlphanumeric(128)
            SavedProperties.setString("velocity_secret", velocitySecret)
            SavedProperties.save()
        }
        val image = getMostRecentTag(repository)
        logger.info("Creating new container with repository $repository and image $image...")
        val response = docker.createContainerCmd(image)
            .withName(containerId.toString()) // container name
            .withEnv(
                "container_id=$containerId", // pass containerId to the program in the container
                "velocity_secret=$velocitySecret", // pass the Velocity modern forwarding secret to the container
            )
            .withExposedPorts(containerMeta.exposedPorts)
            .withHostConfig(HostConfig.newHostConfig()
                .withNetworkMode(puffinNetworkId)
                .withPortBindings(containerMeta.portBindings)
                .withMounts(containerMeta.mounts)
            ) // put this container on the same network, so it can access the DB and messaging
            .exec()
        response.warnings?.forEach {
            logger.warn(it)
        }
        logger.info("Container creation succeeded: container created with ID ${response.id}")
        docker.startContainerCmd(response.id).exec()
        logger.info("Container started successfully.")
    }

    /**
     * Fetches the [branch] from the GitHub [repository], builds a Docker image from its latest commit,
     * and assigns this new image as the server's latest version. This means that
     * all new containers will use this version.
     */
    private fun fetchLatestVersion(repository: String, branch: String) {
        val (tag, commit) = buildDockerImageFromRef(repository, branch)
        if (tag != null && commit != null) {
            setMostRecentVersion(repository, commit)
            logger.info("Update completed.")
        }
    }

    /**
     * Fetches the [branch] from the GitHub [repository] and builds a Docker image from it.
     * This method does not set the server's latest version.
     * @return The tag assigned to the new Docker image paired with the latest commit sha of [branch]
     */
    private fun buildDockerImageFromRef(repository: String, branch: String): Pair<String?, String?> {
        logger.info("Updating $repository to the latest version...")
        val githubURL = getBlobURL(repository, branch)
        val commit = getLatestCommitSha(repository, branch)
        logger.info("Found blob URL from GitHub: $githubURL")
        return buildDockerImage(repository, GzipCompressorInputStream(URL(githubURL).openStream()), commit) to commit
    }

    /**
     * Get the latest commit SHA-1 from a GitHub [repository] on the specified [branch].
     * Returns the full, un-truncated string.
     */
    private fun getLatestCommitSha(repository: String, branch: String): String {
        val request = HttpRequest.newBuilder().GET()
            .uri(URI.create("https://api.github.com/repos/$GITHUB_USER/$repository/commits/$branch"))
            .header("Accept", "application/vnd.github.VERSION.sha").header("Authorization", "Token $githubToken")
            .build()

        val response = http.send(request, BodyHandlers.ofString())
        if (response.statusCode() == 200) {
            logger.info("Found latest commit SHA for branch $branch: ${response.body()}")
            return response.body()
        } else throw RuntimeException("Error received while querying GitHub API: ${response.statusCode()}; ${response.body()}")
    }

    /**
     * Converts a TAR archive into another (gzipped) TAR archive with the first directory level removed.
     */
    private fun removeOuterFolder(input: InputStream, output: OutputStream) {
        val outputStream = TarArchiveOutputStream(GzipCompressorOutputStream(BufferedOutputStream(output)))
        val archive = TarFile(input.readAllBytes())

        archive.entries.forEach { entry ->
            entry.name = entry.name.substringAfter('/')
            if (entry.name.isNotEmpty()) { // The root folder will be replaced by an empty string
                outputStream.putArchiveEntry(entry)
                IOUtils.copy(archive.getInputStream(entry), outputStream)
                outputStream.closeArchiveEntry()
            }
        }
        outputStream.close()
    }

    /**
     * The GitHub personal access token used to contact the GitHub API and download code.
     */
    private val githubToken by lazy {
        SavedProperties.getString("gh_token")
    }

    /**
     * Get the URL which can be used to download a TAR archive containing the
     * entire [repository].
     */
    private fun getBlobURL(repository: String, ref: String): String {
        // Request an archive of the latest version of the repository and download it to a zip file
        logger.info("Requesting archive link from GitHub...")
        val req = HttpRequest.newBuilder().GET()
            .uri(URI.create("https://api.github.com/repos/$GITHUB_USER/$repository/tarball/$ref"))
            .header("Authorization", "Token $githubToken").build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())

        val optional = res.headers().firstValue("location")
        if (optional.isEmpty) logger.warn("No URL found")
        return optional.get()
    }

    /**
     * Build a Docker image using a TAR archive supplied by [urlInputStream].
     * The image is tagged using the values of [repository] and [tag].
     * By default, the image will receive two tags:
     * - `bluedragonmc/{repository}:{version}`
     * - `bluedragonmc/{repository}:latest`
     *
     * It will also be labelled with:
     * `com.bluedragonmc.{repository}.version={tag}`
     */
    private fun buildDockerImage(repository: String, urlInputStream: InputStream, tag: String): String? {
        logger.info("Building a new Docker image with tag: $tag")

        val pb = ProcessBuilder("docker",
            "build",
            "-t", getTag(repository, tag),
            "-t", getTag(repository, "latest"),
            "--label", "${getVersionLabel(repository)}=$tag".lowercase(),
            "-")
        pb.environment()["DOCKER_BUILDKIT"] = "1"
        pb.environment()["BUILDKIT_PROGRESS"] = "plain"
        pb.redirectErrorStream(true)
        val process = pb.start()

        // Get the file at [blobURL] as a gzipped tar archive, remove the outer folder, and pipe the bytes to the Docker CLI.
        removeOuterFolder(urlInputStream, process.outputStream)

        BufferedReader(InputStreamReader(process.inputStream)).use { input ->
            var line: String?
            while (input.readLine().also { line = it } != null) {
                logger.info(line)
            }
        }

        val exitCode = process.waitFor()
        return if (exitCode == 0) {
            logger.info("Success! Docker image '$tag' has been built.")
            getTag(repository, tag)
        } else {
            logger.error("Unexpected exit code from Docker CLI. See logs above for details.")
            null
        }
    }

    /**
     * Forces a container's removal. The container will be killed if it is currently running, and then it will be removed.
     */
    fun removeContainer(container: Container) {
        InstanceManager.onContainerRemoved(container.names.first().substringAfter('/'))
        docker.removeContainerCmd(container.id).withForce(true).exec()
    }

    fun isLatestVersion(container: Container, repository: String): Boolean {
        return container.labels[getVersionLabel(repository)] == getMostRecentVersion(repository)
    }

}