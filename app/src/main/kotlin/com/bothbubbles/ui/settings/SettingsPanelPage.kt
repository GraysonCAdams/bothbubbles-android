package com.bothbubbles.ui.settings

/**
 * Represents the current page within the settings panel.
 * Uses sealed class for exhaustive when expressions.
 */
sealed class SettingsPanelPage {
    /** Main settings menu */
    data object Main : SettingsPanelPage()

    /** Server settings (iMessage/BlueBubbles) */
    data object Server : SettingsPanelPage()

    /** Archived chats */
    data object Archived : SettingsPanelPage()

    /** Blocked contacts */
    data object Blocked : SettingsPanelPage()

    /** Spam protection settings */
    data object Spam : SettingsPanelPage()

    /** Message categorization */
    data object Categorization : SettingsPanelPage()

    /** Sync settings */
    data object Sync : SettingsPanelPage()

    /** Export messages */
    data object Export : SettingsPanelPage()

    /** SMS/MMS settings */
    data object Sms : SettingsPanelPage()

    /** SMS Backup (nested from SMS) */
    data object SmsBackup : SettingsPanelPage()

    /** Notification settings */
    data object Notifications : SettingsPanelPage()

    /** Swipe gesture settings */
    data object Swipe : SettingsPanelPage()

    /** Message effects settings */
    data object Effects : SettingsPanelPage()

    /** Image quality settings */
    data object ImageQuality : SettingsPanelPage()

    /** Quick reply templates */
    data object Templates : SettingsPanelPage()

    /** Auto-responder settings */
    data object AutoResponder : SettingsPanelPage()

    /** ETA sharing settings */
    data object EtaSharing : SettingsPanelPage()

    /** Life360 integration settings */
    data object Life360 : SettingsPanelPage()

    /** About screen */
    data object About : SettingsPanelPage()

    /** Open source licenses (nested from About) */
    data object OpenSourceLicenses : SettingsPanelPage()
}
