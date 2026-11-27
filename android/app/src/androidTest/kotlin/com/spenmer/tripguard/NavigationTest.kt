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
        rule.onNodeWithText("Clear all preview data").performClick()
        rule.onNodeWithText("Clear this session?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Clear all preview data").assertExists()
    }
    @Test fun capabilitiesDiscloseMissingMonitoring() {
        rule.onNodeWithText("Continue locally").performClick()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("View capabilities").performClick()
        rule.onNodeWithText("Not available").assertExists()
    }
    @Test fun garageStartsEmpty() {
        rule.onNodeWithText("Continue locally").performClick()
        rule.onNodeWithText("Manage vehicles").performClick()
        rule.onNodeWithText("No vehicles yet. Add a motorcycle, car or commercial vehicle.").assertExists()
    }
}
