/*
 * Copyright 2020 The Android Open Source Project
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

package androidx.compose.ui.graphics.vector

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ExperimentalComposeApi
import androidx.compose.runtime.compositionReference
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.onPreCommit
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.DensityAmbient
import androidx.compose.ui.unit.Dp

/**
 * Default identifier for the root group if a Vector graphic
 */
const val RootGroupName = "VectorRootGroup"

/**
 * Create a [VectorPainter] with the Vector defined by the provided
 * sub-composition
 *
 * @param [defaultWidth] Intrinsic width of the Vector in [Dp]
 * @param [defaultHeight] Intrinsic height of the Vector in [Dp]
 * @param [viewportWidth] Width of the viewport space. The viewport is the virtual canvas where
 * paths are drawn on.
 *  This parameter is optional. Not providing it will use the [defaultWidth] converted to pixels
 * @param [viewportHeight] Height of the viewport space. The viewport is the virtual canvas where
 * paths are drawn on.
 *  This parameter is optional. Not providing it will use the [defaultHeight] converted to pixels
 * @param [name] optional identifier used to identify the root of this vector graphic
 * @param [children] Composable used to define the structure and contents of the vector graphic
 */
@Composable
fun VectorPainter(
    defaultWidth: Dp,
    defaultHeight: Dp,
    viewportWidth: Float = Float.NaN,
    viewportHeight: Float = Float.NaN,
    name: String = RootGroupName,
    children: @Composable (viewportWidth: Float, viewportHeight: Float) -> Unit
): VectorPainter {
    val density = DensityAmbient.current
    val widthPx = with(density) { defaultWidth.toPx() }
    val heightPx = with(density) { defaultHeight.toPx() }

    val vpWidth = if (viewportWidth.isNaN()) widthPx else viewportWidth
    val vpHeight = if (viewportHeight.isNaN()) heightPx else viewportHeight

    return remember { VectorPainter() }.apply {
        // This assignment is thread safe as the internal Size and VectorComponent parameters are
        // backed by mutableState objects
        size = Size(widthPx, heightPx)
        vector = createVector(
            name = name,
            viewportWidth = vpWidth,
            viewportHeight = vpHeight,
            children = children
        )
    }
}

/**
 * Create a [VectorPainter] with the given [VectorAsset]. This will create a
 * sub-composition of the vector hierarchy given the tree structure in [VectorAsset]
 *
 * @param [asset] VectorAsset used to create a vector graphic sub-composition
 */
@Composable
fun VectorPainter(asset: VectorAsset): VectorPainter {
    // TODO: Get rid of this temporary `vp` variable.
    val vp = VectorPainter(
        name = asset.name,
        defaultWidth = asset.defaultWidth,
        defaultHeight = asset.defaultHeight,
        viewportWidth = asset.viewportWidth,
        viewportHeight = asset.viewportHeight,
        children = { _, _ -> RenderVectorGroup(group = asset.root) }
    )
    return vp
}

/**
 * [Painter] implementation that abstracts the drawing of a Vector graphic.
 * This can be represented by either a [VectorAsset] or a programmatic
 * composition of a vector
 */
class VectorPainter internal constructor() : Painter() {

    internal var vector by mutableStateOf<VectorComponent?>(null)
    internal var size by mutableStateOf(Size.zero)

    private var currentAlpha: Float = 1.0f
    private var currentColorFilter: ColorFilter? = null

    override val intrinsicSize: Size
        get() = size

    override fun DrawScope.onDraw() {
        vector?.let { with (it) { draw(currentAlpha, currentColorFilter) } }
    }

    override fun applyAlpha(alpha: Float): Boolean {
        currentAlpha = alpha
        return true
    }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        currentColorFilter = colorFilter
        return true
    }
}

@Composable
private fun createVector(
    name: String,
    viewportWidth: Float,
    viewportHeight: Float,
    children: @Composable (viewportWidth: Float, viewportHeight: Float) -> Unit
): VectorComponent {
    val vector =
        remember(name, viewportWidth, viewportHeight) {
            VectorComponent(
                name = name,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight
            )
        }

    val composition = composeVector(
        vector,
        // TODO(lmr): refactor these APIs so that recomposer isn't necessary
        @OptIn(ExperimentalComposeApi::class)
        currentComposer.recomposer,
        compositionReference(),
        children
    )
    onPreCommit(vector) {
        onDispose {
            composition.dispose()
        }
    }
    return vector
}

/**
 * Recursive method for creating the vector graphic composition by traversing
 * the tree structure
 */
@Composable
private fun RenderVectorGroup(group: VectorGroup) {
    for (vectorNode in group) {
        if (vectorNode is VectorPath) {
            Path(
                pathData = vectorNode.pathData,
                name = vectorNode.name,
                fill = vectorNode.fill,
                fillAlpha = vectorNode.fillAlpha,
                stroke = vectorNode.stroke,
                strokeAlpha = vectorNode.strokeAlpha,
                strokeLineWidth = vectorNode.strokeLineWidth,
                strokeLineCap = vectorNode.strokeLineCap,
                strokeLineJoin = vectorNode.strokeLineJoin,
                strokeLineMiter = vectorNode.strokeLineMiter,
                trimPathStart = vectorNode.trimPathStart,
                trimPathEnd = vectorNode.trimPathEnd,
                trimPathOffset = vectorNode.trimPathOffset
            )
        } else if (vectorNode is VectorGroup) {
            Group(
                name = vectorNode.name,
                rotation = vectorNode.rotation,
                scaleX = vectorNode.scaleX,
                scaleY = vectorNode.scaleY,
                translationX = vectorNode.translationX,
                translationY = vectorNode.translationY,
                pivotX = vectorNode.pivotX,
                pivotY = vectorNode.pivotY,
                clipPathData = vectorNode.clipPathData
            ) {
                RenderVectorGroup(group = vectorNode)
            }
        }
    }
}