package com.bluedragonmc.puffin.config.serializer

import com.github.dockerjava.api.model.PortBinding
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object PortBindingSerializer : KSerializer<PortBinding> {
    override val descriptor = PrimitiveSerialDescriptor("PortBinding", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PortBinding = PortBinding.parse(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: PortBinding) =
        encoder.encodeString(value.binding.hostIp + ":" + value.binding.hostPortSpec + ":" + value.exposedPort.port + "/" + value.exposedPort.protocol)
}