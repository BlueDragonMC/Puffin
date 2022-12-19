package com.bluedragonmc.puffin.config

data class PuffinConfig(
    val worldsFolder: String,
    val mongoHostname: String,
    val mongoPort: Int,
    val luckPermsApiUrl: String
)