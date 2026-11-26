package app.openhikes.core.data

import app.openhikes.core.model.WikidataFetchResult
import app.openhikes.core.model.WikidataPlace
import app.openhikes.core.model.WikidataPlaceSource
import app.openhikes.core.model.WikipediaArticle
import app.openhikes.core.testing.FakeWikidataPlaceSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WikidataPlaceLookupTest {
    @Test
    fun cachesCompleteAndNullAnswersWhileRetryingFailedAndIncompleteLookups() = runTest {
        val scripted = ArrayDeque<WikidataFetchResult>().apply {
            addLast(WikidataFetchResult.Failed("timeout"))
            addLast(
                WikidataFetchResult.Found(
                    WikidataPlace(
                        item = "Q4115189",
                        article = WikipediaArticle(
                            title = "Thumsee Kopf",
                            extract = "A wooded summit.",
                            url = "https://en.wikipedia.org/wiki/Thumsee_Kopf",
                            language = "en"
                        ),
                        photo = null,
                        isComplete = false
                    )
                )
            )
            addLast(WikidataFetchResult.Found(FakeWikidataPlaceSource.seededThumseeKopf()))
        }
        var calls = 0
        val source = object : WikidataPlaceSource {
            override suspend fun place(item: String): WikidataFetchResult {
                calls += 1
                return if (item == "Q999") {
                    WikidataFetchResult.Found(null)
                } else {
                    scripted.removeFirst()
                }
            }
        }
        val lookup = WikidataPlaceLookup(source)

        // 1. Failure is not cached.
        assertThat(lookup.lookup("Q4115189")).isNull()
        assertThat(calls).isEqualTo(1)

        // 2. Incomplete result is returned & cached for instant display, yet retried on next open.
        val partial = lookup.lookup("Q4115189")
        assertThat(partial?.isComplete).isFalse()
        assertThat(lookup.cached("Q4115189")).isEqualTo(partial)
        assertThat(calls).isEqualTo(2)

        // 3. Complete result is returned and cached permanently for the launch.
        val complete = lookup.lookup("Q4115189")
        assertThat(complete?.isComplete).isTrue()
        assertThat(complete?.photo).isNotNull()
        assertThat(lookup.lookup("Q4115189")).isEqualTo(complete)
        assertThat(calls).isEqualTo(3)

        // 4. Null ("nothing to show") is cached without re-requesting.
        assertThat(lookup.lookup("Q999")).isNull()
        assertThat(lookup.lookup("Q999")).isNull()
        assertThat(calls).isEqualTo(4)
    }

    @Test
    fun evictsOldestEntryWhenExceedingCapacity() = runTest {
        var calls = 0
        val source = object : WikidataPlaceSource {
            override suspend fun place(item: String): WikidataFetchResult {
                calls += 1
                return WikidataFetchResult.Found(null)
            }
        }
        val lookup = WikidataPlaceLookup(source)

        for (index in 1..(WikidataPlaceLookup.CAPACITY + 2)) {
            lookup.lookup("Q$index")
        }
        assertThat(calls).isEqualTo(WikidataPlaceLookup.CAPACITY + 2)

        // Oldest item Q1 was evicted and triggers a new lookup; newest remains cached.
        lookup.lookup("Q${WikidataPlaceLookup.CAPACITY + 2}")
        assertThat(calls).isEqualTo(WikidataPlaceLookup.CAPACITY + 2)
        lookup.lookup("Q1")
        assertThat(calls).isEqualTo(WikidataPlaceLookup.CAPACITY + 3)
    }
}
