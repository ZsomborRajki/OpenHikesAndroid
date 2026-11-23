package app.openhikes.core.model

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * Single country/region row in the Offline Maps directory.
 */
data class OfflineCountryEntry(
    val country: OfflineCountry,
    val displayName: String,
    val fullName: String,
    val byteCount: Long,
    val distanceKm: Double? = null
)

/**
 * Item inside a catalog section: either a standalone/flat country entry or a collapsible
 * split-country group (`US`, `CA`, `RU`) in alphabetical mode.
 */
sealed interface OfflineDirectoryItem {
    val stableKey: String

    data class CountryItem(val entry: OfflineCountryEntry, val namesCountry: Boolean = true) : OfflineDirectoryItem {
        override val stableKey: String = "country-${entry.country.id}"
    }

    data class GroupItem(val groupId: String, val groupName: String, val regions: List<OfflineCountryEntry>) :
        OfflineDirectoryItem {
        override val stableKey: String = "group-$groupId"
    }
}

/**
 * Named section of the Offline Maps directory (alphabet letter, Nearby, or All Countries).
 */
data class OfflineDirectorySection(
    val id: String,
    val kind: OfflineDirectorySectionKind,
    val title: String,
    val items: List<OfflineDirectoryItem>
)

enum class OfflineDirectorySectionKind {
    LETTER,
    NEARBY,
    ALL_COUNTRIES
}

/**
 * Structured Offline Maps directory split into top action sections (`unfinished`, `downloaded`)
 * and sort-dependent browse sections.
 */
data class OfflineCountryDirectory(
    val unfinished: List<OfflineCountryEntry> = emptyList(),
    val downloaded: List<OfflineCountryEntry> = emptyList(),
    val sections: List<OfflineDirectorySection> = emptyList()
) {
    val isEmpty: Boolean
        get() = unfinished.isEmpty() && downloaded.isEmpty() && sections.isEmpty()
}

/**
 * Pure JVM builder for Offline Maps sections, sorting, collapsible split-country groups,
 * and diacritic/case-folded prefix-first search.
 */
object OfflineCountryDirectoryBuilder {
    const val NEARBY_THRESHOLD_KM = 250.0
    private val DIACRITICS_REGEX = Regex("\\p{Mn}+")

    fun build(
        state: OfflineCountriesState,
        sort: OfflineCountrySort,
        userLocation: GeoCoordinate? = null,
        locale: Locale = Locale.getDefault()
    ): OfflineCountryDirectory {
        val listed = state.listedCountries
        if (listed.isEmpty()) return OfflineCountryDirectory()

        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        val entries = listed.map { country ->
            OfflineCountryEntry(
                country = country,
                displayName = country.localizedName(locale),
                fullName = country.fullName(locale),
                byteCount = state.listedByteCount(country),
                distanceKm = userLocation?.let { OfflineCountryGeometry.distanceKm(country, it) }
            )
        }

        val comparator = entryComparator(sort, collator)
        val unfinishedIds = state.downloads.keys.filter { it !in state.installed }.toSet()
        val installedIds = state.installed.keys

        val unfinished = entries.filter { it.country.id in unfinishedIds }.sortedWith(comparator)
        val downloaded = entries.filter { it.country.id in installedIds }.sortedWith(comparator)
        val promotedIds = unfinishedIds + installedIds

        val sections = when (sort) {
            OfflineCountrySort.NAME -> buildAlphabeticalSections(entries, promotedIds, locale, collator)
            OfflineCountrySort.NEAREST -> buildNearestSections(entries, promotedIds, comparator)
            OfflineCountrySort.LARGEST -> buildLargestSections(entries, promotedIds, comparator)
        }
        return OfflineCountryDirectory(unfinished = unfinished, downloaded = downloaded, sections = sections)
    }

    /**
     * Diacritic- and case-folded search against `fullName`, ranking prefix matches strictly ahead
     * of substring containment, then alphabetical by folded name.
     */
    fun search(
        state: OfflineCountriesState,
        query: String,
        locale: Locale = Locale.getDefault()
    ): List<OfflineCountryEntry> {
        val foldedQuery = foldForMatch(query.trim(), locale)
        if (foldedQuery.isEmpty()) return emptyList()
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }

        val ranked = state.listedCountries.mapNotNull { country ->
            val fullName = country.fullName(locale)
            val foldedName = foldForMatch(fullName, locale)
            val rank = when {
                foldedName.startsWith(foldedQuery) -> 0
                foldedName.contains(foldedQuery) -> 1
                else -> return@mapNotNull null
            }
            val entry = OfflineCountryEntry(
                country = country,
                displayName = country.localizedName(locale),
                fullName = fullName,
                byteCount = state.listedByteCount(country)
            )
            RankedMatch(rank = rank, foldedName = foldedName, entry = entry)
        }
        return ranked
            .sortedWith(
                compareBy<RankedMatch> { it.rank }
                    .thenComparator { a, b -> collator.compare(a.foldedName, b.foldedName) }
            )
            .map { it.entry }
    }

    private fun buildAlphabeticalSections(
        entries: List<OfflineCountryEntry>,
        promotedIds: Set<String>,
        locale: Locale,
        collator: Collator
    ): List<OfflineDirectorySection> {
        val items = ArrayList<Pair<String, OfflineDirectoryItem>>()
        val groupedByParent = LinkedHashMap<String, MutableList<OfflineCountryEntry>>()

        for (entry in entries) {
            val group = entry.country.group
            if (group != null) {
                groupedByParent.getOrPut(group) { ArrayList() }.add(entry)
            } else if (entry.country.id !in promotedIds) {
                items.add(entry.displayName to OfflineDirectoryItem.CountryItem(entry, namesCountry = true))
            }
        }

        for ((groupId, regions) in groupedByParent) {
            val groupName = regions.first().country.groupLocalizedName(locale) ?: groupId
            val sortedRegions = regions.sortedWith { a, b -> collator.compare(a.displayName, b.displayName) }
            items.add(
                groupName to OfflineDirectoryItem.GroupItem(
                    groupId = groupId,
                    groupName = groupName,
                    regions = sortedRegions
                )
            )
        }

        val sortedItems = items.sortedWith { a, b -> collator.compare(a.first, b.first) }
        val byLetter = LinkedHashMap<String, MutableList<OfflineDirectoryItem>>()
        for ((sortLabel, item) in sortedItems) {
            val letter = sectionLetter(sortLabel, locale)
            byLetter.getOrPut(letter) { ArrayList() }.add(item)
        }

        return byLetter.map { (letter, sectionItems) ->
            OfflineDirectorySection(
                id = "letter-$letter",
                kind = OfflineDirectorySectionKind.LETTER,
                title = letter,
                items = sectionItems
            )
        }
    }

    private fun buildNearestSections(
        entries: List<OfflineCountryEntry>,
        promotedIds: Set<String>,
        comparator: Comparator<OfflineCountryEntry>
    ): List<OfflineDirectorySection> {
        val remaining = entries.filter { it.country.id !in promotedIds }.sortedWith(comparator)
        val nearby = remaining.filter { (it.distanceKm ?: Double.POSITIVE_INFINITY) <= NEARBY_THRESHOLD_KM }
        val farther = remaining.filter { (it.distanceKm ?: Double.POSITIVE_INFINITY) > NEARBY_THRESHOLD_KM }
        return buildList {
            if (nearby.isNotEmpty()) {
                add(
                    OfflineDirectorySection(
                        id = "nearby",
                        kind = OfflineDirectorySectionKind.NEARBY,
                        title = "Nearby",
                        items = nearby.map { OfflineDirectoryItem.CountryItem(it, namesCountry = true) }
                    )
                )
            }
            if (farther.isNotEmpty()) {
                add(
                    OfflineDirectorySection(
                        id = "all-countries",
                        kind = OfflineDirectorySectionKind.ALL_COUNTRIES,
                        title = "All Countries",
                        items = farther.map { OfflineDirectoryItem.CountryItem(it, namesCountry = true) }
                    )
                )
            }
        }
    }

    private fun buildLargestSections(
        entries: List<OfflineCountryEntry>,
        promotedIds: Set<String>,
        comparator: Comparator<OfflineCountryEntry>
    ): List<OfflineDirectorySection> {
        val remaining = entries.filter { it.country.id !in promotedIds }.sortedWith(comparator)
        if (remaining.isEmpty()) return emptyList()
        return listOf(
            OfflineDirectorySection(
                id = "all-countries",
                kind = OfflineDirectorySectionKind.ALL_COUNTRIES,
                title = "All Countries",
                items = remaining.map { OfflineDirectoryItem.CountryItem(it, namesCountry = true) }
            )
        )
    }

    private fun entryComparator(sort: OfflineCountrySort, collator: Collator): Comparator<OfflineCountryEntry> =
        when (sort) {
            OfflineCountrySort.NAME -> Comparator { a, b -> collator.compare(a.fullName, b.fullName) }
            OfflineCountrySort.NEAREST -> compareBy<OfflineCountryEntry> {
                it.distanceKm ?: Double.POSITIVE_INFINITY
            }.thenComparator { a, b -> collator.compare(a.fullName, b.fullName) }

            OfflineCountrySort.LARGEST -> compareByDescending<OfflineCountryEntry> {
                it.byteCount
            }.thenComparator { a, b -> collator.compare(a.fullName, b.fullName) }
        }

    private fun sectionLetter(label: String, locale: Locale): String {
        val folded = foldForMatch(label.trim(), locale)
        val first = folded.firstOrNull() ?: return "#"
        return if (first.isLetter()) first.uppercaseChar().toString() else "#"
    }

    private fun foldForMatch(raw: String, locale: Locale): String {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKD)
        return DIACRITICS_REGEX.replace(normalized, "").lowercase(locale)
    }

    private data class RankedMatch(val rank: Int, val foldedName: String, val entry: OfflineCountryEntry)
}
