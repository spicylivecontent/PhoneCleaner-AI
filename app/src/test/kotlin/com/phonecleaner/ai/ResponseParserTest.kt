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
}
