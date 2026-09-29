package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ToolsPakistaniFoodEntryTest {
    @Test
    fun `Pakistani food tracker title is clean and route key is preserved`() {
        val tool = pakistaniFoodTrackerTool()

        assertEquals("Pakistani Food Calorie Tracker", tool.name)
        assertFalse(tool.name.contains("🍛"))
        assertEquals("Search and log local and global foods", tool.description)
        assertFalse(tool.description.contains(Regex("\\b\\d+\\b")))
        assertEquals("pakistani_food", tool.key)
    }
}
