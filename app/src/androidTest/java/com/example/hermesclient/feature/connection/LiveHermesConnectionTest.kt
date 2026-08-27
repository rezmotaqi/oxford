package com.example.hermesclient.feature.connection

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hermesclient.app.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class LiveHermesConnectionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun connectsToConfiguredHermesServer() {
        val arguments = InstrumentationRegistry.getArguments()
        val serverUrl = arguments.getString("hermesServerUrl")
        val apiKey = arguments.getString("hermesApiKey")
        assumeTrue("hermesServerUrl is required", !serverUrl.isNullOrBlank())
        assumeTrue("hermesApiKey is required", !apiKey.isNullOrBlank())

        val textFields = composeRule.onAllNodes(hasSetTextAction())
        textFields[0].performTextInput(serverUrl.orEmpty())
        textFields[1].performTextInput(apiKey.orEmpty())
        composeRule.onNodeWithText("Test Connection").performClick()

        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithText("Connection successful")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Continue").performClick()
    }
}
