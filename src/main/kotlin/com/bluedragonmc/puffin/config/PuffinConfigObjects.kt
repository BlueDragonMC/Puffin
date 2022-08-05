package com.bluedragonmc.puffin.config

import com.bluedragonmc.puffin.app.Puffin
import com.bluedragonmc.puffin.config.serializer.ExposedPortSerializer
import com.bluedragonmc.puffin.config.serializer.MountSerializer
import com.bluedragonmc.puffin.config.serializer.PortBindingSerializer
import com.bluedragonmc.puffin.services.ServiceHolder
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.Mount
import com.github.dockerjava.api.model.PortBinding
import kotlinx.coroutines.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.apache.commons.lang3.RandomStringUtils
import org.slf4j.LoggerFactory
import java.util.*

@Serializable
data class SecretsConfig @OptIn(ExperimentalSerializationApi::class) constructor(
    val githubToken: String = error("No GitHub token was specified. GitHub-related Docker features will not work."),
    @EncodeDefault val velocitySecret: String = RandomStringUtils.randomAlphanumeric(128).also {
        LoggerFactory.getLogger("PuffinConfigObjects")
            .warn("The Velocity forwarding secret was not specified in the secrets file. A random one was created.")
        CoroutineScope(Dispatchers.IO).launch {
            delay(10_000)
            Puffin.INSTANCE.get(ConfigService::class).save()
        }
    },
)

@Serializable
data class PuffinConfig @OptIn(ExperimentalSerializationApi::class) constructor(
    val worldsFolder: String,
    @EncodeDefault val versions: VersionConfig = VersionConfig(),
    @EncodeDefault val containers: List<DockerContainerConfig> = emptyList(),
    @EncodeDefault val puffinNetworkName: String = "puffin_network",
    @EncodeDefault val mongoHostname: String = "127.0.0.1",
    @EncodeDefault val mongoPort: Int = 27017,
    @EncodeDefault val amqpHostname: String = "127.0.0.1",
    @EncodeDefault val amqpPort: Int = 5672,
) {
    fun getLatestVersion(id: String) = versions.latestVersions[id]
}

@Serializable
data class VersionConfig(
    val latestVersions: MutableMap<String, String> = mutableMapOf(),
)

@Serializable
sealed class DockerContainerConfig {
    abstract val minimum: Int
    abstract val name: String
    abstract val priority: Int
    abstract val containerUser: String?
    abstract val containerLabels: Map<String, String>

    abstract val networks: List<String>
    abstract val exposedPorts: List<ExposedPort>
    abstract val portBindings: List<PortBinding>
    abstract val mounts: List<Mount>
    abstract val env: Map<String, String>

    abstract fun getTag(version: String?): String
    abstract fun countRunningContainers(docker: DockerClient): Int
    abstract fun getVersionLabel(): String
    abstract fun getHostName(containerId: String): String
    abstract fun getImageId(docker: DockerClient, app: ServiceHolder?): String?
    abstract fun getContainerName(containerId: UUID): String
}

@Serializable
@SerialName("docker_hub")
data class DockerHubContainerConfig(
    override val minimum: Int,
    override val priority: Int = 0,
    override val networks: List<String> = emptyList(),
    override val exposedPorts: List<@Serializable(with = ExposedPortSerializer::class) ExposedPort> = emptyList(),
    override val portBindings: List<@Serializable(with = PortBindingSerializer::class) PortBinding> = emptyList(),
    override val mounts: List<@Serializable(with = MountSerializer::class) Mount> = emptyList(),
    override val env: Map<String, String> = emptyMap(),
    override val containerUser: String? = null,
    @SerialName("labels") override val containerLabels: Map<String, String> = emptyMap(),
    val user: String,
    val image: String,
    val tag: String,
) : DockerContainerConfig() {

    override val name = "$user/$image"

    override fun getTag(version: String?): String = "$user/$image:$tag".lowercase()

    override fun countRunningContainers(docker: DockerClient) =
        docker.listContainersCmd().withAncestorFilter(listOf(getImageId(docker, null))).exec().size

    override fun getVersionLabel() = "$user.$image.version".lowercase()

    override fun getHostName(containerId: String) = if (minimum == 1) image else containerId

    override fun getImageId(docker: DockerClient, app: ServiceHolder?): String? {
        return docker.listImagesCmd().exec().find {
            it.repoTags?.contains("$image:$tag") == true || it.repoTags?.contains("$user/$image:$tag") == true
        }?.id
    }

    override fun getContainerName(containerId: UUID) = image

}

@Serializable
@SerialName("github")
data class GitRepoContainerConfig(
    override val minimum: Int,
    override val priority: Int = 0,
    override val networks: List<String> = emptyList(),
    override val exposedPorts: List<@Serializable(with = ExposedPortSerializer::class) ExposedPort> = emptyList(),
    override val portBindings: List<@Serializable(with = PortBindingSerializer::class) PortBinding> = emptyList(),
    override val mounts: List<@Serializable(with = MountSerializer::class) Mount> = emptyList(),
    override val env: Map<String, String> = emptyMap(),
    override val containerUser: String? = null,
    @SerialName("labels") override val containerLabels: Map<String, String> = emptyMap(),
    val user: String,
    val repoName: String,
    val branch: String = "main",
) : DockerContainerConfig() {

    override val name = "$user/$repoName".lowercase()

    override fun getTag(version: String?) = "$user/$repoName:${version ?: "latest"}".lowercase()

    override fun countRunningContainers(docker: DockerClient) = docker.listContainersCmd().exec().count {
        it.labels.containsKey(getVersionLabel()) || it.labels.containsKey("com.bluedragonmc.$repoName.version".lowercase())
    }

    override fun getVersionLabel() = "${user.lowercase()}.${
        repoName.lowercase().replace('/', '_').replace('.', '_').replace('@', '/')
    }.version"

    override fun getHostName(containerId: String) = containerId

    override fun getImageId(docker: DockerClient, app: ServiceHolder?): String =
        getTag(app!!.get(ConfigService::class).config.getLatestVersion(repoName))

    override fun getContainerName(containerId: UUID) = repoName + "-" + containerId.toString().substringBefore("-")

}
