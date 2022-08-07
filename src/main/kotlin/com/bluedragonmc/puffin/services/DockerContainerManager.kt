package com.bluedragonmc.puffin.services

import com.bluedragonmc.messages.ReportErrorMessage
import com.bluedragonmc.messages.RequestUpdateMessage
import com.bluedragonmc.puffin.app.Puffin
import com.bluedragonmc.puffin.config.ConfigService
import com.bluedragonmc.puffin.config.DockerContainerConfig
import com.bluedragonmc.puffin.config.DockerHubContainerConfig
import com.bluedragonmc.puffin.config.GitRepoContainerConfig
import com.bluedragonmc.puffin.util.Utils
import com.bluedragonmc.puffin.util.Utils.catchingTimer
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.model.Container
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.PruneType
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.io.IOUtils
import java.io.*
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.util.*

class DockerContainerManager(app: Puffin) : Service(app) {

    private lateinit var docker: DockerClient
    private val http = HttpClient.newHttpClient()

    private lateinit var puffinNetworkId: String

    private fun setMostRecentVersion(githubUser: String, repo: String, commit: String) {
        val configService = app.get(ConfigService::class)
        val dummyInfo = GitRepoContainerConfig(1, user = githubUser, repoName = repo)
        configService.config.versions.latestVersions[dummyInfo.name] = commit
        configService.save()
        logger.info("Latest version of $repo was updated to: $commit (Config file saved)")
    }

    override fun initialize() {

        val configService = app.get(ConfigService::class)

        // Connect to Docker via Unix Sockets to create and manage containers
        val dockerClientConfig =
            DefaultDockerClientConfig.Builder().withDockerHost(configService.config.dockerHostname).build()
        val httpClient = ApacheDockerHttpClient.Builder().dockerHost(dockerClientConfig.dockerHost).build()
        docker = DockerClientImpl.getInstance(dockerClientConfig, httpClient)
        logger.info("Connected to the Docker daemon.")

        for (containerInfo in configService.config.containers.filterIsInstance<GitRepoContainerConfig>()) {
            // Make sure the latest version exists for the configuration
            val imageId = containerInfo.getImageId(docker, app)
            val imageExists = try {
                docker.inspectImageCmd(imageId).exec()
                true
            } catch (e: Throwable) {
                false
            }
            if (configService.config.getLatestVersion(containerInfo.name).isNullOrBlank()) {
                logger.warn("No latest version information for ${containerInfo.name} was found. Gathering latest version...")
                fetchLatestVersion(containerInfo.user, containerInfo.repoName, containerInfo.branch)
            }
            if (!imageExists) {
                logger.warn("The latest version for ${containerInfo.name} was found, but its Docker image does not exist. Gathering latest version...")
                fetchLatestVersion(containerInfo.user, containerInfo.repoName, containerInfo.branch)
            }

            // Check for updates on the specified interval
            if (containerInfo.updateInterval > 0) catchingTimer("update-img-${containerInfo.repoName}",
                daemon = true,
                initialDelay = 0L,
                period = containerInfo.updateInterval) {
                val currentVersion = configService.config.getLatestVersion(containerInfo.name)
                val latestVersion = getLatestCommitSha(containerInfo.user, containerInfo.repoName, containerInfo.branch)
                if (currentVersion != latestVersion) {
                    logger.info("Automatically fetching the latest version of ${containerInfo.user}/${containerInfo.repoName}")
                    fetchLatestVersion(containerInfo.user, containerInfo.repoName, latestVersion)
                } else logger.debug("${containerInfo.user}/${containerInfo.repoName} is fully up to date.")
            }
        }

        catchingTimer("docker-container-upkeep", daemon = false, period = 8_000) {
            val config = configService.config

            // Prune old containers
            val containers = docker.listContainersCmd().withShowAll(true).exec()
            if (config.pruneTime != "-1") {
                val pruneResponse = docker.pruneCmd(PruneType.CONTAINERS).withUntilFilter(config.pruneTime)
                    .withLabelFilter(CONTAINER_ID_LABEL).exec()

                @Suppress("UNCHECKED_CAST") val deletedContainers =
                    pruneResponse.rawValues["ContainersDeleted"] as ArrayList<String>?
                if (deletedContainers != null) {
                    logger.info("Deleted containers: $deletedContainers")
                    if (deletedContainers.isNotEmpty()) logger.info("Pruned ${deletedContainers.size} stopped containers.")
                    for (dockerContainerId in deletedContainers) {
                        // Convert Docker's container ID into our own container UUID.
                        val containerId =
                            containers.find { it.id == dockerContainerId }?.labels?.get(CONTAINER_ID_LABEL)
                        logger.info("> Container ${containerId ?: dockerContainerId} was pruned.")
                        if (app.has(InstanceManager::class) && containerId != null)
                            app.get(InstanceManager::class).onContainerRemoved(containerId)
                    }

                    containers.removeAll { it.id in deletedContainers }
                }
            }

            // There is no need to process non-running containers beyond this point
            containers.removeAll { it.state != "running" }

            // Make sure the Puffin network exists and all containers are connected to it
            val puffinNetworks = docker.listNetworksCmd().withNameFilter(config.puffinNetworkName).exec()
            val puffinNetworkExists = puffinNetworks.isNotEmpty()
            if (!puffinNetworkExists) {
                // Create a new network
                logger.warn("Puffin network does not exist, creating...")
                val response = docker.createNetworkCmd().withName(config.puffinNetworkName).withDriver("bridge").exec()
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

            // Make sure there are enough of each container type to satisfy the minimum amounts specified in the config file

            val groups = config.containers.sortedByDescending { it.priority }.groupBy { it.priority }
            for ((priority, containerList) in groups) { // Start containers of the highest priority first
                var containerStarted = false
                for (containerInfo in containerList) {
                    val runningContainers = containerInfo.countRunningContainers(docker)
                    val needed = containerInfo.minimum - runningContainers
                    if (needed <= 0) continue
                    repeat(needed) { i ->
                        logger.info("There are only $runningContainers containers of type ${containerInfo.name} (priority: $priority), but ${containerInfo.minimum} are required. Starting another (${i + 1}/$needed).")
                        startContainer(containerInfo, UUID.randomUUID())
                    }
                    containerStarted = true
                }
                // If a container was started at this priority level,
                // wait until the next interval to start containers at lower priorities.
                // This is because some containers depend on others to be online.
                if (containerStarted) break
            }
        }

        app.get(MessagingService::class).onConnected { client ->
            client.subscribe(ReportErrorMessage::class) { message ->
                // TODO: 7/22/22
            }

            client.subscribe(RequestUpdateMessage::class) { message ->
                // Updates requested via messages can't request and repo outside the BlueDragonMC org
                val branch = message.ref
                val repo = message.product
                logger.info("Update requested for BlueDragonMC/$repo:$branch")
                val sha = getLatestCommitSha("BlueDragonMC", repo, branch)
                logger.info("Found latest commit SHA for branch $branch: $sha")
                Utils.sendChat(message.executor,
                    "<aqua>Building new container from commit $sha on repository $repo:$branch")
                val result = fetchLatestVersion("BlueDragonMC", repo, branch)
                val version = configService.config.getLatestVersion(
                    GitRepoContainerConfig(
                        minimum = 1,
                        user = "BlueDragonMC",
                        repoName = repo
                    ).name
                )
                if (result) {
                    Utils.sendChat(message.executor,
                        "<green>Image built successfully!\n<dark_gray>repo=$repo, branch=$branch, version=$version")
                } else {
                    Utils.sendChat(message.executor,
                        "<red>Image build <b>failed</b>!\n<dark_gray>repo=$repo, branch=$branch, current latest version=$version")
                }
            }
        }
    }

    override fun close() {
        docker.close()
    }

    /**
     * Get a [Container] from its [containerId]. This method can return null,
     * which most likely means the container does not exist or is not running.
     */
    fun getRunningContainer(containerId: UUID): Container? {
        val containers = docker.listContainersCmd()
            .withLabelFilter(mapOf(CONTAINER_ID_LABEL to containerId.toString())).exec()
        return containers.firstOrNull()
    }

    /**
     * Start a container using the meta provided,
     * giving the container an environment variable called `PUFFIN_CONTAINER_ID`
     * with the string contents of [containerId].
     * The container is immediately connected to the Puffin network
     * and is named after the [containerId].
     * The container is started immediately after it is created.
     */
    private fun startContainer(containerMeta: DockerContainerConfig, containerId: UUID) {
        logger.info("Starting new container with containerId=$containerId")
        val config = app.get(ConfigService::class).config
        val secrets = app.get(ConfigService::class).secrets
        var image = containerMeta.getTag(config.getLatestVersion(containerMeta.name))
        var containerName = containerMeta.getContainerName(containerId)

        // Make sure there are no existing containers with this name
        if (docker.listContainersCmd().withNameFilter(listOf(containerName)).exec().isNotEmpty()) {
            // If there is an existing container, append a truncated container ID to the name.
            containerName += "-" + containerId.toString().substringBefore('-')
        }

        logger.info("Creating new container with container meta $containerMeta and image $image...")
        if (containerMeta is DockerHubContainerConfig) { // Third-party containers must be pulled from a public registry
            // If the required image is not present, pull it from the registry.
            if (containerMeta.getImageId(docker, app) == null) {
                logger.info("Pulling third-party Docker image: $image")
                docker.pullImageCmd(containerMeta.getTag(config.getLatestVersion(containerMeta.name)))
                    .withTag(containerMeta.tag).start().awaitCompletion()
                logger.info("Docker pull completed.")
            }
            // Get the ID of the newly-created or already-existing image
            image = containerMeta.getImageId(docker, app)!!
            logger.info("Using third-party image with ID: $image")
        }
        val extraEnvironmentVars = containerMeta.env.map { it.key + "=" + it.value }.toTypedArray()
        val response = docker.createContainerCmd(image).apply {
            withName(containerName) // container name
            withEnv("PUFFIN_CONTAINER_ID=$containerId", // pass containerId to the program in the container
                "PUFFIN_VELOCITY_SECRET=${secrets.velocitySecret}", // pass the Velocity modern forwarding secret to the container
                *extraEnvironmentVars).withExposedPorts(containerMeta.exposedPorts)
            withHostName(containerMeta.getHostName(containerId.toString()))
            withLabels(containerMeta.containerLabels + (CONTAINER_ID_LABEL to containerId.toString()))
            withHostConfig(HostConfig.newHostConfig()
                .withNetworkMode(puffinNetworkId) // put this container on the same network, so it can access other services
                .withPortBindings(containerMeta.portBindings)
                .withMounts(containerMeta.mounts))
            if (containerMeta.containerUser != null) {
                withUser(containerMeta.containerUser)
            }
        }.exec()

        response.warnings?.forEach {
            logger.warn("Warning while creating container $containerId: $it")
        }
        logger.info("Container creation succeeded: container created with ID ${response.id}")
        docker.startContainerCmd(response.id).exec()
        logger.info("Container started successfully.")
    }

    /**
     * Fetches the [branch] from the GitHub [repository], builds a Docker image from its latest commit,
     * and assigns this new image as the server's latest version. This means that
     * all new containers will use this version.
     * @return true if the update was successful, false otherwise
     */
    private fun fetchLatestVersion(githubUser: String, repository: String, branch: String): Boolean {
        val (tag, commit) = buildDockerImageFromRef(githubUser, repository, branch)
        if (tag != null && commit != null) {
            setMostRecentVersion(githubUser, repository, commit)
            logger.info("Update completed.")
        }
        return tag != null && commit != null
    }

    /**
     * Fetches the [branch] from the GitHub [repository] and builds a Docker image from it.
     * This method does not set the server's latest version.
     * @return The tag assigned to the new Docker image paired with the latest commit sha of [branch]
     */
    private fun buildDockerImageFromRef(
        githubUser: String,
        repository: String,
        branch: String,
    ): Pair<String?, String?> {
        logger.info("Updating $repository to the latest version...")
        val githubURL = getBlobURL(githubUser, repository, branch)
        val commit = getLatestCommitSha(githubUser, repository, branch)
        logger.info("Found blob URL from GitHub: $githubURL")
        return buildDockerImage(githubUser,
            repository,
            GzipCompressorInputStream(URL(githubURL).openStream()),
            commit) to commit
    }

    private val commitShaRegex = "[\\da-f]{40}".toRegex()

    /**
     * Get the latest commit SHA-1 from a GitHub [repository] on the specified [branch].
     * Returns the full, un-truncated string. [branch] may be a commit SHA, in which case
     * it is returned without contacting the GitHub API.
     */
    private fun getLatestCommitSha(githubUser: String, repository: String, branch: String): String {
        if (branch.matches(commitShaRegex)) return branch

        val secrets = app.get(ConfigService::class).secrets
        val request = HttpRequest.newBuilder().GET()
            .uri(URI.create("https://api.github.com/repos/$githubUser/$repository/commits/$branch"))
            .header("Accept", "application/vnd.github.VERSION.sha")
            .header("Authorization", "Token ${secrets.githubToken}").build()

        val response = http.send(request, BodyHandlers.ofString())
        if (response.statusCode() == 200) {
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
     * Get the URL which can be used to download a TAR archive containing the
     * entire [repository].
     */
    private fun getBlobURL(githubUser: String, repository: String, ref: String): String {
        // Request an archive of the latest version of the repository and download it to a zip file
        val secrets = app.get(ConfigService::class).secrets
        logger.info("Requesting archive link from GitHub...")
        val req = HttpRequest.newBuilder().GET()
            .uri(URI.create("https://api.github.com/repos/$githubUser/$repository/tarball/$ref"))
            .header("Authorization", "Token ${secrets.githubToken}").build()
        val res = http.send(req, BodyHandlers.ofString())

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
    private fun buildDockerImage(
        githubUser: String,
        repository: String,
        urlInputStream: InputStream,
        tag: String,
    ): String? {
        logger.info("Building a new Docker image with tag: $tag")

        val dummyInfo = GitRepoContainerConfig(1, user = githubUser, repoName = repository)

        val pb = ProcessBuilder("docker",
            "build",
            "-t",
            dummyInfo.getTag(tag),
            "-t",
            dummyInfo.getTag("latest"),
            "--label",
            "${dummyInfo.getVersionLabel()}=$tag".lowercase(),
            "-")
        logger.info("Running command: " + pb.command().joinToString(separator = " "))
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
            dummyInfo.getTag(tag)
        } else {
            logger.error("Unexpected exit code from Docker CLI. See logs above for details.")
            null
        }
    }

    /**
     * Forces a container's removal. The container will be killed if it is currently running, and then it will be removed.
     */
    fun removeContainer(container: Container) {
        val containerId = container.labels[CONTAINER_ID_LABEL] ?: kotlin.run {
            logger.warn("No container ID found for container to remove: $container")
            return
        }
        app.get(InstanceManager::class).onContainerRemoved(containerId)
        docker.removeContainerCmd(container.id).withForce(true).exec()
    }

    fun isLatestVersion(container: Container, githubUser: String, repository: String): Boolean? {
        val config = app.get(ConfigService::class).config
        val dummyInfo = GitRepoContainerConfig(1, user = githubUser, repoName = repository)
        if (!container.labels.containsKey(dummyInfo.getVersionLabel())) return null // The container is not running any version of [repository]
        return container.labels[dummyInfo.getVersionLabel()] == config.getLatestVersion(dummyInfo.name)
    }

    companion object {
        /**
         * A label assigned to all Puffin containers whose value represents the container's unique ID.
         * This value is in a dashed UUID format.
         */
        const val CONTAINER_ID_LABEL = "com.bluedragonmc.puffin.container_id"
    }

}
