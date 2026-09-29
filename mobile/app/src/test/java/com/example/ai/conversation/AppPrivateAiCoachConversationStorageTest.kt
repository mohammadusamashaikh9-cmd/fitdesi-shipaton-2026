package com.example.ai.conversation

import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppPrivateAiCoachConversationStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `first atomic write is readable without leftover staging files`() = runTest {
        val directory = temporaryFolder.newFolder("coach-history")
        val storage = AppPrivateAiCoachConversationStorage(rootDirectory = directory)

        storage.writeAtomically("account-12345", "{\"version\":1}")

        assertEquals("{\"version\":1}", storage.read("account-12345"))
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".new") || it.name.endsWith(".bak") })
    }

    @Test
    fun `atomic replacement swaps the complete owner document without leftover staging files`() = runTest {
        assumeFalse(
            "Robolectric delegates AtomicFile replacement to Windows File.renameTo, which cannot replace an existing target",
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        )
        val directory = temporaryFolder.newFolder("coach-history")
        val storage = AppPrivateAiCoachConversationStorage(rootDirectory = directory)

        storage.writeAtomically("account-12345", "{\"version\":1}")
        storage.writeAtomically("account-12345", "{\"version\":2,\"complete\":true}")

        assertEquals("{\"version\":2,\"complete\":true}", storage.read("account-12345"))
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".new") || it.name.endsWith(".bak") })
    }
}
