/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.communal

import android.provider.Settings
import android.service.dreams.Flags.dreamTracksFocus
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.CoreStartable
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.shared.model.CommunalTransitionKeys
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dock.DockManager
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.util.kotlin.emitOnStart
import com.android.systemui.util.kotlin.sample
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import com.android.systemui.util.settings.SystemSettings
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A [CoreStartable] responsible for automatically navigating between communal scenes when certain
 * conditions are met.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalSceneStartable
@Inject
constructor(
    private val dockManager: DockManager,
    private val communalInteractor: CommunalInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val keyguardInteractor: KeyguardInteractor,
    private val systemSettings: SystemSettings,
    private val notificationShadeWindowController: NotificationShadeWindowController,
    @Application private val applicationScope: CoroutineScope,
    @Background private val bgScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
) : CoreStartable {
    private var screenTimeout: Int = DEFAULT_SCREEN_TIMEOUT

    private var timeoutJob: Job? = null

    private var isDreaming: Boolean = false

    override fun start() {
        // Handle automatically switching based on keyguard state.
        keyguardTransitionInteractor.startedKeyguardTransitionStep
            .mapLatest(::determineSceneAfterTransition)
            .filterNotNull()
            .onEach { nextScene ->
                communalInteractor.changeScene(nextScene, CommunalTransitionKeys.SimpleFade)
            }
            .launchIn(applicationScope)

        // TODO(b/322787129): re-enable once custom animations are in place
        // Handle automatically switching to communal when docked.
        //        dockManager
        //            .retrieveIsDocked()
        //            // Allow some time after docking to ensure the dream doesn't start. If the
        // dream
        //            // starts, then we don't want to automatically transition to glanceable hub.
        //            .debounce(DOCK_DEBOUNCE_DELAY)
        //            .sample(keyguardTransitionInteractor.startedKeyguardState, ::Pair)
        //            .onEach { (docked, lastStartedState) ->
        //                if (docked && lastStartedState == KeyguardState.LOCKSCREEN) {
        //                    communalInteractor.onSceneChanged(CommunalScenes.Communal)
        //                }
        //            }
        //            .launchIn(bgScope)

        systemSettings
            .observerFlow(Settings.System.SCREEN_OFF_TIMEOUT)
            // Read the setting value on start.
            .emitOnStart()
            .onEach {
                screenTimeout =
                    systemSettings.getInt(
                        Settings.System.SCREEN_OFF_TIMEOUT,
                        DEFAULT_SCREEN_TIMEOUT
                    )
            }
            .launchIn(bgScope)

        // The hub mode timeout should start as soon as the user enters hub mode. At the end of the
        // timer, if the device is dreaming, hub mode should closed and reveal the dream. If the
        // dream is not running, nothing will happen. However if the dream starts again underneath
        // hub mode after the initial timeout expires, such as if the device is docked or the dream
        // app is updated by the Play store, a new timeout should be started.
        bgScope.launch {
            combine(
                    communalInteractor.desiredScene,
                    // Emit a value on start so the combine starts.
                    communalInteractor.userActivity.emitOnStart()
                ) { scene, _ ->
                    // Only timeout if we're on the hub is open.
                    scene == CommunalScenes.Communal
                }
                .collectLatest { shouldTimeout ->
                    cancelHubTimeout()
                    if (shouldTimeout) {
                        startHubTimeout()
                    }
                }
        }
        bgScope.launch {
            keyguardInteractor.isDreaming
                .sample(communalInteractor.desiredScene, ::Pair)
                .collectLatest { (isDreaming, scene) ->
                    this@CommunalSceneStartable.isDreaming = isDreaming
                    if (scene == CommunalScenes.Communal && isDreaming && timeoutJob == null) {
                        // If dreaming starts after timeout has expired, ex. if dream restarts under
                        // the hub, just close the hub immediately.
                        communalInteractor.changeScene(CommunalScenes.Blank)
                    }
                }
        }

        if (dreamTracksFocus()) {
            bgScope.launch {
                communalInteractor.isIdleOnCommunal.collectLatest {
                    withContext(mainDispatcher) {
                        notificationShadeWindowController.setGlanceableHubShowing(it)
                    }
                }
            }
        }
    }

    private fun cancelHubTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private fun startHubTimeout() {
        if (timeoutJob == null) {
            timeoutJob =
                bgScope.launch {
                    delay(screenTimeout.milliseconds)
                    if (isDreaming) {
                        communalInteractor.changeScene(CommunalScenes.Blank)
                    }
                    timeoutJob = null
                }
        }
    }

    private suspend fun determineSceneAfterTransition(
        lastStartedTransition: TransitionStep,
    ): SceneKey? {
        val to = lastStartedTransition.to
        val from = lastStartedTransition.from
        val docked = dockManager.isDocked

        return when {
            to == KeyguardState.OCCLUDED -> {
                // Hide communal when an activity is started on keyguard, to ensure the activity
                // underneath the hub is shown.
                CommunalScenes.Blank
            }
            to == KeyguardState.GLANCEABLE_HUB && from == KeyguardState.OCCLUDED -> {
                // When transitioning to the hub from an occluded state, fade out the hub without
                // doing any translation.
                CommunalScenes.Communal
            }
            to == KeyguardState.GONE -> CommunalScenes.Blank
            !docked && !KeyguardState.deviceIsAwakeInState(to) -> {
                // If the user taps the screen and wakes the device within this timeout, we don't
                // want to dismiss the hub
                delay(AWAKE_DEBOUNCE_DELAY)
                CommunalScenes.Blank
            }
            else -> null
        }
    }

    companion object {
        val AWAKE_DEBOUNCE_DELAY = 5.seconds
        val DOCK_DEBOUNCE_DELAY = 1.seconds
        val DEFAULT_SCREEN_TIMEOUT = 15000
    }
}
