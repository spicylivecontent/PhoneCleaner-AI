package com.phonecleaner.ai

import com.phonecleaner.ai.ops.Ops
import com.phonecleaner.ai.ops.Planned
import com.phonecleaner.ai.scan.LockReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val phone = FakePhone()
    private val dao = FakeDao()
    private fun ops() = Ops(tmp.root, dao, phone)

    @Test fun systemAppIsUninstalledAndRestored() = runBlocking {
        val p = phone.add("com.miui.msa.global", system = true)
        val ops = ops()
        assertEquals(Ops.UNINSTALL, ops.removePackage(appInfo(p.name)))
        assertFalse(p.installed)
        assertEquals(listOf("pm uninstall -k --user 0 com.miui.msa.global"), phone.mutating)
        val change = dao.activeChanges().single()
        assertTrue(ops.revert(change))
        assertTrue(p.installed)
        assertTrue(dao.activeChanges().isEmpty())
    }

    @Test fun userAppIsBackedUpProvenAndRestoredFromBackup() = runBlocking {
        val p = phone.add("com.some.game", system = false, installer = "com.android.vending")
        val ops = ops()
        assertEquals(Ops.UNINSTALL, ops.removePackage(appInfo(p.name, system = false)))
        assertFalse(p.installed)
        assertFalse(p.apkPresent)
        // Order: backup, proof install, then the uninstall.
        assertTrue(phone.mutating[0].startsWith("rm -rf /data/local/tmp/phonecleaner-backup/com.some.game; mkdir"))
        assertTrue(phone.mutating[1].contains("pm install-create"))
        assertEquals("pm uninstall -k --user 0 com.some.game", phone.mutating[2])
        assertTrue(ops.revert(dao.activeChanges().single()))
        assertTrue(p.installed)
        assertFalse("backup is cleaned up after restore", p.backedUp)
    }

    @Test fun userAppIsOnlyDisabledWhenShellInstallIsBlocked() = runBlocking {
        phone.shellInstallBlocked = true
        val p = phone.add("com.some.game", system = false)
        val q = phone.add("com.other.app", system = false)
        val ops = ops()
        assertEquals(Ops.DISABLE, ops.removePackage(appInfo(p.name, system = false)))
        assertEquals(Ops.DISABLE, ops.removePackage(appInfo(q.name, system = false)))
        assertTrue(p.installed && !p.enabled && q.installed && !q.enabled)
        assertTrue(phone.commands.none { it.startsWith("pm uninstall") })
        assertEquals("the install check runs only once", 1, phone.commands.count { it.contains("pm install-create") })
        dao.activeChanges().forEach { assertTrue(ops.revert(it)) }
        assertTrue(p.enabled && q.enabled)
    }

    @Test fun userAppIsOnlyDisabledWhenBackupFails() = runBlocking {
        val p = phone.add("com.some.game", system = false).apply { backupFails = true }
        assertEquals(Ops.DISABLE, ops().removePackage(appInfo(p.name, system = false)))
        assertTrue(p.installed && p.apkPresent && !p.enabled)
    }

    @Test fun fallsBackToDisableAndReportsTotalFailure() = runBlocking {
        val p = phone.add("com.miui.videoplayer", system = true).apply { uninstallFails = true }
        val ops = ops()
        assertEquals(Ops.DISABLE, ops.removePackage(appInfo(p.name)))
        assertEquals("DISABLE", dao.activeChanges().single().kind)

        val q = phone.add("com.miui.stuck", system = true).apply { uninstallFails = true; disableFails = true }
        assertNull(ops.removePackage(appInfo(q.name)))
        assertEquals("nothing recorded for a failed removal", 1, dao.activeChanges().size)
        assertTrue(q.installed && q.enabled)
    }

    @Test fun protectedPackagesAreNeverTouched() = runBlocking {
        val ops = ops()
        for (name in listOf("com.android.systemui", "com.google.android.gms", "com.miui.home", BuildConfig.APPLICATION_ID)) {
            phone.add(name, system = true)
            assertNull(ops.removePackage(appInfo(name)))
        }
        phone.add("com.vendor.keyboard", system = true)
        assertNull(ops.removePackage(appInfo("com.vendor.keyboard", lock = LockReason.KEYBOARD)))
        assertNull(ops.removePackage(appInfo("com.x; rm -rf /")))
        assertFalse(ops.apply(Planned(Ops.APPOP, "com.android.systemui", "x", "SYSTEM_ALERT_WINDOW", "deny")))
        assertTrue("no command was run at all: ${phone.commands}", phone.commands.isEmpty())
        assertTrue(dao.changeList.isEmpty())
    }

    @Test fun settingsKeepTheirOriginalValueAcrossRepeatedChanges() = runBlocking {
        phone.settings["global window_animation_scale"] = "1.0"
        val ops = ops()
        val key = "global window_animation_scale"
        assertTrue(ops.apply(Planned(Ops.SETTING, key, "w", newValue = "0.5")))
        assertTrue("applying the same value again records nothing", ops.apply(Planned(Ops.SETTING, key, "w", newValue = "0.5")))
        assertEquals(1, dao.changeList.size)
        assertEquals("1.0", dao.changeList[0].originalValue)

        // Unset setting changed twice, the second time with a hostile value: one record, true original kept.
        val dns = "global private_dns_specifier"
        assertTrue(ops.apply(Planned(Ops.SETTING, dns, "d", newValue = "dns.adguard-dns.com")))
        assertTrue(ops.apply(Planned(Ops.SETTING, dns, "d", newValue = "it's; reboot")))
        assertEquals("it's; reboot", phone.settings[dns])
        assertTrue(phone.commands.none { it == "reboot" || it.endsWith("; reboot") })
        assertEquals(2, dao.changeList.size)
        assertEquals("null", dao.changeList[1].originalValue)

        // Undo newest first, as the Restore screen does.
        for (c in dao.activeChanges()) assertTrue(ops.revert(c))
        assertEquals("1.0", phone.settings[key])
        assertFalse(phone.settings.containsKey(dns))
        assertTrue(dao.activeChanges().isEmpty())

        assertFalse(ops.apply(Planned(Ops.SETTING, "global x; reboot", "bad", newValue = "1")))
    }

    @Test fun appOpsAreRecordedAndRestored() = runBlocking {
        phone.add("com.ad.sdk", system = false)
        phone.appops["com.ad.sdk SYSTEM_ALERT_WINDOW"] = "allow"
        val ops = ops()
        assertTrue(ops.apply(Planned(Ops.APPOP, "com.ad.sdk", "Ad", "SYSTEM_ALERT_WINDOW", "deny")))
        assertTrue(ops.apply(Planned(Ops.APPOP, "com.ad.sdk", "Ad", "RUN_ANY_IN_BACKGROUND", "ignore")))
        assertEquals("deny", phone.appops["com.ad.sdk SYSTEM_ALERT_WINDOW"])
        assertEquals("ignore", phone.appops["com.ad.sdk RUN_ANY_IN_BACKGROUND"])
        assertEquals(listOf("allow", "default"), dao.changeList.map { it.originalValue })
        for (c in dao.activeChanges()) assertTrue(ops.revert(c))
        assertEquals("allow", phone.appops["com.ad.sdk SYSTEM_ALERT_WINDOW"])
        assertFalse(phone.appops.containsKey("com.ad.sdk RUN_ANY_IN_BACKGROUND"))
    }

    @Test fun backupFileListsPackagesAndOriginalValues() = runBlocking {
        phone.settings["global private_dns_mode"] = "opportunistic"
        phone.appops["com.ad.sdk SYSTEM_ALERT_WINDOW"] = "allow"
        val file = ops().writeBackup(
            listOf(
                Planned(Ops.UNINSTALL, "com.miui.msa.global", "MSA"),
                Planned(Ops.SETTING, "global private_dns_mode", "dns", newValue = "hostname"),
                Planned(Ops.APPOP, "com.ad.sdk", "Ad", "SYSTEM_ALERT_WINDOW", "deny"),
            )
        )
        val json = org.json.JSONObject(file.readText())
        assertEquals("com.miui.msa.global", json.getJSONArray("packages").getJSONObject(0).getString("package"))
        assertEquals("opportunistic", json.getJSONObject("originalSettings").getString("global private_dns_mode"))
        assertEquals("allow", json.getJSONArray("originalAppOps").getJSONObject(0).getString("mode"))
        assertTrue("writing the backup changes nothing", phone.mutating.isEmpty())
    }

    @Test fun nothingChangesWhenTheOriginalValueCannotBeRead() = runBlocking {
        phone.add("com.ad.sdk", system = false)
        phone.appops["com.ad.sdk SYSTEM_ALERT_WINDOW"] = "allow"
        phone.settings["global private_dns_mode"] = "off"
        val ops = ops()
        phone.failReadsOf = "SYSTEM_ALERT_WINDOW"
        assertFalse(ops.apply(Planned(Ops.APPOP, "com.ad.sdk", "Ad", "SYSTEM_ALERT_WINDOW", "deny")))
        phone.failReadsOf = "private_dns_mode"
        assertFalse(ops.apply(Planned(Ops.SETTING, "global private_dns_mode", "dns", newValue = "hostname")))
        assertTrue(phone.mutating.isEmpty())
        assertTrue(dao.changeList.isEmpty())
        // The plan as a whole is stopped before any change when the backup cannot record an original.
        val stopped = runCatching { ops.writeBackup(listOf(Planned(Ops.SETTING, "global private_dns_mode", "dns", newValue = "hostname"))) }
        assertTrue(stopped.isFailure)
    }

    @Test fun uidModeIsNotMistakenForThePackageMode() = runBlocking {
        phone.add("com.ad.sdk", system = false)
        phone.uidModes["com.ad.sdk SYSTEM_ALERT_WINDOW"] = "allow"
        val ops = ops()
        assertEquals("default", ops.readAppOp("com.ad.sdk", "SYSTEM_ALERT_WINDOW"))
        assertFalse("unknown modes are rejected", ops.apply(Planned(Ops.APPOP, "com.ad.sdk", "Ad", "SYSTEM_ALERT_WINDOW", "deny; reboot")))
    }

    @Test fun failedSettingWriteLeavesNoRecord() = runBlocking {
        phone.settings["secure blocked"] = "1"
        val ops = ops()
        // "settings put" with a key the fake rejects leaves the value untouched.
        phone.rejectPuts = true
        assertFalse(ops.apply(Planned(Ops.SETTING, "secure blocked", "b", newValue = "0")))
        assertTrue(dao.changeList.isEmpty())
        assertEquals("1", phone.settings["secure blocked"])
    }
}
