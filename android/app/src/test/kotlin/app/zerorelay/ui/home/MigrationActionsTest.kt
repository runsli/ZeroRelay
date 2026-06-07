package app.zerorelay.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationActionsTest {
    @Test
    fun showMigrationGuide_clearsUserErrorWhenOpening() {
        var state = HomeUiState(userError = app.zerorelay.ui.error.UserError(app.zerorelay.ui.error.UserErrorKind.Generic))
        val actions = MigrationActions { reducer -> state = reducer(state) }

        actions.showMigrationGuide(true)

        assertTrue(state.showMigrationGuide)
        assertTrue(state.userError == null)
    }

    @Test
    fun showMigrationGuide_preservesUserErrorWhenClosing() {
        val error = app.zerorelay.ui.error.UserError(app.zerorelay.ui.error.UserErrorKind.Generic)
        var state = HomeUiState(showMigrationGuide = true, userError = error)
        val actions = MigrationActions { reducer -> state = reducer(state) }

        actions.showMigrationGuide(false)

        assertFalse(state.showMigrationGuide)
        assertTrue(state.userError == error)
    }

    @Test
    fun showMigrationImportChecklist_setsFlag() {
        var state = HomeUiState()
        val actions = MigrationActions { reducer -> state = reducer(state) }

        actions.showMigrationImportChecklist()

        assertTrue(state.showMigrationImportChecklist)
    }

    @Test
    fun dismissMigrationImportChecklist_clearsFlag() {
        var state = HomeUiState(showMigrationImportChecklist = true)
        val actions = MigrationActions { reducer -> state = reducer(state) }

        actions.dismissMigrationImportChecklist()

        assertFalse(state.showMigrationImportChecklist)
    }
}
