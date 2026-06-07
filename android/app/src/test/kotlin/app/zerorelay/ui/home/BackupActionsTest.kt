package app.zerorelay.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupActionsTest {
    @Test
    fun isPassphraseValid_requiresMinimumLength() {
        assertFalse(BackupActions.isPassphraseValid("short"))
        assertFalse(BackupActions.isPassphraseValid("1234567"))
        assertTrue(BackupActions.isPassphraseValid("12345678"))
        assertTrue(BackupActions.isPassphraseValid("long-passphrase"))
    }
}
