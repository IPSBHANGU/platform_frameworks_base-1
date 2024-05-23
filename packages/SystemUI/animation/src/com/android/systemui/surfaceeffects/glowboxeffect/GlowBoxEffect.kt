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

package com.android.systemui.surfaceeffects.glowboxeffect

import android.animation.ValueAnimator
import android.graphics.Paint
import androidx.annotation.VisibleForTesting
import androidx.core.animation.doOnEnd
import com.android.systemui.surfaceeffects.PaintDrawCallback
import com.android.systemui.surfaceeffects.utils.MathUtils.lerp

/** Glow box effect where the box moves from start to end positions defined in the [config]. */
class GlowBoxEffect(
    private val config: GlowBoxConfig,
    private val paintDrawCallback: PaintDrawCallback
) {
    private val glowBoxShader =
        GlowBoxShader().apply {
            setSize(config.width, config.height)
            setCenter(config.startCenterX, config.startCenterY)
            setBlur(config.blurAmount)
            setColor(config.color)
        }
    private var animator: ValueAnimator? = null
    @VisibleForTesting var state: AnimationState = AnimationState.NOT_PLAYING
    private val paint = Paint().apply { shader = glowBoxShader }

    fun play() {
        if (state != AnimationState.NOT_PLAYING) {
            return
        }

        playEaseIn()
    }

    fun finish() {
        if (state == AnimationState.NOT_PLAYING || state == AnimationState.EASE_OUT) {
            return
        }

        animator?.pause()
        playEaseOut()
    }

    private fun playEaseIn() {
        if (state == AnimationState.EASE_IN) {
            return
        }
        state = AnimationState.EASE_IN

        animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = config.easeInDuration
                addUpdateListener {
                    val progress = it.animatedValue as Float
                    glowBoxShader.setCenter(
                        lerp(config.startCenterX, config.endCenterX, progress),
                        lerp(config.startCenterY, config.endCenterY, progress)
                    )

                    draw()
                }

                doOnEnd {
                    animator = null
                    playMain()
                }

                start()
            }
    }

    private fun playMain() {
        if (state == AnimationState.MAIN) {
            return
        }
        state = AnimationState.MAIN

        animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = config.duration
                addUpdateListener { draw() }

                doOnEnd {
                    animator = null
                    playEaseOut()
                }

                start()
            }
    }

    private fun playEaseOut() {
        if (state == AnimationState.EASE_OUT) return
        state = AnimationState.EASE_OUT

        animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = config.easeOutDuration
                addUpdateListener {
                    val progress = it.animatedValue as Float
                    glowBoxShader.setCenter(
                        lerp(config.endCenterX, config.startCenterX, progress),
                        lerp(config.endCenterY, config.startCenterY, progress)
                    )

                    draw()
                }

                doOnEnd {
                    animator = null
                    state = AnimationState.NOT_PLAYING
                }

                start()
            }
    }

    private fun draw() {
        paintDrawCallback.onDraw(paint)
    }

    /**
     * The animation state of the effect. The animation state transitions as follows: [EASE_IN] ->
     * [MAIN] -> [EASE_OUT] -> [NOT_PLAYING].
     */
    enum class AnimationState {
        EASE_IN,
        MAIN,
        EASE_OUT,
        NOT_PLAYING,
    }
}
