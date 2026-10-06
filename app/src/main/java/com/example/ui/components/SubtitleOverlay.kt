package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.SubtitleCue
import com.example.model.SubtitleHorizontalAlign
import com.example.model.SubtitleStyle
import com.example.model.SubtitleVerticalAlign
import com.example.parser.AssGenerator
import com.example.parser.AssParsedTags
import com.example.parser.AssTagManager
import com.example.parser.HtmlSubtitleParser
import kotlin.math.roundToInt

/**
 * Aegisub-compliant Subtitle Overlay.
 *
 * Uses the canonical ASS PlayRes coordinate system (PlayResX x PlayResY)
 * with 1:1 scaling to the video content rectangle.
 *
 * Features:
 * - Direct mapping of ASS \fs100 to screen pixels without sp/dp heuristics.
 * - Real-time touch and drag directly over subtitle elements, modifying \pos(x,y).
 * - Full ASS override tag support (\an, \pos, \fs, \b, \i, \u, \s, \c, \3c, \4c, \bord, \shad, \fscx, \fscy, \frz, \fad).
 */
@Composable
fun SubtitleOverlay(
    activeCues: List<SubtitleCue>,
    style: SubtitleStyle,
    fontFamily: FontFamily?,
    sourceVideoWidth: Int = 1920,
    sourceVideoHeight: Int = 1080,
    currentPositionMs: Long = 0L,
    onCueDragged: ((cueId: Int, newAssX: Float, newAssY: Float) -> Unit)? = null,
    onCueDragEnded: ((cueId: Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (activeCues.isEmpty()) return

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        val density = LocalDensity.current.density
        val containerWidth = maxWidth
        val containerHeight = maxHeight

        val playResX = if (sourceVideoWidth > 0) sourceVideoWidth.toFloat() else 1920f
        val playResY = if (sourceVideoHeight > 0) sourceVideoHeight.toFloat() else 1080f
        val videoAspect = playResX / playResY
        val containerAspect = if (containerHeight.value > 0) containerWidth.value / containerHeight.value else videoAspect

        // Compute exact video content rectangle inside letterboxed container
        val contentWidth: Dp
        val contentHeight: Dp
        val contentOffsetX: Dp
        val contentOffsetY: Dp

        if (containerAspect > videoAspect) {
            // Container is wider: pillarbox (black bars left and right)
            contentHeight = containerHeight
            contentWidth = (contentHeight.value * videoAspect).dp
            contentOffsetX = (containerWidth - contentWidth) / 2f
            contentOffsetY = 0.dp
        } else {
            // Container is taller: letterbox (black bars top and bottom)
            contentWidth = containerWidth
            contentHeight = (contentWidth.value / videoAspect).dp
            contentOffsetX = 0.dp
            contentOffsetY = (containerHeight - contentHeight) / 2f
        }

        // Canonical scale factor: 1 PlayRes pixel = assScale screen dp/pixels
        val contentHeightPx = contentHeight.value * density
        val contentWidthPx = contentWidth.value * density
        val assScale = if (playResY > 0) contentHeightPx / playResY else 1f

        // Subtitle content Box strictly aligned with video content bounds
        Box(
            modifier = Modifier
                .offset(x = contentOffsetX, y = contentOffsetY)
                .size(contentWidth, contentHeight)
        ) {
            activeCues.forEach { cue ->
                AegisubSubtitleItem(
                    cue = cue,
                    style = style,
                    fontFamily = fontFamily,
                    playResX = playResX,
                    playResY = playResY,
                    assScale = assScale,
                    density = density,
                    currentPositionMs = currentPositionMs,
                    onCueDragged = onCueDragged,
                    onCueDragEnded = onCueDragEnded
                )
            }
        }
    }
}

@Composable
private fun AegisubSubtitleItem(
    cue: SubtitleCue,
    style: SubtitleStyle,
    fontFamily: FontFamily?,
    playResX: Float,
    playResY: Float,
    assScale: Float,
    density: Float,
    currentPositionMs: Long,
    onCueDragged: ((cueId: Int, newAssX: Float, newAssY: Float) -> Unit)?,
    onCueDragEnded: ((cueId: Int) -> Unit)?
) {
    val tags = remember(cue.rawText) {
        AssTagManager.parseTags(cue.rawText)
    }

    var measuredSize by remember { mutableStateOf(IntSize.Zero) }

    // 1. Alignment \an (1..9), defaults to style or 2 (bottom center)
    val alignmentAn = tags.an ?: AssGenerator.getAssAlignment(style.verticalAlign, style.horizontalAlign)

    // 2. Canonical Font Size: directly in PlayResY units
    val fsAss = tags.fs ?: style.fontSizeSp
    val fontSizePx = fsAss * assScale
    val effectiveFontSize = (fontSizePx / density).sp

    // 3. Colors & Outline
    val primaryColor = AssTagManager.assColorToColor(tags.c ?: tags.c1) ?: style.textColor
    val outlineColor = AssTagManager.assColorToColor(tags.c3) ?: style.outlineColor
    val shadowColor = AssTagManager.assColorToColor(tags.c4) ?: Color.Black.copy(alpha = 0.75f)

    val bordAss = tags.bord ?: if (style.hasOutline) style.outlineWidth else 0f
    val effectiveOutlineWidthPx = bordAss * assScale

    val shadAss = tags.shad ?: if (style.hasOutline) 2f else 0f
    val xshadAss = tags.xshad ?: shadAss
    val yshadAss = tags.yshad ?: shadAss
    val shadowOffsetX = xshadAss * assScale
    val shadowOffsetY = yshadAss * assScale

    // 4. Font Style overrides
    val isBold = if (tags.b != null) (tags.b != 0) else style.isBold
    val isItalic = tags.i ?: style.isItalic
    val isUnderline = tags.u ?: style.isUnderline
    val isStrike = tags.s ?: false

    // 5. Transforms (\fscx, \fscy, \frz)
    val fscx = tags.fscx ?: 100f
    val fscy = tags.fscy ?: 100f
    val frz = tags.frz ?: 0f

    // 6. Fade effect (\fad)
    val fadeAlpha = if (tags.fad != null && currentPositionMs > 0L) {
        val (fadeInMs, fadeOutMs) = tags.fad
        val elapsed = (currentPositionMs - cue.startTimeMs).coerceAtLeast(0L)
        val remaining = (cue.endTimeMs - currentPositionMs).coerceAtLeast(0L)
        when {
            fadeInMs > 0L && elapsed < fadeInMs -> (elapsed.toFloat() / fadeInMs).coerceIn(0f, 1f)
            fadeOutMs > 0L && remaining < fadeOutMs -> (remaining.toFloat() / fadeOutMs).coerceIn(0f, 1f)
            else -> 1f
        }
    } else 1f

    // 7. Calculate Position in PlayRes coordinates
    val (anchorAssX, anchorAssY) = if (tags.pos != null) {
        tags.pos
    } else {
        val marginV = (if (cue.marginV > 0) cue.marginV.toFloat() else style.verticalOffsetDp)
        val marginH = style.horizontalPaddingDp
        val defX = when (alignmentAn) {
            1, 4, 7 -> marginH
            3, 6, 9 -> playResX - marginH
            else -> playResX / 2f
        }
        val defY = when (alignmentAn) {
            7, 8, 9 -> marginV
            4, 5, 6 -> playResY / 2f
            else -> playResY - marginV
        }
        Pair(defX, defY)
    }

    // Convert anchor from PlayRes units to screen pixels
    val screenAnchorX = anchorAssX * assScale
    val screenAnchorY = anchorAssY * assScale

    // Adjust for text bounding box according to \an anchor
    val w = measuredSize.width.toFloat()
    val h = measuredSize.height.toFloat()

    val screenOffsetX = when (alignmentAn) {
        1, 4, 7 -> screenAnchorX
        3, 6, 9 -> screenAnchorX - w
        else -> screenAnchorX - (w / 2f)
    }

    val screenOffsetY = when (alignmentAn) {
        7, 8, 9 -> screenAnchorY
        4, 5, 6 -> screenAnchorY - (h / 2f)
        else -> screenAnchorY - h
    }

    val currentAnchorX by rememberUpdatedState(anchorAssX)
    val currentAnchorY by rememberUpdatedState(anchorAssY)
    val currentAssScale by rememberUpdatedState(assScale)
    val currentPlayResX by rememberUpdatedState(playResX)
    val currentPlayResY by rememberUpdatedState(playResY)
    val currentOnCueDragged by rememberUpdatedState(onCueDragged)
    val currentOnCueDragEnded by rememberUpdatedState(onCueDragEnded)

    // Interactive Drag Modifier directly over the subtitle cue
    val dragModifier = if (onCueDragged != null) {
        Modifier.pointerInput(cue.id) {
            var liveAssX = currentAnchorX
            var liveAssY = currentAnchorY
            detectDragGestures(
                onDragStart = {
                    liveAssX = currentAnchorX
                    liveAssY = currentAnchorY
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    val scale = if (currentAssScale > 0f) currentAssScale else 1f
                    val deltaAssX = dragAmount.x / scale
                    val deltaAssY = dragAmount.y / scale
                    liveAssX = (liveAssX + deltaAssX).coerceIn(0f, currentPlayResX)
                    liveAssY = (liveAssY + deltaAssY).coerceIn(0f, currentPlayResY)
                    currentOnCueDragged?.invoke(cue.id, liveAssX, liveAssY)
                },
                onDragEnd = {
                    currentOnCueDragEnded?.invoke(cue.id)
                },
                onDragCancel = {
                    currentOnCueDragEnded?.invoke(cue.id)
                }
            )
        }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    screenOffsetX.roundToInt(),
                    screenOffsetY.roundToInt()
                )
            }
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .onSizeChanged { measuredSize = it }
            .graphicsLayer {
                scaleX = fscx / 100f
                scaleY = fscy / 100f
                rotationZ = frz
                alpha = fadeAlpha
            }
            .then(dragModifier)
            .testTag("subtitle_cue_${cue.id}")
    ) {
        val annotatedText = buildAnnotatedSubtitle(
            rawText = cue.rawText,
            cleanText = cue.cleanText,
            style = style,
            assScale = assScale,
            density = density
        )

        val textAlign = when (alignmentAn) {
            1, 4, 7 -> TextAlign.Start
            3, 6, 9 -> TextAlign.End
            else -> TextAlign.Center
        }

        val boxModifier = if (style.hasBackgroundBox) {
            Modifier
                .background(style.backgroundColor, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        } else {
            Modifier
        }

        Box(modifier = boxModifier) {
            // Shadow Layer
            if (shadAss > 0f) {
                Text(
                    text = annotatedText,
                    modifier = Modifier.offset {
                        IntOffset(shadowOffsetX.roundToInt(), shadowOffsetY.roundToInt())
                    },
                    style = TextStyle(
                        fontSize = effectiveFontSize,
                        textAlign = textAlign,
                        fontFamily = fontFamily,
                        fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal,
                        textDecoration = when {
                            isUnderline && isStrike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                            isUnderline -> TextDecoration.Underline
                            isStrike -> TextDecoration.LineThrough
                            else -> TextDecoration.None
                        },
                        color = shadowColor
                    )
                )
            }

            // Outline / Border Stroke Layer
            if (effectiveOutlineWidthPx > 0f && !style.hasBackgroundBox) {
                Text(
                    text = annotatedText,
                    style = TextStyle(
                        fontSize = effectiveFontSize,
                        textAlign = textAlign,
                        fontFamily = fontFamily,
                        fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal,
                        textDecoration = when {
                            isUnderline && isStrike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                            isUnderline -> TextDecoration.Underline
                            isStrike -> TextDecoration.LineThrough
                            else -> TextDecoration.None
                        },
                        drawStyle = Stroke(width = effectiveOutlineWidthPx * 2f),
                        color = outlineColor
                    )
                )
            }

            // Foreground Text Layer
            Text(
                text = annotatedText,
                style = TextStyle(
                    fontSize = effectiveFontSize,
                    textAlign = textAlign,
                    fontFamily = fontFamily,
                    fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = when {
                        isUnderline && isStrike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                        isUnderline -> TextDecoration.Underline
                        isStrike -> TextDecoration.LineThrough
                        else -> TextDecoration.None
                    },
                    color = primaryColor
                )
            )
        }
    }
}

/**
 * Parses ASS tags ({\i1}, {\b1}, {\u1}, {\s1}, {\c...}, {\fs...}, \N, etc.)
 * and HTML tags (via HtmlSubtitleParser) to build an AnnotatedString preserving styling,
 * without ever showing raw tag syntax.
 */
fun buildAnnotatedSubtitle(
    rawText: String,
    cleanText: String,
    style: SubtitleStyle,
    assScale: Float = 1.0f,
    density: Float = 1.0f
): AnnotatedString {
    val sourceRaw = if (rawText.isNotBlank()) rawText else cleanText
    // Step 1: Decode entities and translate all HTML tags (<i>, <b>, <u>, <br>, <font>) to ASS overrides
    val assText = HtmlSubtitleParser.htmlToAss(sourceRaw)

    // Tokenize ASS override blocks {...} and newline tags \N, \n, \h
    val tokenPattern = Regex("""(\{[^}]*\}|\\N|\\n|\\h)""")
    val matches = tokenPattern.findAll(assText).toList()

    if (matches.isEmpty()) {
        return buildAnnotatedString {
            withStyle(
                SpanStyle(
                    fontWeight = if (style.isBold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (style.isItalic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = if (style.isUnderline) TextDecoration.Underline else TextDecoration.None
                )
            ) {
                append(HtmlSubtitleParser.cleanToPlainText(cleanText))
            }
        }
    }

    return buildAnnotatedString {
        var currentIndex = 0
        var activeBold = style.isBold
        var activeItalic = style.isItalic
        var activeUnderline = style.isUnderline
        var activeStrike = false
        var activeColor: Color? = null
        var activeFontSize: TextUnit? = null

        fun currentSpanStyle(): SpanStyle {
            val decoration = when {
                activeUnderline && activeStrike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                activeUnderline -> TextDecoration.Underline
                activeStrike -> TextDecoration.LineThrough
                else -> TextDecoration.None
            }
            return SpanStyle(
                color = activeColor ?: Color.Unspecified,
                fontSize = activeFontSize ?: TextUnit.Unspecified,
                fontWeight = if (activeBold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (activeItalic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = decoration
            )
        }

        for (match in matches) {
            val matchRange = match.range
            if (matchRange.first > currentIndex) {
                val plainPart = assText.substring(currentIndex, matchRange.first)
                if (plainPart.isNotEmpty()) {
                    withStyle(currentSpanStyle()) {
                        append(plainPart)
                    }
                }
            }

            val token = match.value
            when {
                token == "\\N" || token == "\\n" -> {
                    append("\n")
                }
                token == "\\h" -> {
                    append(" ")
                }
                token.startsWith("{") && token.endsWith("}") -> {
                    val inner = token.substring(1, token.length - 1)
                    if (inner.contains("\\i1")) activeItalic = true
                    if (inner.contains("\\i0")) activeItalic = false
                    if (inner.contains("\\b1") || inner.contains("\\b700")) activeBold = true
                    if (inner.contains("\\b0")) activeBold = false
                    if (inner.contains("\\u1")) activeUnderline = true
                    if (inner.contains("\\u0")) activeUnderline = false
                    if (inner.contains("\\s1")) activeStrike = true
                    if (inner.contains("\\s0")) activeStrike = false

                    // Inline text color \c&HBBGGRR& or \1c&HBBGGRR&
                    if (inner.contains("\\c") || inner.contains("\\1c")) {
                        val colMatch = Regex("""\\(?:1c|c)(&?H?[0-9a-fA-F]{6}&?)""").find(inner)
                        if (colMatch != null) {
                            activeColor = AssTagManager.assColorToColor(colMatch.value)
                        } else if (inner.contains("\\c") && !inner.contains("&H")) {
                            activeColor = null // reset
                        }
                    }

                    // Inline font size \fsXX in PlayRes units
                    if (inner.contains("\\fs")) {
                        val fsMatch = Regex("""\\fs([0-9.]+)""").find(inner)
                        val rawFs = fsMatch?.groupValues?.get(1)?.toFloatOrNull()
                        if (rawFs != null) {
                            val fsPixels = rawFs * assScale
                            activeFontSize = (fsPixels / density).sp
                        } else {
                            activeFontSize = null
                        }
                    }
                }
            }

            currentIndex = matchRange.last + 1
        }

        if (currentIndex < assText.length) {
            val tail = assText.substring(currentIndex)
            if (tail.isNotEmpty()) {
                withStyle(currentSpanStyle()) {
                    append(tail)
                }
            }
        }
    }
}

/**
 * Extracts alignment from ASS \an tag.
 */
fun extractAssAlignmentFromText(text: String): Pair<SubtitleVerticalAlign, SubtitleHorizontalAlign>? {
    val match = Regex("""\\an([1-9])""").find(text) ?: return null
    return when (match.groupValues[1].toIntOrNull()) {
        7 -> Pair(SubtitleVerticalAlign.TOP, SubtitleHorizontalAlign.LEFT)
        8 -> Pair(SubtitleVerticalAlign.TOP, SubtitleHorizontalAlign.CENTER)
        9 -> Pair(SubtitleVerticalAlign.TOP, SubtitleHorizontalAlign.RIGHT)
        4 -> Pair(SubtitleVerticalAlign.MIDDLE, SubtitleHorizontalAlign.LEFT)
        5 -> Pair(SubtitleVerticalAlign.MIDDLE, SubtitleHorizontalAlign.CENTER)
        6 -> Pair(SubtitleVerticalAlign.MIDDLE, SubtitleHorizontalAlign.RIGHT)
        1 -> Pair(SubtitleVerticalAlign.BOTTOM, SubtitleHorizontalAlign.LEFT)
        3 -> Pair(SubtitleVerticalAlign.BOTTOM, SubtitleHorizontalAlign.RIGHT)
        else -> Pair(SubtitleVerticalAlign.BOTTOM, SubtitleHorizontalAlign.CENTER)
    }
}
