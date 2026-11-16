package com.example.editor

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.ClotchApplication
import com.example.data.RecordedClip
import com.example.diagnostics.CrashLogger
import com.example.editor.engine.EditorThumbnailCache
import com.example.editor.engine.ExportRenderResult
import com.example.editor.engine.ExtractAudioResult
import com.example.editor.engine.FFmpegEditorEngine
import com.example.editor.engine.VoiceoverRecorder
import com.example.editor.model.AudioClipKind
import com.example.editor.model.EditorExportConfig
import com.example.editor.model.EditorExportState
import com.example.editor.model.EditorHistoryStack
import com.example.editor.model.EditorProject
import com.example.editor.model.TimelineAudioClip
import com.example.editor.model.TimelineVideoClip
import com.example.editor.model.VoiceoverState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ClotchApplication
    private val history = EditorHistoryStack()
    val thumbnailCache = EditorThumbnailCache()
    private val voiceoverRecorder = VoiceoverRecorder(application)

    private val _project = MutableStateFlow(EditorProject())
    val project: StateFlow<EditorProject> = _project.asStateFlow()

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

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(application).build()
    private val bgmPreviewPlayer: ExoPlayer = ExoPlayer.Builder(application).build()

    private var dragInitialSnapshot: EditorProject? = null
    private var lastLoadedSignature: String = ""
    private var lastLoadedAudioSignature: String = ""
    private var playbackTickerJob: Job? = null
    private var voiceoverTickerJob: Job? = null
    private var exportJob: Job? = null

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    startPlaybackTicker()
                } else {
                    stopPlaybackTicker()
                    bgmPreviewPlayer.pause()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    _isPlaying.value = false
                    bgmPreviewPlayer.pause()
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
        if (recordUndo) {
            history.pushSnapshot(prev)
        }
        _project.value = newState
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
        syncMedia3PlaylistIfNeeded(newState)
        syncAudioPreviewIfNeeded(newState)
    }

    fun importVideoUri(uri: Uri, fallbackName: String = "Imported Clip") {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val info = FFmpegEditorEngine.probeMedia(app, uri, fallbackName)
                val newClip = TimelineVideoClip(
                    sourceUri = uri,
                    displayName = info.displayName,
                    sourceDurationMs = info.durationMs,
                    trimStartMs = 0L,
                    trimEndMs = info.durationMs,
                    width = info.width,
                    height = info.height
                )
                val cur = _project.value
                val updated = cur.copy(
                    videoClips = cur.videoClips + newClip,
                    selectedVideoClipId = newClip.id,
                    selectedAudioClipId = null
                )
                updateProject(updated, recordUndo = true)
                showToast("Added ${info.displayName}")
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
            selectedAudioClipId = null
        )
    }

    fun selectAudioClip(audioId: String?) {
        _project.value = _project.value.copy(
            selectedAudioClipId = audioId,
            selectedVideoClipId = null
        )
    }

    fun togglePlayPause() {
        if (_project.value.videoClips.isEmpty()) return
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
            bgmPreviewPlayer.pause()
        } else {
            syncExoPlayerToTimelineMs(_project.value.playheadMs)
            syncAudioPreviewToTimelineMs(_project.value.playheadMs)
            exoPlayer.play()
            if (_project.value.audioClips.isNotEmpty()) {
                bgmPreviewPlayer.play()
            }
        }
    }

    fun pausePlayback() {
        if (exoPlayer.isPlaying) exoPlayer.pause()
        if (bgmPreviewPlayer.isPlaying) bgmPreviewPlayer.pause()
    }

    fun seekTo(timelineMs: Long) {
        val maxDur = _project.value.totalTimelineDurationMs.coerceAtLeast(0L)
        val clamped = timelineMs.coerceIn(0L, maxDur)
        _project.value = _project.value.copy(playheadMs = clamped)
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
        val targetWindow = cur.findVideoClipAt(cur.playheadMs) ?: return
        val clip = targetWindow.clip
        val offsetIntoClip = cur.playheadMs - targetWindow.timelineStartMs
        if (offsetIntoClip < 250L || clip.effectiveDurationMs - offsetIntoClip < 250L) {
            showToast("Move playhead at least 0.25s inside a clip to split")
            return
        }
        val splitPointInSource = clip.trimStartMs + offsetIntoClip
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
                selectedVideoClipId = rightPart.id,
                selectedAudioClipId = null
            ),
            recordUndo = true
        )
    }

    fun duplicateSelectedClip() {
        val cur = _project.value
        val selectedId = cur.selectedVideoClipId
        if (selectedId != null) {
            val idx = cur.videoClips.indexOfFirst { it.id == selectedId }
            if (idx >= 0) {
                val copyClip = cur.videoClips[idx].copy(id = UUID.randomUUID().toString())
                val list = cur.videoClips.toMutableList().apply { add(idx + 1, copyClip) }
                updateProject(cur.copy(videoClips = list, selectedVideoClipId = copyClip.id), true)
                return
            }
        }
        val audioId = cur.selectedAudioClipId ?: return
        val audio = cur.audioClips.firstOrNull { it.id == audioId } ?: return
        val dupAudio = audio.copy(
            id = UUID.randomUUID().toString(),
            timelineStartMs = audio.timelineEndMs
        )
        updateProject(
            cur.copy(audioClips = cur.audioClips + dupAudio, selectedAudioClipId = dupAudio.id),
            true
        )
    }

    fun deleteSelectedClip() {
        val cur = _project.value
        val selectedVideoId = cur.selectedVideoClipId
        if (selectedVideoId != null) {
            val remaining = cur.videoClips.filterNot { it.id == selectedVideoId }
            val newMax = remaining.sumOf { it.effectiveDurationMs }
            updateProject(
                cur.copy(
                    videoClips = remaining,
                    selectedVideoClipId = remaining.firstOrNull()?.id,
                    playheadMs = cur.playheadMs.coerceAtMost(newMax)
                ),
                true
            )
            return
        }
        val selectedAudioId = cur.selectedAudioClipId ?: return
        val remainingAudio = cur.audioClips.filterNot { it.id == selectedAudioId }
        updateProject(
            cur.copy(
                audioClips = remainingAudio,
                selectedAudioClipId = null
            ),
            true
        )
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

    fun zoomIn() {
        val next = (_project.value.zoomLevel * 1.25f).coerceIn(0.5f, 4.0f)
        _project.value = _project.value.copy(zoomLevel = next)
    }

    fun zoomOut() {
        val next = (_project.value.zoomLevel / 1.25f).coerceIn(0.5f, 4.0f)
        _project.value = _project.value.copy(zoomLevel = next)
    }

    fun undo() {
        val previous = history.undo(_project.value) ?: return
        _project.value = previous
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
        syncMedia3PlaylistIfNeeded(previous)
        syncAudioPreviewIfNeeded(previous)
        syncExoPlayerToTimelineMs(previous.playheadMs)
    }

    fun redo() {
        val next = history.redo(_project.value) ?: return
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
        val vId = cur.selectedVideoClipId
        if (vId != null) {
            val updated = cur.videoClips.map {
                if (it.id == vId) it.copy(volume = clamped, isMuted = clamped == 0f) else it
            }
            updateProject(cur.copy(videoClips = updated), commitUndo)
            applyLivePreviewVolumes()
            return
        }
        val aId = cur.selectedAudioClipId ?: return
        val updatedAudio = cur.audioClips.map {
            if (it.id == aId) it.copy(volume = clamped, isMuted = clamped == 0f) else it
        }
        updateProject(cur.copy(audioClips = updatedAudio), commitUndo)
        applyLivePreviewVolumes()
    }

    fun toggleSelectedMute() {
        val cur = _project.value
        val vId = cur.selectedVideoClipId
        if (vId != null) {
            val updated = cur.videoClips.map {
                if (it.id == vId) it.copy(isMuted = !it.isMuted) else it
            }
            updateProject(cur.copy(videoClips = updated), true)
            applyLivePreviewVolumes()
            return
        }
        val aId = cur.selectedAudioClipId ?: return
        val updatedAudio = cur.audioClips.map {
            if (it.id == aId) it.copy(isMuted = !it.isMuted) else it
        }
        updateProject(cur.copy(audioClips = updatedAudio), true)
        applyLivePreviewVolumes()
    }

    fun setSelectedFadeIn(fadeInMs: Long, commitUndo: Boolean) {
        val cur = _project.value
        val vId = cur.selectedVideoClipId
        if (vId != null) {
            val updated = cur.videoClips.map {
                if (it.id == vId) {
                    it.copy(fadeInMs = fadeInMs.coerceIn(0L, it.effectiveDurationMs / 2))
                } else it
            }
            updateProject(cur.copy(videoClips = updated), commitUndo)
            return
        }
        val aId = cur.selectedAudioClipId ?: return
        val updatedAudio = cur.audioClips.map {
            if (it.id == aId) {
                it.copy(fadeInMs = fadeInMs.coerceIn(0L, it.effectiveDurationMs / 2))
            } else it
        }
        updateProject(cur.copy(audioClips = updatedAudio), commitUndo)
    }

    fun setSelectedFadeOut(fadeOutMs: Long, commitUndo: Boolean) {
        val cur = _project.value
        val vId = cur.selectedVideoClipId
        if (vId != null) {
            val updated = cur.videoClips.map {
                if (it.id == vId) {
                    it.copy(fadeOutMs = fadeOutMs.coerceIn(0L, it.effectiveDurationMs / 2))
                } else it
            }
            updateProject(cur.copy(videoClips = updated), commitUndo)
            return
        }
        val aId = cur.selectedAudioClipId ?: return
        val updatedAudio = cur.audioClips.map {
            if (it.id == aId) {
                it.copy(fadeOutMs = fadeOutMs.coerceIn(0L, it.effectiveDurationMs / 2))
            } else it
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
            _isBusy.value = false
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
                    timelineStartMs = cur.playheadMs,
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
        val anchorMs = _project.value.playheadMs
        val file = voiceoverRecorder.startRecording()
        if (file == null) {
            showToast("Unable to start microphone voiceover")
            return
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

    fun stopVoiceover() {
        val currentRec = _voiceoverState.value as? VoiceoverState.Recording ?: return
        voiceoverTickerJob?.cancel()
        _voiceoverState.value = VoiceoverState.Idle
        val result = voiceoverRecorder.stopRecording()
        if (result == null) {
            showToast("Voiceover recording was empty")
            return
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

    fun exportProject(config: EditorExportConfig) {
        if (_exportState.value is EditorExportState.Exporting) return
        pausePlayback()
        val snapshot = _project.value
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            _exportState.value = EditorExportState.Exporting(0f, "Preparing export...")
            when (val res = FFmpegEditorEngine.exportTimeline(app, snapshot, config) { p, msg ->
                _exportState.value = EditorExportState.Exporting(p, msg)
            }) {
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
            "${it.id}:${it.sourceUri}:${it.trimStartMs}:${it.trimEndMs}"
        }
        if (signature == lastLoadedSignature) return
        lastLoadedSignature = signature

        if (project.videoClips.isEmpty()) {
            exoPlayer.clearMediaItems()
            return
        }
        val items = project.videoClips.map { clip ->
            MediaItem.Builder()
                .setMediaId(clip.id)
                .setUri(clip.sourceUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.trimStartMs)
                        .setEndPositionMs(clip.trimEndMs)
                        .build()
                )
                .build()
        }
        val wasPlaying = exoPlayer.isPlaying
        exoPlayer.setMediaItems(items, false)
        exoPlayer.prepare()
        syncExoPlayerToTimelineMs(project.playheadMs)
        applyLivePreviewVolumes()
        if (wasPlaying) exoPlayer.play()
    }

    @OptIn(UnstableApi::class)
    private fun syncAudioPreviewIfNeeded(project: EditorProject) {
        val audioSig = project.audioClips.joinToString("|") {
            "${it.id}:${it.sourceUri}:${it.trimStartMs}:${it.trimEndMs}"
        }
        if (audioSig == lastLoadedAudioSignature) return
        lastLoadedAudioSignature = audioSig

        val primaryAudio = project.audioClips.firstOrNull()
        if (primaryAudio == null) {
            bgmPreviewPlayer.clearMediaItems()
            return
        }
        val item = MediaItem.Builder()
            .setUri(primaryAudio.sourceUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(primaryAudio.trimStartMs)
                    .setEndPositionMs(primaryAudio.trimEndMs)
                    .build()
            )
            .build()
        bgmPreviewPlayer.setMediaItem(item)
        bgmPreviewPlayer.prepare()
        applyLivePreviewVolumes()
    }

    private fun syncExoPlayerToTimelineMs(timelineMs: Long) {
        val window = _project.value.findVideoClipAt(timelineMs) ?: return
        val localOffset = (timelineMs - window.timelineStartMs).coerceIn(0L, window.clip.effectiveDurationMs)
        if (window.index in 0 until exoPlayer.mediaItemCount) {
            exoPlayer.seekTo(window.index, localOffset)
        }
        applyLivePreviewVolumes()
    }

    private fun syncAudioPreviewToTimelineMs(timelineMs: Long) {
        val primary = _project.value.audioClips.firstOrNull() ?: return
        val offset = timelineMs - primary.timelineStartMs
        if (offset in 0L until primary.effectiveDurationMs) {
            bgmPreviewPlayer.seekTo(offset)
        } else {
            bgmPreviewPlayer.seekTo(0L)
        }
    }

    private fun applyLivePreviewVolumes() {
        val cur = _project.value
        val activeWindow = cur.findVideoClipAt(cur.playheadMs)
        val vidVol = activeWindow?.clip?.effectiveVolume?.coerceIn(0f, 1f) ?: 1f
        exoPlayer.volume = vidVol
        val audioVol = cur.audioClips.firstOrNull()?.effectiveVolume?.coerceIn(0f, 1f) ?: 0f
        bgmPreviewPlayer.volume = audioVol
    }

    private fun startPlaybackTicker() {
        playbackTickerJob?.cancel()
        playbackTickerJob = viewModelScope.launch {
            while (isActive && exoPlayer.isPlaying) {
                val cur = _project.value
                val idx = exoPlayer.currentMediaItemIndex
                val windows = cur.videoWindows()
                if (idx in windows.indices) {
                    val globalPos = windows[idx].timelineStartMs + exoPlayer.currentPosition.coerceAtLeast(0L)
                    _project.value = cur.copy(playheadMs = globalPos)
                    applyLivePreviewVolumes()
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
        voiceoverRecorder.cancelRecording()
        exoPlayer.release()
        bgmPreviewPlayer.release()
        thumbnailCache.clear()
    }
}
