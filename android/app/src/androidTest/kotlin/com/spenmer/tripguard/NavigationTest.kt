package com.spenmer.tripguard
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import org.junit.Rule
import org.junit.Test
class NavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun rescueAccessibleWithoutVehicle() {
        rule.onNodeWithText("SOS").performClick()
        rule.onNodeWithText("Open 112 dialer").assertExists()
        rule.onNodeWithText("Open 112 dialer").performClick()
        rule.onNodeWithText("Open phone dialer?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
    }
    @Test fun privacyClearRequiresConfirmation() {
        rule.onNodeWithText("Continue locally").performClick()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Manage local data").performClick()
        rule.onNodeWithText("Clear all local data").performClick()
        rule.onNodeWithText("Clear local records?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Clear all local data").assertExists()
    }
    @Test fun capabilitiesDiscloseMissingMonitoring() {
        rule.onNodeWithText("Continue locally").performClick()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("View capabilities").performClick()
        rule.onNodeWithText("Not available").assertExists()
    }
    @Test fun rcCaptureAllowsManualFallbackWithoutCamera() {
        rule.waitUntil(10000) { rule.onAllNodesWithText("Continue locally").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Continue locally").performClick()
        rule.onNodeWithText("Garage").performClick()
        rule.onNodeWithText("Optional RC capture").performClick()
        rule.onNodeWithText("Open camera").assertExists()
        rule.onNodeWithText("No image captured.").assertExists()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Continue manually"))
        rule.onNodeWithText("Continue manually").performClick()
        rule.onNodeWithText("Vehicle nickname").assertExists()
    }
    @Test fun guardianPreviewDoesNotClaimActiveMonitoring() {
        rule.waitUntil(10000) { rule.onAllNodesWithText("Continue locally").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Continue locally").performClick()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Explore future features").performClick()
        rule.onNodeWithText("Explore Guardian").performClick()
        rule.onNodeWithText("FUTURE FEATURE • INTERACTIVE PREVIEW").assertExists()
        rule.onNodeWithText("Preview consent review").assertDoesNotExist()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Sharing consent"))
        rule.onNodeWithText("Sharing consent").performClick()
        rule.onNodeWithText("Preview consent review").performClick()
        rule.onNodeWithText("Selected: Preview consent review").assertExists()
    }
    @Test fun devicePreviewDisclosesUnverifiedCompatibility() {
        rule.waitUntil(10000) { rule.onAllNodesWithText("Continue locally").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Continue locally").performClick()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Explore future features").performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Explore Connected devices"))
        rule.onNodeWithText("Explore Connected devices").performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Compatibility"))
        rule.onNodeWithText("Compatibility").performClick()
        rule.onNodeWithText("Device unverified").performClick()
        rule.onNodeWithText("Selected: Device unverified").assertExists()
    }
    @Test fun garageAllowsAddingVehicle() {
        rule.onNodeWithText("Continue locally").performClick()
        rule.onNodeWithText("Manage vehicles").performClick()
        rule.onNodeWithText("Add vehicle").assertExists()
    }
}
