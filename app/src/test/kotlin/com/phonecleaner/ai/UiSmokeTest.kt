package com.phonecleaner.ai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.phonecleaner.ai.data.AppDb
import com.phonecleaner.ai.shizuku.ShizukuManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders every screen with real data in both languages and drives the main buttons. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class UiSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application
    private fun str(id: Int, vararg args: Any) = rule.activity.getString(id, *args)
    private lateinit var phone: FakePhone
    private lateinit var vm: MainViewModel

    @After fun tearDown() {
        // Let late background writes finish before Robolectric resets the app's files for the next test.
        repeat(20) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(25) }
        ShizukuManager.setTestShell(null)
        AppDb.closeForTest()
        // Robolectric can hand the next test this test's files: remove them explicitly.
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        ctx.deleteDatabase("phonecleaner.db")
        java.io.File(ctx.filesDir, "scan.json").delete()
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        try {
            // Work finishing on background threads posts back to the main looper, which Robolectric only runs when asked.
            rule.waitUntil(30_000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                condition()
            }
        } catch (t: Throwable) {
            throw AssertionError(
                "timed out waiting for $what; screen=${vm.screen.value} scanning=${vm.scanning.value} scanError=${vm.scanError.value} " +
                    "shizuku=${vm.shizukuState.value} last=${phone.commands.takeLast(1)} threads=" +
                    "scanNull=${vm.scan.value == null} step=${vm.scanStep.value} " + Thread.getAllStackTraces().filter { it.value.any { f -> f.className.contains("phonecleaner.ai.scan") || f.className.contains("MainViewModel") } }
                        .map { it.key.name + ":" + it.value.take(22).joinToString(" < ") { f -> f.className.substringAfterLast('.') + "." + f.methodName + ":" + f.lineNumber } }, t
            )
        }
        rule.waitForIdle()
    }

    private fun walkThrough() {
        vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
        // Robolectric can hand a later test the previous test's files; always start from "not scanned".
        java.io.File(app.filesDir, "scan.json").delete()
        rule.runOnUiThread { vm.scan.value = null }
        rule.waitForIdle()

        // Start page without Shizuku: explains how to start it.
        rule.onNodeWithText(str(R.string.shizuku_title)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.shizuku_get)).assertIsDisplayed()

        phone = samplePhone(ApplicationProvider.getApplicationContext())
        ShizukuManager.setTestShell(phone)
        waitFor("shizuku ready") { rule.onAllNodesWithText(str(R.string.shizuku_test)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(str(R.string.shizuku_test)).performClick()
        waitFor("id output") { rule.onAllNodesWithText("uid=2000(shell) gid=2000(shell)").fetchSemanticsNodes().isNotEmpty() }

        // Scan screen: run the scan from its button.
        rule.onNodeWithText(str(R.string.step_scan)).performScrollTo().performClick()
        rule.onNodeWithText(str(R.string.scan_start)).performClick()
        waitFor("scan") { vm.scan.value != null && !vm.scanning.value }
        rule.onNodeWithText(str(R.string.scan_suspicious, 1)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(str(R.string.scan_admins, 1)).performScrollTo().assertIsDisplayed()
        assertTrue(phone.mutating.isEmpty())

        // Settings: saving a key must never crash, even where the Keystore is unavailable (as in this test).
        rule.runOnUiThread { vm.go(Screen.SETTINGS) }
        rule.onNodeWithText(str(R.string.settings_api_key)).assertIsDisplayed()
        rule.onNodeWithText("sk-ant-…").performTextInput("sk-ant-test-key")
        rule.onAllNodesWithText(str(R.string.save))[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.settings_cache_clear)).performScrollTo().assertIsDisplayed()

        // Research screen: plan and cost estimate.
        rule.runOnUiThread { vm.go(Screen.RESEARCH) }
        rule.onNodeWithText(str(R.string.research_plan)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.research_plan_fmt, 9, 0, 7, 1)).assertIsDisplayed()
        rule.runOnUiThread {
            vm.research.value = MainViewModel.ResearchState(
                running = false, batchesTotal = 3, batchesDone = 2, packagesDone = 20, batchesFailed = 1,
                error = "boom", errorKind = com.phonecleaner.ai.ai.ClaudeException.Kind.NETWORK,
            )
        }
        rule.onNodeWithText(str(R.string.research_err_network, "boom")).performScrollTo().assertIsDisplayed()

        runBlocking { AppDb.get(app).dao().upsertAnalyses(sampleAnalyses) }
        waitFor("analyses") { vm.selected.value.size == 4 }

        // Review list: groups, search, details, untick all.
        rule.runOnUiThread { vm.go(Screen.LIST) }
        rule.onNodeWithText(str(R.string.list_selected_fmt, 4)).assertIsDisplayed()
        rule.onNodeWithText("▾ " + str(R.string.group_malware) + "  (1)").assertIsDisplayed()
        rule.onNodeWithText("▸ " + str(R.string.group_locked) + "  (10)").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.list_search)).performTextInput("msa")
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithText("com.miui.analytics").fetchSemanticsNodes().size)
        rule.onNodeWithText(str(R.string.list_uncheck_all)).performClick()
        waitFor("unticked") { vm.selected.value.isEmpty() }
        rule.onNodeWithText(str(R.string.list_reset)).performClick()
        waitFor("suggested") { vm.selected.value.size == 4 }

        // Clean: confirmation lists the exact commands; cancelling runs nothing.
        phone.commands.clear()
        rule.onNodeWithText(str(R.string.clean_button_fmt, 4)).performClick()
        rule.onNodeWithText(str(R.string.confirm_title)).assertIsDisplayed()
        rule.onNodeWithText("pm uninstall -k --user 0 com.miui.msa.global").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.cancel)).performClick()
        rule.waitForIdle()
        assertTrue(phone.commands.isEmpty())
        rule.onNodeWithText(str(R.string.clean_button_fmt, 4)).performClick()
        rule.onNodeWithText(str(R.string.confirm_yes)).performClick()
        waitFor("clean") { vm.work.value.visible && !vm.work.value.running }
        rule.onNodeWithText(str(R.string.work_progress_fmt, 4, 4, 4)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.ok)).performClick()
        assertFalse(phone.pkgs.getValue("com.miui.msa.global").installed)

        // Optimise screen.
        rule.runOnUiThread { vm.go(Screen.OPTIMIZE) }
        rule.onNodeWithText(str(R.string.opt_animations)).performClick()
        rule.onNodeWithText(str(R.string.optimize_review)).performClick()
        rule.onNodeWithText("settings put global window_animation_scale '0.5'").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.confirm_yes)).performClick()
        waitFor("optimise") { vm.work.value.visible && !vm.work.value.running }
        rule.onNodeWithText(str(R.string.ok)).performClick()
        assertEquals("0.5", phone.settings["global window_animation_scale"])
        waitFor("active shown") { rule.onAllNodesWithText(str(R.string.optimize_active, 3)).fetchSemanticsNodes().isNotEmpty() }

        // Restore screen: confirmation first, then everything is undone.
        rule.runOnUiThread { vm.go(Screen.RESTORE) }
        rule.onNodeWithText(str(R.string.restore_all_fmt, 7)).performClick()
        rule.onNodeWithText("pm install-existing --user 0 com.miui.msa.global").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.confirm_yes)).performClick()
        waitFor("restore") { vm.work.value.visible && !vm.work.value.running }
        rule.onNodeWithText(str(R.string.ok)).performClick()
        assertTrue(phone.pkgs.values.all { it.installed })
        assertEquals("1.0", phone.settings["global window_animation_scale"])
        waitFor("empty") { rule.onAllNodesWithText(str(R.string.restore_empty)).fetchSemanticsNodes().isNotEmpty() }

        // Log screen shows what was run.
        rule.runOnUiThread { vm.go(Screen.LOG) }
        waitFor("log") { rule.onAllNodesWithText("$ pm uninstall -k --user 0 com.miui.msa.global").fetchSemanticsNodes().isNotEmpty() }

        // Back to the start page.
        rule.activity.onBackPressedDispatcher.let { d -> rule.runOnUiThread { d.onBackPressed() } }
        rule.onNodeWithText(str(R.string.home_intro)).assertIsDisplayed()

        // Detail sheet: explanation, risk and sources.
        rule.runOnUiThread { vm.go(Screen.LIST) }
        rule.onNodeWithText("Does com.miui.msa.global things.").performClick()
        rule.onNodeWithText(str(R.string.detail_risk)).assertIsDisplayed()
        rule.onNodeWithText("Risk for com.miui.msa.global.").assertIsDisplayed()
        rule.onNodeWithText("https://example.com/com.miui.msa.global").assertIsDisplayed()
    }

    @Test fun english() {
        assertEquals("Start scan", str(R.string.scan_start))
        walkThrough()
    }

    @Test @Config(qualifiers = "sv-w411dp-h2000dp") fun swedish() {
        assertEquals("Starta skanning", str(R.string.scan_start))
        walkThrough()
    }
}
