package com.bluedragonmc.puffin.config

@kotlinx.serialization.Serializable
data class PuffinConfig(
    val worldsFolder: String,
    val mongoHostname: String = "mongo",
    val mongoPort: Int = 27017
)