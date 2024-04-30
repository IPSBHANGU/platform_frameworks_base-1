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

package com.android.systemui.communal.view.viewmodel

import android.app.smartspace.SmartspaceTarget
import android.appwidget.AppWidgetProviderInfo
import android.content.pm.UserInfo
import android.os.UserHandle
import android.platform.test.flag.junit.FlagsParameterization
import android.provider.Settings
import android.widget.RemoteViews
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.Flags.FLAG_COMMUNAL_HUB
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.FakeCommunalMediaRepository
import com.android.systemui.communal.data.repository.FakeCommunalRepository
import com.android.systemui.communal.data.repository.FakeCommunalTutorialRepository
import com.android.systemui.communal.data.repository.FakeCommunalWidgetRepository
import com.android.systemui.communal.data.repository.fakeCommunalMediaRepository
import com.android.systemui.communal.data.repository.fakeCommunalRepository
import com.android.systemui.communal.data.repository.fakeCommunalTutorialRepository
import com.android.systemui.communal.data.repository.fakeCommunalWidgetRepository
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.domain.interactor.communalTutorialInteractor
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel.Companion.POPUP_AUTO_HIDE_TIMEOUT_MS
import com.android.systemui.communal.ui.viewmodel.PopupType
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.flags.Flags.COMMUNAL_SERVICE_ENABLED
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.shade.ShadeTestUtil
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.smartspace.data.repository.FakeSmartspaceRepository
import com.android.systemui.smartspace.data.repository.fakeSmartspaceRepository
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class CommunalViewModelTest(flags: FlagsParameterization?) : SysuiTestCase() {
    @Mock private lateinit var mediaHost: MediaHost
    @Mock private lateinit var user: UserInfo
    @Mock private lateinit var providerInfo: AppWidgetProviderInfo

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var tutorialRepository: FakeCommunalTutorialRepository
    private lateinit var widgetRepository: FakeCommunalWidgetRepository
    private lateinit var smartspaceRepository: FakeSmartspaceRepository
    private lateinit var mediaRepository: FakeCommunalMediaRepository
    private lateinit var userRepository: FakeUserRepository
    private lateinit var shadeTestUtil: ShadeTestUtil
    private lateinit var keyguardTransitionRepository: FakeKeyguardTransitionRepository
    private lateinit var communalRepository: FakeCommunalRepository

    private lateinit var underTest: CommunalViewModel

    init {
        mSetFlagsRule.setFlagsParameterization(flags!!)
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        keyguardRepository = kosmos.fakeKeyguardRepository
        keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
        tutorialRepository = kosmos.fakeCommunalTutorialRepository
        widgetRepository = kosmos.fakeCommunalWidgetRepository
        smartspaceRepository = kosmos.fakeSmartspaceRepository
        mediaRepository = kosmos.fakeCommunalMediaRepository
        userRepository = kosmos.fakeUserRepository
        shadeTestUtil = kosmos.shadeTestUtil
        communalRepository = kosmos.fakeCommunalRepository

        kosmos.fakeFeatureFlagsClassic.set(COMMUNAL_SERVICE_ENABLED, true)
        mSetFlagsRule.enableFlags(FLAG_COMMUNAL_HUB)

        kosmos.fakeUserTracker.set(
            userInfos = listOf(MAIN_USER_INFO),
            selectedUserIndex = 0,
        )
        whenever(providerInfo.profile).thenReturn(UserHandle(MAIN_USER_INFO.id))

        underTest =
            CommunalViewModel(
                testScope,
                kosmos.keyguardTransitionInteractor,
                kosmos.communalInteractor,
                kosmos.communalTutorialInteractor,
                kosmos.shadeInteractor,
                kosmos.deviceEntryInteractor,
                mediaHost,
                logcatLogBuffer("CommunalViewModelTest"),
            )
    }

    @Test
    fun init_initsMediaHost() =
        testScope.runTest {
            // MediaHost is initialized as soon as the class is created.
            verify(mediaHost).init(MediaHierarchyManager.LOCATION_COMMUNAL_HUB)
        }

    @Test
    fun tutorial_tutorialNotCompletedAndKeyguardVisible_showTutorialContent() =
        testScope.runTest {
            // Keyguard showing, storage unlocked, main user, and tutorial not started.
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            keyguardRepository.setIsEncryptedOrLockdown(false)
            setIsMainUser(true)
            tutorialRepository.setTutorialSettingState(
                Settings.Secure.HUB_MODE_TUTORIAL_NOT_STARTED
            )

            val communalContent by collectLastValue(underTest.communalContent)

            assertThat(communalContent!!).isNotEmpty()
            communalContent!!.forEach { model ->
                assertThat(model is CommunalContentModel.Tutorial).isTrue()
            }
        }

    @Test
    fun ordering_smartspaceBeforeUmoBeforeWidgetsBeforeCtaTile() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)

            // Widgets available.
            val widgets =
                listOf(
                    CommunalWidgetContentModel(
                        appWidgetId = 0,
                        priority = 30,
                        providerInfo = providerInfo,
                    ),
                    CommunalWidgetContentModel(
                        appWidgetId = 1,
                        priority = 20,
                        providerInfo = providerInfo,
                    ),
                )
            widgetRepository.setCommunalWidgets(widgets)

            // Smartspace available.
            val target = Mockito.mock(SmartspaceTarget::class.java)
            whenever(target.smartspaceTargetId).thenReturn("target")
            whenever(target.featureType).thenReturn(SmartspaceTarget.FEATURE_TIMER)
            whenever(target.remoteViews).thenReturn(Mockito.mock(RemoteViews::class.java))
            smartspaceRepository.setCommunalSmartspaceTargets(listOf(target))

            // Media playing.
            mediaRepository.mediaActive()

            val communalContent by collectLastValue(underTest.communalContent)

            // Order is smart space, then UMO, widget content and cta tile.
            assertThat(communalContent?.size).isEqualTo(5)
            assertThat(communalContent?.get(0))
                .isInstanceOf(CommunalContentModel.Smartspace::class.java)
            assertThat(communalContent?.get(1)).isInstanceOf(CommunalContentModel.Umo::class.java)
            assertThat(communalContent?.get(2))
                .isInstanceOf(CommunalContentModel.WidgetContent::class.java)
            assertThat(communalContent?.get(3))
                .isInstanceOf(CommunalContentModel.WidgetContent::class.java)
            assertThat(communalContent?.get(4))
                .isInstanceOf(CommunalContentModel.CtaTileInViewMode::class.java)
        }

    @Test
    fun isEmptyState_isTrue_noWidgetButActiveLiveContent() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)

            widgetRepository.setCommunalWidgets(emptyList())
            // UMO playing
            mediaRepository.mediaActive()
            smartspaceRepository.setCommunalSmartspaceTargets(emptyList())

            val isEmptyState by collectLastValue(underTest.isEmptyState)
            assertThat(isEmptyState).isTrue()
        }

    @Test
    fun isEmptyState_isFalse_withWidgets() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)

            widgetRepository.setCommunalWidgets(
                listOf(
                    CommunalWidgetContentModel(
                        appWidgetId = 1,
                        priority = 1,
                        providerInfo = providerInfo,
                    )
                ),
            )
            mediaRepository.mediaInactive()
            smartspaceRepository.setCommunalSmartspaceTargets(emptyList())

            val isEmptyState by collectLastValue(underTest.isEmptyState)
            assertThat(isEmptyState).isFalse()
        }

    @Test
    fun dismissCta_hidesCtaTileAndShowsPopup_thenHidesPopupAfterTimeout() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)

            val communalContent by collectLastValue(underTest.communalContent)
            val currentPopup by collectLastValue(underTest.currentPopup)

            assertThat(communalContent?.size).isEqualTo(1)
            assertThat(communalContent?.get(0))
                .isInstanceOf(CommunalContentModel.CtaTileInViewMode::class.java)

            underTest.onDismissCtaTile()

            // hide CTA tile and show the popup
            assertThat(communalContent).isEmpty()
            assertThat(currentPopup).isEqualTo(PopupType.CtaTile)

            // hide popup after time elapsed
            advanceTimeBy(POPUP_AUTO_HIDE_TIMEOUT_MS)
            assertThat(currentPopup).isNull()
        }

    @Test
    fun popup_onDismiss_hidesImmediately() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)

            val currentPopup by collectLastValue(underTest.currentPopup)

            underTest.onDismissCtaTile()
            assertThat(currentPopup).isEqualTo(PopupType.CtaTile)

            // dismiss the popup directly
            underTest.onHidePopup()
            assertThat(currentPopup).isNull()
        }

    @Test
    fun customizeWidgetButton_showsThenHidesAfterTimeout() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)
            val currentPopup by collectLastValue(underTest.currentPopup)

            assertThat(currentPopup).isNull()
            underTest.onShowCustomizeWidgetButton()
            assertThat(currentPopup).isEqualTo(PopupType.CustomizeWidgetButton)
            advanceTimeBy(POPUP_AUTO_HIDE_TIMEOUT_MS)
            assertThat(currentPopup).isNull()
        }

    @Test
    fun customizeWidgetButton_onDismiss_hidesImmediately() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)
            val currentPopup by collectLastValue(underTest.currentPopup)

            underTest.onShowCustomizeWidgetButton()
            assertThat(currentPopup).isEqualTo(PopupType.CustomizeWidgetButton)

            underTest.onHidePopup()
            assertThat(currentPopup).isNull()
        }

    @Test
    fun canChangeScene_shadeNotExpanded() =
        testScope.runTest {
            // On keyguard without any shade expansion.
            kosmos.fakeKeyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            shadeTestUtil.setLockscreenShadeExpansion(0f)
            runCurrent()
            assertThat(underTest.canChangeScene()).isTrue()
        }

    @Test
    fun canChangeScene_shadeExpanded() =
        testScope.runTest {
            // On keyguard with shade fully expanded.
            kosmos.fakeKeyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            shadeTestUtil.setLockscreenShadeExpansion(1f)
            runCurrent()
            assertThat(underTest.canChangeScene()).isFalse()
        }

    @Test
    fun touchesAllowed_shadeNotExpanded() =
        testScope.runTest {
            val touchesAllowed by collectLastValue(underTest.touchesAllowed)

            // On keyguard without any shade expansion.
            kosmos.fakeKeyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            shadeTestUtil.setLockscreenShadeExpansion(0f)
            runCurrent()
            assertThat(touchesAllowed).isTrue()
        }

    @Test
    fun touchesAllowed_shadeExpanded() =
        testScope.runTest {
            val touchesAllowed by collectLastValue(underTest.touchesAllowed)

            // On keyguard with shade fully expanded.
            kosmos.fakeKeyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            shadeTestUtil.setLockscreenShadeExpansion(1f)
            runCurrent()
            assertThat(touchesAllowed).isFalse()
        }

    @Test
    fun isFocusable_isFalse_whenTransitioningAwayFromGlanceableHub() =
        testScope.runTest {
            val isFocusable by collectLastValue(underTest.isFocusable)

            // Shade not expanded.
            shadeTestUtil.setLockscreenShadeExpansion(0f)
            // On communal scene.
            communalRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(CommunalScenes.Communal))
            )
            // Open bouncer.
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GLANCEABLE_HUB,
                    to = KeyguardState.PRIMARY_BOUNCER,
                    transitionState = TransitionState.STARTED,
                )
            )

            keyguardTransitionRepository.sendTransitionStep(
                from = KeyguardState.GLANCEABLE_HUB,
                to = KeyguardState.PRIMARY_BOUNCER,
                transitionState = TransitionState.RUNNING,
                value = 0.5f,
            )
            assertThat(isFocusable).isEqualTo(false)

            // Transitioned to bouncer.
            keyguardTransitionRepository.sendTransitionStep(
                from = KeyguardState.GLANCEABLE_HUB,
                to = KeyguardState.PRIMARY_BOUNCER,
                transitionState = TransitionState.FINISHED,
                value = 1f,
            )
            assertThat(isFocusable).isEqualTo(false)
        }

    @Test
    fun isFocusable_isFalse_whenNotOnCommunalScene() =
        testScope.runTest {
            val isFocusable by collectLastValue(underTest.isFocusable)

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GLANCEABLE_HUB,
                testScope = testScope,
            )
            shadeTestUtil.setLockscreenShadeExpansion(0f)
            // Transitioned away from communal scene.
            communalRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(CommunalScenes.Blank))
            )

            assertThat(isFocusable).isEqualTo(false)
        }

    @Test
    fun isFocusable_isTrue_whenIdleOnCommunal_andShadeNotExpanded() =
        testScope.runTest {
            val isFocusable by collectLastValue(underTest.isFocusable)

            // On communal scene.
            communalRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(CommunalScenes.Communal))
            )
            // Transitioned to Glanceable hub.
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GLANCEABLE_HUB,
                testScope = testScope,
            )
            // Shade not expanded.
            shadeTestUtil.setLockscreenShadeExpansion(0f)

            assertThat(isFocusable).isEqualTo(true)
        }

    @Test
    fun isFocusable_isFalse_whenQsIsExpanded() =
        testScope.runTest {
            val isFocusable by collectLastValue(underTest.isFocusable)

            // On communal scene.
            communalRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(CommunalScenes.Communal))
            )
            // Transitioned to Glanceable hub.
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GLANCEABLE_HUB,
                testScope = testScope,
            )
            // Qs is expanded.
            shadeTestUtil.setQsExpansion(1f)

            assertThat(isFocusable).isEqualTo(false)
        }

    private suspend fun setIsMainUser(isMainUser: Boolean) {
        whenever(user.isMain).thenReturn(isMainUser)
        userRepository.setUserInfos(listOf(user))
        userRepository.setSelectedUserInfo(user)
    }

    private companion object {
        val MAIN_USER_INFO = UserInfo(0, "primary", UserInfo.FLAG_MAIN)

        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf().andSceneContainer()
        }
    }
}
