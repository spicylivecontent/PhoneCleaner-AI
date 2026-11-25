package com.phonecleaner.ai

import com.phonecleaner.ai.scan.Critical
import com.phonecleaner.ai.scan.Parsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {
    @Test fun pmList() {
        val out = """
            package:/data/app/~~AbC==/com.foo.bar-XyZ==/base.apk=com.foo.bar  installer=com.android.vending
            package:/system/app/Thing/Thing.apk=com.miui.thing  installer=null
            package:/data/app/x/base.apk=org.side.loaded  installer=com.google.android.packageinstaller
            package:/product/app/A.apk=com.a.b
            garbage line
        """.trimIndent()
        val l = Parsers.pmList(out)
        assertEquals(4, l.size)
        assertEquals("com.foo.bar", l[0].pkg)
        assertEquals("com.android.vending", l[0].installer)
        assertEquals("/data/app/~~AbC==/com.foo.bar-XyZ==/base.apk", l[0].path)
        assertNull(l[1].installer)
        assertEquals("com.google.android.packageinstaller", l[2].installer)
        assertEquals("com.a.b", l[3].pkg)
        assertEquals(setOf("com.a", "com.b.c"), Parsers.pmNames("package:com.a\npackage:com.b.c\n"))
    }

    @Test fun admins() {
        val out = "  Enabled Device Admins (User 0, provisioningState: 0):\n    com.evil.app/.Admin:\n      uid=10234\n      admin=ComponentInfo{com.evil.app/com.evil.app.Admin}\n"
        assertEquals(setOf("com.evil.app"), Parsers.deviceAdmins(out))
        assertEquals(
            setOf("com.a.b", "com.c.d"),
            Parsers.componentPackages("com.a.b/com.a.b.Svc:com.c.d/.Other\n")
        )
        assertTrue(Parsers.componentPackages("null").isEmpty())
    }

    @Test fun battery() {
        val out = "  Estimated power use (mAh):\n    Capacity: 5160, Computed drain: 812\n    UID u0a123: 55.1 fg: 3.2 ( cpu=1 )\n    Uid 1000: 30.5 ( cpu=2 )\n    UID u0a7: 0.5\n"
        val m = Parsers.batteryByUid(out)
        assertEquals(55.1, m[10123]!!, 0.001)
        assertEquals(30.5, m[1000]!!, 0.001)
        assertEquals(0.5, m[10007]!!, 0.001)
    }

    @Test fun netstats() {
        val out = """
            Dev stats:
              ident=[{type=1}] uid=-1 set=ALL tag=0x0
                  st=1 rb=999 rp=1 tb=999 tp=1 op=0
            UID stats:
              Pending bytes: 0
              ident=[{type=0, metered=true}] uid=10158 set=DEFAULT tag=0x0
                NetworkStatsHistory: bucketDuration=7200
                  st=1699999200 rb=1000 rp=5 tb=500 tp=7 op=0
                  st=1700006400 rb=10 rp=5 tb=5 tp=7 op=0
              ident=[{type=1}] uid=10158 set=FOREGROUND tag=0x0
                  st=1699999200 rb=1 rp=5 tb=1 tp=7 op=0
            UID tag stats:
              ident=[{type=1}] uid=10158 set=FOREGROUND tag=0x1
                  st=1699999200 rb=7777 rp=5 tb=1 tp=7 op=0
        """.trimIndent()
        assertEquals(mapOf(10158 to 1517L), Parsers.dataByUid(out))
    }

    @Test fun diskAndUsage() {
        val disk = "Latency: 1ms\nPackage Names: [\"com.a.b\",\"com.c.d\"]\nApp Sizes: [100,200]\nApp Data Sizes: [10,20]\nCache Sizes: [1,2]\n"
        assertEquals(mapOf("com.a.b" to 111L, "com.c.d" to 222L), Parsers.storageByPackage(disk))
        val usage = "    package=com.a.b totalTimeUsed=\"00:12\" lastTimeUsed=\"2026-11-20 10:00:00\" totalTimeVisible=\"1\"\n" +
            "    package=com.a.b totalTimeUsed=\"00:12\" lastTimeUsed=\"2026-11-24 09:00:00\"\n" +
            "    package=com.c.d totalTimeUsed=\"00:00\" lastTimeUsed=\"1970-01-01 01:00:00\"\n"
        assertEquals(mapOf("com.a.b" to "2026-11-24 09:00:00"), Parsers.lastUsedByPackage(usage))
    }

    @Test fun critical() {
        for (p in listOf(
            "com.android.systemui", "com.android.settings", "com.android.phone", "com.google.android.gms",
            "com.android.vending", "com.miui.securitycenter", "com.miui.home", "com.miui.core",
            "com.xiaomi.finddevice", "android", "com.android.providers.settings",
        )) assertTrue(p, Critical.isCore(p))
        for (p in listOf("com.miui.msa.global", "com.facebook.katana", "com.miui.analytics", "com.xiaomi.glgm"))
            assertFalse(p, Critical.isCore(p))
    }
}
