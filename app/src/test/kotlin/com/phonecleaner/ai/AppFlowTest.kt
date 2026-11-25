package com.phonecleaner.ai

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.phonecleaner.ai.MainViewModel.Group
import com.phonecleaner.ai.data.AnalysisEntity
import com.phonecleaner.ai.data.AppDb
import com.phonecleaner.ai.ops.Ops
import com.phonecleaner.ai.scan.LockReason
import com.phonecleaner.ai.shizuku.ShizukuManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Fills a simulated phone with a realistic mix of packages. Shared with the UI test. */
fun samplePhone(app: Application): FakePhone {
    val phone = FakePhone()
    val pm = shadowOf(app.packageManager)
    fun pkg(name: String, system: Boolean, installer: String? = null, flags: Int = ApplicationInfo.FLAG_HAS_CODE, known: Boolean = true) {
        phone.add(name, system, installer)
        if (!known) return
        pm.installPackage(PackageInfo().apply {
            packageName = name
            versionName = "1.0"
            applicationInfo = ApplicationInfo().apply {
                packageName = name
                this.name = name
                this.flags = flags or if (system) ApplicationInfo.FLAG_SYSTEM else 0
                uid = 10000 + phone.pkgs.size
            }
        })
    }
    pkg("com.android.systemui", true)
    pkg("com.google.android.gms", true)
    pkg("com.miui.home", true)
    pkg("com.vendor.launcher", true)
    pkg("com.vendor.keyboard", true)
    pkg("com.vendor.persistent", true, flags = ApplicationInfo.FLAG_HAS_CODE or ApplicationInfo.FLAG_PERSISTENT)
    pkg("com.vendor.rro", true, flags = 0)
    pkg("com.vendor.mystery", true, known = false)
    pkg("com.miui.msa.global", true)
    pkg("com.miui.analytics", true)
    pkg("com.miui.videoplayer", true)
    pkg("com.vendor.needed", true)
    pkg("com.vendor.browser", true)
    pkg("com.some.game", false, "com.android.vending")
    pkg("com.evil.cleaner", false, "com.miui.packageinstaller")
    pkg(BuildConfig.APPLICATION_ID, false)
    phone.roles["HOME"] = "com.vendor.launcher"
    phone.settings["secure default_input_method"] = "com.vendor.keyboard/.Ime"
    phone.settings["global window_animation_scale"] = "1.0"
    phone.settings["global transition_animation_scale"] = "1.0"
    phone.settings["global animator_duration_scale"] = "1.0"
    phone.settings["global private_dns_mode"] = "opportunistic"
    phone.overlayAllowed += listOf("com.evil.cleaner", "com.miui.msa.global")
    phone.appops["com.evil.cleaner SYSTEM_ALERT_WINDOW"] = "allow"
    phone.appops["com.miui.msa.global SYSTEM_ALERT_WINDOW"] = "allow"
    phone.dumps["device_policy"] = "  Enabled Device Admins (User 0):\n    admin=ComponentInfo{com.evil.cleaner/com.evil.cleaner.Admin}\n"
    phone.props["ro.mi.os.version.name"] = "OS2.0"
    phone.props["ro.product.marketname"] = "Redmi 15C"
    return phone
}

fun analysis(pkg: String, verdict: String, ad: Boolean = false, malware: Boolean = false) = AnalysisEntity(
    pkg = pkg, appName = pkg, whatItDoes = "Does $pkg things.", verdict = verdict, risk = "Risk for $pkg.",
    isAdOrTracker = ad, suspectedMalware = malware, sources = "https://example.com/$pkg", model = "m", analyzedAt = 1L,
)

val sampleAnalyses = listOf(
    analysis("com.miui.msa.global", "safe", ad = true),
    analysis("com.miui.analytics", "safe", ad = true),
    analysis("com.miui.videoplayer", "caution"),
    analysis("com.vendor.needed", "keep"),
    analysis("com.vendor.browser", "safe"),
    analysis("com.some.game", "safe"),
    analysis("com.evil.cleaner", "safe", malware = true),
)

fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        Thread.sleep(10)
    }
    throw AssertionError("timed out waiting for: $what")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppFlowTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var phone: FakePhone
    private lateinit var vm: MainViewModel

    @Before fun setUp() {
        phone = samplePhone(app)
        ShizukuManager.setTestShell(phone)
        vm = MainViewModel(app)
    }

    @After fun tearDown() {
        ShizukuManager.setTestShell(null)
        AppDb.closeForTest()
    }

    private fun scan() {
        vm.startScan()
        await("scan") { vm.scan.value != null && !vm.scanning.value }
        assertNull(vm.scanError.value)
    }

    private fun research() {
        runBlocking { AppDb.get(app).dao().upsertAnalyses(sampleAnalyses) }
        await("analyses") { vm.analyses.value.size == sampleAnalyses.size && vm.rows.value.any { it.analysis != null } }
    }

    private fun finishWork() {
        await("work") { vm.work.value.visible && !vm.work.value.running }
    }

    @Test fun scanChangesNothingAndLocksWhatMustStay() {
        scan()
        assertTrue("scan ran only read-only commands: ${phone.mutating}", phone.mutating.isEmpty())
        val apps = vm.scan.value!!.apps.associateBy { it.pkg }
        assertEquals(phone.pkgs.size, apps.size)
        assertEquals(LockReason.CORE, apps.getValue("com.android.systemui").lockReason)
        assertEquals(LockReason.CORE, apps.getValue("com.miui.home").lockReason)
        assertEquals(LockReason.LAUNCHER, apps.getValue("com.vendor.launcher").lockReason)
        assertEquals(LockReason.KEYBOARD, apps.getValue("com.vendor.keyboard").lockReason)
        assertEquals(LockReason.PERSISTENT, apps.getValue("com.vendor.persistent").lockReason)
        assertEquals(LockReason.RESOURCE_OVERLAY, apps.getValue("com.vendor.rro").lockReason)
        assertEquals("unidentified packages fail closed", LockReason.UNKNOWN, apps.getValue("com.vendor.mystery").lockReason)
        assertEquals(LockReason.SELF, apps.getValue(BuildConfig.APPLICATION_ID).lockReason)
        assertNull(apps.getValue("com.miui.msa.global").lockReason)

        val evil = apps.getValue("com.evil.cleaner")
        assertTrue(evil.sideloaded && evil.overlay && evil.deviceAdmin && evil.suspicious)
        val game = apps.getValue("com.some.game")
        assertFalse(game.sideloaded || game.suspicious)
        assertTrue(vm.scan.value!!.osVersion.contains("HyperOS OS2.0"))
        assertEquals("1.0", vm.scan.value!!.settings["global window_animation_scale"])
        assertEquals(
            setOf("com.miui.msa.global", "com.miui.analytics", "com.miui.videoplayer", "com.vendor.needed", "com.vendor.browser", "com.some.game", "com.evil.cleaner"),
            vm.researchCandidates(vm.scan.value!!).map { it.pkg }.toSet(),
        )
    }

    @Test fun listGroupsAndPreTicksFollowTheRules() {
        scan()
        // Before research: nothing is pre-ticked, the heuristic hit is already in the malware group.
        assertTrue(vm.selected.value.isEmpty())
        research()
        val rows = vm.rows.value.associateBy { it.app.pkg }
        assertEquals(Group.MALWARE, rows.getValue("com.evil.cleaner").group)
        assertEquals(Group.ADS, rows.getValue("com.miui.msa.global").group)
        assertEquals(Group.SAFE, rows.getValue("com.vendor.browser").group)
        assertEquals(Group.UNSURE, rows.getValue("com.miui.videoplayer").group)
        assertEquals(Group.LOCKED, rows.getValue("com.vendor.needed").group)
        assertEquals(Group.LOCKED, rows.getValue("com.android.systemui").group)
        // safe is ticked, caution is not, the user's own store app is not, keep/locked cannot be.
        assertEquals(setOf("com.miui.msa.global", "com.miui.analytics", "com.vendor.browser", "com.evil.cleaner"), vm.selected.value)
        vm.toggle("com.vendor.needed", true)
        vm.toggle("com.android.systemui", true)
        vm.toggle("com.miui.videoplayer", true)
        await("toggle") { "com.miui.videoplayer" in vm.selected.value }
        assertFalse("com.vendor.needed" in vm.selected.value || "com.android.systemui" in vm.selected.value)
        vm.uncheckAll()
        await("uncheck") { vm.selected.value.isEmpty() }
        vm.resetSelection()
        await("reset") { vm.selected.value.size == 4 }
    }

    @Test fun nothingRunsBeforeConfirmationThenCleanAndRestoreEverything() {
        scan(); research()
        vm.toggle("com.some.game", true)
        await("tick") { "com.some.game" in vm.selected.value }
        phone.commands.clear()
        vm.planClean()
        val plan = vm.pendingPlan.value!!
        assertEquals(5, plan.size)
        assertTrue(plan.all { it.command == "pm uninstall -k --user 0 ${it.target}" })
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("nothing runs before the user confirms", phone.commands.isEmpty())
        vm.cancelPlan()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(phone.commands.isEmpty())

        // The user changes their default browser after the scan: it must now be refused.
        phone.roles["BROWSER"] = "com.vendor.browser"
        vm.planClean()
        vm.confirmPlan()
        finishWork()
        val work = vm.work.value
        assertNull(work.error)
        assertEquals(4, work.succeeded)
        assertEquals(listOf("com.vendor.browser"), work.failed)
        assertTrue(java.io.File(work.backupPath!!).readText().contains("com.miui.msa.global"))
        for (gone in listOf("com.miui.msa.global", "com.miui.analytics", "com.evil.cleaner", "com.some.game")) {
            assertFalse(gone, phone.pkgs.getValue(gone).installed)
        }
        assertTrue(phone.pkgs.getValue("com.vendor.browser").installed)
        // Only the selected packages were touched.
        val touched = phone.mutating.flatMap { c -> phone.pkgs.keys.filter { c.contains(it) } }.toSet()
        assertEquals(setOf("com.miui.msa.global", "com.miui.analytics", "com.evil.cleaner", "com.some.game"), touched)
        vm.dismissWork()
        await("list updated") { vm.rows.value.none { it.app.pkg == "com.miui.msa.global" } }
        await("changes") { vm.changes.value.count { !it.reverted } == 4 }
        await("log") { vm.logs.value.any { it.command.startsWith("pm uninstall") } }

        // Restore: nothing until confirmed, then everything is back.
        phone.commands.clear()
        vm.planRevert(vm.changes.value)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(phone.mutating.isEmpty())
        vm.confirmRevert()
        finishWork()
        assertEquals(4, vm.work.value.succeeded)
        assertTrue(phone.pkgs.values.all { it.installed && it.enabled })
        await("reverted") { vm.changes.value.all { it.reverted } }
        await("back in list") { vm.rows.value.any { it.app.pkg == "com.miui.msa.global" } }
    }

    @Test fun refusesToChangeAnythingAsRootOrWithoutShizuku() {
        scan(); research()
        phone.commands.clear()
        ShizukuManager.setTestShell(phone, uid = 0)
        vm.planClean(); vm.confirmPlan()
        await("refused") { vm.work.value.visible }
        assertEquals(MainViewModel.ERR_ROOT, vm.work.value.error)
        vm.dismissWork()
        ShizukuManager.setTestShell(null)
        vm.planOptimize(animations = true, dns = true, overlay = true, background = true)
        vm.confirmPlan()
        await("refused") { vm.work.value.visible }
        assertEquals(MainViewModel.ERR_SHIZUKU, vm.work.value.error)
        assertTrue(phone.commands.isEmpty())
    }

    @Test fun optimisationsApplyAndUndo() {
        scan(); research()
        phone.commands.clear()
        vm.setDnsHost("dns.adguard-dns.com")
        vm.planOptimize(animations = true, dns = true, overlay = true, background = true)
        val plan = vm.pendingPlan.value!!
        assertTrue(plan.any { it.command == "settings put global private_dns_mode 'hostname'" })
        assertTrue(plan.any { it.command == "appops set --user 0 com.evil.cleaner SYSTEM_ALERT_WINDOW deny" })
        assertTrue(plan.none { it.target == "com.android.systemui" || it.target == "com.vendor.needed" })
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(phone.commands.isEmpty())
        vm.confirmPlan()
        finishWork()
        assertTrue(vm.work.value.failed.toString(), vm.work.value.failed.isEmpty())
        assertEquals("0.5", phone.settings["global animator_duration_scale"])
        assertEquals("hostname", phone.settings["global private_dns_mode"])
        assertEquals("dns.adguard-dns.com", phone.settings["global private_dns_specifier"])
        assertEquals("deny", phone.appops["com.evil.cleaner SYSTEM_ALERT_WINDOW"])
        assertEquals("ignore", phone.appops["com.miui.analytics RUN_ANY_IN_BACKGROUND"])
        assertEquals("0.5", vm.scan.value!!.settings["global window_animation_scale"])
        vm.dismissWork()
        await("changes") { vm.changes.value.isNotEmpty() && vm.changes.value.none { it.reverted } }

        vm.planRevert(vm.changes.value)
        vm.confirmRevert()
        finishWork()
        assertTrue(vm.work.value.failed.toString(), vm.work.value.failed.isEmpty())
        assertEquals("1.0", phone.settings["global animator_duration_scale"])
        assertEquals("opportunistic", phone.settings["global private_dns_mode"])
        assertFalse(phone.settings.containsKey("global private_dns_specifier"))
        assertEquals("allow", phone.appops["com.evil.cleaner SYSTEM_ALERT_WINDOW"])
        assertFalse(phone.appops.containsKey("com.miui.analytics RUN_ANY_IN_BACKGROUND"))
    }

    @Test fun scanSurvivesRestartAndRejectsBadDnsHost() {
        scan()
        await("saved") { java.io.File(app.filesDir, "scan.json").length() > 0 }
        val second = MainViewModel(app)
        await("reloaded") { second.scan.value != null }
        assertEquals(vm.scan.value!!.apps.map { it.pkg to it.lockReason }, second.scan.value!!.apps.map { it.pkg to it.lockReason })
        vm.setDnsHost("evil.com; reboot")
        assertEquals("dns.adguard-dns.com", vm.dnsHost.value)
    }
}
