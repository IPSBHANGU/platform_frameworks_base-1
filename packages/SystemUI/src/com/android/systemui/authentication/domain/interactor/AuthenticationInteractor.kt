/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.authentication.domain.interactor

import com.android.internal.widget.LockPatternView
import com.android.internal.widget.LockscreenCredential
import com.android.systemui.authentication.data.repository.AuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.authentication.shared.model.AuthenticationPatternCoordinate
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Hosts application business logic related to user authentication.
 *
 * Note: there is a distinction between authentication (determining a user's identity) and device
 * entry (dismissing the lockscreen). For logic that is specific to device entry, please use
 * `DeviceEntryInteractor` instead.
 */
@SysUISingleton
class AuthenticationInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val repository: AuthenticationRepository,
) {
    /**
     * The currently-configured authentication method. This determines how the authentication
     * challenge needs to be completed in order to unlock an otherwise locked device.
     *
     * Note: there may be other ways to unlock the device that "bypass" the need for this
     * authentication challenge (notably, biometrics like fingerprint or face unlock).
     *
     * Note: by design, this is a [Flow] and not a [StateFlow]; a consumer who wishes to get a
     * snapshot of the current authentication method without establishing a collector of the flow
     * can do so by invoking [getAuthenticationMethod].
     *
     * Note: this layer adds the synthetic authentication method of "swipe" which is special. When
     * the current authentication method is "swipe", the user does not need to complete any
     * authentication challenge to unlock the device; they just need to dismiss the lockscreen to
     * get past it. This also means that the value of `DeviceEntryInteractor#isUnlocked` remains
     * `true` even when the lockscreen is showing and still needs to be dismissed by the user to
     * proceed.
     */
    val authenticationMethod: Flow<AuthenticationMethodModel> = repository.authenticationMethod

    /**
     * Whether the auto confirm feature is enabled for the currently-selected user.
     *
     * Note that the length of the PIN is also important to take into consideration, please see
     * [hintedPinLength].
     */
    val isAutoConfirmEnabled: StateFlow<Boolean> =
        combine(repository.isAutoConfirmFeatureEnabled, repository.hasLockoutOccurred) {
                featureEnabled,
                hasLockoutOccurred ->
                // Disable auto-confirm if lockout occurred since the last successful
                // authentication attempt.
                featureEnabled && !hasLockoutOccurred
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /** The length of the hinted PIN, or `null` if pin length hint should not be shown. */
    val hintedPinLength: StateFlow<Int?> =
        isAutoConfirmEnabled
            .map { isAutoConfirmEnabled ->
                repository.getPinLength().takeIf {
                    isAutoConfirmEnabled && it == repository.hintedPinLength
                }
            }
            .stateIn(
                scope = applicationScope,
                // Make sure this is kept as WhileSubscribed or we can run into a bug where the
                // downstream continues to receive old/stale/cached values.
                started = SharingStarted.WhileSubscribed(),
                initialValue = null,
            )

    /** Whether the pattern should be visible for the currently-selected user. */
    val isPatternVisible: StateFlow<Boolean> = repository.isPatternVisible

    private val _onAuthenticationResult = MutableSharedFlow<Boolean>()
    /**
     * Emits the outcome (successful or unsuccessful) whenever a PIN/Pattern/Password security
     * challenge is attempted by the user in order to unlock the device.
     */
    val onAuthenticationResult: SharedFlow<Boolean> = _onAuthenticationResult.asSharedFlow()

    /** Whether the "enhanced PIN privacy" setting is enabled for the current user. */
    val isPinEnhancedPrivacyEnabled: StateFlow<Boolean> = repository.isPinEnhancedPrivacyEnabled

    /**
     * The number of failed authentication attempts for the selected user since the last successful
     * authentication.
     */
    val failedAuthenticationAttempts: StateFlow<Int> = repository.failedAuthenticationAttempts

    /**
     * Timestamp for when the current lockout (aka "throttling") will end, allowing the user to
     * attempt authentication again. Returns `null` if no lockout is active.
     *
     * To be notified whenever a lockout is started, the caller should subscribe to
     * [onAuthenticationResult].
     *
     * Note that the value is in milliseconds and matches [SystemClock.elapsedRealtime].
     *
     * Also note that the value may change when the selected user is changed.
     */
    val lockoutEndTimestamp: Long?
        get() = repository.lockoutEndTimestamp

    /**
     * Returns the currently-configured authentication method. This determines how the
     * authentication challenge needs to be completed in order to unlock an otherwise locked device.
     *
     * Note: there may be other ways to unlock the device that "bypass" the need for this
     * authentication challenge (notably, biometrics like fingerprint or face unlock).
     *
     * Note: by design, this is offered as a convenience method alongside [authenticationMethod].
     * The flow should be used for code that wishes to stay up-to-date its logic as the
     * authentication changes over time and this method should be used for simple code that only
     * needs to check the current value.
     */
    suspend fun getAuthenticationMethod() = repository.getAuthenticationMethod()

    /**
     * Attempts to authenticate the user and unlock the device.
     *
     * If [tryAutoConfirm] is `true`, authentication is attempted if and only if the auth method
     * supports auto-confirming, and the input's length is at least the required length. Otherwise,
     * `AuthenticationResult.SKIPPED` is returned.
     *
     * @param input The input from the user to try to authenticate with. This can be a list of
     *   different things, based on the current authentication method.
     * @param tryAutoConfirm `true` if called while the user inputs the code, without an explicit
     *   request to validate.
     * @return The result of this authentication attempt.
     */
    suspend fun authenticate(
        input: List<Any>,
        tryAutoConfirm: Boolean = false
    ): AuthenticationResult {
        if (input.isEmpty()) {
            throw IllegalArgumentException("Input was empty!")
        }

        val authMethod = getAuthenticationMethod()
        val skipCheck =
            when {
                // Lockout is active, the UI layer should not have called this; skip the attempt.
                repository.lockoutEndTimestamp != null -> true
                // The input is too short; skip the attempt.
                input.isTooShort(authMethod) -> true
                // Auto-confirm attempt when the feature is not enabled; skip the attempt.
                tryAutoConfirm && !isAutoConfirmEnabled.value -> true
                // Auto-confirm should skip the attempt if the pin entered is too short.
                tryAutoConfirm &&
                    authMethod == AuthenticationMethodModel.Pin &&
                    input.size < repository.getPinLength() -> true
                else -> false
            }
        if (skipCheck) {
            return AuthenticationResult.SKIPPED
        }

        // Attempt to authenticate:
        val credential = authMethod.createCredential(input) ?: return AuthenticationResult.SKIPPED
        val authenticationResult = repository.checkCredential(credential)
        credential.zeroize()

        if (authenticationResult.isSuccessful || !tryAutoConfirm) {
            repository.reportAuthenticationAttempt(authenticationResult.isSuccessful)
        }

        // Check if lockout should start and, if so, kick off the countdown:
        if (!authenticationResult.isSuccessful && authenticationResult.lockoutDurationMs > 0) {
            repository.reportLockoutStarted(authenticationResult.lockoutDurationMs)
        }

        if (authenticationResult.isSuccessful || !tryAutoConfirm) {
            _onAuthenticationResult.emit(authenticationResult.isSuccessful)
        }

        return if (authenticationResult.isSuccessful) {
            AuthenticationResult.SUCCEEDED
        } else {
            AuthenticationResult.FAILED
        }
    }

    private fun List<Any>.isTooShort(authMethod: AuthenticationMethodModel): Boolean {
        return when (authMethod) {
            AuthenticationMethodModel.Pattern -> size < repository.minPatternLength
            AuthenticationMethodModel.Password -> size < repository.minPasswordLength
            else -> false
        }
    }

    private fun AuthenticationMethodModel.createCredential(
        input: List<Any>
    ): LockscreenCredential? {
        return when (this) {
            is AuthenticationMethodModel.Pin ->
                LockscreenCredential.createPin(input.joinToString(""))
            is AuthenticationMethodModel.Password ->
                LockscreenCredential.createPassword(input.joinToString(""))
            is AuthenticationMethodModel.Pattern ->
                LockscreenCredential.createPattern(
                    input
                        .map { it as AuthenticationPatternCoordinate }
                        .map { LockPatternView.Cell.of(it.y, it.x) }
                )
            else -> null
        }
    }

    companion object {
        const val TAG = "AuthenticationInteractor"
    }
}

/** Result of a user authentication attempt. */
enum class AuthenticationResult {
    /** Authentication succeeded. */
    SUCCEEDED,
    /** Authentication failed. */
    FAILED,
    /** Authentication was not performed, e.g. due to insufficient input. */
    SKIPPED,
}
