package com.nuvio.app.features.sports

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonExtraProperty
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.catalog.buildCatalogUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SportsAddonCatalogsTest {
    @Test
    fun findsEnabledSportsCatalogsWithoutAnEspnEvent() {
        val nebula = addon("org.nebulastreams.sports", "Nebula Sports", AddonCatalog("sports", "events", "Events"))
        val pencari = addon("example.pencari", "Pencari Sports", AddonCatalog("tv", "live", "Live"))
        val tennis = addon("com.sleeyax.dlive-addon", "Tennis Channel", AddonCatalog("tv", "dlive_catalog", "Tennis"))

        listOf(nebula, pencari, tennis).forEach { manifest ->
            assertEquals(manifest.catalogs, SportsAddonCatalogs.browseCatalogs(manifest))
            val disabled = ManagedAddon(manifest.transportUrl, manifest, enabled = false)
            assertTrue(SportsAddonDiscovery.catalogs(listOf(disabled)).isEmpty())
        }
        assertEquals("sports", SportsAddonCatalogs.routes(nebula, SportsLeague.NBA).single().type)
    }

    @Test
    fun usesConfiguredSportsGenreAndOmitsMovieCatalogs() {
        val live = AddonCatalog(
            "tv", "live-tv", "Live TV",
            listOf(AddonExtraProperty("genre", isRequired = true, options = listOf("All", "Sports Channels"))),
        )
        val addon = addon("com.penguplay", "PenguPlay", live, AddonCatalog("movie", "movies", "Movies"))

        assertEquals(listOf(live), SportsAddonCatalogs.browseCatalogs(addon))
        assertEquals("Sports Channels", SportsAddonCatalogs.genre(live))
    }

    @Test
    fun sendsSkipZeroOnlyWhenRequested() {
        val manifest = "https://example.com/manifest.json"
        assertEquals(
            "https://example.com/catalog/sports/live/skip=0.json",
            buildCatalogUrl(manifest, "sports", "live", null, null, 0, includeInitialSkip = true),
        )
        assertEquals(
            "https://example.com/catalog/sports/live.json",
            buildCatalogUrl(manifest, "sports", "live", null, null, 0),
        )
    }

    @Test
    fun pencariSportGenreIsBrowsableOutsideGameDetails() {
        val popular = AddonCatalog(
            "movie", "top", "Popular",
            listOf(AddonExtraProperty("genre", options = listOf("Action", "Sport"))),
        )
        val addon = addon("org.pencarimovie.addon.server", "PencariMovie", popular)

        assertEquals(listOf(popular), SportsAddonCatalogs.browseCatalogs(addon))
        assertEquals("Sport", SportsAddonCatalogs.genre(popular))
        assertTrue(SportsAddonCatalogs.routes(addon, SportsLeague.NBA).isEmpty())
    }

    private fun addon(id: String, name: String, vararg catalogs: AddonCatalog): AddonManifest {
        val types = catalogs.map { it.type }
        return AddonManifest(
            id = id,
            name = name,
            description = "",
            version = "1.0.0",
            resources = listOf(AddonResource("stream", types)),
            types = types,
            catalogs = catalogs.toList(),
            transportUrl = "https://example.com/manifest.json",
        )
    }
}
