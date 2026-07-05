package com.jdbcchecker.profile

import com.fasterxml.jackson.databind.ObjectMapper
import com.jdbcchecker.report.json.createYamlObjectMapper
import java.io.InputStream

/**
 * Loads [DriverProfile] instances from bundled YAML resources.
 *
 * Bundled profiles live under the `/profiles/` resource directory, one
 * `<name>.yaml` file per profile. The list of bundled names is read from
 * `_index.yaml` in the same directory, which contains:
 *
 *   profiles:
 *     - mssql
 *     - mysql
 *     - pgjdbc
 *     - mariadb
 *     - cubrid
 *
 * Using an index file (rather than ClassLoader resource scanning) avoids
 * fragility on shaded JARs and varied class loaders. New bundled profiles
 * must be added to both the directory and the index file.
 */
class DriverProfileLoader(
    private val mapper: ObjectMapper = createYamlObjectMapper(),
) {
    /**
     * Load a bundled profile by name. Returns null if no such bundled profile
     * exists (caller decides whether that's an error or a soft-fail).
     */
    fun loadBundled(name: String): DriverProfile? {
        val stream = javaClass.getResourceAsStream("$BUNDLED_DIR/$name.yaml")
            ?: return null
        return stream.use { parse(it, source = "bundled:$name") }
    }

    /**
     * Enumerate all bundled profiles. Used by [ProfileResolver] for
     * auto-detection and by the CLI for listing available profile names.
     *
     * Reads `_index.yaml` from bundled resources and loads each listed
     * profile. Missing profiles are silently skipped so a partial index
     * doesn't crash analysis.
     */
    fun listBundled(): List<DriverProfile> {
        val indexStream = javaClass.getResourceAsStream("$BUNDLED_DIR/_index.yaml")
            ?: return emptyList()
        val index: BundledIndex = indexStream.use { mapper.readValue(it, BundledIndex::class.java) }
        return index.profiles.mapNotNull { loadBundled(it) }
    }

    /**
     * Parse a [DriverProfile] from a YAML input stream. Used internally; the
     * `source` parameter is included in error messages to aid debugging.
     */
    private fun parse(input: InputStream, source: String): DriverProfile {
        try {
            val profile = mapper.readValue(input, DriverProfile::class.java)
            require(profile.name.isNotBlank()) {
                "Profile '$source' has no 'name' field"
            }
            return profile
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse driver profile from $source: ${e.message}", e)
        }
    }

    /** Schema for `_index.yaml`. */
    private data class BundledIndex(val profiles: List<String> = emptyList())

    companion object {
        private const val BUNDLED_DIR = "/profiles"
    }
}
