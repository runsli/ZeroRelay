package app.zerorelay.ui.error

import app.zerorelay.data.error.DataError
import org.junit.Assert.assertEquals
import org.junit.Test

class UserErrorMappingTest {
    @Test
    fun mapsGroupInviteExpiredToGroupExpired() {
        val err = UserErrorMapping.fromThrowable(DataError.GroupInviteExpired)
        assertEquals(UserErrorKind.GroupExpired, err.kind)
    }

    @Test
    fun mapsAddSelfToAddContactFailed() {
        val err = UserErrorMapping.fromThrowable(DataError.AddSelfAsContact)
        assertEquals(UserErrorKind.AddContactFailed, err.kind)
    }

    @Test
    fun mapsBackupFormatToBackupRestoreFailed() {
        val err = UserErrorMapping.fromThrowable(DataError.BackupFormatUnsupported)
        assertEquals(UserErrorKind.BackupRestoreFailed, err.kind)
    }
}
