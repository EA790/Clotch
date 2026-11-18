package com.example.editor

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.example.ClotchApplication
import com.example.data.RecordedClip
import com.example.diagnostics.CrashLogger
import com.example.editor.engine.EditorThumbnailCache
import com.example.editor.engine.ExportRenderResult
import com.example.editor.engine.ExtractAudioResult
import com.example.editor.engine.FFmpegEditorEngine
import com.example.editor.engine.GeminiCaptionResult
import com.example.editor.engine.HybridAutoCaptionEngine
import com.example.editor.engine.HybridChromaKeyProcessor
import com.example.editor.engine.VoiceoverRecorder
import com.example.editor.model.AudioClipKind
import com.example.editor.model.AutoCaptionMode
import com.example.editor.model.AutoCaptionState
import com.example.editor.model.ChromaKeyConfig
import com.example.editor.model.ClipKeyframe
import com.example.editor.model.ColorFilterPreset
import com.example.editor.model.CropMode
import com.example.editor.model.EditorAspectRatio
import com.example.editor.model.EditorExportConfig
import com.example.editor.model.EditorExportState
import com.example.editor.model.EditorFontChoice
import com.example.editor.model.EditorHistoryStack
import com.example.editor.model.EditorProject
import com.example.editor.model.TextAnimationType
import com.example.editor.model.TimelineAudioClip
import com.example.editor.model.TimelineSticker
import com.example.editor.model.TimelineTextOverlay
import com.example.editor.model.TimelineVideoClip
import com.example.editor.model.TransitionSpec
import com.example.editor.model.UpscaleTarget
import com.example.editor.model.VoiceoverState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ClotchApplication
    private val history = EditorHistoryStack()
    val thumbnailCache = EditorThumbnailCache()
    private val voiceoverRecorder = VoiceoverRecorder(application)
    private val captionEngine = HybridAutoCaptionEngine(application)
    private val chromaProcessor = HybridChromaKeyProcessor()

    private val _project = MutableStateFlow(EditorProject())
    val project: StateFlow<EditorProject> = _project.asStateFlow()

    private val _playheadMs = MutableStateFlow(0L)
    val playheadMs: StateFlow<Long> = _playheadMs.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _exportState = MutableStateFlow<EditorExportState>(EditorExportState.Idle)
    val exportState: StateFlow<EditorExportState> = _exportState.asStateFlow()

    private val _voiceoverState = MutableStateFlow<VoiceoverState>(VoiceoverState.Idle)
    val voiceoverState: StateFlow<VoiceoverState> = _voiceoverState.asStateFlow()

    private val _autoCaptionState = MutableStateFlow<AutoCaptionState>(AutoCaptionState.Idle)
    val autoCaptionState: StateFlow<AutoCaptionState> = _autoCaptionState.asStateFlow()

    private val _chromaPreviewBitmap = MutableStateFlow<Bitmap?>(null)
    val chromaPreviewBitmap: StateFlow<Bitmap?> = _chromaPreviewBitmap.asStateFlow()

    @OptIn(UnstableApi::class)
    val exoPlayer: ExoPlayer = run {
        val renderersFactory = DefaultRenderersFactory(application)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2_000,
                10_000,
                250,
                500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        ExoPlayer.Builder(application, renderersFactory)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply {
                setSeekParameters(SeekParameters.CLOSEST_SYNC)
            }
    }
    private val audioPreviewPlayers = mutableMapOf<String, ExoPlayer>()
    private val audioPlayerSignatures = mutableMapOf<String, String>()

    private var dragInitialSnapshot: EditorProject? = null
    private var lastLoadedSignature: String = ""
    private var lastAppliedVideoVol: Float = -1f
    private var lastAppliedSpeed: Float = -1f
    private var playbackTickerJob: Job? = null
    private var voiceoverTickerJob: Job? = null
    private var exportJob: Job? = null
    private var chromaJob: Job? = null

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    syncAudioPreviewToTimelineMs(_playheadMs.value)
                    startPlaybackTicker()
                } else {
                    stopPlaybackTicker()
                    _project.value = _project.value.copy(playheadMs = _playheadMs.value)
                    audioPreviewPlayers.values.forEach { it.pause() }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    _isPlaying.value = false
                    audioPreviewPlayers.values.forEach { it.pause() }
                    seekTo(0L)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                CrashLogger.logError("Media3Preview", "ExoPlayer playback error", error)
                showToast("Preview error logged to ${CrashLogger.getLogDisplayPath()}")
            }
        })
    }

    private fun updateProject(newState: EditorProject, recordUndo: Boolean = true) {
        val prev = _project.value
        if (prev == newState) return
        val syncedState = if (newState.playheadMs != _playheadMs.value) {
            _playheadMs.value = newState.playheadMs
            newState
        } else {
            newState
        }
        if (recordUndo) {
            history.pushSnapshot(prev.copy(playheadMs = _playheadMs.value))
        }
        _project.value = syncedState
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
        syncMedia3PlaylistIfNeeded(syncedState)
        syncAudioPreviewIfNeeded(syncedState)
        applyLivePreviewPlaybackSettings()
    }

    fun importVideoUri(uri: Uri, fallbackName: String = "Imported Clip") {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val info = FFmpegEditorEngine.probeMedia(app, uri, fallbackName)
                val companionTracks = app.recordingController.clipsRepository
                    .findCompanionAudioTracks(info.displayName)
                val cur = _project.value
                val insertionStartMs = cur.totalVideoDurationMs

                val companionAudioClips = companionTracks.map { track ->
                    val resolvedDuration = if (track.durationMs > 250L) {
                        track.durationMs
                    } else {
                        FFmpegEditorEngine.probeMedia(app, track.uri, track.kind.label).durationMs
                    }.coerceAtLeast(info.durationMs.coerceAtMost(500L))
                    TimelineAudioClip(
                        sourceUri = track.uri,
                        displayName = track.kind.label,
                        kind = track.kind,
                        timelineStartMs = insertionStartMs,
                        sourceDurationMs = resolvedDuration,
                        trimStartMs = 0L,
                        trimEndMs = resolvedDuration
                    )
                }

                val hasSeparatedGameTrack = companionAudioClips.any {
                    it.kind == AudioClipKind.GAME || it.kind == AudioClipKind.EXTRACTED
                }

                val newClip = TimelineVideoClip(
                    sourceUri = uri,
                    displayName = info.displayName,
                    sourceDurationMs = info.durationMs,
                    trimStartMs = 0L,
                    trimEndMs = info.durationMs,
                    audioDetached = hasSeparatedGameTrack,
                    width = info.width,
                    height = info.height
                )

                val updated = cur.copy(
                    videoClips = cur.videoClips + newClip,
                    audioClips = cur.audioClips + companionAudioClips,
                    selectedVideoClipId = newClip.id,
                    selectedAudioClipId = null,
                    selectedOverlayId = null,
                    selectedStickerId = null
                )
                updateProject(updated, recordUndo = true)
                if (companionAudioClips.isNotEmpty()) {
                    showToast("Loaded ${info.displayName} + ${companionAudioClips.size} separate audio track(s)")
                } else {
                    showToast("Added ${info.displayName}")
                }
            } catch (t: Throwable) {
                CrashLogger.logError("EditorViewModel.importVideoUri", "Failed to import $uri", t)
                showToast("Failed to import clip")
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun openRecordedClip(clip: RecordedClip) {
        importVideoUri(clip.uri, clip.displayName)
    }

    fun selectVideoClip(clipId: String?) {
        _project.value = _project.value.copy(
            selectedVideoClipId = clipId,
            selectedAudioClipId = null,
            selectedOverlayId = null,
            selectedStickerId = null
        )
        refreshChromaPreview()
    }

    fun selectAudioClip(audioId: String?) {
        _project.value = _project.value.copy(
            selectedAudioClipId = audioId,
            selectedVideoClipId = null,
            selectedOverlayId = null,
            selectedStickerId = null
        )
    }

    fun selectOverlay(overlayId: String?) {
        _project.value = _project.value.copy(
            selectedOverlayId = overlayId,
            selectedVideoClipId = null,
            selectedAudioClipId = null,
            selectedStickerId = null
        )
    }

    fun selectSticker(stickerId: String?) {
        _project.value = _project.value.copy(
            selectedStickerId = stickerId,
            selectedVideoClipId = null,
            selectedAudioClipId = null,
            selectedOverlayId = null
        )
    }

    fun togglePlayPause() {
        if (_project.value.videoClips.isEmpty()) return
        val currentPlayhead = _playheadMs.value
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
            audioPreviewPlayers.values.forEach { it.pause() }
            _project.value = _project.value.copy(playheadMs = currentPlayhead)
        } else {
            syncExoPlayerToTimelineMs(currentPlayhead)
            syncAudioPreviewToTimelineMs(currentPlayhead)
            exoPlayer.play()
            playActiveAudioTracksAt(currentPlayhead)
        }
    }

    fun pausePlayback() {
        stopPlaybackTicker()
        _project.value = _project.value.copy(playheadMs = _playheadMs.value)
        if (exoPlayer.isPlaying) exoPlayer.pause()
        audioPreviewPlayers.values.forEach { if (it.isPlaying) it.pause() }
    }

    fun stopPreviewForExit() {
        stopPlaybackTicker()
        _isPlaying.value = false
        _project.value = _project.value.copy(playheadMs = _playheadMs.value)
        lastAppliedVideoVol = -1f
        lastAppliedSpeed = -1f
        runCatching {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
            }
            exoPlayer.stop()
            exoPlayer.clearVideoSurface()
        }
        audioPreviewPlayers.values.forEach { player ->
            runCatching {
                if (player.isPlaying) {
                    player.pause()
                }
                player.stop()
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun preparePreviewIfNeeded() {
        val current = _project.value
        if (current.videoClips.isEmpty()) return
        val resumePlayhead = _playheadMs.value.coerceIn(0L, current.totalTimelineDurationMs.coerceAtLeast(0L))
        _playheadMs.value = resumePlayhead
        lastAppliedVideoVol = -1f
        lastAppliedSpeed = -1f

        if (exoPlayer.playbackState == Player.STATE_IDLE || exoPlayer.mediaItemCount == 0) {
            lastLoadedSignature = ""
            syncMedia3PlaylistIfNeeded(current.copy(playheadMs = resumePlayhead))
        } else {
            syncExoPlayerToTimelineMs(resumePlayhead)
        }

        current.audioClips.forEach { audio ->
            val player = audioPreviewPlayers[audio.id]
            if (player != null && player.playbackState == Player.STATE_IDLE) {
                if (player.mediaItemCount == 0) {
                    val item = MediaItem.Builder()
                        .setUri(audio.sourceUri)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(audio.trimStartMs)
                                .setEndPositionMs(audio.trimEndMs)
                                .build()
                        )
                        .build()
                    player.setMediaItem(item)
                }
                player.prepare()
            }
        }
        syncAudioPreviewToTimelineMs(resumePlayhead)
        applyLivePreviewPlaybackSettings()
    }

    fun seekTo(timelineMs: Long) {
        val maxDur = _project.value.totalTimelineDurationMs.coerceAtLeast(0L)
        val clamped = timelineMs.coerceIn(0L, maxDur)
        _playheadMs.value = clamped
        if (!exoPlayer.isPlaying) {
            _project.value = _project.value.copy(playheadMs = clamped)
        }
        syncExoPlayerToTimelineMs(clamped)
        syncAudioPreviewToTimelineMs(clamped)
    }

    fun beginContinuousEdit() {
        if (dragInitialSnapshot == null) {
            dragInitialSnapshot = _project.value
        }
    }

    fun commitContinuousEdit() {
        val initial = dragInitialSnapshot ?: return
        dragInitialSnapshot = null
        if (initial != _project.value) {
            history.pushSnapshot(initial)
            _canUndo.value = history.canUndo
            _canRedo.value = history.canRedo
        }
    }

    fun trimVideoClip(clipId: String, newStartMs: Long, newEndMs: Long, commitUndo: Boolean) {
        val cur = _project.value
        val updatedClips = cur.videoClips.map { clip ->
            if (clip.id != clipId) {
                clip
            } else if (clip.isFreezeFrame) {
                clip
            } else {
                val safeStart = newStartMs.coerceIn(0L, clip.sourceDurationMs - 250L)
                val safeEnd = newEndMs.coerceIn(safeStart + 250L, clip.sourceDurationMs)
                clip.copy(trimStartMs = safeStart, trimEndMs = safeEnd)
            }
        }
        val next = cur.copy(
            videoClips = updatedClips,
            playheadMs = cur.playheadMs.coerceIn(0L, updatedClips.sumOf { it.effectiveDurationMs })
        )
        updateProject(next, recordUndo = commitUndo)
    }

    fun splitAtPlayhead() {
        val cur = _project.value
        val curPlayhead = _playheadMs.value
        val targetWindow = cur.findVideoClipAt(curPlayhead) ?: return
        val clip = targetWindow.clip
        if (clip.isFreezeFrame) {
            showToast("Freeze frames cannot be split")
            return
        }
        val offsetIntoClip = curPlayhead - targetWindow.timelineStartMs
        if (offsetIntoClip < 250L || clip.effectiveDurationMs - offsetIntoClip < 250L) {
            showToast("Move playhead at least 0.25s inside a clip to split")
            return
        }
        val sourceOffset = (offsetIntoClip * clip.speed).toLong()
        val splitPointInSource = (clip.trimStartMs + sourceOffset)
            .coerceIn(clip.trimStartMs + 250L, clip.trimEndMs - 250L)
        val leftPart = clip.copy(trimEndMs = splitPointInSource)
        val rightPart = clip.copy(
            id = UUID.randomUUID().toString(),
            trimStartMs = splitPointInSource
        )
        val mutable = cur.videoClips.toMutableList()
        mutable.removeAt(targetWindow.index)
        mutable.add(targetWindow.index, leftPart)
        mutable.add(targetWindow.index + 1, rightPart)
        updateProject(
            cur.copy(
                videoClips = mutable,
                playheadMs = curPlayhead,
                selectedVideoClipId = rightPart.id,
                selectedAudioClipId = null,
                selectedOverlayId = null,
                selectedStickerId = null
            ),
            recordUndo = true
        )
    }

    fun duplicateSelectedClip() {
        val cur = _project.value
        cur.selectedVideoClipId?.let { selectedId ->
            val idx = cur.videoClips.indexOfFirst { it.id == selectedId }
            if (idx >= 0) {
                val copyClip = cur.videoClips[idx].copy(id = UUID.randomUUID().toString())
                val list = cur.videoClips.toMutableList().apply { add(idx + 1, copyClip) }
                updateProject(cur.copy(videoClips = list, selectedVideoClipId = copyClip.id), true)
                return
            }
        }
        cur.selectedAudioClipId?.let { audioId ->
            val audio = cur.audioClips.firstOrNull { it.id == audioId } ?: return
            val dupAudio = audio.copy(
                id = UUID.randomUUID().toString(),
                timelineStartMs = audio.timelineEndMs
            )
            updateProject(
                cur.copy(audioClips = cur.audioClips + dupAudio, selectedAudioClipId = dupAudio.id),
                true
            )
            return
        }
        cur.selectedOverlayId?.let { ovId ->
            val ov = cur.overlays.firstOrNull { it.id == ovId } ?: return
            val dupOv = ov.copy(
                id = UUID.randomUUID().toString(),
                timelineStartMs = ov.timelineEndMs
            )
            updateProject(
                cur.copy(overlays = cur.overlays + dupOv, selectedOverlayId = dupOv.id),
                true
            )
            return
        }
        cur.selectedStickerId?.let { stId ->
            val st = cur.stickers.firstOrNull { it.id == stId } ?: return
            val dupSt = st.copy(
                id = UUID.randomUUID().toString(),
                timelineStartMs = st.timelineEndMs
            )
            updateProject(
                cur.copy(stickers = cur.stickers + dupSt, selectedStickerId = dupSt.id),
                true
            )
        }
    }

    fun deleteSelectedClip() {
        val cur = _project.value
        cur.selectedVideoClipId?.let { selectedVideoId ->
            val remaining = cur.videoClips.filterNot { it.id == selectedVideoId }
            val updatedTransitions = cur.transitions.filterKeys { key -> remaining.any { it.id == key } }
            val newMax = remaining.sumOf { it.effectiveDurationMs }
            updateProject(
                cur.copy(
                    videoClips = remaining,
                    transitions = updatedTransitions,
                    selectedVideoClipId = remaining.firstOrNull()?.id,
                    playheadMs = cur.playheadMs.coerceAtMost(newMax)
                ),
                true
            )
            return
        }
        cur.selectedAudioClipId?.let { selectedAudioId ->
            val remainingAudio = cur.audioClips.filterNot { it.id == selectedAudioId }
            updateProject(cur.copy(audioClips = remainingAudio, selectedAudioClipId = null), true)
            return
        }
        cur.selectedOverlayId?.let { selectedOvId ->
            val remainingOv = cur.overlays.filterNot { it.id == selectedOvId }
            updateProject(cur.copy(overlays = remainingOv, selectedOverlayId = null), true)
            return
        }
        cur.selectedStickerId?.let { selectedStId ->
            val remainingSt = cur.stickers.filterNot { it.id == selectedStId }
            updateProject(cur.copy(stickers = remainingSt, selectedStickerId = null), true)
        }
    }

    fun moveVideoClip(fromIndex: Int, toIndex: Int) {
        val cur = _project.value
        if (fromIndex !in cur.videoClips.indices || toIndex !in cur.videoClips.indices || fromIndex == toIndex) {
            return
        }
        val mutable = cur.videoClips.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        updateProject(cur.copy(videoClips = mutable), recordUndo = true)
    }

    fun moveAudioClipStart(audioId: String, newStartMs: Long, commitUndo: Boolean) {
        val cur = _project.value
        val safeStart = newStartMs.coerceAtLeast(0L)
        val updatedAudio = cur.audioClips.map { clip ->
            if (clip.id == audioId) clip.copy(timelineStartMs = safeStart) else clip
        }
        updateProject(cur.copy(audioClips = updatedAudio), recordUndo = commitUndo)
    }

    fun moveOverlayStartMs(overlayId: String, newStartMs: Long, commitUndo: Boolean) {
        val cur = _project.value
        val safeStart = newStartMs.coerceAtLeast(0L)
        val updated = cur.overlays.map { ov ->
            if (ov.id == overlayId) ov.copy(timelineStartMs = safeStart) else ov
        }
        updateProject(cur.copy(overlays = updated), recordUndo = commitUndo)
    }

    fun zoomIn() {
        val next = (_project.value.zoomLevel * 1.25f).coerceIn(0.5f, 4.0f)
        _project.value = _project.value.copy(zoomLevel = next)
    }

    fun zoomOut() {
        val next = (_project.value.zoomLevel / 1.25f).coerceIn(0.5f, 4.0f)
        _project.value = _project.value.copy(zoomLevel = next)
    }

    fun undo() {
        val previous = history.undo(_project.value.copy(playheadMs = _playheadMs.value)) ?: return
        _playheadMs.value = previous.playheadMs
        _project.value = previous
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
        syncMedia3PlaylistIfNeeded(previous)
        syncAudioPreviewIfNeeded(previous)
        syncExoPlayerToTimelineMs(previous.playheadMs)
    }

    fun redo() {
        val next = history.redo(_project.value.copy(playheadMs = _playheadMs.value)) ?: return
        _playheadMs.value = next.playheadMs
        _project.value = next
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
        syncMedia3PlaylistIfNeeded(next)
        syncAudioPreviewIfNeeded(next)
        syncExoPlayerToTimelineMs(next.playheadMs)
    }

    fun setSelectedVolume(volume: Float, commitUndo: Boolean) {
        val clamped = volume.coerceIn(0f, 2f)
        val cur = _project.value
        cur.selectedVideoClipId?.let { vId ->
            val updated = cur.videoClips.map {
                if (it.id == vId) it.copy(volume = clamped, isMuted = clamped == 0f) else it
            }
            updateProject(cur.copy(videoClips = updated), commitUndo)
            return
        }
        val aId = cur.selectedAudioClipId ?: return
        val updatedAudio = cur.audioClips.map {
            if (it.id == aId) it.copy(volume = clamped, isMuted = clamped == 0f) else it
        }
        updateProject(cur.copy(audioClips = updatedAudio), commitUndo)
    }

    fun toggleSelectedMute() {
        val cur = _project.value
        cur.selectedVideoClipId?.let { vId ->
            val updated = cur.videoClips.map {
                if (it.id == vId) it.copy(isMuted = !it.isMuted) else it
            }
            updateProject(cur.copy(videoClips = updated), true)
            return
        }
        val aId = cur.selectedAudioClipId ?: return
        toggleAudioTrackMute(aId)
    }

    fun toggleAudioTrackMute(audioId: String) {
        val cur = _project.value
        val updatedAudio = cur.audioClips.map {
            if (it.id == audioId) it.copy(isMuted = !it.isMuted) else it
        }
        updateProject(cur.copy(audioClips = updatedAudio), true)
    }

    fun setSelectedFadeIn(fadeInMs: Long, commitUndo: Boolean) {
        val cur = _project.value
        cur.selectedVideoClipId?.let { vId ->
            val updated = cur.videoClips.map {
                if (it.id == vId) it.copy(fadeInMs = fadeInMs.coerceIn(0L, it.effectiveDurationMs / 2)) else it
            }
            updateProject(cur.copy(videoClips = updated), commitUndo)
            return
        }
        val aId = cur.selectedAudioClipId ?: return
        val updatedAudio = cur.audioClips.map {
            if (it.id == aId) it.copy(fadeInMs = fadeInMs.coerceIn(0L, it.effectiveDurationMs / 2)) else it
        }
        updateProject(cur.copy(audioClips = updatedAudio), commitUndo)
    }

    fun setSelectedFadeOut(fadeOutMs: Long, commitUndo: Boolean) {
        val cur = _project.value
        cur.selectedVideoClipId?.let { vId ->
            val updated = cur.videoClips.map {
                if (it.id == vId) it.copy(fadeOutMs = fadeOutMs.coerceIn(0L, it.effectiveDurationMs / 2)) else it
            }
            updateProject(cur.copy(videoClips = updated), commitUndo)
            return
        }
        val aId = cur.selectedAudioClipId ?: return
        val updatedAudio = cur.audioClips.map {
            if (it.id == aId) it.copy(fadeOutMs = fadeOutMs.coerceIn(0L, it.effectiveDurationMs / 2)) else it
        }
        updateProject(cur.copy(audioClips = updatedAudio), commitUndo)
    }

    fun extractAudioFromSelectedClip() {
        val cur = _project.value
        val target = cur.selectedVideoClip ?: return
        if (target.audioDetached) {
            showToast("Audio is already detached from this clip")
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            try {
                when (val res = FFmpegEditorEngine.extractAudioToM4a(app, target)) {
                    is ExtractAudioResult.Success -> {
                        val latest = _project.value
                        val startMs = latest.clipStartTimeMs(target.id)
                        val audioClip = TimelineAudioClip(
                            sourceUri = res.uri,
                            displayName = "${target.displayName} (Audio)",
                            kind = AudioClipKind.EXTRACTED,
                            timelineStartMs = startMs,
                            sourceDurationMs = res.durationMs,
                            trimStartMs = 0L,
                            trimEndMs = res.durationMs
                        )
                        val updatedVideo = latest.videoClips.map {
                            if (it.id == target.id) it.copy(audioDetached = true) else it
                        }
                        updateProject(
                            latest.copy(
                                videoClips = updatedVideo,
                                audioClips = latest.audioClips + audioClip,
                                selectedAudioClipId = audioClip.id,
                                selectedVideoClipId = null
                            ),
                            recordUndo = true
                        )
                        showToast("Audio extracted to audio track")
                    }
                    is ExtractAudioResult.Failure -> showToast(res.message)
                }
            } catch (t: Throwable) {
                CrashLogger.logError("EditorViewModel.extractAudio", "Unexpected failure extracting audio", t)
                showToast("Unable to extract audio")
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun importBgmAudio(uri: Uri) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val info = FFmpegEditorEngine.probeMedia(app, uri, "Background Music")
                val cur = _project.value
                val audioClip = TimelineAudioClip(
                    sourceUri = uri,
                    displayName = info.displayName,
                    kind = AudioClipKind.BGM,
                    timelineStartMs = _playheadMs.value,
                    sourceDurationMs = info.durationMs,
                    trimStartMs = 0L,
                    trimEndMs = info.durationMs,
                    volume = 0.7f
                )
                updateProject(
                    cur.copy(
                        audioClips = cur.audioClips + audioClip,
                        selectedAudioClipId = audioClip.id,
                        selectedVideoClipId = null
                    ),
                    recordUndo = true
                )
                showToast("Added BGM: ${info.displayName}")
            } catch (t: Throwable) {
                CrashLogger.logError("EditorViewModel.importBgm", "Failed importing audio $uri", t)
                showToast("Failed to import audio file")
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun startVoiceover() {
        if (_voiceoverState.value is VoiceoverState.Recording) return
        pausePlayback()
        val anchorMs = _playheadMs.value
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { voiceoverRecorder.startRecording() }
            if (file == null) {
                showToast("Unable to start microphone voiceover")
                return@launch
            }
            _voiceoverState.value = VoiceoverState.Recording(
                startedAtPlayheadMs = anchorMs,
                elapsedMs = 0L,
                outputFilePath = file.absolutePath
            )
            voiceoverTickerJob?.cancel()
            voiceoverTickerJob = viewModelScope.launch {
                val startWall = System.currentTimeMillis()
                while (isActive && _voiceoverState.value is VoiceoverState.Recording) {
                    val elapsed = System.currentTimeMillis() - startWall
                    _voiceoverState.value = VoiceoverState.Recording(
                        startedAtPlayheadMs = anchorMs,
                        elapsedMs = elapsed,
                        outputFilePath = file.absolutePath
                    )
                    delay(100L)
                }
            }
        }
    }

    fun stopVoiceover() {
        val currentRec = _voiceoverState.value as? VoiceoverState.Recording ?: return
        voiceoverTickerJob?.cancel()
        _voiceoverState.value = VoiceoverState.Idle
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { voiceoverRecorder.stopRecording() }
            if (result == null) {
                showToast("Voiceover recording was empty")
                return@launch
            }
            val cur = _project.value
            val voiceClip = TimelineAudioClip(
                sourceUri = result.uri,
                displayName = "Voiceover",
                kind = AudioClipKind.VOICEOVER,
                timelineStartMs = currentRec.startedAtPlayheadMs,
                sourceDurationMs = result.durationMs,
                trimStartMs = 0L,
                trimEndMs = result.durationMs,
                volume = 1.2f
            )
            updateProject(
                cur.copy(
                    audioClips = cur.audioClips + voiceClip,
                    selectedAudioClipId = voiceClip.id,
                    selectedVideoClipId = null
                ),
                recordUndo = true
            )
            showToast("Voiceover added to timeline")
        }
    }

    // ==================== Stage 2 Feature Actions ====================

    fun setAspectRatio(ratio: EditorAspectRatio) {
        val cur = _project.value
        if (cur.aspectRatio == ratio) return
        updateProject(cur.copy(aspectRatio = ratio), recordUndo = true)
    }

    fun setSelectedClipSpeed(speed: Float) {
        val cur = _project.value
        val targetId = cur.selectedVideoClipId ?: return
        val safeSpeed = speed.coerceIn(0.5f, 4.0f)
        val updated = cur.videoClips.map {
            if (it.id == targetId && !it.isFreezeFrame) it.copy(speed = safeSpeed) else it
        }
        updateProject(cur.copy(videoClips = updated), recordUndo = true)
    }

    fun setSelectedColorFilter(preset: ColorFilterPreset) {
        val cur = _project.value
        val targetId = cur.selectedVideoClipId ?: return
        val updated = cur.videoClips.map {
            if (it.id == targetId) it.copy(filterPreset = preset) else it
        }
        updateProject(cur.copy(videoClips = updated), recordUndo = true)
    }

    fun setSelectedColorAdjust(
        brightness: Float,
        contrast: Float,
        saturation: Float,
        commitUndo: Boolean
    ) {
        val cur = _project.value
        val targetId = cur.selectedVideoClipId ?: return
        val updated = cur.videoClips.map {
            if (it.id == targetId) {
                it.copy(
                    brightness = brightness.coerceIn(-0.5f, 0.5f),
                    contrast = contrast.coerceIn(0.5f, 2.0f),
                    saturation = saturation.coerceIn(0f, 2.0f)
                )
            } else it
        }
        updateProject(cur.copy(videoClips = updated), recordUndo = commitUndo)
    }

    fun toggleSelectedBackgroundBlur() {
        val cur = _project.value
        val targetId = cur.selectedVideoClipId ?: return
        val updated = cur.videoClips.map {
            if (it.id == targetId) it.copy(blurBackground = !it.blurBackground) else it
        }
        updateProject(cur.copy(videoClips = updated), recordUndo = true)
    }

    fun rotateSelectedClip90() {
        val cur = _project.value
        val targetId = cur.selectedVideoClipId ?: return
        val updated = cur.videoClips.map {
            if (it.id == targetId) it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360) else it
        }
        updateProject(cur.copy(videoClips = updated), recordUndo = true)
    }

    fun setSelectedCropMode(cropMode: CropMode) {
        val cur = _project.value
        val targetId = cur.selectedVideoClipId ?: return
        val updated = cur.videoClips.map {
            if (it.id == targetId) it.copy(cropMode = cropMode) else it
        }
        updateProject(cur.copy(videoClips = updated), recordUndo = true)
    }

    fun addOrUpdateKeyframeAtPlayhead(
        posX: Float,
        posY: Float,
        scale: Float,
        opacity: Float
    ) {
        val cur = _project.value
        val curPlayhead = _playheadMs.value
        val targetWindow = cur.findVideoClipAt(curPlayhead) ?: return
        val clip = targetWindow.clip
        val localOffset = (curPlayhead - targetWindow.timelineStartMs).coerceIn(0L, clip.effectiveDurationMs)

        val newKf = ClipKeyframe(
            timeOffsetMs = localOffset,
            posX = posX.coerceIn(-0.4f, 0.4f),
            posY = posY.coerceIn(-0.4f, 0.4f),
            scale = scale.coerceIn(0.5f, 2.5f),
            opacity = opacity.coerceIn(0.1f, 1.0f)
        )
        val updatedList = clip.keyframes
            .filterNot { kotlin.math.abs(it.timeOffsetMs - localOffset) < 120L }
            .plus(newKf)
            .sortedBy { it.timeOffsetMs }

        val updatedClips = cur.videoClips.map {
            if (it.id == clip.id) it.copy(keyframes = updatedList) else it
        }
        updateProject(
            cur.copy(videoClips = updatedClips, selectedVideoClipId = clip.id),
            recordUndo = true
        )
        showToast("Keyframe saved at ${localOffset / 1000.0}s")
    }

    fun deleteKeyframe(clipId: String, keyframeId: String) {
        val cur = _project.value
        val updatedClips = cur.videoClips.map { clip ->
            if (clip.id == clipId) {
                clip.copy(keyframes = clip.keyframes.filterNot { it.id == keyframeId })
            } else clip
        }
        updateProject(cur.copy(videoClips = updatedClips), recordUndo = true)
    }

    fun setTransitionAfterClip(clipId: String, spec: TransitionSpec) {
        val cur = _project.value
        val nextTransitions = cur.transitions.toMutableMap()
        if (spec.type == com.example.editor.model.TransitionType.NONE) {
            nextTransitions.remove(clipId)
        } else {
            nextTransitions[clipId] = spec
        }
        updateProject(cur.copy(transitions = nextTransitions), recordUndo = true)
    }

    fun toggleSelectedReverse() {
        val cur = _project.value
        val targetId = cur.selectedVideoClipId ?: return
        val updated = cur.videoClips.map {
            if (it.id == targetId && !it.isFreezeFrame) it.copy(isReversed = !it.isReversed) else it
        }
        updateProject(cur.copy(videoClips = updated), recordUndo = true)
    }

    fun insertFreezeFrameAtPlayhead(durationMs: Long = 3000L) {
        val cur = _project.value
        val curPlayhead = _playheadMs.value
        val window = cur.findVideoClipAt(curPlayhead) ?: return
        val clip = window.clip
        val offsetInClip = (curPlayhead - window.timelineStartMs).coerceIn(0L, clip.effectiveDurationMs)
        val sourceTimeMs = (clip.trimStartMs + (offsetInClip * clip.speed).toLong())
            .coerceIn(clip.trimStartMs, clip.trimEndMs)

        viewModelScope.launch {
            _isBusy.value = true
            val result = try {
                FFmpegEditorEngine.createFreezeFrameMedia(app, clip, sourceTimeMs, durationMs)
            } finally {
                _isBusy.value = false
            }
            if (result == null) {
                showToast("Unable to capture freeze frame")
                return@launch
            }
            val freezeClip = TimelineVideoClip(
                sourceUri = result.mp4Uri,
                displayName = "${clip.displayName} (Freeze)",
                sourceDurationMs = durationMs,
                trimStartMs = 0L,
                trimEndMs = durationMs,
                width = result.width,
                height = result.height,
                isFreezeFrame = true,
                freezeImagePath = result.pngPath
            )
            val latest = _project.value
            val insertIdx = (latest.videoClips.indexOfFirst { it.id == clip.id } + 1)
                .coerceIn(0, latest.videoClips.size)
            val mutable = latest.videoClips.toMutableList().apply { add(insertIdx, freezeClip) }
            updateProject(
                latest.copy(
                    videoClips = mutable,
                    selectedVideoClipId = freezeClip.id,
                    selectedAudioClipId = null
                ),
                recordUndo = true
            )
            showToast("Inserted ${durationMs / 1000L}s Freeze Frame")
        }
    }

    fun setSelectedChromaKey(config: ChromaKeyConfig) {
        val cur = _project.value
        val targetId = cur.selectedVideoClipId ?: return
        val updated = cur.videoClips.map {
            if (it.id == targetId) it.copy(chromaKey = config) else it
        }
        updateProject(cur.copy(videoClips = updated), recordUndo = true)
        refreshChromaPreview()
    }

    fun refreshChromaPreview() {
        val clip = _project.value.selectedVideoClip
        if (clip == null || !clip.chromaKey.enabled) {
            _chromaPreviewBitmap.value = null
            return
        }
        val cur = _project.value
        val curPlayhead = _playheadMs.value
        val window = cur.findVideoClipAt(curPlayhead)
        val sampleSourceMs = if (window != null && window.clip.id == clip.id) {
            val offset = (curPlayhead - window.timelineStartMs).coerceIn(0L, clip.effectiveDurationMs)
            clip.trimStartMs + (offset * clip.speed).toLong()
        } else {
            clip.trimStartMs + clip.sourceSpanMs / 2L
        }

        chromaJob?.cancel()
        chromaJob = viewModelScope.launch {
            val frame = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(app, clip.sourceUri)
                    retriever.getScaledFrameAtTime(
                        sampleSourceMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        320,
                        180
                    )
                } catch (_: Throwable) {
                    null
                } finally {
                    runCatching { retriever.release() }
                }
            } ?: return@launch
            val cutout = chromaProcessor.previewSegmentedFrame(frame, clip.chromaKey)
            frame.recycle()
            _chromaPreviewBitmap.value = cutout
        }
    }

    fun setSelectedUpscaleTarget(target: UpscaleTarget) {
        val cur = _project.value
        val targetId = cur.selectedVideoClipId ?: return
        val updated = cur.videoClips.map {
            if (it.id == targetId) it.copy(upscaleTarget = target) else it
        }
        updateProject(cur.copy(videoClips = updated), recordUndo = true)
    }

    fun addTextOverlay(
        text: String,
        fontChoice: EditorFontChoice,
        colorArgb: Long,
        animation: TextAnimationType,
        hasBackgroundPill: Boolean,
        isCaption: Boolean = false
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val cur = _project.value
        val overlay = TimelineTextOverlay(
            text = trimmed,
            timelineStartMs = _playheadMs.value,
            durationMs = 3000L,
            fontChoice = fontChoice,
            colorArgb = colorArgb,
            hasBackgroundPill = hasBackgroundPill,
            animation = animation,
            posX = 0.5f,
            posY = if (isCaption) 0.84f else 0.28f,
            fontSizeSp = if (isCaption) 19f else 24f,
            isCaption = isCaption
        )
        updateProject(
            cur.copy(
                overlays = cur.overlays + overlay,
                selectedOverlayId = overlay.id,
                selectedVideoClipId = null,
                selectedAudioClipId = null,
                selectedStickerId = null
            ),
            recordUndo = true
        )
    }

    fun updateSelectedTextOverlay(
        newText: String,
        fontChoice: EditorFontChoice,
        colorArgb: Long,
        animation: TextAnimationType,
        hasBackgroundPill: Boolean,
        durationMs: Long,
        posX: Float,
        posY: Float
    ) {
        val cur = _project.value
        val targetId = cur.selectedOverlayId ?: return
        val updated = cur.overlays.map { ov ->
            if (ov.id == targetId) {
                ov.copy(
                    text = newText,
                    fontChoice = fontChoice,
                    colorArgb = colorArgb,
                    animation = animation,
                    hasBackgroundPill = hasBackgroundPill,
                    durationMs = durationMs.coerceIn(400L, 60_000L),
                    posX = posX.coerceIn(0.1f, 0.9f),
                    posY = posY.coerceIn(0.1f, 0.9f)
                )
            } else ov
        }
        updateProject(cur.copy(overlays = updated), recordUndo = true)
    }

    fun addSticker(emoji: String) {
        val cur = _project.value
        val sticker = TimelineSticker(
            emoji = emoji,
            timelineStartMs = _playheadMs.value,
            durationMs = 3000L,
            posX = 0.5f,
            posY = 0.35f,
            scale = 1.0f
        )
        updateProject(
            cur.copy(
                stickers = cur.stickers + sticker,
                selectedStickerId = sticker.id,
                selectedVideoClipId = null,
                selectedAudioClipId = null,
                selectedOverlayId = null
            ),
            recordUndo = true
        )
    }

    fun updateSelectedSticker(posX: Float, posY: Float, scale: Float, durationMs: Long) {
        val cur = _project.value
        val targetId = cur.selectedStickerId ?: return
        val updated = cur.stickers.map { st ->
            if (st.id == targetId) {
                st.copy(
                    posX = posX.coerceIn(0.1f, 0.9f),
                    posY = posY.coerceIn(0.1f, 0.9f),
                    scale = scale.coerceIn(0.5f, 2.5f),
                    durationMs = durationMs.coerceIn(400L, 60_000L)
                )
            } else st
        }
        updateProject(cur.copy(stickers = updated), recordUndo = true)
    }

    fun generateAutoCaptions(mode: AutoCaptionMode, geminiApiKey: String) {
        val cur = _project.value
        val window = cur.findVideoClipAt(_playheadMs.value) ?: cur.videoWindows().firstOrNull()
        if (window == null) {
            showToast("Import a video clip before generating captions")
            return
        }

        if (mode == AutoCaptionMode.FAST_OFFLINE) {
            startOfflineSpeechCaption()
            return
        }

        viewModelScope.launch {
            _autoCaptionState.value = AutoCaptionState.Generating("Transcribing with Gemini 1.5 Flash...")
            when (
                val res = captionEngine.generateWithGemini(
                    clip = window.clip,
                    clipTimelineStartMs = window.timelineStartMs,
                    apiKey = geminiApiKey
                )
            ) {
                is GeminiCaptionResult.Success -> {
                    if (res.captions.isEmpty()) {
                        _autoCaptionState.value = AutoCaptionState.Message("Gemini detected no speech. Tap Offline Mic or + Caption to add manually.")
                    } else {
                        val latest = _project.value
                        updateProject(
                            latest.copy(
                                overlays = latest.overlays + res.captions,
                                selectedOverlayId = res.captions.first().id,
                                selectedVideoClipId = null
                            ),
                            recordUndo = true
                        )
                        _autoCaptionState.value = AutoCaptionState.Message("Added ${res.captions.size} AI captions to timeline")
                    }
                }
                is GeminiCaptionResult.FallbackToOffline -> {
                    _autoCaptionState.value = AutoCaptionState.Message(res.reason)
                    startOfflineSpeechCaption()
                }
            }
        }
    }

    fun startOfflineSpeechCaption() {
        val anchorMs = _playheadMs.value
        _autoCaptionState.value = AutoCaptionState.ListeningOffline("Listening on-device (speak now)...")
        captionEngine.startOfflineSpeechCapture(
            playheadAnchorMs = anchorMs,
            onPartial = { partial ->
                _autoCaptionState.value = AutoCaptionState.ListeningOffline(partial)
            },
            onSegmentReady = { captionOverlay ->
                val latest = _project.value
                updateProject(
                    latest.copy(
                        overlays = latest.overlays + captionOverlay,
                        selectedOverlayId = captionOverlay.id,
                        selectedVideoClipId = null
                    ),
                    recordUndo = true
                )
                showToast("Caption added to timeline")
            },
            onFinished = { errMsg ->
                _autoCaptionState.value = if (errMsg != null) {
                    AutoCaptionState.Message(errMsg)
                } else {
                    AutoCaptionState.Idle
                }
            }
        )
    }

    fun stopOfflineSpeechCaption() {
        captionEngine.stopOfflineSpeechCapture()
        _autoCaptionState.value = AutoCaptionState.Idle
    }

    fun clearAutoCaptionMessage() {
        _autoCaptionState.value = AutoCaptionState.Idle
    }

    // ==================== Export ====================

    fun exportProject(config: EditorExportConfig) {
        if (_exportState.value is EditorExportState.Exporting) return
        pausePlayback()
        val snapshot = _project.value.copy(playheadMs = _playheadMs.value)
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            _exportState.value = EditorExportState.Exporting(0f, "Preparing export...")
            when (
                val res = FFmpegEditorEngine.exportTimeline(
                    context = app,
                    project = snapshot,
                    config = config,
                    chromaProcessor = chromaProcessor
                ) { p, msg ->
                    _exportState.value = EditorExportState.Exporting(p, msg)
                }
            ) {
                is ExportRenderResult.Success -> {
                    app.recordingController.refreshClips()
                    _exportState.value = EditorExportState.Completed(res.uri, res.fileName)
                }
                is ExportRenderResult.Failure -> {
                    _exportState.value = EditorExportState.Error(res.message, res.logPath)
                }
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        _exportState.value = EditorExportState.Idle
    }

    fun resetExportState() {
        _exportState.value = EditorExportState.Idle
    }

    @OptIn(UnstableApi::class)
    private fun syncMedia3PlaylistIfNeeded(project: EditorProject) {
        val signature = project.videoClips.joinToString("|") {
            "${it.id}:${it.sourceUri}:${it.trimStartMs}:${it.trimEndMs}:${it.isFreezeFrame}"
        }
        if (signature == lastLoadedSignature) return
        lastLoadedSignature = signature

        if (project.videoClips.isEmpty()) {
            exoPlayer.clearMediaItems()
            return
        }
        val items = project.videoClips.map { clip ->
            val builder = MediaItem.Builder()
                .setMediaId(clip.id)
                .setUri(clip.sourceUri)
            if (!clip.isFreezeFrame) {
                builder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.trimStartMs)
                        .setEndPositionMs(clip.trimEndMs)
                        .build()
                )
            }
            builder.build()
        }
        val wasPlaying = exoPlayer.isPlaying
        exoPlayer.setMediaItems(items, false)
        exoPlayer.prepare()
        syncExoPlayerToTimelineMs(project.playheadMs)
        applyLivePreviewPlaybackSettings()
        if (wasPlaying) exoPlayer.play()
    }

    @OptIn(UnstableApi::class)
    private fun syncAudioPreviewIfNeeded(project: EditorProject) {
        val activeIds = project.audioClips.map { it.id }.toSet()
        val staleIds = audioPreviewPlayers.keys.filter { it !in activeIds }
        staleIds.forEach { staleId ->
            audioPreviewPlayers.remove(staleId)?.release()
            audioPlayerSignatures.remove(staleId)
        }

        project.audioClips.forEach { audio ->
            val sig = "${audio.sourceUri}:${audio.trimStartMs}:${audio.trimEndMs}"
            if (audioPlayerSignatures[audio.id] != sig) {
                val player = audioPreviewPlayers.getOrPut(audio.id) {
                    ExoPlayer.Builder(app).build()
                }
                audioPlayerSignatures[audio.id] = sig
                val item = MediaItem.Builder()
                    .setUri(audio.sourceUri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(audio.trimStartMs)
                            .setEndPositionMs(audio.trimEndMs)
                            .build()
                    )
                    .build()
                player.setMediaItem(item)
                player.prepare()
            }
        }
        applyLivePreviewPlaybackSettings()
    }

    private fun syncExoPlayerToTimelineMs(timelineMs: Long) {
        val window = _project.value.findVideoClipAt(timelineMs) ?: return
        val localOffset = (timelineMs - window.timelineStartMs).coerceIn(0L, window.clip.effectiveDurationMs)
        val sourcePos = (localOffset * window.clip.speed).toLong()
        if (window.index in 0 until exoPlayer.mediaItemCount) {
            exoPlayer.seekTo(window.index, sourcePos)
        }
        applyLivePreviewPlaybackSettings()
    }

    private fun syncAudioPreviewToTimelineMs(timelineMs: Long) {
        _project.value.audioClips.forEach { audio ->
            val player = audioPreviewPlayers[audio.id] ?: return@forEach
            val offset = timelineMs - audio.timelineStartMs
            if (offset in 0L until audio.effectiveDurationMs) {
                player.seekTo(offset)
            } else {
                player.seekTo(0L)
            }
        }
    }

    private fun playActiveAudioTracksAt(timelineMs: Long) {
        _project.value.audioClips.forEach { audio ->
            val player = audioPreviewPlayers[audio.id] ?: return@forEach
            val inWindow = timelineMs in audio.timelineStartMs until audio.timelineEndMs
            if (inWindow) {
                val offset = (timelineMs - audio.timelineStartMs).coerceIn(0L, audio.effectiveDurationMs)
                if (kotlin.math.abs(player.currentPosition - offset) > 120L) {
                    player.seekTo(offset)
                }
                if (!player.isPlaying) {
                    player.play()
                }
            } else if (player.isPlaying) {
                player.pause()
            }
        }
    }

    private fun applyLivePreviewPlaybackSettings(playheadMsOverride: Long = _playheadMs.value) {
        val cur = _project.value
        val activeWindow = cur.findVideoClipAt(playheadMsOverride)
        val activeClip = activeWindow?.clip
        val vidVol = activeClip?.effectiveVolume?.coerceIn(0f, 1f) ?: 1f
        val speed = activeClip?.speed?.coerceIn(0.5f, 4.0f) ?: 1.0f

        if (vidVol != lastAppliedVideoVol) {
            exoPlayer.volume = vidVol
            lastAppliedVideoVol = vidVol
        }
        if (speed != lastAppliedSpeed) {
            exoPlayer.playbackParameters = PlaybackParameters(speed, 1.0f)
            lastAppliedSpeed = speed
        }

        val isPlayingNow = exoPlayer.isPlaying
        cur.audioClips.forEach { audio ->
            val player = audioPreviewPlayers[audio.id] ?: return@forEach
            val inWindow = playheadMsOverride in audio.timelineStartMs until audio.timelineEndMs
            val targetVol = if (inWindow) audio.effectiveVolume.coerceIn(0f, 1f) else 0f
            if (player.volume != targetVol) {
                player.volume = targetVol
            }
            if (isPlayingNow) {
                if (inWindow && !player.isPlaying) {
                    val offset = (playheadMsOverride - audio.timelineStartMs).coerceIn(0L, audio.effectiveDurationMs)
                    player.seekTo(offset)
                    player.play()
                } else if (!inWindow && player.isPlaying) {
                    player.pause()
                }
            }
        }
    }

    private fun startPlaybackTicker() {
        playbackTickerJob?.cancel()
        playbackTickerJob = viewModelScope.launch {
            while (isActive && exoPlayer.isPlaying) {
                val cur = _project.value
                val idx = exoPlayer.currentMediaItemIndex
                val windows = cur.videoWindows()
                if (idx in windows.indices) {
                    val clip = windows[idx].clip
                    val scaledPos = (exoPlayer.currentPosition.coerceAtLeast(0L) / clip.speed.coerceIn(0.5f, 4f)).toLong()
                    val globalPos = windows[idx].timelineStartMs + scaledPos
                    if (_playheadMs.value != globalPos) {
                        _playheadMs.value = globalPos
                    }
                    applyLivePreviewPlaybackSettings(playheadMsOverride = globalPos)
                }
                delay(33L)
            }
        }
    }

    private fun stopPlaybackTicker() {
        playbackTickerJob?.cancel()
    }

    fun showToast(msg: String) {
        Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onCleared() {
        super.onCleared()
        stopPlaybackTicker()
        voiceoverTickerJob?.cancel()
        exportJob?.cancel()
        chromaJob?.cancel()
        voiceoverRecorder.cancelRecording()
        captionEngine.destroy()
        chromaProcessor.closeAllDetectors()
        runCatching {
            exoPlayer.stop()
            exoPlayer.clearVideoSurface()
            exoPlayer.release()
        }
        audioPreviewPlayers.values.forEach {
            runCatching {
                it.stop()
                it.release()
            }
        }
        audioPreviewPlayers.clear()
        audioPlayerSignatures.clear()
        thumbnailCache.clear()
    }
}
