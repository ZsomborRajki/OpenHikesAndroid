package app.openhikes.core.testing.android

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpenHikesTestRunnerTest {
    @Test
    fun instantiatesCleanly() {
        val runner = OpenHikesTestRunner()
        assertThat(runner).isNotNull()
    }
}
