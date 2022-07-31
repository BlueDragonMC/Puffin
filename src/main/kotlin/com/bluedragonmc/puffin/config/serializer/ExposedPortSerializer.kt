package com.bluedragonmc.puffin.config.serializer

import com.github.dockerjava.api.model.ExposedPort
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object ExposedPortSerializer : KSerializer<ExposedPort> {
    override val descriptor = PrimitiveSerialDescriptor("ExposedPort", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ExposedPort = ExposedPort.parse(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: ExposedPort) = encoder.encodeString(value.toString())
}