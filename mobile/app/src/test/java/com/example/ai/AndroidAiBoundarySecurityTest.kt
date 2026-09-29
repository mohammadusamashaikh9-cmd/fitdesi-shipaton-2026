package com.example.ai

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAiBoundarySecurityTest {
    @Test
    fun androidSourcesAndGeneratedConfigContainNoServerSecretSettings() {
        val moduleDirectory = moduleDirectory()
        val sourceDirectory = File(moduleDirectory, "src/main")
        val buildFile = File(moduleDirectory, "build.gradle.kts")
        val candidates = buildList {
            add(sourceDirectory)
            add(buildFile)
            val generated = File(moduleDirectory, "build/generated/source/buildConfig")
            if (generated.exists()) add(generated)
        }.flatMap { root ->
            if (root.isFile) listOf(root) else root.walkTopDown().filter(File::isFile).toList()
        }

        assertTrue("Expected Android source/config files to scan", candidates.isNotEmpty())
        candidates.forEach { file ->
            val content = runCatching(file::readText).getOrDefault("")
            SECRET_NAMES.forEach { secretName ->
                assertFalse(
                    "Secret setting name $secretName found in ${file.path}",
                    content.contains(secretName)
                )
            }
        }
        assertTrue(
            "Committed Android runtime mode must default to LOCAL",
            buildFile.readText().contains(
                "buildWeekConfig(\"FITDESI_AI_RUNTIME_MODE\", \"LOCAL\")"
            )
        )
    }

    @Test
    fun authenticatedRemoteBoundariesContainNoPersistenceOrLoggingDependency() {
        val sourceRoot = File(moduleDirectory(), "src/main/java/com/example/ai/backend")
        val boundaries = listOf(
            File(sourceRoot, "AuthenticatedCoachClient.kt"),
            File(sourceRoot, "RemoteAiConsentRepository.kt")
        )

        boundaries.forEach { file ->
            assertTrue("Expected boundary source ${file.path}", file.isFile)
            val content = file.readText()
            FORBIDDEN_BOUNDARY_REFERENCES.forEach { forbidden ->
                assertFalse("$forbidden must not enter ${file.name}", content.contains(forbidden))
            }
        }
    }

    private fun moduleDirectory(): File {
        val compiledTestLocation = File(
            requireNotNull(AndroidAiBoundarySecurityTest::class.java.protectionDomain.codeSource) {
                "Expected a code-source location for the Android unit-test module"
            }.location.toURI()
        ).canonicalFile
        return generateSequence(compiledTestLocation) { it.parentFile }
            .firstOrNull { candidate ->
                File(candidate, "src/main").isDirectory &&
                    File(candidate, "build.gradle.kts").isFile
            }
            ?: error("Could not resolve the Android app module from $compiledTestLocation")
    }

    private companion object {
        val SECRET_NAMES = listOf(
            "FIREWORKS" + "_API_KEY",
            "OPENROUTER" + "_API_KEY",
            "REMOTE_ADMISSION" + "_HMAC_SECRET"
        )
        val FORBIDDEN_BOUNDARY_REFERENCES = listOf(
            "DataStore",
            "RoomDatabase",
            "SharedPreferences",
            "FirebaseFirestore",
            "android.util.Log",
            "println("
        )
    }
}
