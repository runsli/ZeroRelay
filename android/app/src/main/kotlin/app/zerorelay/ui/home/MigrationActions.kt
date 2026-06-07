package app.zerorelay.ui.home

/**
 * Device migration guide and post-import checklist UI state.
 */
class MigrationActions(
    private val updateState: ((HomeUiState) -> HomeUiState) -> Unit,
) {
    fun showMigrationGuide(show: Boolean) = updateState {
        it.copy(showMigrationGuide = show, userError = if (show) null else it.userError)
    }

    fun dismissMigrationImportChecklist() = updateState {
        it.copy(showMigrationImportChecklist = false)
    }

    fun showMigrationImportChecklist() = updateState {
        it.copy(showMigrationImportChecklist = true)
    }
}
