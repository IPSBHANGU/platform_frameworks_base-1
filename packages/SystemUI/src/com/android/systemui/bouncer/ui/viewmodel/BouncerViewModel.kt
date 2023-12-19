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

package com.android.systemui.bouncer.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import com.android.internal.R
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.BouncerActionButtonInteractor
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.bouncer.domain.interactor.SimBouncerInteractor
import com.android.systemui.bouncer.shared.model.BouncerActionButtonModel
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.user.ui.viewmodel.UserActionViewModel
import com.android.systemui.user.ui.viewmodel.UserSwitcherViewModel
import com.android.systemui.user.ui.viewmodel.UserViewModel
import com.android.systemui.util.time.SystemClock
import dagger.Module
import dagger.Provides
import kotlin.math.ceil
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** Holds UI state and handles user input on bouncer UIs. */
class BouncerViewModel(
    @Application private val applicationContext: Context,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val bouncerInteractor: BouncerInteractor,
    private val authenticationInteractor: AuthenticationInteractor,
    flags: SceneContainerFlags,
    selectedUser: Flow<UserViewModel>,
    users: Flow<List<UserViewModel>>,
    userSwitcherMenu: Flow<List<UserActionViewModel>>,
    actionButtonInteractor: BouncerActionButtonInteractor,
    private val simBouncerInteractor: SimBouncerInteractor,
    private val clock: SystemClock,
) {
    val selectedUserImage: StateFlow<Bitmap?> =
        selectedUser
            .map { it.image.toBitmap() }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null,
            )

    val userSwitcherDropdown: StateFlow<List<UserSwitcherDropdownItemViewModel>> =
        combine(
                users,
                userSwitcherMenu,
            ) { users, actions ->
                users.map { user ->
                    UserSwitcherDropdownItemViewModel(
                        icon = Icon.Loaded(user.image, contentDescription = null),
                        text = user.name,
                        onClick = user.onClicked ?: {},
                    )
                } +
                    actions.map { action ->
                        UserSwitcherDropdownItemViewModel(
                            icon = Icon.Resource(action.iconResourceId, contentDescription = null),
                            text = Text.Resource(action.textResourceId),
                            onClick = action.onClicked,
                        )
                    }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = emptyList(),
            )

    val isUserSwitcherVisible: Boolean
        get() = bouncerInteractor.isUserSwitcherVisible

    // Handle to the scope of the child ViewModel (stored in [authMethod]).
    private var childViewModelScope: CoroutineScope? = null
    private val _dialogMessage = MutableStateFlow<String?>(null)

    /** View-model for the current UI, based on the current authentication method. */
    val authMethodViewModel: StateFlow<AuthMethodBouncerViewModel?> =
        authenticationInteractor.authenticationMethod
            .map(::getChildViewModel)
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null,
            )

    /**
     * A message for a dialog to show when the user has attempted the wrong credential too many
     * times and now must wait a while before attempting again.
     *
     * If `null`, no dialog should be shown.
     *
     * Once the dialog is shown, the UI should call [onDialogDismissed] when the user dismisses this
     * dialog.
     */
    val dialogMessage: StateFlow<String?> = _dialogMessage.asStateFlow()

    /**
     * A message shown when the user has attempted the wrong credential too many times and now must
     * wait a while before attempting to authenticate again.
     *
     * This is updated every second (countdown) during the lockout duration. When lockout is not
     * active, this is `null` and no lockout message should be shown.
     */
    private val lockoutMessage = MutableStateFlow<String?>(null)

    /** The user-facing message to show in the bouncer. */
    val message: StateFlow<MessageViewModel> =
        combine(bouncerInteractor.message, lockoutMessage) { _, _ -> createMessageViewModel() }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = createMessageViewModel(),
            )

    /**
     * The bouncer action button (Return to Call / Emergency Call). If `null`, the button should not
     * be shown.
     */
    val actionButton: StateFlow<BouncerActionButtonModel?> =
        actionButtonInteractor.actionButton.stateIn(
            scope = applicationScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = null
        )

    /**
     * Whether the "side-by-side" layout is supported.
     *
     * When presented on its own, without a user switcher (e.g. not on communal devices like
     * tablets, for example), some authentication method UIs don't do well if they're shown in the
     * side-by-side layout; these need to be shown with the standard layout so they can take up as
     * much width as possible.
     */
    val isSideBySideSupported: StateFlow<Boolean> =
        authMethodViewModel
            .map { authMethod -> isSideBySideSupported(authMethod) }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = isSideBySideSupported(authMethodViewModel.value),
            )

    /**
     * Whether the splitting the UI around the fold seam (where the hinge is on a foldable device)
     * is required.
     */
    val isFoldSplitRequired: StateFlow<Boolean> =
        authMethodViewModel
            .map { authMethod -> isFoldSplitRequired(authMethod) }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = isFoldSplitRequired(authMethodViewModel.value),
            )

    private val isInputEnabled: StateFlow<Boolean> =
        lockoutMessage
            .map { it == null }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = authenticationInteractor.lockoutEndTimestamp == null,
            )

    private var lockoutCountdownJob: Job? = null

    init {
        if (flags.isEnabled()) {
            applicationScope.launch {
                bouncerInteractor.onLockoutStarted.collect {
                    showLockoutDialog()
                    startLockoutCountdown()
                }
            }

            applicationScope.launch {
                // Update the lockout countdown whenever the selected user is switched.
                selectedUser.collect { startLockoutCountdown() }
            }
        }
    }

    /** Notifies that the dialog has been dismissed by the user. */
    fun onDialogDismissed() {
        _dialogMessage.value = null
    }

    private fun showLockoutDialog() {
        applicationScope.launch {
            val failedAttempts = authenticationInteractor.failedAuthenticationAttempts.value
            _dialogMessage.value =
                authMethodViewModel.value?.lockoutMessageId?.let { messageId ->
                    applicationContext.getString(
                        messageId,
                        failedAttempts,
                        remainingLockoutSeconds()
                    )
                }
        }
    }

    /** Shows the countdown message and refreshes it every second. */
    private fun startLockoutCountdown() {
        lockoutCountdownJob?.cancel()
        lockoutCountdownJob =
            applicationScope.launch {
                do {
                    val remainingSeconds = remainingLockoutSeconds()
                    lockoutMessage.value =
                        if (remainingSeconds > 0) {
                            applicationContext.getString(
                                R.string.lockscreen_too_many_failed_attempts_countdown,
                                remainingSeconds,
                            )
                        } else {
                            null
                        }
                    delay(1.seconds)
                } while (remainingSeconds > 0)
                lockoutCountdownJob = null
            }
    }

    private fun remainingLockoutSeconds(): Int {
        val endTimestampMs = authenticationInteractor.lockoutEndTimestamp ?: 0
        val remainingMs = max(0, endTimestampMs - clock.elapsedRealtime())
        return ceil(remainingMs / 1000f).toInt()
    }

    private fun isSideBySideSupported(authMethod: AuthMethodBouncerViewModel?): Boolean {
        return isUserSwitcherVisible || authMethod !is PasswordBouncerViewModel
    }

    private fun isFoldSplitRequired(authMethod: AuthMethodBouncerViewModel?): Boolean {
        return authMethod !is PasswordBouncerViewModel
    }

    private fun createMessageViewModel(): MessageViewModel {
        val isLockedOut = lockoutMessage.value != null
        return MessageViewModel(
            // A lockout message takes precedence over the non-lockout message.
            text = lockoutMessage.value ?: bouncerInteractor.message.value ?: "",
            isUpdateAnimated = !isLockedOut,
        )
    }

    private fun getChildViewModel(
        authenticationMethod: AuthenticationMethodModel,
    ): AuthMethodBouncerViewModel? {
        // If the current child view-model matches the authentication method, reuse it instead of
        // creating a new instance.
        val childViewModel = authMethodViewModel.value
        if (authenticationMethod == childViewModel?.authenticationMethod) {
            return childViewModel
        }

        childViewModelScope?.cancel()
        val newViewModelScope = createChildCoroutineScope(applicationScope)
        childViewModelScope = newViewModelScope
        return when (authenticationMethod) {
            is AuthenticationMethodModel.Pin ->
                PinBouncerViewModel(
                    applicationContext = applicationContext,
                    viewModelScope = newViewModelScope,
                    interactor = bouncerInteractor,
                    isInputEnabled = isInputEnabled,
                    simBouncerInteractor = simBouncerInteractor,
                    authenticationMethod = authenticationMethod
                )
            is AuthenticationMethodModel.Sim ->
                PinBouncerViewModel(
                    applicationContext = applicationContext,
                    viewModelScope = newViewModelScope,
                    interactor = bouncerInteractor,
                    isInputEnabled = isInputEnabled,
                    simBouncerInteractor = simBouncerInteractor,
                    authenticationMethod = authenticationMethod,
                )
            is AuthenticationMethodModel.Password ->
                PasswordBouncerViewModel(
                    viewModelScope = newViewModelScope,
                    interactor = bouncerInteractor,
                    isInputEnabled = isInputEnabled,
                )
            is AuthenticationMethodModel.Pattern ->
                PatternBouncerViewModel(
                    applicationContext = applicationContext,
                    viewModelScope = newViewModelScope,
                    interactor = bouncerInteractor,
                    isInputEnabled = isInputEnabled,
                )
            else -> null
        }
    }

    private fun createChildCoroutineScope(parentScope: CoroutineScope): CoroutineScope {
        return CoroutineScope(
            SupervisorJob(parent = parentScope.coroutineContext.job) + mainDispatcher
        )
    }

    data class MessageViewModel(
        val text: String,

        /**
         * Whether updates to the message should be cross-animated from one message to another.
         *
         * If `false`, no animation should be applied, the message text should just be replaced
         * instantly.
         */
        val isUpdateAnimated: Boolean,
    )

    data class UserSwitcherDropdownItemViewModel(
        val icon: Icon,
        val text: Text,
        val onClick: () -> Unit,
    )
}

@Module
object BouncerViewModelModule {

    @Provides
    @SysUISingleton
    fun viewModel(
        @Application applicationContext: Context,
        @Application applicationScope: CoroutineScope,
        @Main mainDispatcher: CoroutineDispatcher,
        bouncerInteractor: BouncerInteractor,
        authenticationInteractor: AuthenticationInteractor,
        flags: SceneContainerFlags,
        userSwitcherViewModel: UserSwitcherViewModel,
        actionButtonInteractor: BouncerActionButtonInteractor,
        simBouncerInteractor: SimBouncerInteractor,
        clock: SystemClock,
    ): BouncerViewModel {
        return BouncerViewModel(
            applicationContext = applicationContext,
            applicationScope = applicationScope,
            mainDispatcher = mainDispatcher,
            bouncerInteractor = bouncerInteractor,
            authenticationInteractor = authenticationInteractor,
            flags = flags,
            selectedUser = userSwitcherViewModel.selectedUser,
            users = userSwitcherViewModel.users,
            userSwitcherMenu = userSwitcherViewModel.menu,
            actionButtonInteractor = actionButtonInteractor,
            simBouncerInteractor = simBouncerInteractor,
            clock = clock,
        )
    }
}
