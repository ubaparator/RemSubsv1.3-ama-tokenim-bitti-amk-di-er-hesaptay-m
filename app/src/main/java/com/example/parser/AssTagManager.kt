package com.example.parser

import androidx.compose.ui.graphics.Color
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Parsed ASS override tags from a dialogue string.
 * Canonical coordinate space: PlayResX / PlayResY (Aegisub standard).
 */
data class AssParsedTags(
    val pos: Pair<Float, Float>? = null,
    val move: List<Float>? = null, // [x1, y1, x2, y2, t1, t2]
    val an: Int? = null,           // 1..9
    val fs: Float? = null,         // Font size in PlayResY units
    val fn: String? = null,        // Font name
    val b: Int? = null,            // 0, 1, or weight (e.g. 700)
    val i: Boolean? = null,        // Italic
    val u: Boolean? = null,        // Underline
    val s: Boolean? = null,        // Strikeout
    val c: String? = null,         // Primary color (&HBBGGRR& or &HAABBGGRR&)
    val c1: String? = null,
    val c2: String? = null,        // Secondary color
    val c3: String? = null,        // Outline / border color
    val c4: String? = null,        // Shadow color
    val alpha: String? = null,     // Alpha
    val a1: String? = null,
    val a2: String? = null,
    val a3: String? = null,
    val a4: String? = null,
    val bord: Float? = null,       // Outline width in PlayRes units
    val xbord: Float? = null,
    val ybord: Float? = null,
    val shad: Float? = null,       // Shadow depth in PlayRes units
    val xshad: Float? = null,
    val yshad: Float? = null,
    val blur: Float? = null,       // Gaussian blur
    val be: Int? = null,           // Edge blur
    val fscx: Float? = null,       // Font scale X percent (default 100)
    val fscy: Float? = null,       // Font scale Y percent (default 100)
    val fsp: Float? = null,        // Letter spacing in PlayRes units
    val frz: Float? = null,        // Rotation Z (degrees)
    val frx: Float? = null,
    val fry: Float? = null,
    val fax: Float? = null,        // Slant / shear
    val fay: Float? = null,
    val q: Int? = null,            // Wrap style (0, 1, 2, 3)
    val r: String? = null,         // Reset style
    val fad: Pair<Long, Long>? = null, // \fad(fadeInMs, fadeOutMs)
    val fade: List<Long>? = null,
    val clip: String? = null,
    val iclip: String? = null,
    val p: Int? = null,            // Drawing mode
    val pbo: Int? = null           // Baseline offset
)

/**
 * Universal ASS tag parser and tag updater that preserves all other tags.
 * Aegisub-compatible: single canonical ASS source of truth.
 */
object AssTagManager {

    /**
     * Extracts all known ASS override tags from the given raw dialogue line.
     */
    fun parseTags(rawText: String): AssParsedTags {
        if (!rawText.contains("{")) {
            return AssParsedTags()
        }

        var pos: Pair<Float, Float>? = null
        var move: List<Float>? = null
        var an: Int? = null
        var fs: Float? = null
        var fn: String? = null
        var b: Int? = null
        var i: Boolean? = null
        var u: Boolean? = null
        var s: Boolean? = null
        var c: String? = null
        var c1: String? = null
        var c2: String? = null
        var c3: String? = null
        var c4: String? = null
        var alpha: String? = null
        var a1: String? = null
        var a2: String? = null
        var a3: String? = null
        var a4: String? = null
        var bord: Float? = null
        var xbord: Float? = null
        var ybord: Float? = null
        var shad: Float? = null
        var xshad: Float? = null
        var yshad: Float? = null
        var blur: Float? = null
        var be: Int? = null
        var fscx: Float? = null
        var fscy: Float? = null
        var fsp: Float? = null
        var frz: Float? = null
        var frx: Float? = null
        var fry: Float? = null
        var fax: Float? = null
        var fay: Float? = null
        var q: Int? = null
        var r: String? = null
        var fad: Pair<Long, Long>? = null
        var fade: List<Long>? = null
        var clip: String? = null
        var iclip: String? = null
        var p: Int? = null
        var pbo: Int? = null

        val blockPattern = Regex("""\{([^}]*)\}""")
        for (blockMatch in blockPattern.findAll(rawText)) {
            val content = blockMatch.groupValues[1]

            // \pos(x, y)
            Regex("""\\pos\s*\(\s*([0-9.-]+)\s*,\s*([0-9.-]+)\s*\)""").find(content)?.let { m ->
                val x = m.groupValues[1].toFloatOrNull()
                val y = m.groupValues[2].toFloatOrNull()
                if (x != null && y != null) pos = Pair(x, y)
            }

            // \move(x1, y1, x2, y2[, t1, t2])
            Regex("""\\move\s*\(\s*([0-9.-]+)\s*,\s*([0-9.-]+)\s*,\s*([0-9.-]+)\s*,\s*([0-9.-]+)(?:\s*,\s*([0-9.-]+)\s*,\s*([0-9.-]+))?\s*\)""").find(content)?.let { m ->
                val list = mutableListOf<Float>()
                for (idx in 1..m.groupValues.size - 1) {
                    val v = m.groupValues[idx]
                    if (v.isNotBlank()) v.toFloatOrNull()?.let { list.add(it) }
                }
                if (list.size >= 4) move = list
            }

            // \an1..\an9
            Regex("""\\an([1-9])""").find(content)?.let { m ->
                an = m.groupValues[1].toIntOrNull()
            }

            // \fs (font size)
            Regex("""\\fs([0-9.]+)""").find(content)?.let { m ->
                fs = m.groupValues[1].toFloatOrNull()
            }

            // \fn (font name)
            Regex("""\\fn([^\\}]+)""").find(content)?.let { m ->
                fn = m.groupValues[1].trim()
            }

            // \b (bold)
            Regex("""\\b([0-9]+)""").find(content)?.let { m ->
                b = m.groupValues[1].toIntOrNull()
            }

            // \i (italic)
            Regex("""\\i([01])""").find(content)?.let { m ->
                i = m.groupValues[1] == "1"
            }

            // \u (underline)
            Regex("""\\u([01])""").find(content)?.let { m ->
                u = m.groupValues[1] == "1"
            }

            // \s (strikeout)
            Regex("""\\s([01])""").find(content)?.let { m ->
                s = m.groupValues[1] == "1"
            }

            // Colors: \c, \1c, \2c, \3c, \4c
            Regex("""\\1c(&?H?[0-9a-fA-F]+&?)""").find(content)?.let { c1 = it.groupValues[1] }
            Regex("""\\2c(&?H?[0-9a-fA-F]+&?)""").find(content)?.let { c2 = it.groupValues[1] }
            Regex("""\\3c(&?H?[0-9a-fA-F]+&?)""").find(content)?.let { c3 = it.groupValues[1] }
            Regex("""\\4c(&?H?[0-9a-fA-F]+&?)""").find(content)?.let { c4 = it.groupValues[1] }
            Regex("""\\c(&?H?[0-9a-fA-F]+&?)""").find(content)?.let { c = it.groupValues[1] }

            // Alpha: \alpha, \1a..\4a
            Regex("""\\alpha(&?H?[0-9a-fA-F]+&?)""").find(content)?.let { alpha = it.groupValues[1] }
            Regex("""\\1a(&?H?[0-9a-fA-F]+&?)""").find(content)?.let { a1 = it.groupValues[1] }
            Regex("""\\2a(&?H?[0-9a-fA-F]+&?)""").find(content)?.let { a2 = it.groupValues[1] }
            Regex("""\\3a(&?H?[0-9a-fA-F]+&?)""").find(content)?.let { a3 = it.groupValues[1] }
            Regex("""\\4a(&?H?[0-9a-fA-F]+&?)""").find(content)?.let { a4 = it.groupValues[1] }

            // \bord, \xbord, \ybord
            Regex("""\\xbord([0-9.]+)""").find(content)?.let { xbord = it.groupValues[1].toFloatOrNull() }
            Regex("""\\ybord([0-9.]+)""").find(content)?.let { ybord = it.groupValues[1].toFloatOrNull() }
            Regex("""\\bord([0-9.]+)""").find(content)?.let { bord = it.groupValues[1].toFloatOrNull() }

            // \shad, \xshad, \yshad
            Regex("""\\xshad([0-9.-]+)""").find(content)?.let { xshad = it.groupValues[1].toFloatOrNull() }
            Regex("""\\yshad([0-9.-]+)""").find(content)?.let { yshad = it.groupValues[1].toFloatOrNull() }
            Regex("""\\shad([0-9.-]+)""").find(content)?.let { shad = it.groupValues[1].toFloatOrNull() }

            // \blur, \be
            Regex("""\\blur([0-9.]+)""").find(content)?.let { blur = it.groupValues[1].toFloatOrNull() }
            Regex("""\\be([0-9]+)""").find(content)?.let { be = it.groupValues[1].toIntOrNull() }

            // \fscx, \fscy
            Regex("""\\fscx([0-9.]+)""").find(content)?.let { fscx = it.groupValues[1].toFloatOrNull() }
            Regex("""\\fscy([0-9.]+)""").find(content)?.let { fscy = it.groupValues[1].toFloatOrNull() }

            // \fsp
            Regex("""\\fsp([0-9.-]+)""").find(content)?.let { fsp = it.groupValues[1].toFloatOrNull() }

            // \frz, \frx, \fry
            Regex("""\\frz([0-9.-]+)""").find(content)?.let { frz = it.groupValues[1].toFloatOrNull() }
            Regex("""\\frx([0-9.-]+)""").find(content)?.let { frx = it.groupValues[1].toFloatOrNull() }
            Regex("""\\fry([0-9.-]+)""").find(content)?.let { fry = it.groupValues[1].toFloatOrNull() }

            // \fax, \fay
            Regex("""\\fax([0-9.-]+)""").find(content)?.let { fax = it.groupValues[1].toFloatOrNull() }
            Regex("""\\fay([0-9.-]+)""").find(content)?.let { fay = it.groupValues[1].toFloatOrNull() }

            // \q
            Regex("""\\q([0-3])""").find(content)?.let { q = it.groupValues[1].toIntOrNull() }

            // \r
            Regex("""\\r([^\\}]*)""").find(content)?.let { r = it.groupValues[1].trim() }

            // \fad(fadeIn, fadeOut)
            Regex("""\\fad\s*\(\s*([0-9]+)\s*,\s*([0-9]+)\s*\)""").find(content)?.let { m ->
                val fIn = m.groupValues[1].toLongOrNull()
                val fOut = m.groupValues[2].toLongOrNull()
                if (fIn != null && fOut != null) fad = Pair(fIn, fOut)
            }

            // \fade(...)
            Regex("""\\fade\s*\(\s*([0-9, ]+)\s*\)""").find(content)?.let { m ->
                fade = m.groupValues[1].split(",").mapNotNull { it.trim().toLongOrNull() }
            }

            // \clip, \iclip
            Regex("""\\clip\s*\(([^)]+)\)""").find(content)?.let { clip = it.groupValues[1].trim() }
            Regex("""\\iclip\s*\(([^)]+)\)""").find(content)?.let { iclip = it.groupValues[1].trim() }

            // \p, \pbo
            Regex("""\\pbo([0-9.-]+)""").find(content)?.let { pbo = it.groupValues[1].toIntOrNull() }
            Regex("""\\p([0-9]+)""").find(content)?.let { p = it.groupValues[1].toIntOrNull() }
        }

        return AssParsedTags(
            pos = pos,
            move = move,
            an = an,
            fs = fs,
            fn = fn,
            b = b,
            i = i,
            u = u,
            s = s,
            c = c ?: c1,
            c1 = c1 ?: c,
            c2 = c2,
            c3 = c3,
            c4 = c4,
            alpha = alpha,
            a1 = a1,
            a2 = a2,
            a3 = a3,
            a4 = a4,
            bord = bord,
            xbord = xbord,
            ybord = ybord,
            shad = shad,
            xshad = xshad,
            yshad = yshad,
            blur = blur,
            be = be,
            fscx = fscx,
            fscy = fscy,
            fsp = fsp,
            frz = frz,
            frx = frx,
            fry = fry,
            fax = fax,
            fay = fay,
            q = q,
            r = r,
            fad = fad,
            fade = fade,
            clip = clip,
            iclip = iclip,
            p = p,
            pbo = pbo
        )
    }

    /**
     * Updates or inserts a single tag into rawText without modifying or destroying any other tag.
     *
     * Example:
     * updateTag("{\\an8\\fs100\\bord3\\pos(960,200)}Hello", "fs", "80")
     * -> "{\\an8\\fs80\\bord3\\pos(960,200)}Hello"
     *
     * updateTag("Hello", "pos", "(500,300)")
     * -> "{\\pos(500,300)}Hello"
     */
    fun updateTag(rawText: String, tagKey: String, tagValue: String?): String {
        val cleanValue = tagValue?.trim()
        val formattedNewTag = if (!cleanValue.isNullOrEmpty()) {
            if (cleanValue.startsWith("(") && cleanValue.endsWith(")")) {
                "\\$tagKey$cleanValue"
            } else {
                "\\$tagKey$cleanValue"
            }
        } else null

        // Determine tag regex matching this specific key inside a tag block
        val tagRegex = when (tagKey) {
            "pos" -> Regex("""\\pos\s*\([^)]*\)""")
            "move" -> Regex("""\\move\s*\([^)]*\)""")
            "fad" -> Regex("""\\fad\s*\([^)]*\)""")
            "fade" -> Regex("""\\fade\s*\([^)]*\)""")
            "clip" -> Regex("""(?<!i)\\clip\s*\([^)]*\)""")
            "iclip" -> Regex("""\\iclip\s*\([^)]*\)""")
            "c" -> Regex("""(?<![1-4])\\c(&H[0-9a-fA-F]+&|&?[0-9a-fA-F]+&?)""")
            "1c" -> Regex("""\\1c(&H[0-9a-fA-F]+&|&?[0-9a-fA-F]+&?)""")
            "2c" -> Regex("""\\2c(&H[0-9a-fA-F]+&|&?[0-9a-fA-F]+&?)""")
            "3c" -> Regex("""\\3c(&H[0-9a-fA-F]+&|&?[0-9a-fA-F]+&?)""")
            "4c" -> Regex("""\\4c(&H[0-9a-fA-F]+&|&?[0-9a-fA-F]+&?)""")
            "alpha" -> Regex("""\\alpha(&H[0-9a-fA-F]+&|&?[0-9a-fA-F]+&?)""")
            "1a" -> Regex("""\\1a(&H[0-9a-fA-F]+&|&?[0-9a-fA-F]+&?)""")
            "2a" -> Regex("""\\2a(&H[0-9a-fA-F]+&|&?[0-9a-fA-F]+&?)""")
            "3a" -> Regex("""\\3a(&H[0-9a-fA-F]+&|&?[0-9a-fA-F]+&?)""")
            "4a" -> Regex("""\\4a(&H[0-9a-fA-F]+&|&?[0-9a-fA-F]+&?)""")
            "xbord" -> Regex("""\\xbord[0-9.]+""")
            "ybord" -> Regex("""\\ybord[0-9.]+""")
            "bord" -> Regex("""(?<![xy])\\bord[0-9.]+""")
            "xshad" -> Regex("""\\xshad[0-9.-]+""")
            "yshad" -> Regex("""\\yshad[0-9.-]+""")
            "shad" -> Regex("""(?<![xy])\\shad[0-9.-]+""")
            "fscx" -> Regex("""\\fscx[0-9.]+""")
            "fscy" -> Regex("""\\fscy[0-9.]+""")
            "fsp" -> Regex("""\\fsp[0-9.-]+""")
            "frz" -> Regex("""\\frz[0-9.-]+""")
            "frx" -> Regex("""\\frx[0-9.-]+""")
            "fry" -> Regex("""\\fry[0-9.-]+""")
            "fax" -> Regex("""\\fax[0-9.-]+""")
            "fay" -> Regex("""\\fay[0-9.-]+""")
            "fs" -> Regex("""\\fs[0-9.]+""")
            "fn" -> Regex("""\\fn[^\\}]+""")
            "an" -> Regex("""\\an[1-9]""")
            "b" -> Regex("""\\b[0-9]+""")
            "i" -> Regex("""\\i[01]""")
            "u" -> Regex("""\\u[01]""")
            "s" -> Regex("""\\s[01]""")
            "q" -> Regex("""\\q[0-3]""")
            "blur" -> Regex("""\\blur[0-9.]+""")
            "be" -> Regex("""\\be[0-9]+""")
            "pbo" -> Regex("""\\pbo[0-9.-]+""")
            "p" -> Regex("""(?<![a-zA-Z])\\p[0-9]+""")
            "r" -> Regex("""\\r[^\\}]*""")
            else -> Regex("""\\$tagKey[^\\}]+""")
        }

        // If rawText starts with a tag block "{...}"
        val firstBlockMatch = Regex("""^\{([^}]*)\}""").find(rawText)
        if (firstBlockMatch != null) {
            val oldBlockContent = firstBlockMatch.groupValues[1]
            val remainder = rawText.substring(firstBlockMatch.range.last + 1)

            val match = tagRegex.find(oldBlockContent)
            val updatedBlockContent: String = if (formattedNewTag != null) {
                if (match != null) {
                    oldBlockContent.replaceRange(match.range, formattedNewTag)
                } else {
                    "$oldBlockContent$formattedNewTag"
                }
            } else {
                if (match != null) {
                    oldBlockContent.replaceRange(match.range, "")
                } else {
                    oldBlockContent
                }
            }

            val cleaned = updatedBlockContent.trim()
            return if (cleaned.isEmpty()) {
                remainder
            } else {
                "{$cleaned}$remainder"
            }
        }

        // If rawText has a tag block elsewhere or no tag block at all:
        if (formattedNewTag != null) {
            return "{$formattedNewTag}$rawText"
        }
        return rawText
    }

    /**
     * Updates or sets \pos(x, y) coordinates cleanly.
     */
    fun updatePos(rawText: String, posX: Float, posY: Float): String {
        val xRounded = posX.roundToInt()
        val yRounded = posY.roundToInt()
        return updateTag(rawText, "pos", "($xRounded,$yRounded)")
    }

    /**
     * Converts ASS BGR hex (e.g. "&H00FFFF&" or "&H0000FF") to Compose Color.
     */
    fun assColorToColor(assHex: String?): Color? {
        if (assHex.isNullOrBlank()) return null
        val clean = assHex.replace("&", "").replace("H", "").replace("h", "").trim()
        val hex = clean.padStart(6, '0').takeLast(6)
        if (hex.length >= 6) {
            return try {
                val b = hex.substring(0, 2).toInt(16)
                val g = hex.substring(2, 4).toInt(16)
                val r = hex.substring(4, 6).toInt(16)
                Color(r, g, b)
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    /**
     * Converts Compose Color to ASS BGR inline hex string (e.g. "&H00FFFF&").
     */
    fun colorToAssColor(color: Color): String {
        val r = (color.red * 255).roundToInt().coerceIn(0, 255)
        val g = (color.green * 255).roundToInt().coerceIn(0, 255)
        val b = (color.blue * 255).roundToInt().coerceIn(0, 255)
        return String.format(Locale.US, "&H%02X%02X%02X&", b, g, r)
    }

    /**
     * Converts Compose Color to 6-digit hex without wrapper (e.g. "00FFFF").
     */
    fun colorToAssHexRaw(color: Color): String {
        val r = (color.red * 255).roundToInt().coerceIn(0, 255)
        val g = (color.green * 255).roundToInt().coerceIn(0, 255)
        val b = (color.blue * 255).roundToInt().coerceIn(0, 255)
        return String.format(Locale.US, "&H%02X%02X%02X&", b, g, r)
    }

    /**
     * Set of tag keys that are safe to apply across all subtitles in batch mode.
     * Coordinate/position tags like \pos, \move, \clip, \iclip, \t are excluded as they vary per dialogue.
     */
    val SAFE_BATCH_TAG_KEYS = setOf(
        "b", "i", "u", "s",
        "an", "fs", "fn",
        "bord", "xbord", "ybord",
        "shad", "xshad", "yshad",
        "c", "1c", "2c", "3c", "4c",
        "alpha", "1a", "2a", "3a", "4a",
        "fscx", "fscy", "frz", "blur", "be", "q"
    )

    /**
     * Applies a batch of tags across all subtitles, updating existing tags safely or adding them,
     * without touching other tags (like \pos) or dialogue text.
     */
    fun applyBatchTags(
        cues: List<com.example.model.SubtitleCue>,
        tagsToApply: Map<String, String?>
    ): List<com.example.model.SubtitleCue> {
        val filteredTags = tagsToApply.filterKeys { it in SAFE_BATCH_TAG_KEYS }
        if (filteredTags.isEmpty()) return cues

        return cues.map { cue ->
            var updatedRaw = cue.rawText
            for ((key, value) in filteredTags) {
                updatedRaw = updateTag(updatedRaw, key, value)
            }
            val clean = HtmlSubtitleParser.cleanToPlainText(updatedRaw)
            cue.copy(rawText = updatedRaw, cleanText = clean)
        }
    }
}
