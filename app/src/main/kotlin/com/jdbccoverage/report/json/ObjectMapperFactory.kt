package com.jdbccoverage.report.json

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jdbccoverage.model.ImplementationStatus

/**
 * Creates a shared Jackson ObjectMapper configured for JDBC checker models.
 *
 * - Serializes [ImplementationStatus] as its string key (e.g. "NOT_FOUND")
 * - Deserializes [ImplementationStatus] back from the same key
 * - Uses ISO-8601 timestamps (not Unix epoch)
 * - Ignores unknown JSON properties to support forward compatibility
 */
fun createObjectMapper(): ObjectMapper = baseConfiguration(ObjectMapper())

/**
 * Creates a Jackson ObjectMapper for YAML I/O, sharing the same configuration
 * profile as [createObjectMapper] (Kotlin module, lenient unknown-property
 * handling). Used for loading driver profiles from bundled YAML resources
 * and user-supplied YAML files.
 */
fun createYamlObjectMapper(): ObjectMapper = baseConfiguration(ObjectMapper(YAMLFactory()))

/**
 * Apply the shared module/feature configuration to an ObjectMapper.
 *
 * Suppressed: in Jackson 2.18, per-instance `configure(MapperFeature, Boolean)` is
 * deprecated in favor of the `JsonMapper.builder()` API, but it remains fully
 * functional and is the smallest change here — switching to the builder API would
 * require reshaping both [createObjectMapper] and [createYamlObjectMapper].
 */
@Suppress("DEPRECATION")
private fun baseConfiguration(mapper: ObjectMapper): ObjectMapper {
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

    return mapper
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .registerModule(statusModule)
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        // Computed getters (e.g. AnalysisReport.versionBreakdown) have no matching field;
        // without this, Jackson reflectively "sets" them via the getter on deserialization,
        // which JDK 26 flags as an illegal reflective mutation of a final field.
        .configure(MapperFeature.USE_GETTERS_AS_SETTERS, false)
}
