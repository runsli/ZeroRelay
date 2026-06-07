package app.zerorelay.ui.error

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Relay and other app-wide user-facing errors (banner, not snackbar). */
object UserErrorBus {
    private val _errors = MutableSharedFlow<UserError>(extraBufferCapacity = 8)
    val errors: SharedFlow<UserError> = _errors.asSharedFlow()

    fun show(error: UserError) {
        _errors.tryEmit(error)
    }
}
