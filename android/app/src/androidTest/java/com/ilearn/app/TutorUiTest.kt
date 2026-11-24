package com.ilearn.app

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.ilearn.app.data.Lesson
import com.ilearn.app.tutor.*
import com.ilearn.app.ui.screens.ProviderSettingsScreen
import com.ilearn.app.ui.screens.TutorDialog
import com.ilearn.app.ui.theme.ILearnTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class TutorUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixtureDir: File
    private lateinit var profiles: ProviderProfileStore

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fixtureDir = File(context.cacheDir, "tutor-ui-${UUID.randomUUID()}").apply { mkdirs() }
        val isolatedContext = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = fixtureDir }
        profiles = ProviderProfileStore(isolatedContext)
    }

    @After fun tearDown() {
        val cache = ApplicationProvider.getApplicationContext<Context>().cacheDir.canonicalFile
        check(fixtureDir.canonicalPath.startsWith(cache.path + File.separator))
        fixtureDir.deleteRecursively()
    }

    @Test fun userCanSaveAndSelectACustomApiProfileWithoutMakingANetworkRequest() {
        compose.setContent { ILearnTheme { ProviderSettingsScreen(profiles) {} } }
        compose.onNodeWithText("Profile name").performTextInput("My compatible endpoint")
        compose.onNodeWithText("HTTPS API base URL").performTextReplacement("https://example.test/v1")
        compose.onNodeWithText("Model ID").performScrollTo().performTextInput("fixture-model")
        compose.onNodeWithText("API key • never included in backups").performScrollTo().performTextInput("fixture-only-key")
        compose.onNodeWithText("Save & use").performScrollTo().performClick()
        compose.waitForIdle()
        val active = profiles.active()!!
        assertEquals("https://example.test/v1", active.endpoint)
        assertEquals("fixture-model", active.model)
        assertEquals("fixture-only-key", profiles.key(active))
        assertFalse(File(fixtureDir, "api_profiles.json").readText().contains("fixture-only-key"))
    }

    @Test fun missingLessonEvidenceKeepsGroundedSendDisabled() {
        profiles.save(ProviderProfile(name = "Fixture", format = ApiFormat.OPENAI_CHAT, endpoint = "https://example.test/v1", model = "fixture"), "fixture-only-key", false)
        val lesson = Lesson("ui-fixture-${UUID.randomUUID()}", "fixture-course", 1, "Fixture lesson", "")
        compose.setContent { ILearnTheme { TutorDialog(lesson, 0, profiles, {}, {}, {}) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Lesson transcript is unavailable. Attach SRT/VTT/TXT or enable general help.").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("Bilingual").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Ask about this lesson…").performTextInput("Explain this lesson")
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }
}
