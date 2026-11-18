package com.example.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.RecordedClip
import com.example.editor.EditorViewModel
import com.example.editor.model.AutoCaptionMode
import com.example.editor.model.EditorExportState
import com.example.editor.model.TransitionSpec
import com.example.editor.model.VoiceoverState
import com.example.ui.components.ClotchInnerCardShape
import com.example.ui.components.ClotchPillShape
import com.example.ui.components.TactileIconButton
import com.example.ui.components.TactilePillButton
import com.example.ui.components.TactileSurface
import com.example.ui.components.tactilePressScale
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ClotchBlue
import com.example.ui.theme.CrimsonAccent
import com.example.ui.theme.CrimsonGlow
import com.example.ui.theme.ElevatedSlate
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.SlateCard
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

private enum class EditorToolTab(val label: String, val icon: ImageVector) {
    AUDIO("Audio", Icons.Filled.GraphicEq),
    TEXT_CAPTIONS("Text & AI", Icons.Filled.TextFields),
    COLOR_SPEED("Color/Speed", Icons.Filled.Palette),
    TRANSFORM("Transform", Icons.Filled.CropRotate),
    PRO_FX("Chroma/4K", Icons.Filled.AutoFixHigh)
}

@Composable
fun EditorScreen(
    editorViewModel: EditorViewModel,
    savedClips: List<RecordedClip>,
    geminiApiKey: String,
    isEditorActive: Boolean = true,
    onNavigateToClips: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val project by editorViewModel.project.collectAsStateWithLifecycle()
    val canUndo by editorViewModel.canUndo.collectAsStateWithLifecycle()
    val canRedo by editorViewModel.canRedo.collectAsStateWithLifecycle()
    val isPlaying by editorViewModel.isPlaying.collectAsStateWithLifecycle()
    val isBusy by editorViewModel.isBusy.collectAsStateWithLifecycle()
    val exportState by editorViewModel.exportState.collectAsStateWithLifecycle()
    val voiceoverState by editorViewModel.voiceoverState.collectAsStateWithLifecycle()
    val autoCaptionState by editorViewModel.autoCaptionState.collectAsStateWithLifecycle()
    val chromaPreviewBitmap by editorViewModel.chromaPreviewBitmap.collectAsStateWithLifecycle()

    var activeToolTab by remember { mutableStateOf(EditorToolTab.AUDIO) }
    var showSavedClipsDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var transitionTargetClipId by remember { mutableStateOf<String?>(null) }
    var pendingCaptionMode by remember { mutableStateOf<AutoCaptionMode?>(null) }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            editorViewModel.importVideoUri(uri, "Gallery Video")
        }
    }

    val bgmPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            editorViewModel.importBgmAudio(uri)
        }
    }

    val voiceoverMicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val captionMode = pendingCaptionMode
            pendingCaptionMode = null
            if (captionMode != null) {
                editorViewModel.generateAutoCaptions(captionMode, geminiApiKey)
            } else {
                editorViewModel.startVoiceover()
            }
        } else {
            pendingCaptionMode = null
            editorViewModel.showToast("Microphone permission is required")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            editorViewModel.stopPreviewForExit()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top Studio Action Bar: Undo, Redo, Zoom, Export Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TactileIconButton(
                        onClick = { editorViewModel.undo() },
                        enabled = canUndo,
                        modifier = Modifier.testTag("editor_undo_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo",
                            tint = if (canUndo) TextPrimary else TextMuted
                        )
                    }
                    TactileIconButton(
                        onClick = { editorViewModel.redo() },
                        enabled = canRedo,
                        modifier = Modifier.testTag("editor_redo_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Redo,
                            contentDescription = "Redo",
                            tint = if (canRedo) TextPrimary else TextMuted
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TactileIconButton(
                        onClick = { editorViewModel.zoomOut() },
                        modifier = Modifier.testTag("editor_zoom_out_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ZoomOut,
                            contentDescription = "Zoom out timeline",
                            tint = TextSecondary
                        )
                    }
                    Surface(
                        color = SlateCard,
                        shape = ClotchPillShape
                    ) {
                        Text(
                            text = String.format(Locale.US, "%.1fx", project.zoomLevel),
                            fontFamily = JetBrainsMonoFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                    TactileIconButton(
                        onClick = { editorViewModel.zoomIn() },
                        modifier = Modifier.testTag("editor_zoom_in_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ZoomIn,
                            contentDescription = "Zoom in timeline",
                            tint = TextSecondary
                        )
                    }
                }

                TactilePillButton(
                    onClick = {
                        editorViewModel.resetExportState()
                        showExportDialog = true
                    },
                    enabled = project.videoClips.isNotEmpty() && !isBusy,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("editor_export_button"),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                    containerColor = ClotchBlue,
                    contentColor = TextPrimary
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = "Export video",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export", style = MaterialTheme.typography.labelLarge)
                }
            }

            // Interactive Media3 Preview Viewport
            EditorPreviewStage(
                project = project,
                playheadMsFlow = editorViewModel.playheadMs,
                exoPlayer = editorViewModel.exoPlayer,
                isBusy = isBusy,
                voiceoverState = voiceoverState,
                isEditorActive = isEditorActive,
                onPreparePreview = { editorViewModel.preparePreviewIfNeeded() },
                onStopPreview = { editorViewModel.stopPreviewForExit() },
                onImportGallery = {
                    videoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                },
                onOpenSavedClips = { showSavedClipsDialog = true },
                onSelectOverlay = { ovId ->
                    editorViewModel.selectOverlay(ovId)
                    activeToolTab = EditorToolTab.TEXT_CAPTIONS
                },
                onSelectSticker = { stId ->
                    editorViewModel.selectSticker(stId)
                    activeToolTab = EditorToolTab.TEXT_CAPTIONS
                }
            )

            // Playback Transport & Clip Edit Actions Bar (Borderless Tonal Surface)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SlateCard,
                shape = ClotchInnerCardShape
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TactileIconButton(
                            onClick = { editorViewModel.stepFrame(-1) },
                            enabled = project.videoClips.isNotEmpty(),
                            hapticType = HapticFeedbackType.TextHandleMove,
                            modifier = Modifier.testTag("editor_step_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ChevronLeft,
                                contentDescription = "Previous frame",
                                tint = if (project.videoClips.isNotEmpty()) TextSecondary else TextMuted
                            )
                        }

                        TactileSurface(
                            onClick = { editorViewModel.togglePlayPause() },
                            enabled = project.videoClips.isNotEmpty(),
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("editor_play_pause_button"),
                            shape = CircleShape,
                            color = if (project.videoClips.isNotEmpty()) ClotchBlue else ElevatedSlate,
                            pressedScale = 0.92f,
                            hapticType = HapticFeedbackType.LongPress
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause preview" else "Play preview",
                                    tint = TextPrimary
                                )
                            }
                        }

                        TactileIconButton(
                            onClick = { editorViewModel.stepFrame(1) },
                            enabled = project.videoClips.isNotEmpty(),
                            hapticType = HapticFeedbackType.TextHandleMove,
                            modifier = Modifier.testTag("editor_step_forward_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = "Next frame",
                                tint = if (project.videoClips.isNotEmpty()) TextSecondary else TextMuted
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))
                        EditorPlayheadTimecodeText(
                            playheadMsFlow = editorViewModel.playheadMs,
                            totalDurationMs = project.totalTimelineDurationMs
                        )
                    }

                    val hasSelection = project.selectedVideoClipId != null ||
                        project.selectedAudioClipId != null ||
                        project.selectedOverlayId != null ||
                        project.selectedStickerId != null
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        TactileIconButton(
                            onClick = { editorViewModel.splitAtPlayhead() },
                            enabled = project.videoClips.isNotEmpty(),
                            hapticType = HapticFeedbackType.LongPress,
                            modifier = Modifier.testTag("editor_split_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCut,
                                contentDescription = "Split clip at playhead",
                                tint = if (project.videoClips.isNotEmpty()) TextPrimary else TextMuted
                            )
                        }
                        TactileIconButton(
                            onClick = { editorViewModel.duplicateSelectedClip() },
                            enabled = hasSelection,
                            hapticType = HapticFeedbackType.LongPress,
                            modifier = Modifier.testTag("editor_duplicate_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "Duplicate selected item",
                                tint = if (hasSelection) TextPrimary else TextMuted
                            )
                        }
                        TactileIconButton(
                            onClick = { editorViewModel.deleteSelectedClip() },
                            enabled = hasSelection,
                            hapticType = HapticFeedbackType.LongPress,
                            modifier = Modifier.testTag("editor_delete_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = "Delete selected item",
                                tint = if (hasSelection) CrimsonAccent else TextMuted
                            )
                        }
                    }
                }
            }

            // Media Import & Voiceover Pill Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EditorActionPill(
                    label = "+ Video",
                    icon = Icons.Filled.AddPhotoAlternate,
                    testTag = "add_gallery_video_button",
                    onClick = {
                        videoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    }
                )
                EditorActionPill(
                    label = "+ Clip",
                    icon = Icons.Filled.VideoLibrary,
                    testTag = "add_saved_clip_button",
                    onClick = { showSavedClipsDialog = true }
                )
                EditorActionPill(
                    label = "+ BGM",
                    icon = Icons.Filled.LibraryMusic,
                    testTag = "add_bgm_button",
                    onClick = { bgmPickerLauncher.launch(arrayOf("audio/*")) }
                )
                val isRecVoice = voiceoverState is VoiceoverState.Recording
                EditorActionPill(
                    label = if (isRecVoice) "Stop VO" else "Voiceover",
                    icon = if (isRecVoice) Icons.Filled.Stop else Icons.Filled.Mic,
                    accent = isRecVoice,
                    testTag = "voiceover_button",
                    onClick = {
                        if (isRecVoice) {
                            editorViewModel.stopVoiceover()
                        } else {
                            val hasMic = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasMic) {
                                editorViewModel.startVoiceover()
                            } else {
                                pendingCaptionMode = null
                                voiceoverMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }
                )
            }

            // Multi-Track Interactive InShot-Style Timeline
            TimelineCanvas(
                project = project,
                playheadMsFlow = editorViewModel.playheadMs,
                thumbnailCache = editorViewModel.thumbnailCache,
                isPlaying = isPlaying,
                onSeekTo = { editorViewModel.seekTo(it) },
                onScrubFinished = { editorViewModel.finishScrubbing() },
                onSelectVideoClip = { editorViewModel.selectVideoClip(it) },
                onSelectAudioClip = { editorViewModel.selectAudioClip(it) },
                onSelectOverlay = { ovId ->
                    editorViewModel.selectOverlay(ovId)
                    activeToolTab = EditorToolTab.TEXT_CAPTIONS
                },
                onSelectSticker = { stId ->
                    editorViewModel.selectSticker(stId)
                    activeToolTab = EditorToolTab.TEXT_CAPTIONS
                },
                onOpenTransitionPicker = { clipId -> transitionTargetClipId = clipId },
                onBeginContinuousEdit = { editorViewModel.beginContinuousEdit() },
                onCommitContinuousEdit = { editorViewModel.commitContinuousEdit() },
                onTrimVideoClip = { clipId, startMs, endMs ->
                    editorViewModel.trimVideoClip(clipId, startMs, endMs, commitUndo = false)
                },
                onMoveVideoClip = { from, to ->
                    editorViewModel.moveVideoClip(from, to)
                },
                onMoveAudioClipStart = { audioId, newStartMs ->
                    editorViewModel.moveAudioClipStart(audioId, newStartMs, commitUndo = false)
                },
                onToggleAudioMute = { audioId ->
                    editorViewModel.toggleAudioTrackMute(audioId)
                },
                onMoveOverlayStart = { overlayId, newStartMs ->
                    editorViewModel.moveOverlayStartMs(overlayId, newStartMs, commitUndo = false)
                }
            )

            // InShot-Style Scrolling Bottom Toolbar Tab Bar with Active Underline Indicator
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = CarbonSurface,
                shape = ClotchInnerCardShape
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EditorToolTab.entries.forEach { tab ->
                        val selected = activeToolTab == tab
                        val interactionSource = remember { MutableInteractionSource() }
                        val fgColor by animateColorAsState(
                            targetValue = if (selected) TextPrimary else TextSecondary,
                            animationSpec = tween(160),
                            label = "tab_fg"
                        )
                        val iconTint by animateColorAsState(
                            targetValue = if (selected) ClotchBlue else TextSecondary,
                            animationSpec = tween(160),
                            label = "tab_icon"
                        )
                        val underlineWidth by animateDpAsState(
                            targetValue = if (selected) 26.dp else 0.dp,
                            animationSpec = tween(200, easing = FastOutSlowInEasing),
                            label = "tab_underline_w"
                        )
                        val underlineAlpha by animateFloatAsState(
                            targetValue = if (selected) 1f else 0f,
                            animationSpec = tween(180),
                            label = "tab_underline_a"
                        )

                        Column(
                            modifier = Modifier
                                .tactilePressScale(interactionSource, pressedScale = 0.93f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = androidx.compose.material3.ripple(color = ClotchBlue)
                                ) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    activeToolTab = tab
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("tool_tab_${tab.name}"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = iconTint,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = fgColor
                            )
                            Box(
                                modifier = Modifier
                                    .width(underlineWidth)
                                    .height(3.dp)
                                    .alpha(underlineAlpha)
                                    .clip(ClotchPillShape)
                                    .background(ClotchBlue)
                            )
                        }
                    }
                }
            }

            // Active Tool Panel
            val selectedVideoIdx = project.videoClips.indexOfFirst { it.id == project.selectedVideoClipId }
            when (activeToolTab) {
                EditorToolTab.AUDIO -> {
                    SelectedClipInspectorPanel(
                        selectedVideoClip = project.selectedVideoClip,
                        selectedVideoIndex = selectedVideoIdx,
                        totalVideoClips = project.videoClips.size,
                        selectedAudioClip = project.selectedAudioClip,
                        onBeginContinuousEdit = { editorViewModel.beginContinuousEdit() },
                        onCommitContinuousEdit = { editorViewModel.commitContinuousEdit() },
                        onVolumeChange = { editorViewModel.setSelectedVolume(it, commitUndo = false) },
                        onToggleMute = { editorViewModel.toggleSelectedMute() },
                        onFadeInChange = { editorViewModel.setSelectedFadeIn(it, commitUndo = false) },
                        onFadeOutChange = { editorViewModel.setSelectedFadeOut(it, commitUndo = false) },
                        onExtractAudio = { editorViewModel.extractAudioFromSelectedClip() },
                        onMoveVideoOrder = { from, to -> editorViewModel.moveVideoClip(from, to) }
                    )
                }
                EditorToolTab.TEXT_CAPTIONS -> {
                    TextAndCaptionsPanel(
                        project = project,
                        geminiApiKey = geminiApiKey,
                        autoCaptionState = autoCaptionState,
                        onAddTextOverlay = { txt, font, col, anim, pill ->
                            editorViewModel.addTextOverlay(txt, font, col, anim, pill)
                        },
                        onUpdateSelectedOverlay = { txt, font, col, anim, pill, dur, px, py ->
                            editorViewModel.updateSelectedTextOverlay(txt, font, col, anim, pill, dur, px, py)
                        },
                        onGenerateCaptions = { mode ->
                            if (mode == AutoCaptionMode.FAST_OFFLINE || geminiApiKey.isBlank()) {
                                val hasMic = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasMic) {
                                    editorViewModel.generateAutoCaptions(mode, geminiApiKey)
                                } else {
                                    pendingCaptionMode = mode
                                    voiceoverMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            } else {
                                editorViewModel.generateAutoCaptions(mode, geminiApiKey)
                            }
                        },
                        onStopOfflineCaption = { editorViewModel.stopOfflineSpeechCaption() },
                        onOpenSettingsForKey = onOpenSettings,
                        onAddSticker = { emoji -> editorViewModel.addSticker(emoji) },
                        onUpdateSelectedSticker = { x, y, sc, dur ->
                            editorViewModel.updateSelectedSticker(x, y, sc, dur)
                        }
                    )
                }
                EditorToolTab.COLOR_SPEED -> {
                    ColorAndSpeedPanel(
                        selectedClip = project.selectedVideoClip,
                        onSelectFilter = { editorViewModel.setSelectedColorFilter(it) },
                        onBeginContinuousEdit = { editorViewModel.beginContinuousEdit() },
                        onCommitContinuousEdit = { editorViewModel.commitContinuousEdit() },
                        onColorAdjust = { b, c, s ->
                            editorViewModel.setSelectedColorAdjust(b, c, s, commitUndo = false)
                        },
                        onSelectSpeed = { editorViewModel.setSelectedClipSpeed(it) },
                        onToggleBlurBackground = { editorViewModel.toggleSelectedBackgroundBlur() }
                    )
                }
                EditorToolTab.TRANSFORM -> {
                    TransformAndKeyframesPanel(
                        project = project,
                        selectedClip = project.selectedVideoClip,
                        onSelectAspectRatio = { editorViewModel.setAspectRatio(it) },
                        onRotate90 = { editorViewModel.rotateSelectedClip90() },
                        onSelectCropMode = { editorViewModel.setSelectedCropMode(it) },
                        onSaveKeyframe = { x, y, sc, op ->
                            editorViewModel.addOrUpdateKeyframeAtPlayhead(x, y, sc, op)
                        },
                        onDeleteKeyframe = { clipId, kfId ->
                            editorViewModel.deleteKeyframe(clipId, kfId)
                        }
                    )
                }
                EditorToolTab.PRO_FX -> {
                    ProEffectsChromaUpscalePanel(
                        selectedClip = project.selectedVideoClip,
                        chromaPreviewBitmap = chromaPreviewBitmap,
                        onToggleReverse = { editorViewModel.toggleSelectedReverse() },
                        onInsertFreezeFrame = { editorViewModel.insertFreezeFrameAtPlayhead(3000L) },
                        onUpdateChromaKey = { editorViewModel.setSelectedChromaKey(it) },
                        onRefreshChromaPreview = { editorViewModel.refreshChromaPreview() },
                        onSelectUpscale = { editorViewModel.setSelectedUpscaleTarget(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (showSavedClipsDialog) {
        SavedClipsPickerDialog(
            clips = savedClips,
            onPickClip = { clip ->
                editorViewModel.openRecordedClip(clip)
                showSavedClipsDialog = false
            },
            onDismiss = { showSavedClipsDialog = false }
        )
    }

    transitionTargetClipId?.let { clipId ->
        TransitionPickerDialog(
            initialSpec = project.transitions[clipId] ?: TransitionSpec(),
            onApply = { spec ->
                editorViewModel.setTransitionAfterClip(clipId, spec)
                transitionTargetClipId = null
            },
            onDismiss = { transitionTargetClipId = null }
        )
    }

    if (showExportDialog) {
        ExportEditorDialog(
            exportState = exportState,
            onStartExport = { cfg -> editorViewModel.exportProject(cfg) },
            onCancelExport = { editorViewModel.cancelExport() },
            onDismiss = {
                if (exportState !is EditorExportState.Exporting) {
                    editorViewModel.resetExportState()
                    showExportDialog = false
                }
            },
            onOpenClips = {
                editorViewModel.resetExportState()
                showExportDialog = false
                onNavigateToClips()
            }
        )
    }
}

@Composable
private fun EditorActionPill(
    label: String,
    icon: ImageVector,
    testTag: String,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    TactileSurface(
        onClick = onClick,
        modifier = Modifier
            .height(48.dp)
            .testTag(testTag),
        shape = ClotchPillShape,
        color = if (accent) CrimsonGlow else SlateCard,
        pressedScale = 0.94f,
        hapticType = HapticFeedbackType.LongPress
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (accent) CrimsonAccent else ClotchBlue,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (accent) CrimsonAccent else TextPrimary
            )
        }
    }
}

@Composable
private fun EditorPlayheadTimecodeText(
    playheadMsFlow: kotlinx.coroutines.flow.StateFlow<Long>,
    totalDurationMs: Long
) {
    val playheadMs by playheadMsFlow.collectAsStateWithLifecycle()
    Text(
        text = "${formatClockMs(playheadMs)} / ${formatClockMs(totalDurationMs)}",
        fontFamily = JetBrainsMonoFamily,
        style = MaterialTheme.typography.labelMedium,
        color = TextPrimary
    )
}

private fun formatClockMs(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
