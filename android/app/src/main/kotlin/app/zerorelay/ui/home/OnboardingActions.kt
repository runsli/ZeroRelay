package app.zerorelay.ui.home

import app.zerorelay.data.local.UserPreferences

/**
 * First-run onboarding step flow.
 */
class OnboardingActions(
    private val prefs: UserPreferences,
    private val getState: () -> HomeUiState,
    private val updateState: ((HomeUiState) -> HomeUiState) -> Unit,
    private val withSetupFlags: (HomeUiState) -> HomeUiState,
) {
    fun skipOnboarding() {
        prefs.setOnboardingDismissed(true)
        updateState { withSetupFlags(it.copy(showOnboarding = false)) }
    }

    fun reopenOnboarding() {
        updateState {
            it.copy(
                showOnboarding = true,
                onboardingStep = HomeStateLogic.initialOnboardingStep(prefs),
                userError = null,
            )
        }
    }

    fun advanceOnboardingStep() {
        val next = when (getState().onboardingStep) {
            OnboardingStep.Server -> OnboardingStep.Identity
            OnboardingStep.Identity -> OnboardingStep.AddContact
            OnboardingStep.AddContact -> return
        }
        updateState { it.copy(onboardingStep = next, userError = null) }
    }

    fun finishOnboardingFromAddContact() {
        prefs.setOnboardingDismissed(true)
        updateState { withSetupFlags(it.copy(showOnboarding = false)) }
    }

    fun maybeFinishOnboardingAfterContact() {
        if (getState().showOnboarding && getState().contacts.isNotEmpty()) {
            finishOnboardingFromAddContact()
        } else if (getState().contacts.isNotEmpty()) {
            updateState { withSetupFlags(it) }
        }
    }
}
