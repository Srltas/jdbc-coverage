package com.jdbcchecker.report.json

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jdbcchecker.model.ImplementationStatus

/**
 * Creates a shared Jackson ObjectMapper configured for JDBC checker models.
 *
 * - Serializes [ImplementationStatus] as its string key (e.g. "NOT_FOUND")
 * - Deserializes [ImplementationStatus] back from the same key
 * - Uses ISO-8601 timestamps (not Unix epoch)
 * - Ignores unknown JSON properties to support forward compatibility
 */
fun createObjectMapper(): ObjectMapper {
    val statusModule = SimpleModule("ImplementationStatusModule").apply {
        addSerializer(
            ImplementationStatus::class.java,
            object : StdSerializer<ImplementationStatus>(ImplementationStatus::class.java) {
                override fun serialize(
                    value: ImplementationStatus,
                    gen: JsonGenerator,
                    provider: SerializerProvider,
                ) {
                    gen.writeString(value.key)
                }
            },
        )
        addDeserializer(
            ImplementationStatus::class.java,
            object : StdDeserializer<ImplementationStatus>(ImplementationStatus::class.java) {
                override fun deserialize(
                    p: JsonParser,
                    ctxt: DeserializationContext,
                ): ImplementationStatus = ImplementationStatus.fromKey(p.valueAsString)
            },
        )
    }

    return ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .registerModule(statusModule)
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}
