package com.cliftonia.fs42tv.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * The fill colour the FieldStation42 box's own OSD draws in - see `field_player.py:158-185`,
 * which renders the whole OSD as a single ASS overlay: `{\an7\pos(60,50)\bord3\3c&H001100&
 * \c&H33FF33&\fs55}<channel line>\N{\fs22}<title line>`. ASS colour literals are BGR: `&H33FF33&`
 * is palindromic so it reads the same either way, but the outline below is not - do not "fix" it
 * back to a guessed colour without re-decoding the literal.
 */
val OsdGreen = Color(0xFF33FF33)

/** `&H001100&` decoded as BGR (B=00 G=11 R=00) - a near-black green, not the dark blue it would be as RGB. */
private val OsdOutline = Color(0xFF001100)

/** `\bord3` is 3 px at the box's native 1920x1080 ASS canvas - 1.5.dp at this app's 2.0 density. */
private val OsdOutlineWidth = 1.5.dp

/**
 * One line of OSD text, drawn twice - a stroked back copy then a filled front copy - because
 * Compose's `Text` has no outline property of its own. The outline is functional, not
 * decorative: without it `#33FF33` disappears over a snow scene or a bright studio background,
 * exactly the footage this app plays. There is no backing rectangle behind either copy - the box's
 * own OSD has none, and the outline alone is what keeps the text legible over bright footage.
 *
 * `fontWeight` defaults to [FontWeight.Normal]: the box's ASS string carries no `\b1` and mpv's
 * `--osd-bold` defaults to `no`, so the real OSD it is matching is regular weight, not bold.
 *
 * `BasicText` rather than `androidx.tv.material3.Text`: the latter's `maxLines`/`overflow`
 * wiring wasn't reaching the box that draws it (a long title used to run to the physical screen
 * edge and clip mid-word, no ellipsis, no wrap) - `BasicText` is the primitive both build on, so
 * this bypasses that entirely, and it is also the layer that exposes `TextStyle.drawStyle` for
 * the stroke pass in the first place.
 */
@Composable
internal fun OsdText(
    text: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    fontStyle: androidx.compose.ui.text.font.FontStyle? = null,
    color: Color = OsdGreen,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Clip,
    /**
     * Whether to draw the outline pass.
     *
     * The outline exists to keep text legible over moving footage, and costs a SECOND full text
     * layout and draw of the same string. Where there is already a background behind the text -
     * the picker's near-opaque backdrop - it buys nothing and doubles the work for every row
     * that scrolls into view, on a 32-bit SoC, twice per row because each row has two of these.
     */
    outline: Boolean = true,
) {
    val outlineWidthPx = with(LocalDensity.current) { OsdOutlineWidth.toPx() }
    // Remembered rather than rebuilt: these are allocated on every recomposition otherwise, and
    // a scrolling list recomposes constantly.
    val fill = remember(fontWeight, fontStyle, fontSize, color) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            fontSize = fontSize,
            color = color,
        )
    }
    val stroke = remember(fontWeight, fontStyle, fontSize, outlineWidthPx) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            fontSize = fontSize,
            color = OsdOutline,
            drawStyle = Stroke(width = outlineWidthPx),
        )
    }
    if (!outline) {
        BasicText(text = text, modifier = modifier, style = fill,
            maxLines = maxLines, overflow = overflow)
        return
    }
    Box(modifier = modifier) {
        BasicText(text = text, style = stroke, maxLines = maxLines, overflow = overflow)
        BasicText(text = text, style = fill, maxLines = maxLines, overflow = overflow)
    }
}
