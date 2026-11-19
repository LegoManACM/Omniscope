package dev.omniscope.core

import dev.omniscope.plugins.DeclarativePlugin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Checks every manifest in the repo's plugins/ folder (the same ones the app bundles and the catalogue serves). */
class ManifestTest {
    private val pluginsDir = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "plugins") }.first { File(it, "official").isDirectory }

    private fun manifests(): List<Pair<File, Manifest>> = pluginsDir.walkTopDown()
        .filter { it.name == "plugin.json" }.map { it to Manifest.parse(it.readText()) }.toList()

    @Test fun everyManifestIsValid() {
        val all = manifests()
        assertTrue("found manifests", all.size >= 20)
        val problems = all.flatMap { (f, m) -> m.problems().map { "${f.parentFile.name}: $it" } }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
        all.forEach { (f, m) -> assertTrue("${f.path}: folder must match id", f.parentFile.name == m.id) }
        val ids = all.map { it.second.id }
        assertTrue("ids are unique", ids.size == ids.toSet().size)
    }

    /**
     * Runs every declarative official plugin against the real APIs near Spokane, WA. Off by default (network);
     * run with `OMNISCOPE_LIVE=1 ./gradlew testDebugUnitTest --tests '*ManifestTest*'`.
     */
    @Test fun liveFetch() {
        assumeTrue(System.getenv("OMNISCOPE_LIVE") == "1")
        val http = Http(Files.createTempDirectory("omniscope-test").toFile())
        val report = StringBuilder()
        var failures = 0
        // OMNISCOPE_PLUGIN=id1,id2 limits the run to those plugins.
        val only = System.getenv("OMNISCOPE_PLUGIN")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        for ((_, m) in manifests().filter { it.second.native == null && it.second.key == null && (only == null || it.second.id in only) }) {
            val p = DeclarativePlugin(m)
            // Spokane, WA by default; OMNISCOPE_LAT/OMNISCOPE_LON test elsewhere (e.g. London for UK-only APIs).
            val lat = System.getenv("OMNISCOPE_LAT")?.toDoubleOrNull() ?: 47.6588
            val lon = System.getenv("OMNISCOPE_LON")?.toDoubleOrNull() ?: -117.4260
            val ctx = Ctx(lat, lon, 600.0, System.currentTimeMillis(), radiusM = m.radius?.defaultM ?: 0.0)
            try {
                val items = runBlocking { p.fetch(ctx, PluginEnv(http, null)) }
                val first = items.firstOrNull()
                report.append("OK   ${m.id}: ${items.size} items. e.g. ${first?.title} | ${first?.subtitle} | ${first?.lat},${first?.lon} " +
                    "| alt=${first?.altM} | img=${first?.image != null} | fields=${first?.fields?.size} | links=${first?.links?.map { it.first }}\n")
                // Empty is often legitimate (no alerts today, no coverage here, a UK-only API), so it's a warning.
                if (items.isEmpty()) report.append("     ^ no items here (check coverage)\n")
            } catch (e: Exception) {
                // The public Overpass servers are often overloaded; that's their outage, not a manifest bug.
                val overpassFlake = m.request?.has("overpass") == true && (e.message ?: "").contains(Regex("timed out|50[234]|429"))
                if (!overpassFlake) failures++
                report.append("${if (overpassFlake) "SKIP" else "FAIL"} ${m.id}: ${e.message?.take(200)}\n")
            }
        }
        println(report)
        assertTrue(report.toString(), failures == 0)
    }
}
