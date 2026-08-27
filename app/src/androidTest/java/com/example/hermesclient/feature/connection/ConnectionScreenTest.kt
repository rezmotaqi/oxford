package com.example.hermesclient.feature.connection

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.hermesclient.core.ui.theme.HermesClientTheme
import com.example.hermesclient.domain.model.HermesCapabilities
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConnectionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun connectedStateShowsSuccessAndContinues() {
        var continued = false
        composeRule.setContent {
            HermesClientTheme {
                ConnectionScreen(
                    state = ConnectionUiState.Connected(
                        form = ConnectionForm(
                            serverUrl = "https://hermes.example.com",
                            hasSavedApiKey = true,
                        ),
                        capabilities = HermesCapabilities(
                            sessionsSupported = true,
                            sessionStreamingSupported = true,
                            runsSupported = true,
                            approvalsSupported = false,
                        ),
                    ),
                    onServerUrlChange = {},
                    onApiKeyChange = {},
                    onToggleApiKeyVisibility = {},
                    onTestConnection = {},
                    onContinue = { continued = true },
                )
            }
        }

        composeRule.onNodeWithText("Connection successful").assertIsDisplayed()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.runOnIdle { assertTrue(continued) }
    }
}
