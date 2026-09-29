package com.example.identity

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthArchitectureContractTest {
    @Test
    fun `identity foundation has no local fitness persistence dependency`() {
        val source = authSurfaceSource()

        listOf(
            "ProfileRepository",
            "SavedRoutineRepository",
            "PersonalTrainerDao",
            "AppDatabase",
            "DataStore",
            "SharedPreferences",
            "deleteAllWorkoutLogs",
            "deleteCalorieLog"
        ).forEach { protectedOwner ->
            assertFalse("Unexpected local-data dependency: $protectedOwner", source.contains(protectedOwner))
        }
    }

    @Test
    fun `pure identity package has no commercial dependency`() {
        val source = identityPackageSource()

        listOf("RevenueCat", "Purchases", "CustomerInfo", "Boost", "AdMob").forEach { owner ->
            assertFalse("Unexpected commercial dependency: $owner", source.contains(owner))
        }
    }

    @Test
    fun `Firebase SDK types remain confined to FirebaseAuthProvider`() {
        val offenders = identityPackageDirectory().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "FirebaseAuthProvider.kt" }
            .filter { it.readText().contains("com.google.firebase") }
            .map(File::getName)
            .toList()

        assertTrue("Firebase SDK imports escaped provider confinement: $offenders", offenders.isEmpty())
    }

    @Test
    fun `backend token boundary adds no persistence analytics or logging owner`() {
        val source = identityPackageSource() + "\n" +
            locate("src/main/java/com/example/ai/backend/BackendSessionClient.kt") + "\n" +
            locate("src/main/java/com/example/ai/backend/RetrofitAiBackendService.kt")

        listOf(
            "DataStore",
            "SharedPreferences",
            "RoomDatabase",
            "FileOutputStream",
            "android.util.Log",
            "FirebaseAnalytics"
        ).forEach { forbiddenOwner ->
            assertFalse("Unexpected token persistence/logging owner: $forbiddenOwner", source.contains(forbiddenOwner))
        }
    }

    @Test
    fun `AccountViewModel orchestrates only through the app owned identity coordinator`() {
        val source = accountViewModelSource()

        assertTrue(
            source.contains("import com.example.subscription.RevenueCatIdentityCoordinator")
        )
        assertTrue(
            source.contains("import com.example.subscription.DisabledRevenueCatIdentityCoordinator")
        )
        listOf(
            "com.revenuecat.purchases",
            "Purchases",
            "CustomerInfo",
            "RevenueCatSdkClient",
            "RevenueCatPurchaseClient",
            "Boost",
            "AdMob"
        ).forEach { concreteOwner ->
            assertFalse(
                "Unexpected direct commercial dependency: $concreteOwner",
                source.contains(concreteOwner)
            )
        }
    }

    private fun authSurfaceSource(): String =
        "${identityPackageSource()}\n${accountViewModelSource()}"

    private fun identityPackageSource(): String {
        val directory = identityPackageDirectory()
        return directory.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
    }

    private fun identityPackageDirectory(): File {
        val candidates = listOf(
            File("src/main/java/com/example/identity"),
            File("app/src/main/java/com/example/identity"),
            File("mobile/app/src/main/java/com/example/identity")
        )
        val directory = candidates.firstOrNull(File::isDirectory)
            ?: error("Could not locate identity production sources")
        return directory
    }

    private fun accountViewModelSource(): String =
        locate("src/main/java/com/example/viewmodel/AccountViewModel.kt")

    private fun locate(relativePath: String): String {
        val candidates = listOf(
            File(relativePath),
            File("app/$relativePath"),
            File("mobile/app/$relativePath")
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate $relativePath")
    }
}
