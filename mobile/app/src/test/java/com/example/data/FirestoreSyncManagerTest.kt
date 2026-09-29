package com.example.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FirestoreSyncManagerTest {
    @Test
    fun `legacy runtime flag has no activation hook or Firestore client owner`() {
        val source = source("src/main/java/com/example/data/FirestoreSyncManager.kt")

        assertFalse(source.contains("BuildWeekRuntimeConfig"))
        assertFalse(source.contains("FIRESTORE_SYNC_ENABLED"))
        assertFalse(source.contains("FirebaseFirestore"))
        assertFalse(source.contains("firebase.firestore"))
        assertFalse(source.contains("collection("))
        assertFalse(source.contains("firestore_user_id"))
    }

    @Test
    fun `legacy fetch remains a compatibility no-op`() = kotlinx.coroutines.test.runTest {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

        assertNull(FirestoreSyncManager.fetchWeightPreference(context))
    }

    private fun source(relativePath: String): String {
        val candidates = listOf(
            File(relativePath),
            File("app/$relativePath"),
            File("mobile/app/$relativePath")
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate $relativePath")
    }
}
