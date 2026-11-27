package com.bharathvishal.messagecommunicationusingwearabledatalayer

import org.junit.Assert.*
import org.junit.Test

class SettingsRulesTest {
    @Test fun professionDisplayNamesKeepExistingPreferenceNamespace() {
        assertEquals("profession_display_names", PROFESSION_NAMES_PREFERENCES)
        assertNull(professionNameError("Nurse", listOf("Assistant")))
        assertEquals(SettingsNameError.EMPTY, professionNameError("   ", emptyList()))
        assertEquals(SettingsNameError.DUPLICATE, professionNameError(" nurse ", listOf("Nurse")))
    }
    @Test fun activityNamesProtectCommandProtocol() {
        for (label in listOf("a,b", "a:b", "a\nb", "a\rb")) {
            assertEquals(SettingsNameError.DELIMITER, activityNameError(label, emptyList()))
        }
        assertEquals(SettingsNameError.EMPTY, activityNameError(" ", emptyList()))
        assertEquals(SettingsNameError.DUPLICATE, activityNameError("Rest break", listOf("Rest break")))
        assertNull(activityNameError("Toilette au lit", listOf("Rest break")))
        assertEquals("AS:6", ActivityLabels.key("AS", 6))
    }
}
