package com.launchpoint.stereosine.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer

/**
 * Width measuring for layout decisions (UI-READABILITY-03A-B). These helpers only MEASURE text at the user's real font scale so a
 * container can choose a layout (more width, another row, stacking). Nothing here shrinks text or overrides the font scale.
 */

/** Words of [text] split on whitespace; a word is the smallest unit a line may break between. */
internal fun wordsOf(text: String): List<String> = text.split(Regex("""\s+""")).filter { it.isNotEmpty() }

/** Width in px of the widest single word of [text]: the least width in which it can be laid out without a mid-word break. */
internal fun TextMeasurer.widestWordPx(text: String, style: TextStyle): Float =
    wordsOf(text).maxOfOrNull { measure(it, style, softWrap = false, maxLines = 1).size.width.toFloat() } ?: 0f

@Composable
internal fun rememberFitMeasurer(): TextMeasurer = rememberTextMeasurer()
