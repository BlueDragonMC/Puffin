package com.bluedragonmc.puffin.config.serializer

import com.github.dockerjava.api.model.Mount
import com.github.dockerjava.api.model.MountType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

object MountSerializer : KSerializer<Mount> {

    private val surrogate = JsonObject.serializer()

    override val descriptor: SerialDescriptor = surrogate.descriptor

    override fun deserialize(decoder: Decoder): Mount {
        val map = surrogate.deserialize(decoder)
        val mount = Mount()

        map.useString("type") { mount.withType(MountType.valueOf(it)) }
        map.useString("target") { mount.withTarget(it) }
        map.useString("source") { mount.withSource(it) }
        map.useString("readOnly") { mount.withReadOnly(it.toBooleanStrict()) }

        return mount
    }

    override fun serialize(encoder: Encoder, value: Mount) {
        val map = mutableMapOf<String, JsonElement>()

        value.type?.toString()?.useToSet("type", map)
        value.target?.useToSet("target", map)
        value.source?.useToSet("source", map)
        value.readOnly?.toString()?.useToSet("readOnly", map)

        surrogate.serialize(encoder, JsonObject(map))
    }

    private fun Map<String, JsonElement>.useString(key: String, block: (String) -> Unit) {
        get(key)?.jsonPrimitive?.content?.let(block)
    }

    private fun String?.useToSet(key: String, map: MutableMap<String, JsonElement>) {
        if (this != null) map[key] = JsonPrimitive(this)
    }
}