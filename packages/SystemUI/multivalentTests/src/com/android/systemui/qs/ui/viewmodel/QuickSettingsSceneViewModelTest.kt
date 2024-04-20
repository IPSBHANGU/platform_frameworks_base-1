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

package com.android.systemui.qs.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.Back
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.FooterActionsController
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel
import com.android.systemui.qs.ui.adapter.FakeQSSceneAdapter
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.brightness.ui.viewmodel.brightnessMirrorViewModel
import com.android.systemui.shade.ui.viewmodel.shadeHeaderViewModel
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.notificationsPlaceholderViewModel
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class QuickSettingsSceneViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val qsFlexiglassAdapter = FakeQSSceneAdapter({ mock() })
    private val footerActionsViewModel = mock<FooterActionsViewModel>()
    private val footerActionsViewModelFactory =
        mock<FooterActionsViewModel.Factory> {
            whenever(create(any())).thenReturn(footerActionsViewModel)
        }
    private val footerActionsController = mock<FooterActionsController>()

    private val sceneInteractor = kosmos.sceneInteractor

    private lateinit var underTest: QuickSettingsSceneViewModel

    @Before
    fun setUp() {
        kosmos.fakeFeatureFlagsClassic.set(Flags.NEW_NETWORK_SLICE_UI, false)

        underTest =
            QuickSettingsSceneViewModel(
                brightnessMirrorViewModel = kosmos.brightnessMirrorViewModel,
                shadeHeaderViewModel = kosmos.shadeHeaderViewModel,
                qsSceneAdapter = qsFlexiglassAdapter,
                notifications = kosmos.notificationsPlaceholderViewModel,
                footerActionsViewModelFactory = footerActionsViewModelFactory,
                footerActionsController = footerActionsController,
                sceneInteractor = sceneInteractor,
            )
    }

    @Test
    fun destinations_whenNotCustomizing() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, false)
            val destinations by collectLastValue(underTest.destinationScenes)
            qsFlexiglassAdapter.setCustomizing(false)

            assertThat(destinations)
                .isEqualTo(
                    mapOf(
                        Back to UserActionResult(Scenes.Shade),
                        Swipe(SwipeDirection.Up) to UserActionResult(Scenes.Shade),
                    )
                )
        }

    @Test
    fun destinations_whenNotCustomizing_withPreviousSceneLockscreen() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, false)
            qsFlexiglassAdapter.setCustomizing(false)
            val destinations by collectLastValue(underTest.destinationScenes)

            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val previousScene by collectLastValue(sceneInteractor.previousScene())
            sceneInteractor.changeScene(Scenes.Lockscreen, "reason")
            sceneInteractor.changeScene(Scenes.QuickSettings, "reason")
            assertThat(currentScene).isEqualTo(Scenes.QuickSettings)
            assertThat(previousScene).isEqualTo(Scenes.Lockscreen)

            assertThat(destinations)
                .isEqualTo(
                    mapOf(
                        Back to UserActionResult(Scenes.Lockscreen),
                        Swipe(SwipeDirection.Up) to UserActionResult(Scenes.Lockscreen),
                    )
                )
        }

    @Test
    fun destinations_whenCustomizing_noDestinations() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, false)
            val destinations by collectLastValue(underTest.destinationScenes)
            qsFlexiglassAdapter.setCustomizing(true)

            assertThat(destinations).isEmpty()
        }

    @Test
    fun destinations_whenNotCustomizing_inSplitShade() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, true)
            val destinations by collectLastValue(underTest.destinationScenes)
            qsFlexiglassAdapter.setCustomizing(false)

            assertThat(destinations)
                .isEqualTo(
                    mapOf(
                        Back to UserActionResult(Scenes.Shade),
                        Swipe(SwipeDirection.Up) to UserActionResult(Scenes.Shade),
                    )
                )
        }

    @Test
    fun destinations_whenCustomizing_inSplitShade_noDestinations() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, true)
            val destinations by collectLastValue(underTest.destinationScenes)
            qsFlexiglassAdapter.setCustomizing(true)

            assertThat(destinations).isEmpty()
        }

    @Test
    fun gettingViewModelInitializesControllerOnlyOnce() {
        underTest.getFooterActionsViewModel(mock())
        underTest.getFooterActionsViewModel(mock())

        verify(footerActionsController, times(1)).init()
    }
}
