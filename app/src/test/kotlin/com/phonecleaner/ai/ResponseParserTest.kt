package com.phonecleaner.ai

import com.phonecleaner.ai.ai.ResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseParserTest {
    @Test fun parsesArrayWithNoise() {
        val text = """I checked [1] the packages.
```json
[ {"package":"com.miui.msa.global","appName":"MSA","whatItDoes":"Visar reklam.","verdict":"safe","risk":"Inget.","isAdOrTracker":true,"suspectedMalware":false,"sources":["https://example.com/a","not a url"]},
  {"package":"com.x.y","appName":"X","whatItDoes":"?","verdict":"maybe","risk":"","isAdOrTracker":false,"suspectedMalware":false,"sources":[]},
  {"package":"com.z.q","appName":"Z","whatItDoes":"Z","verdict":"KEEP","risk":"r","isAdOrTracker":false,"suspectedMalware":false,"sources":[]} ]
```"""
        val items = ResponseParser.parse(text, "m", 1L)
        assertEquals(listOf("com.miui.msa.global", "com.z.q"), items.map { it.pkg })
        assertEquals("https://example.com/a", items[0].sources)
        assertTrue(items[0].isAdOrTracker)
        assertEquals("keep", items[1].verdict)
        assertTrue(ResponseParser.parse("no json here", "m").isEmpty())
    }

    @Test fun toleratesTrailingBracketsAndTruncation() {
        val obj = { p: String -> """{"package":"$p","appName":"a ] } [","whatItDoes":"x \" y","verdict":"safe","risk":"","isAdOrTracker":false,"suspectedMalware":false,"sources":["https://e.com/[1]"]}""" }
        val trailing = "[${obj("com.a.b")}, ${obj("com.c.d")}]\nSources: [1] [2]"
        assertEquals(listOf("com.a.b", "com.c.d"), ResponseParser.parse(trailing, "m").map { it.pkg })
        val cut = "[${obj("com.a.b")}, ${obj("com.c.d")}, {\"package\":\"com.e.f\",\"appNa"
        assertEquals(listOf("com.a.b", "com.c.d"), ResponseParser.parse(cut, "m").map { it.pkg })
        assertEquals("a ] } [", ResponseParser.parse(trailing, "m")[0].appName)
    }
}
