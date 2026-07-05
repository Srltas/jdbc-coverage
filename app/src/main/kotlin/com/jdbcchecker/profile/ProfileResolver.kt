package com.jdbcchecker.profile

import com.github.javaparser.ast.CompilationUnit

/**
 * Selects the [DriverProfile] (if any) to apply to an analysis, given CLI
 * flags and the set of parsed compilation units.
 *
 * Precedence (highest first):
 *   1. `profileName` non-null              → load bundled profile or fail
 *   2. auto-detect                         → match bundled profiles' package
 *                                            prefixes against the parsed CU
 *                                            packages; pick the longest-prefix
 *                                            match. Ties go to alphabetical
 *                                            order for determinism. No match
 *                                            → null.
 */
class ProfileResolver(
    private val loader: DriverProfileLoader = DriverProfileLoader(),
) {
    /**
     * Resolve which profile to use. Returns null when no bundled profile
     * matches the source.
     *
     * Precedence: explicit `profileName` > auto-detect by package prefix.
     *
     * @throws IllegalArgumentException when `profileName` is given but no
     *     bundled profile with that name exists.
     */
    fun resolve(
        compilationUnits: List<CompilationUnit>,
        profileName: String? = null,
    ): DriverProfile? {
        if (profileName != null) {
            return loader.loadBundled(profileName)
                ?: throw IllegalArgumentException(
                    "Unknown driver profile: '$profileName'. " +
                        "Available bundled profiles: ${loader.listBundled().joinToString { it.name }}",
                )
        }
        return autoDetect(compilationUnits)
    }

    /**
     * Auto-detect a bundled profile by matching `detectBy.packagePrefix`
     * entries against the parsed compilation units' package declarations.
     *
     * Scoring: each (profile, prefix) pair that matches at least one CU
     * scores by the prefix's length. The highest-scoring profile wins;
     * ties are broken by profile name for determinism.
     *
     * Returns null when no bundled profile's prefix matches any CU.
     */
    internal fun autoDetect(compilationUnits: List<CompilationUnit>): DriverProfile? {
        if (compilationUnits.isEmpty()) return null
        val packages = compilationUnits
            .mapNotNull { cu ->
                cu.packageDeclaration.map { it.nameAsString }.orElse(null)
            }
            .toSet()
        if (packages.isEmpty()) return null

        val bundled = loader.listBundled()
        val matched = bundled.mapNotNull { profile ->
            val bestPrefix = profile.detectBy.packagePrefix
                .filter { prefix -> packages.any { it == prefix || it.startsWith("$prefix.") } }
                .maxByOrNull { it.length }
            if (bestPrefix != null) profile to bestPrefix.length else null
        }
        if (matched.isEmpty()) return null

        return matched
            .sortedWith(compareByDescending<Pair<DriverProfile, Int>> { it.second }.thenBy { it.first.name })
            .first()
            .first
    }
}
