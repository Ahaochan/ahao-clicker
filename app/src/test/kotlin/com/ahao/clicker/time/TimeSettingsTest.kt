package com.ahao.clicker.time

import com.ahao.clicker.core.BuiltinTimeSource
import org.junit.Assert.*
import org.junit.Test

class TimeSettingsTest {
    @Test fun `interval sync applies configuration without first refreshing and cancelling idle page requests`() {
        var refreshed = 0
        var saved = ""
        var synced = 0L
        val actions = TimeSettingsController({ false }, { saved = it.getValue("syncInterval") },
            { refreshed++ }, { synced = it }, {})
        assertEquals(TimeSettingsResult.SAVED, actions.saveAndSync("7777"))
        assertEquals("7777", saved)
        assertEquals(7777L, synced)
        assertEquals(0, refreshed)
    }
    @Test fun `source order migrates missing sources and drops unknown or duplicate names`() {
        val local = BuiltinTimeSource.valueOf("LOCAL")
        val sources = SourceOrder.decode("TAOBAO,unknown,JD,TAOBAO")
        assertEquals(listOf(BuiltinTimeSource.TAOBAO, BuiltinTimeSource.JD), sources.take(2))
        assertEquals(BuiltinTimeSource.entries.toSet(), sources.toSet())
        assertEquals(6, sources.size)
        assertTrue(local in sources)
        assertEquals(sources, SourceOrder.decode(SourceOrder.encode(sources)))
    }
    @Test fun `drop inserts a card without changing the relative order of others`() {
        val before = SourceOrder.decode("")
        val after = SourceOrder.move(before, before.first(), before.last())
        assertEquals(before.drop(1) + before.first(), after)
        assertEquals(before, SourceOrder.move(before, before[1], before[1]))
        assertEquals(before, SourceOrder.move(after, before.first(), after.first()))
    }
    @Test fun `active task blocks source and interval changes without saving or applying`() {
        val saved = mutableListOf<Map<String, String>>()
        var applied = 0; var synced = 0
        val actions = TimeSettingsController({ true }, saved::add, { applied++ }, { synced++ }, { synced++ })
        assertFalse(actions.select(BuiltinTimeSource.JD))
        assertEquals(TimeSettingsResult.ACTIVE_TASK, actions.saveAndSync("1000"))
        assertTrue(saved.isEmpty()); assertEquals(0, applied); assertEquals(0, synced)
    }
    @Test fun `interval bounds and source changes go through one operation boundary`() {
        val saved = mutableListOf<Map<String, String>>()
        val calls = mutableListOf<String>()
        val actions = TimeSettingsController({ false }, { saved.add(it); calls.add("save") },
            { calls.add("apply") }, { calls.add("sync:$it") }, { calls.add("sync-selected") })
        for (bad in listOf("", "999", "86400001", "9223372036854775808")) {
            assertEquals(TimeSettingsResult.INVALID_INTERVAL, actions.saveAndSync(bad))
        }
        assertTrue(saved.isEmpty())
        assertTrue(actions.select(BuiltinTimeSource.JD))
        assertEquals(mapOf("source" to "JD"), saved.single())
        assertEquals(listOf("save", "apply", "sync-selected"), calls)
        calls.clear()
        assertEquals(TimeSettingsResult.SAVED, actions.saveAndSync("1000"))
        assertEquals(listOf("save", "sync:1000"), calls)
        assertEquals(TimeSettingsResult.SAVED, actions.saveAndSync("86400000"))
    }
}
