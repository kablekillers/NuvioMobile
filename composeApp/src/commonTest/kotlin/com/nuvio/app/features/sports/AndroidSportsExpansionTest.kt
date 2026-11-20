package com.nuvio.app.features.sports

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidSportsExpansionTest {
    @Test
    fun keepsExpandedLeaguesOutOfIos() {
        val original = listOf("NFL", "COLLEGE_FOOTBALL", "NBA", "MLB", "NHL", "UFC")
        assertEquals(original, SportsLeague.entries.filter { it.supportsPlatform(android = false) }.map { it.name })
        assertTrue(SportsLeague.entries.all { it.supportsPlatform(android = true) })
    }

    @Test
    fun routesFreeSportsCatalogsAndKeepsCatalogOnlySportsScoped() {
        val catalogs = listOf(
            AddonCatalog("sport", "sports_live", "Live Now"),
            AddonCatalog("sport", "sports_basketball", "Basketball"),
            AddonCatalog("sport", "sports_tennis", "Tennis"),
            AddonCatalog("sport", "sports_darts", "Darts"),
            AddonCatalog("sport", "sports_motor_sports", "Motor Sports"),
        )
        val addon = AddonManifest(
            id = "community.sports.fly",
            name = "Sports Streams",
            description = "",
            version = "1.0.0",
            resources = emptyList(),
            types = listOf("sport"),
            catalogs = catalogs,
            transportUrl = "https://example.com/manifest.json",
        )
        assertEquals(listOf("sports_live", "sports_basketball"), SportsAddonCatalogs.routes(addon, SportsLeague.NCAA_WOMEN).map { it.id })
        assertEquals(listOf("sports_live", "sports_tennis"), SportsAddonCatalogs.routes(addon, SportsLeague.WTA).map { it.id })
        assertEquals(listOf("sports_darts"), SportsAddonCatalogs.routes(addon, SportsLeague.DARTS).map { it.id })
        assertEquals(listOf("sports_motor_sports"), SportsAddonCatalogs.routes(addon, SportsLeague.MOTORSPORT).map { it.id })
        assertTrue(SportsAddonCatalogs.routes(addon, SportsLeague.NBA, expanded = false).isEmpty())
    }

    @Test
    fun tennisUsesTheMatchStatusAndDoublesRosterNames() {
        val payload = """{"events":[{
            "id":"t1","name":"Tour Finals","status":{"type":{"state":"post"}},
            "groupings":[{"competitions":[{
                "id":"m1","date":"2026-11-20T18:00Z","status":{"type":{"state":"in"}},
                "competitors":[
                    {"homeAway":"away","roster":{"displayName":"Alex Reed / Robin Allen"},"linescores":[{"winner":true}]},
                    {"homeAway":"home","roster":{"displayName":"Gabriel Brown / Rafael Gomez"},"linescores":[{"winner":false}]}
                ]
            }]}]
        }]}"""
        val (tournament, match) = EspnSportsEventProvider.parseScoreboard(payload, SportsLeague.ATP)
        assertEquals(SportsEventStatus.FINAL, tournament.status)
        assertEquals(SportsEventStatus.LIVE, match.status)
        assertEquals("ATP:t1:m1", match.id)
        assertEquals("Alex Reed / Robin Allen vs Gabriel Brown / Rafael Gomez", match.title)
        assertEquals("1", match.away?.score)
        assertEquals("0", match.home?.score)
    }

    @Test
    fun racingSessionsUseTheirOwnStatusAndMatchTheRightStream() {
        val payload = """{"events":[{
            "id":"r1","name":"Sponsored Las Vegas Grand Prix","shortName":"Las Vegas Grand Prix",
            "status":{"type":{"state":"post"}},
            "competitions":[
                {"id":"p1","date":"2026-11-20T00:30Z","type":{"abbreviation":"FP1"},"status":{"type":{"state":"post"}}},
                {"id":"q1","date":"2026-11-21T04:00Z","type":{"abbreviation":"Qual"},"status":{"type":{"state":"pre"}}},
                {"id":"g1","date":"2026-11-22T04:00Z","type":{"abbreviation":"Race"},"status":{"type":{"state":"pre"}}}
            ]
        }]}"""
        val (practice, qualifying, race) = EspnSportsEventProvider.parseScoreboard(payload, SportsLeague.FORMULA_1)
        assertEquals(SportsEventStatus.FINAL, practice.status)
        assertEquals(listOf(qualifying, race), SportsEventSections.upcoming(listOf(practice, qualifying, race)))
        assertTrue(SportsEventMatcher.score(practice, "Las Vegas Grand Prix Free Practice 1") > 0)
        assertEquals(0, SportsEventMatcher.score(race, "Las Vegas Grand Prix Free Practice 1"))
        assertEquals(0, SportsEventMatcher.score(qualifying, "Las Vegas Grand Prix"))
        assertTrue(SportsEventMatcher.score(race, "Las Vegas Grand Prix") > 0)
    }
}
