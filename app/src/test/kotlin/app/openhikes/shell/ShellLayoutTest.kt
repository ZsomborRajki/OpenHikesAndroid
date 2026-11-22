package app.openhikes.shell

import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShellLayoutTest {
    @Test
    fun compactPortraitPhoneUsesBottomSheet() {
        val portraitPhone = WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(411f, 891f)
        val narrowSplit = WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(360f, 500f)

        assertThat(resolveShellLayout(portraitPhone)).isEqualTo(ShellLayout.BOTTOM_SHEET)
        assertThat(resolveShellLayout(narrowSplit)).isEqualTo(ShellLayout.BOTTOM_SHEET)
    }

    @Test
    fun landscapePhoneFoldableAndTabletUseSidePanel() {
        val landscapePhone = WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(800f, 400f)
        val shortNarrowSplit = WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(450f, 400f)
        val unfoldedFoldable = WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(673f, 841f)
        val tablet = WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(1024f, 768f)

        assertThat(resolveShellLayout(landscapePhone)).isEqualTo(ShellLayout.SIDE_PANEL)
        assertThat(resolveShellLayout(shortNarrowSplit)).isEqualTo(ShellLayout.SIDE_PANEL)
        assertThat(resolveShellLayout(unfoldedFoldable)).isEqualTo(ShellLayout.SIDE_PANEL)
        assertThat(resolveShellLayout(tablet)).isEqualTo(ShellLayout.SIDE_PANEL)
    }
}
