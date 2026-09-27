package com.nuvio.app.core.portable

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Central filesystem layout for portable desktop builds.
 * Persistent paths are derived from the application location rather than a
 * Windows drive letter so a USB installation can move between hosts safely.
 *
 * This class only defines and prepares the layout. Existing Nuvio storage is
 * deliberately not redirected here yet; consumers will be migrated and tested
 * incrementally.
 */
object PortablePaths {
    private const val PORTABLE_ROOT_OVERRIDE = "nuvio.portable.root"
    private const val PORTABLE_ROOT_ENV = "NUVIO_PORTABLE_ROOT"

    val root: Path by lazy { resolveRoot() }
    val app: Path by lazy { root.resolve("App") }
    val data: Path by lazy { root.resolve("Data") }
    val profile: Path by lazy { data.resolve("Profile") }
    val config: Path by lazy { data.resolve("Config") }
    val cache: Path by lazy { data.resolve("Cache") }
    val logs: Path by lazy { data.resolve("Logs") }
    val downloads: Path by lazy { root.resolve("Downloads") }

    /** Creates the portable directory layout without deleting or migrating data. */
    fun ensureLayout() {
        listOf(data, profile, config, cache, logs, downloads).forEach { path ->
            path.toFile().mkdirs()
        }
    }

    private fun resolveRoot(): Path {
        System.getProperty(PORTABLE_ROOT_OVERRIDE)
            ?.takeIf { it.isNotBlank() }
            ?.let { return normalize(it) }

        System.getenv(PORTABLE_ROOT_ENV)
            ?.takeIf { it.isNotBlank() }
            ?.let { return normalize(it) }

        val codeLocation = runCatching {
            PortablePaths::class.java.protectionDomain.codeSource.location.toURI()
        }.getOrNull()

        if (codeLocation != null && codeLocation.scheme.equals("file", ignoreCase = true)) {
            val location = Paths.get(codeLocation).toAbsolutePath().normalize()
            val base = if (location.toFile().isFile) location.parent else location
            if (base != null) {
                // Packaged layout: <root>/App/<runtime>. During development we
                // safely fall back to the current runtime/build location.
                if (base.fileName?.toString().equals("App", ignoreCase = true)) {
                    return base.parent ?: base
                }
                base.parent
                    ?.takeIf { base.fileName?.toString().equals("runtime", ignoreCase = true) }
                    ?.let { appDir ->
                        if (appDir.fileName?.toString().equals("App", ignoreCase = true)) {
                            return appDir.parent ?: appDir
                        }
                    }
                return base
            }
        }

        return File(System.getProperty("user.dir", ".")).toPath().toAbsolutePath().normalize()
    }

    private fun normalize(value: String): Path =
        Paths.get(value).toAbsolutePath().normalize()
}
