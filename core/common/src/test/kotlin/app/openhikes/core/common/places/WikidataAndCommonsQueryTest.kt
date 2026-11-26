package app.openhikes.core.common.places

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WikidataAndCommonsQueryTest {
    @Test
    fun languagesMapsWikimediaRenamedCodesAndFallsBackToEnglish() {
        val languages = WikidataQuery.languages(listOf("nb-NO", "de-DE", "en-US"))
        assertThat(languages).containsExactly("no", "de", "en").inOrder()

        val zhLanguages = WikidataQuery.languages(listOf("zh-Hans-CN"))
        assertThat(zhLanguages).containsExactly("zh", "en").inOrder()
    }

    @Test
    fun decodesWikidataEntitySitelinkAndPreferredImageAndWikipediaSummary() {
        val entityJson = """
            {
              "entities": {
                "Q4115189": {
                  "sitelinks": {
                    "dewiki": { "site": "dewiki", "title": "Thumseekopf" },
                    "enwiki": { "site": "enwiki", "title": "Thumsee Kopf" }
                  },
                  "claims": {
                    "P18": [
                      {
                        "rank": "normal",
                        "mainsnak": { "datavalue": { "value": "Fallback.jpg" } }
                      },
                      {
                        "rank": "preferred",
                        "mainsnak": { "datavalue": { "value": "Thumsee_Kopf.jpg" } }
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent().encodeToByteArray()

        val entity = WikidataQuery.decodeEntity(entityJson)
        assertThat(entity.articleIn(listOf("en", "de"))).isEqualTo("en" to "Thumsee Kopf")
        assertThat(entity.image).isEqualTo("Thumsee_Kopf.jpg")

        val summaryJson = """
            {
              "type": "standard",
              "title": "Thumsee Kopf",
              "extract": "A wooded summit above the Thumsee.",
              "content_urls": {
                "desktop": { "page": "https://en.wikipedia.org/wiki/Thumsee_Kopf" }
              }
            }
        """.trimIndent().encodeToByteArray()

        val article = WikidataQuery.decodeArticle(summaryJson, language = "en")
        assertThat(article?.title).isEqualTo("Thumsee Kopf")
        assertThat(article?.extract).isEqualTo("A wooded summit above the Thumsee.")
        assertThat(article?.url).isEqualTo("https://en.wikipedia.org/wiki/Thumsee_Kopf")
    }

    @Test
    fun commonsLicenseAllowlistMetadataDecodeAndImageMagicWork() {
        assertThat(CommonsLicense.accepted("CC BY-SA 4.0")).isEqualTo("CC BY-SA 4.0")
        assertThat(CommonsLicense.accepted("Public domain")).isEqualTo("Public domain")
        assertThat(CommonsLicense.accepted("CC0 1.0")).isEqualTo("CC0 1.0")
        assertThat(CommonsLicense.accepted("CC BY-NC 4.0")).isNull()
        assertThat(CommonsLicense.accepted("CC BY-ND 4.0")).isNull()
        assertThat(CommonsLicense.accepted("Fair use")).isNull()

        assertThat(CommonsText.author("<a href=\"https://example.org\">User:Lena &amp; Max</a>"))
            .isEqualTo("Lena & Max")

        val commonsJson = """
            {
              "query": {
                "pages": [
                  {
                    "pageid": 42,
                    "imageinfo": [
                      {
                        "mime": "image/jpeg",
                        "width": 1600,
                        "sha1": "abc123",
                        "thumburl": "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a1/Test.jpg/960px-Test.jpg",
                        "descriptionurl": "https://commons.wikimedia.org/wiki/File:Test.jpg",
                        "extmetadata": {
                          "Artist": { "value": "<a href=\"//commons.wikimedia.org/wiki/User:Lena_Gruber\">User:Lena Gruber</a>" },
                          "LicenseShortName": { "value": "CC BY 4.0" },
                          "LicenseUrl": { "value": "https://creativecommons.org/licenses/by/4.0/" }
                        }
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent().encodeToByteArray()

        val files = CommonsPhotoQuery.decodeFiles(commonsJson)
        assertThat(files).hasSize(1)
        assertThat(files.single().credit.author).isEqualTo("Lena Gruber")
        assertThat(files.single().credit.license.name).isEqualTo("CC BY 4.0")

        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        assertThat(CommonsPhotoQuery.isValidImageBytes(pngHeader)).isTrue()
        assertThat(CommonsPhotoQuery.isValidImageBytes(jpegHeader)).isTrue()
        assertThat(CommonsPhotoQuery.isValidImageBytes("<html>404</html>".encodeToByteArray())).isFalse()
    }
}
