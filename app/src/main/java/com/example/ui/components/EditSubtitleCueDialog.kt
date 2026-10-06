package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.SubtitleCue
import com.example.parser.AssTagManager
import com.example.parser.HtmlSubtitleParser
import kotlin.math.roundToInt

/**
 * Single, unified ASS / Override Tags Editor for a Subtitle Cue.
 *
 * All positioning and formatting updates operate directly on the cue's ASS text,
 * guaranteeing:
 * - Single source of truth (ASS raw text).
 * - Preservation of existing tags when updating a single tag.
 * - Live synchronization between Drag on video and Tag Editor.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditSubtitleCueDialog(
    cue: SubtitleCue,
    currentVideoPositionMs: Long,
    totalCuesCount: Int = 1,
    initialTab: Int = 0,
    onDismiss: () -> Unit,
    onSave: (SubtitleCue) -> Unit,
    onDelete: (Int) -> Unit,
    onApplyBatchTags: (Map<String, String?>) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedDialogTab by remember { mutableStateOf(initialTab) }
    var rawText by remember { mutableStateOf(cue.rawText) }
    var startTimeMs by remember { mutableLongStateOf(cue.startTimeMs) }
    var endTimeMs by remember { mutableLongStateOf(cue.endTimeMs) }
    var startText by remember { mutableStateOf(cue.formatStartTime()) }
    var endText by remember { mutableStateOf(cue.formatEndTime()) }

    // Color pickers
    var activeColorPickerTag by remember { mutableStateOf<String?>(null) } // "c", "3c", "4c"
    var activeColorPickerInitial by remember { mutableStateOf(Color.White) }

    val scrollState = rememberScrollState()

    // Derived active tags from current rawText
    val tags = remember(rawText) {
        AssTagManager.parseTags(rawText)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("edit_subtitle_dialog")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(scrollState)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "#${cue.id}",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ASS / Override Tags Düzenleyici",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Kapat")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Tab Switcher: Single Cue vs Batch (Tüm Altyazılara Uygula)
            TabRow(
                selectedTabIndex = selectedDialogTab,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .testTag("dialog_tab_row")
            ) {
                Tab(
                    selected = selectedDialogTab == 0,
                    onClick = { selectedDialogTab = 0 },
                    text = { Text("Seçili (#${cue.id})", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                )
                Tab(
                    selected = selectedDialogTab == 1,
                    onClick = { selectedDialogTab = 1 },
                    text = { Text("Tüm Altyazılara Uygula", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (selectedDialogTab == 0) {
            // Text Input & Direct ASS Dialogue text
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DİYALOG & ASS METNİ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                // Quick Tag Actions
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { rawText = "$rawText\\N" }
                            .testTag("tag_newline_button")
                    ) {
                        Text(
                            text = "\\N Satır",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = rawText,
                onValueChange = { rawText = it },
                placeholder = { Text("{\\fs100\\an8}Diyalog metni...") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("edit_dialog_text_field")
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Live Aegisub Preview Card
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.9f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .testTag("edit_dialog_live_preview_card")
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "CANLI AEGISUB ÖNİZLEME",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.LightGray,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = when (tags.an ?: 2) {
                            1, 4, 7 -> Alignment.CenterStart
                            3, 6, 9 -> Alignment.CenterEnd
                            else -> Alignment.Center
                        }
                    ) {
                        val previewAnnotated = buildAnnotatedSubtitle(
                            rawText = rawText,
                            cleanText = HtmlSubtitleParser.cleanToPlainText(rawText),
                            style = com.example.model.SubtitleStyle(),
                            assScale = 0.5f,
                            density = 1.0f
                        )
                        val previewFs = (tags.fs ?: 48f) * 0.45f
                        Text(
                            text = previewAnnotated,
                            fontSize = previewFs.sp,
                            color = AssTagManager.assColorToColor(tags.c ?: tags.c1) ?: Color.White,
                            textAlign = when (tags.an ?: 2) {
                                1, 4, 7 -> TextAlign.Start
                                3, 6, 9 -> TextAlign.End
                                else -> TextAlign.Center
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Time Interval Section
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AccessTime,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ZAMAN ARALIĞI",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        val durationMs = (endTimeMs - startTimeMs).coerceAtLeast(0)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (endTimeMs >= startTimeMs) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "Süre: ${String.format("%.2f", durationMs / 1000f)}s",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (endTimeMs >= startTimeMs) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Başlangıç", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(2.dp))
                            OutlinedTextField(
                                value = startText,
                                onValueChange = {
                                    startText = it
                                    SubtitleCue.parseTimestamp(it)?.let { parsed -> startTimeMs = parsed }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bitiş", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(2.dp))
                            OutlinedTextField(
                                value = endText,
                                onValueChange = {
                                    endText = it
                                    SubtitleCue.parseTimestamp(it)?.let { parsed -> endTimeMs = parsed }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Quick Nudge Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    startTimeMs = (startTimeMs - 100).coerceAtLeast(0)
                                    startText = SubtitleCue.formatTimestamp(startTimeMs)
                                }
                        ) {
                            Text("-100ms", fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 4.dp))
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    startTimeMs += 100
                                    startText = SubtitleCue.formatTimestamp(startTimeMs)
                                }
                        ) {
                            Text("+100ms", fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 4.dp))
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .weight(1.5f)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    startTimeMs = currentVideoPositionMs
                                    startText = SubtitleCue.formatTimestamp(startTimeMs)
                                    if (endTimeMs <= startTimeMs) {
                                        endTimeMs = startTimeMs + 2500L
                                        endText = SubtitleCue.formatTimestamp(endTimeMs)
                                    }
                                }
                        ) {
                            Text("Videoya Hizala", fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // --- ASS / OVERRIDE TAGS SECTION ---
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ass_override_tags_section")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "ASS OVERRIDE TAGLERİ",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Bu menüde düzenlenen tüm değerler doğrudan ASS taglerine yazılır ve libass ile 1:1 render edilir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 1. POSITION (\pos) & ALIGNMENT (\an)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Konum (\\pos) & Hizalama (\\an)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        if (tags.pos != null) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable {
                                        rawText = AssTagManager.updateTag(rawText, "pos", null)
                                    }
                            ) {
                                Text("\\pos Kaldır", fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Pos X
                        OutlinedTextField(
                            value = tags.pos?.first?.roundToInt()?.toString() ?: "",
                            onValueChange = { newVal ->
                                val xVal = newVal.toFloatOrNull()
                                val yVal = tags.pos?.second ?: 900f
                                if (xVal != null) {
                                    rawText = AssTagManager.updatePos(rawText, xVal, yVal)
                                }
                            },
                            label = { Text("\\pos X") },
                            placeholder = { Text("960") },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("override_pos_x_input")
                        )

                        // Pos Y
                        OutlinedTextField(
                            value = tags.pos?.second?.roundToInt()?.toString() ?: "",
                            onValueChange = { newVal ->
                                val yVal = newVal.toFloatOrNull()
                                val xVal = tags.pos?.first ?: 960f
                                if (yVal != null) {
                                    rawText = AssTagManager.updatePos(rawText, xVal, yVal)
                                }
                            },
                            label = { Text("\\pos Y") },
                            placeholder = { Text("900") },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("override_pos_y_input")
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Alignment \an visual pad (1..9)
                    Text("Hizalama Anchor Noktası (\\an)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            listOf(7 to "Top-Left (\\an7)", 8 to "Top-Center (\\an8)", 9 to "Top-Right (\\an9)"),
                            listOf(4 to "Mid-Left (\\an4)", 5 to "Mid-Center (\\an5)", 6 to "Mid-Right (\\an6)"),
                            listOf(1 to "Bot-Left (\\an1)", 2 to "Bot-Center (\\an2)", 3 to "Bot-Right (\\an3)")
                        ).forEach { rowList ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                rowList.forEach { (anVal, label) ->
                                    val isSelected = (tags.an ?: 2) == anVal
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                rawText = AssTagManager.updateTag(rawText, "an", anVal.toString())
                                            }
                                            .testTag("align_an_$anVal")
                                    ) {
                                        Text(
                                            text = "\\an$anVal",
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 2. FONT SIZE (\fs) & FONT NAME (\fn) & STYLE (\b, \i, \u, \s)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.TextFields, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Yazı Tipi & Boyutu (\\fs, \\fn)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Font Size \fs
                        OutlinedTextField(
                            value = tags.fs?.roundToInt()?.toString() ?: "",
                            onValueChange = { newVal ->
                                val cleaned = newVal.filter { it.isDigit() }
                                rawText = AssTagManager.updateTag(rawText, "fs", cleaned.ifEmpty { null })
                            },
                            label = { Text("Boyut (\\fs)") },
                            placeholder = { Text("100") },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("override_fs_input")
                        )

                        // Font Name \fn
                        OutlinedTextField(
                            value = tags.fn ?: "",
                            onValueChange = { newVal ->
                                rawText = AssTagManager.updateTag(rawText, "fn", newVal.ifEmpty { null })
                            },
                            label = { Text("Yazı Tipi (\\fn)") },
                            placeholder = { Text("Arial") },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("override_fn_input")
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Bold, Italic, Underline, Strikeout Chips
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val isBold = tags.b != null && tags.b != 0
                        FilterChip(
                            selected = isBold,
                            onClick = {
                                rawText = AssTagManager.updateTag(rawText, "b", if (isBold) "0" else "1")
                            },
                            label = { Text("\\b1 Kalın", fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.FormatBold, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.testTag("override_b_chip")
                        )

                        val isItalic = tags.i == true
                        FilterChip(
                            selected = isItalic,
                            onClick = {
                                rawText = AssTagManager.updateTag(rawText, "i", if (isItalic) "0" else "1")
                            },
                            label = { Text("\\i1 İtalik", fontStyle = FontStyle.Italic, fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.FormatItalic, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.testTag("override_i_chip")
                        )

                        val isUnderline = tags.u == true
                        FilterChip(
                            selected = isUnderline,
                            onClick = {
                                rawText = AssTagManager.updateTag(rawText, "u", if (isUnderline) "0" else "1")
                            },
                            label = { Text("\\u1 Altı Çizili", textDecoration = TextDecoration.Underline, fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.FormatUnderlined, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.testTag("override_u_chip")
                        )

                        val isStrike = tags.s == true
                        FilterChip(
                            selected = isStrike,
                            onClick = {
                                rawText = AssTagManager.updateTag(rawText, "s", if (isStrike) "0" else "1")
                            },
                            label = { Text("\\s1 Üstü Çizili", textDecoration = TextDecoration.LineThrough, fontSize = 11.sp) },
                            modifier = Modifier.testTag("override_s_chip")
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 3. COLORS: \c (1c), \3c (outline), \4c (shadow)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Renkler (\\c, \\3c, \\4c)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Primary text color \c
                        val curColor = AssTagManager.assColorToColor(tags.c ?: tags.c1) ?: Color.White
                        AssColorButton(
                            title = "Yazı (\\c)",
                            color = curColor,
                            assHex = tags.c ?: tags.c1 ?: "&HFFFFFF&",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                activeColorPickerTag = "c"
                                activeColorPickerInitial = curColor
                            },
                            onClear = {
                                rawText = AssTagManager.updateTag(rawText, "c", null)
                            }
                        )

                        // Outline color \3c
                        val curBorder = AssTagManager.assColorToColor(tags.c3) ?: Color.Black
                        AssColorButton(
                            title = "Kenar (\\3c)",
                            color = curBorder,
                            assHex = tags.c3 ?: "&H000000&",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                activeColorPickerTag = "3c"
                                activeColorPickerInitial = curBorder
                            },
                            onClear = {
                                rawText = AssTagManager.updateTag(rawText, "3c", null)
                            }
                        )

                        // Shadow color \4c
                        val curShadow = AssTagManager.assColorToColor(tags.c4) ?: Color.DarkGray
                        AssColorButton(
                            title = "Gölge (\\4c)",
                            color = curShadow,
                            assHex = tags.c4 ?: "&H000000&",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                activeColorPickerTag = "4c"
                                activeColorPickerInitial = curShadow
                            },
                            onClear = {
                                rawText = AssTagManager.updateTag(rawText, "4c", null)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 4. BORDER (\bord) & SHADOW (\shad) & BLUR (\blur)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = tags.bord?.roundToInt()?.toString() ?: "",
                            onValueChange = { newVal ->
                                val cleaned = newVal.filter { it.isDigit() }
                                rawText = AssTagManager.updateTag(rawText, "bord", cleaned.ifEmpty { null })
                            },
                            label = { Text("Kenar (\\bord)") },
                            placeholder = { Text("3") },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("override_bord_input")
                        )

                        OutlinedTextField(
                            value = tags.shad?.roundToInt()?.toString() ?: "",
                            onValueChange = { newVal ->
                                val cleaned = newVal.filter { it.isDigit() }
                                rawText = AssTagManager.updateTag(rawText, "shad", cleaned.ifEmpty { null })
                            },
                            label = { Text("Gölge (\\shad)") },
                            placeholder = { Text("2") },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("override_shad_input")
                        )

                        OutlinedTextField(
                            value = tags.blur?.roundToInt()?.toString() ?: "",
                            onValueChange = { newVal ->
                                val cleaned = newVal.filter { it.isDigit() }
                                rawText = AssTagManager.updateTag(rawText, "blur", cleaned.ifEmpty { null })
                            },
                            label = { Text("Bulanıklık (\\blur)") },
                            placeholder = { Text("1") },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("override_blur_input")
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 5. SCALE (\fscx, \fscy), ROTATION (\frz), SPACING (\fsp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = tags.fscx?.roundToInt()?.toString() ?: "",
                            onValueChange = { newVal ->
                                val cleaned = newVal.filter { it.isDigit() }
                                rawText = AssTagManager.updateTag(rawText, "fscx", cleaned.ifEmpty { null })
                            },
                            label = { Text("Ölçek X% (\\fscx)") },
                            placeholder = { Text("150") },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("override_fscx_input")
                        )

                        OutlinedTextField(
                            value = tags.fscy?.roundToInt()?.toString() ?: "",
                            onValueChange = { newVal ->
                                val cleaned = newVal.filter { it.isDigit() }
                                rawText = AssTagManager.updateTag(rawText, "fscy", cleaned.ifEmpty { null })
                            },
                            label = { Text("Ölçek Y% (\\fscy)") },
                            placeholder = { Text("80") },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("override_fscy_input")
                        )

                        OutlinedTextField(
                            value = tags.frz?.roundToInt()?.toString() ?: "",
                            onValueChange = { newVal ->
                                val cleaned = newVal.filter { it.isDigit() || it == '-' }
                                rawText = AssTagManager.updateTag(rawText, "frz", cleaned.ifEmpty { null })
                            },
                            label = { Text("Açı (\\frz)") },
                            placeholder = { Text("30") },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("override_frz_input")
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 6. ADVANCED TAGS: \fad, \move, \q, \r
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = if (tags.fad != null) "${tags.fad.first},${tags.fad.second}" else "",
                            onValueChange = { newVal ->
                                if (newVal.isBlank()) {
                                    rawText = AssTagManager.updateTag(rawText, "fad", null)
                                } else {
                                    rawText = AssTagManager.updateTag(rawText, "fad", "($newVal)")
                                }
                            },
                            label = { Text("Fade (\\fad)") },
                            placeholder = { Text("200,200") },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("override_fad_input")
                        )

                        OutlinedTextField(
                            value = tags.fsp?.roundToInt()?.toString() ?: "",
                            onValueChange = { newVal ->
                                val cleaned = newVal.filter { it.isDigit() || it == '-' }
                                rawText = AssTagManager.updateTag(rawText, "fsp", cleaned.ifEmpty { null })
                            },
                            label = { Text("Aralık (\\fsp)") },
                            placeholder = { Text("2") },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("override_fsp_input")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons: Cancel, Delete, Save
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onDelete(cue.id) },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("delete_cue_button")
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Sil")
                }

                Spacer(modifier = Modifier.weight(1f))

                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("İptal")
                }

                Button(
                    onClick = {
                        val clean = HtmlSubtitleParser.cleanToPlainText(rawText)
                        val updatedCue = cue.copy(
                            startTimeMs = startTimeMs,
                            endTimeMs = endTimeMs,
                            rawText = rawText,
                            cleanText = clean
                        )
                        onSave(updatedCue)
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("save_cue_button")
                ) {
                    Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Kaydet & Uygula")
                }
            }
            } else {
                // --- TAB 1: BATCH ASS TAG APPLICATION ---
                BatchAssTagApplicatorContent(
                    totalCuesCount = totalCuesCount,
                    onDismiss = onDismiss,
                    onApplyBatchTags = onApplyBatchTags
                )
            }
        }
    }

    // Color Picker Dialog
    if (activeColorPickerTag != null) {
        RgbColorPickerDialog(
            initialColor = activeColorPickerInitial,
            title = when (activeColorPickerTag) {
                "c" -> "Yazı Rengi Seç (\\c)"
                "3c" -> "Dış Çizgi Kenar Rengi Seç (\\3c)"
                "4c" -> "Gölge Rengi Seç (\\4c)"
                else -> "Renk Seç"
            },
            onDismiss = { activeColorPickerTag = null },
            onColorSelected = { chosenColor ->
                val assColor = AssTagManager.colorToAssColor(chosenColor)
                val tagKey = activeColorPickerTag!!
                rawText = AssTagManager.updateTag(rawText, tagKey, assColor)
                activeColorPickerTag = null
            }
        )
    }
}

@Composable
private fun AssColorButton(
    title: String,
    color: Color,
    assHex: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onClear: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(color, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(assHex, fontSize = 8.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchAssTagApplicatorContent(
    totalCuesCount: Int,
    onDismiss: () -> Unit,
    onApplyBatchTags: (Map<String, String?>) -> Unit
) {
    var batchFs by remember { mutableStateOf("") }
    var batchFn by remember { mutableStateOf("") }
    var batchAn by remember { mutableStateOf<Int?>(null) }
    var batchBold by remember { mutableStateOf<String?>(null) }
    var batchItalic by remember { mutableStateOf<String?>(null) }
    var batchUnderline by remember { mutableStateOf<String?>(null) }
    var batchStrike by remember { mutableStateOf<String?>(null) }
    var batchColorC by remember { mutableStateOf<String?>(null) }
    var batchColor2c by remember { mutableStateOf<String?>(null) }
    var batchColor3c by remember { mutableStateOf<String?>(null) }
    var batchColor4c by remember { mutableStateOf<String?>(null) }
    var batchAlpha by remember { mutableStateOf("") }
    var batchA1 by remember { mutableStateOf("") }
    var batchA2 by remember { mutableStateOf("") }
    var batchA3 by remember { mutableStateOf("") }
    var batchA4 by remember { mutableStateOf("") }
    var batchBord by remember { mutableStateOf("") }
    var batchShad by remember { mutableStateOf("") }
    var batchBlur by remember { mutableStateOf("") }
    var batchFscx by remember { mutableStateOf("") }
    var batchFscy by remember { mutableStateOf("") }
    var batchFrz by remember { mutableStateOf("") }

    var activeColorPickerKey by remember { mutableStateOf<String?>(null) }
    var activeColorPickerInit by remember { mutableStateOf(Color.White) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("batch_ass_tag_applicator_section")
    ) {
        // Info Banner
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "TÜM ALTYAZILARA TOPLU ASS TAG UYGULA",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Burada belirleyeceğiniz etiketler projedeki tüm $totalCuesCount altyazı olayına tek işlemde uygulanır. Var olan diğer etiketler (\\pos gibi) ezilmez; aynı etiket varsa güncellenir. Tek işlemle Geri Alınabilir (Undo).",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 1. Yazı Tipi ve Boyutu
        Text("1. Yazı Boyutu & Yazı Tipi (\\fs, \\fn)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = batchFs,
                onValueChange = { batchFs = it.filter { ch -> ch.isDigit() } },
                label = { Text("Boyut (\\fs)") },
                placeholder = { Text("100") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("batch_fs_input")
            )

            OutlinedTextField(
                value = batchFn,
                onValueChange = { batchFn = it },
                label = { Text("Yazı Tipi (\\fn)") },
                placeholder = { Text("Trebuchet MS") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("batch_fn_input")
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 2. Hizalama
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("2. Hizalama Noktası (\\an)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            if (batchAn != null) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { batchAn = null }
                ) {
                    Text("Temizle", fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(
                listOf(7 to "\\an7 Üst-Sol", 8 to "\\an8 Üst-Orta", 9 to "\\an9 Üst-Sağ"),
                listOf(4 to "\\an4 Orta-Sol", 5 to "\\an5 Orta-Merkez", 6 to "\\an6 Orta-Sağ"),
                listOf(1 to "\\an1 Alt-Sol", 2 to "\\an2 Alt-Orta", 3 to "\\an3 Alt-Sağ")
            ).forEach { rowList ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    rowList.forEach { (anVal, label) ->
                        val isSelected = batchAn == anVal
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    batchAn = if (isSelected) null else anVal
                                }
                                .testTag("batch_an_$anVal")
                        ) {
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 3. Biçimlendirme
        Text("3. Metin Biçimlendirme (\\b, \\i, \\u, \\s)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = batchBold == "1",
                onClick = { batchBold = if (batchBold == "1") null else "1" },
                label = { Text("\\b1 Kalın", fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.FormatBold, contentDescription = null, modifier = Modifier.size(14.dp)) }
            )
            FilterChip(
                selected = batchBold == "0",
                onClick = { batchBold = if (batchBold == "0") null else "0" },
                label = { Text("\\b0 Normal", fontSize = 11.sp) }
            )
            FilterChip(
                selected = batchItalic == "1",
                onClick = { batchItalic = if (batchItalic == "1") null else "1" },
                label = { Text("\\i1 İtalik", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.FormatItalic, contentDescription = null, modifier = Modifier.size(14.dp)) }
            )
            FilterChip(
                selected = batchItalic == "0",
                onClick = { batchItalic = if (batchItalic == "0") null else "0" },
                label = { Text("\\i0 Düz", fontSize = 11.sp) }
            )
            FilterChip(
                selected = batchUnderline == "1",
                onClick = { batchUnderline = if (batchUnderline == "1") null else "1" },
                label = { Text("\\u1 Altı Çizili", textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline, fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.FormatUnderlined, contentDescription = null, modifier = Modifier.size(14.dp)) }
            )
            FilterChip(
                selected = batchUnderline == "0",
                onClick = { batchUnderline = if (batchUnderline == "0") null else "0" },
                label = { Text("\\u0 Çizgisiz", fontSize = 11.sp) }
            )
            FilterChip(
                selected = batchStrike == "1",
                onClick = { batchStrike = if (batchStrike == "1") null else "1" },
                label = { Text("\\s1 Üstü Çizili", textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough, fontSize = 11.sp) }
            )
            FilterChip(
                selected = batchStrike == "0",
                onClick = { batchStrike = if (batchStrike == "0") null else "0" },
                label = { Text("\\s0 Normal", fontSize = 11.sp) }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 4. Renkler (\1c, \2c, \3c, \4c)
        Text("4. Renkler (\\1c, \\2c, \\3c, \\4c)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val cCol = AssTagManager.assColorToColor(batchColorC) ?: Color.White
            AssColorButton(
                title = "Yazı (\\1c/\\c)",
                color = cCol,
                assHex = batchColorC ?: "Seçilmedi",
                modifier = Modifier.weight(1f),
                onClick = {
                    activeColorPickerKey = "c"
                    activeColorPickerInit = cCol
                },
                onClear = { batchColorC = null }
            )

            val c2Col = AssTagManager.assColorToColor(batchColor2c) ?: Color.Cyan
            AssColorButton(
                title = "İkincil (\\2c)",
                color = c2Col,
                assHex = batchColor2c ?: "Seçilmedi",
                modifier = Modifier.weight(1f),
                onClick = {
                    activeColorPickerKey = "2c"
                    activeColorPickerInit = c2Col
                },
                onClear = { batchColor2c = null }
            )

            val c3Col = AssTagManager.assColorToColor(batchColor3c) ?: Color.Black
            AssColorButton(
                title = "Kenar (\\3c)",
                color = c3Col,
                assHex = batchColor3c ?: "Seçilmedi",
                modifier = Modifier.weight(1f),
                onClick = {
                    activeColorPickerKey = "3c"
                    activeColorPickerInit = c3Col
                },
                onClear = { batchColor3c = null }
            )

            val c4Col = AssTagManager.assColorToColor(batchColor4c) ?: Color.DarkGray
            AssColorButton(
                title = "Gölge (\\4c)",
                color = c4Col,
                assHex = batchColor4c ?: "Seçilmedi",
                modifier = Modifier.weight(1f),
                onClick = {
                    activeColorPickerKey = "4c"
                    activeColorPickerInit = c4Col
                },
                onClear = { batchColor4c = null }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 5. Saydamlık (Alpha: \alpha, \1a, \2a, \3a, \4a)
        Text("5. Saydamlık / Alpha (\\alpha, \\1a, \\2a, \\3a, \\4a)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "ASS Hex formatı (&H00& = Opak, &H80& = %50 Saydam, &HFF& = Görünmez)",
            style = MaterialTheme.typography.bodySmall,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))

        // Genel Alpha (\alpha)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = batchAlpha,
                onValueChange = { batchAlpha = it },
                label = { Text("Genel (\\alpha)") },
                placeholder = { Text("&H00&") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f).testTag("batch_alpha_input")
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { batchAlpha = "&H00&" }
                ) {
                    Text("Opak", fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp))
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { batchAlpha = "&H80&" }
                ) {
                    Text("%50", fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp))
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { batchAlpha = "&HFF&" }
                ) {
                    Text("Gizli", fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Kanal Bazlı Alpha (\1a, \2a, \3a, \4a)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = batchA1,
                onValueChange = { batchA1 = it },
                label = { Text("Yazı (\\1a)") },
                placeholder = { Text("00") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = batchA2,
                onValueChange = { batchA2 = it },
                label = { Text("İkincil (\\2a)") },
                placeholder = { Text("00") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = batchA3,
                onValueChange = { batchA3 = it },
                label = { Text("Kenar (\\3a)") },
                placeholder = { Text("00") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = batchA4,
                onValueChange = { batchA4 = it },
                label = { Text("Gölge (\\4a)") },
                placeholder = { Text("00") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 6. Kenar & Gölge & Bulanıklık
        Text("6. Kenar, Gölge & Bulanıklık (\\bord, \\shad, \\blur)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = batchBord,
                onValueChange = { batchBord = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = { Text("Kenar (\\bord)") },
                placeholder = { Text("3") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            )

            OutlinedTextField(
                value = batchShad,
                onValueChange = { batchShad = it.filter { ch -> ch.isDigit() || ch == '.' || ch == '-' } },
                label = { Text("Gölge (\\shad)") },
                placeholder = { Text("2") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            )

            OutlinedTextField(
                value = batchBlur,
                onValueChange = { batchBlur = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = { Text("Bulanıklık (\\blur)") },
                placeholder = { Text("1") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 7. Ölçek & Açı
        Text("7. Ölçek & Açı (\\fscx, \\fscy, \\frz)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = batchFscx,
                onValueChange = { batchFscx = it.filter { ch -> ch.isDigit() } },
                label = { Text("Ölçek X% (\\fscx)") },
                placeholder = { Text("100") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            )

            OutlinedTextField(
                value = batchFscy,
                onValueChange = { batchFscy = it.filter { ch -> ch.isDigit() } },
                label = { Text("Ölçek Y% (\\fscy)") },
                placeholder = { Text("100") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            )

            OutlinedTextField(
                value = batchFrz,
                onValueChange = { batchFrz = it.filter { ch -> ch.isDigit() || ch == '-' } },
                label = { Text("Açı (\\frz)") },
                placeholder = { Text("0") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("İptal")
            }

            Button(
                onClick = {
                    val map = mutableMapOf<String, String?>()
                    if (batchFs.isNotBlank()) map["fs"] = batchFs
                    if (batchFn.isNotBlank()) map["fn"] = batchFn.trim()
                    if (batchAn != null) map["an"] = batchAn.toString()
                    if (batchBold != null) map["b"] = batchBold
                    if (batchItalic != null) map["i"] = batchItalic
                    if (batchUnderline != null) map["u"] = batchUnderline
                    if (batchStrike != null) map["s"] = batchStrike
                    if (batchColorC != null) {
                        map["c"] = batchColorC
                        map["1c"] = batchColorC
                    }
                    if (batchColor2c != null) map["2c"] = batchColor2c
                    if (batchColor3c != null) map["3c"] = batchColor3c
                    if (batchColor4c != null) map["4c"] = batchColor4c
                    if (batchAlpha.isNotBlank()) {
                        val v = batchAlpha.trim()
                        map["alpha"] = if (v.startsWith("&H", ignoreCase = true)) v else "&H${v.replace("&", "").trim()}&"
                    }
                    if (batchA1.isNotBlank()) {
                        val v = batchA1.trim()
                        map["1a"] = if (v.startsWith("&H", ignoreCase = true)) v else "&H${v.replace("&", "").trim()}&"
                    }
                    if (batchA2.isNotBlank()) {
                        val v = batchA2.trim()
                        map["2a"] = if (v.startsWith("&H", ignoreCase = true)) v else "&H${v.replace("&", "").trim()}&"
                    }
                    if (batchA3.isNotBlank()) {
                        val v = batchA3.trim()
                        map["3a"] = if (v.startsWith("&H", ignoreCase = true)) v else "&H${v.replace("&", "").trim()}&"
                    }
                    if (batchA4.isNotBlank()) {
                        val v = batchA4.trim()
                        map["4a"] = if (v.startsWith("&H", ignoreCase = true)) v else "&H${v.replace("&", "").trim()}&"
                    }
                    if (batchBord.isNotBlank()) map["bord"] = batchBord
                    if (batchShad.isNotBlank()) map["shad"] = batchShad
                    if (batchBlur.isNotBlank()) map["blur"] = batchBlur
                    if (batchFscx.isNotBlank()) map["fscx"] = batchFscx
                    if (batchFscy.isNotBlank()) map["fscy"] = batchFscy
                    if (batchFrz.isNotBlank()) map["frz"] = batchFrz

                    onApplyBatchTags(map)
                    onDismiss()
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(2f)
                    .testTag("apply_batch_tags_button")
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Tüm Altyazılara Uygula ($totalCuesCount Adet)")
            }
        }
    }

    if (activeColorPickerKey != null) {
        RgbColorPickerDialog(
            initialColor = activeColorPickerInit,
            title = when (activeColorPickerKey) {
                "c" -> "Yazı Rengi (\\c)"
                "2c" -> "İkincil Renk (\\2c)"
                "3c" -> "Kenar Rengi (\\3c)"
                "4c" -> "Gölge Rengi (\\4c)"
                else -> "Renk Seç"
            },
            onDismiss = { activeColorPickerKey = null },
            onColorSelected = { chosen ->
                val assColor = AssTagManager.colorToAssColor(chosen)
                when (activeColorPickerKey) {
                    "c" -> batchColorC = assColor
                    "2c" -> batchColor2c = assColor
                    "3c" -> batchColor3c = assColor
                    "4c" -> batchColor4c = assColor
                }
                activeColorPickerKey = null
            }
        )
    }
}

