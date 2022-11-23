package com.bluedragonmc.puffin.config

data class PuffinConfig(
    val worldsFolder: String,
    val mongoHostname: String = "mongo",
    val mongoPort: Int = 27017
)