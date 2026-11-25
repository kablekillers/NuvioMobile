package com.nuvio.app.features.sports

/**
 * One choice in the Sports Hub's top row. A choice can cover several ESPN
 * leagues (College Basketball = men's + women's, Golf = PGA + LPGA…).
 * "All" holds the cross-sport views (All Sports, Live Events, US Channels, My Teams).
 */
internal data class HubSport(
    val key: String,
    val label: String,
    val logo: SportsLeague?,
    val members: List<SportsLeague>,
) {
    val isAll: Boolean get() = members.isEmpty()
    /** League used for standings, NFL Channels and other single-league features. */
    val primary: SportsLeague get() = members.firstOrNull() ?: SportsLeague.NFL
    val scoreboardMembers: List<SportsLeague> get() = members.filter { it.scoreboardPath != null }
    /** Members with no ESPN scoreboard (boxing, wrestling, world soccer) come from add-on event lists. */
    val catalogMembers: List<SportsLeague> get() = members.filter { it.scoreboardPath == null }
    val mixesLeagues: Boolean get() = scoreboardMembers.size > 1
}

internal object HubSports {
    val All = HubSport("all", "All", null, emptyList())

    /** US sports only, in the order shown. */
    val list: List<HubSport> = listOf(
        All,
        HubSport("nfl", "NFL", SportsLeague.NFL, listOf(SportsLeague.NFL)),
        HubSport("nba", "NBA", SportsLeague.NBA, listOf(SportsLeague.NBA)),
        HubSport("wnba", "WNBA", SportsLeague.WNBA, listOf(SportsLeague.WNBA)),
        HubSport("mlb", "MLB", SportsLeague.MLB, listOf(SportsLeague.MLB)),
        HubSport("nhl", "NHL", SportsLeague.NHL, listOf(SportsLeague.NHL)),
        HubSport("cfb", "College FB", SportsLeague.COLLEGE_FOOTBALL, listOf(SportsLeague.COLLEGE_FOOTBALL)),
        HubSport("cbb", "College BB", SportsLeague.NCAA_MEN, listOf(SportsLeague.NCAA_MEN, SportsLeague.NCAA_WOMEN)),
        HubSport("chockey", "College Hockey", SportsLeague.NCAA_HOCKEY_MEN, listOf(SportsLeague.NCAA_HOCKEY_MEN, SportsLeague.NCAA_HOCKEY_WOMEN)),
        HubSport("cbase", "College Baseball", SportsLeague.COLLEGE_BASEBALL, listOf(SportsLeague.COLLEGE_BASEBALL)),
        HubSport("soccer", "Soccer", SportsLeague.MLS, listOf(SportsLeague.MLS, SportsLeague.WORLD_CUP, SportsLeague.SOCCER)),
        HubSport("fight", "Fighting", SportsLeague.UFC, listOf(SportsLeague.UFC, SportsLeague.COMBAT, SportsLeague.WRESTLING)),
        HubSport("tennis", "Tennis", SportsLeague.ATP, listOf(SportsLeague.ATP, SportsLeague.WTA)),
        HubSport("golf", "Golf", SportsLeague.PGA, listOf(SportsLeague.PGA, SportsLeague.LPGA)),
        HubSport("f1", "F1", SportsLeague.FORMULA_1, listOf(SportsLeague.FORMULA_1)),
        HubSport("nascar", "NASCAR", SportsLeague.NASCAR, listOf(SportsLeague.NASCAR)),
        HubSport("indycar", "IndyCar", SportsLeague.INDYCAR, listOf(SportsLeague.INDYCAR)),
    )

    /** Every ESPN scoreboard the hub uses (All Sports feed, search). */
    val scoreboardLeagues: List<SportsLeague> = list.flatMap { it.scoreboardMembers }.distinct()

    fun byKey(key: String?): HubSport = list.firstOrNull { it.key == key } ?: All
}
