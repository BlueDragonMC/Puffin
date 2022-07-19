package com.bluedragonmc.puffin

import java.io.File
import java.util.*

object SavedProperties {

    private val propertiesFile = File("puffin.properties")

    private val properties by lazy {
        val props = Properties()
        props.load(propertiesFile.inputStream())
        props
    }

    fun getString(key: String): String? = properties.getProperty(key)

    fun setString(key: String, value: String) {
        properties.setProperty(key, value)
    }

    fun save() {
        properties.store(propertiesFile.outputStream(), "Automatically saved by Puffin.")
    }
}