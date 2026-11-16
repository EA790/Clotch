package com.example.editor.engine

import com.example.editor.model.ChromaKeyBgStyle
import com.example.editor.model.ColorFilterPreset
import com.example.editor.model.CropMode
import com.example.editor.model.TextAnimationType
import com.example.editor.model.TimelineSticker
import com.example.editor.model.TimelineTextOverlay
import com.example.editor.model.TimelineVideoClip
import com.example.editor.model.UpscaleTarget
import java.util.Locale
import kotlin.math.abs

object FFmpegFilterBuilder {

    fun buildClipVideoChain(
        inputIndex: Int,
        clipIndex: Int,
        clip: TimelineVideoClip,
        evenW: Int,
        evenH: Int,
        targetFps: Int,
        matteInputIndex: Int?
    ): List<String> {
        val filters = mutableListOf<String>()
        val startSec = formatSec(clip.trimStartMs)
        val endSec = formatSec(clip.trimEndMs.coerceAtLeast(clip.trimStartMs + 250L))
        val effectiveDurSec = formatSec(clip.effectiveDurationMs)

        val prep = mutableListOf<String>()
        if (clip.isFreezeFrame) {
            prep.add("fps=$targetFps")
            prep.add("trim=duration=$effectiveDurSec")
            prep.add("setpts=PTS-STARTPTS")
        } else {
            prep.add("trim=start=$startSec:end=$endSec")
            prep.add("setpts=PTS-STARTPTS")
            if (clip.isReversed) {
                prep.add("reverse")
            }
            if (abs(clip.speed - 1.0f) > 0.01f) {
                val spd = clip.speed.coerceIn(0.25f, 4.0f)
                prep.add(String.format(Locale.US, "setpts=PTS/%.3f", spd))
            }
        }

        // Rotate
        when ((clip.rotationDegrees % 360 + 360) % 360) {
            90 -> prep.add("transpose=1")
            180 -> {
                prep.add("transpose=1")
                prep.add("transpose=1")
            }
            270 -> prep.add("transpose=2")
        }

        // Crop
        when (clip.cropMode) {
            CropMode.FULL -> Unit
            CropMode.CENTER_SQUARE -> prep.add("crop=min(iw\\,ih):min(iw\\,ih):(iw-min(iw\\,ih))/2:(ih-min(iw\\,ih))/2")
            CropMode.TOP_HALF -> prep.add("crop=iw:trunc(ih/2/2)*2:0:0")
            CropMode.BOTTOM_HALF -> prep.add("crop=iw:trunc(ih/2/2)*2:0:trunc(ih/2/2)*2")
            CropMode.CINEMA_21_9 -> prep.add("crop=iw:trunc(min(ih\\,iw*9/21)/2)*2:0:(ih-trunc(min(ih\\,iw*9/21)/2)*2)/2")
        }

        // Color grading (Brightness / Contrast / Saturation)
        val hasEq = abs(clip.brightness) > 0.01f ||
            abs(clip.contrast - 1.0f) > 0.01f ||
            abs(clip.saturation - 1.0f) > 0.01f
        if (hasEq) {
            prep.add(
                String.format(
                    Locale.US,
                    "eq=brightness=%.2f:contrast=%.2f:saturation=%.2f",
                    clip.brightness.coerceIn(-0.5f, 0.5f),
                    clip.contrast.coerceIn(0.5f, 2.0f),
                    clip.saturation.coerceIn(0.0f, 2.0f)
                )
            )
        }

        // Color filter presets
        when (clip.filterPreset) {
            ColorFilterPreset.NONE -> Unit
            ColorFilterPreset.WARM -> prep.add("colorbalance=rs=0.10:gs=0.02:bs=-0.10")
            ColorFilterPreset.COOL -> prep.add("colorbalance=rs=-0.10:gs=0.02:bs=0.12")
            ColorFilterPreset.VINTAGE -> prep.add("colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131,eq=contrast=1.08:saturation=0.82")
            ColorFilterPreset.CYBER -> prep.add("colorbalance=rs=-0.06:gs=0.04:bs=0.16,eq=contrast=1.18:saturation=1.35")
        }

        val scaleFlags = if (clip.upscaleTarget != UpscaleTarget.OFF) ":flags=lanczos" else ""
        val sharpenSuffix = if (clip.upscaleTarget != UpscaleTarget.OFF) ",unsharp=5:5:0.75:3:3:0.4" else ""

        val baseStreamLabel = "[vprep$clipIndex]"
        prep.add("fps=$targetFps")
        prep.add("setsar=1")
        filters.add("[$inputIndex:v]${prep.joinToString(",")}$baseStreamLabel")

        // Framing: Chroma Key matte OR Blurred Background OR Standard letterbox/pillarbox pad
        val framedLabel = "[vframed$clipIndex]"
        if (matteInputIndex != null && clip.chromaKey.enabled) {
            val fgLabel = "[vkfg$clipIndex]"
            val matteLabel = "[vkmatte$clipIndex]"
            val alphaFgLabel = "[vkalpha$clipIndex]"
            val bgLabel = "[vkbg$clipIndex]"

            filters.add(
                "${baseStreamLabel}scale=$evenW:$evenH:force_original_aspect_ratio=decrease${scaleFlags}," +
                    "pad=$evenW:$evenH:(ow-iw)/2:(oh-ih)/2:black${sharpenSuffix},format=rgba$fgLabel"
            )
            filters.add(
                "[$matteInputIndex:v]fps=$targetFps,scale=$evenW:$evenH:flags=lanczos,format=gray$matteLabel"
            )
            filters.add("${fgLabel}${matteLabel}alphamerge$alphaFgLabel")

            val bgFilter = when (clip.chromaKey.bgStyle) {
                ChromaKeyBgStyle.TRANSPARENT_DARK -> "color=c=#101727:s=${evenW}x${evenH}:r=$targetFps:d=$effectiveDurSec"
                ChromaKeyBgStyle.NEON_GAMING -> "color=c=#1B1464:s=${evenW}x${evenH}:r=$targetFps:d=$effectiveDurSec,drawbox=x=0:y=0:w=iw:h=4:color=#2B6BFF@0.6:t=fill"
                ChromaKeyBgStyle.BLUR_BG -> "color=c=#141C2E:s=${evenW}x${evenH}:r=$targetFps:d=$effectiveDurSec"
                ChromaKeyBgStyle.SOLID_GREEN -> "color=c=#00B140:s=${evenW}x${evenH}:r=$targetFps:d=$effectiveDurSec"
            }
            filters.add("$bgFilter,format=rgba$bgLabel")
            filters.add("${bgLabel}${alphaFgLabel}overlay=0:0:shortest=1,format=yuv420p$framedLabel")
        } else if (clip.blurBackground) {
            val bgSplit = "[vsplit_bg$clipIndex]"
            val fgSplit = "[vsplit_fg$clipIndex]"
            val bgOut = "[vblur_bg$clipIndex]"
            val fgOut = "[vblur_fg$clipIndex]"
            filters.add("${baseStreamLabel}split=2${bgSplit}${fgSplit}")
            filters.add(
                "${bgSplit}scale=$evenW:$evenH:force_original_aspect_ratio=increase," +
                    "crop=$evenW:$evenH,boxblur=20:3$bgOut"
            )
            filters.add(
                "${fgSplit}scale=$evenW:$evenH:force_original_aspect_ratio=decrease${scaleFlags}${sharpenSuffix}$fgOut"
            )
            filters.add("${bgOut}${fgOut}overlay=(W-w)/2:(H-h)/2,format=yuv420p$framedLabel")
        } else {
            filters.add(
                "${baseStreamLabel}scale=$evenW:$evenH:force_original_aspect_ratio=decrease${scaleFlags}," +
                    "pad=$evenW:$evenH:(ow-iw)/2:(oh-ih)/2:black${sharpenSuffix},format=yuv420p$framedLabel"
            )
        }

        // Apply Keyframe motion (position + scale + opacity) if present
        val outLabel = "[v$clipIndex]"
        if (clip.keyframes.isNotEmpty()) {
            val avgX = clip.keyframes.map { it.posX }.average().toFloat()
            val avgY = clip.keyframes.map { it.posY }.average().toFloat()
            val firstKf = clip.keyframes.minBy { it.timeOffsetMs }
            val lastKf = clip.keyframes.maxBy { it.timeOffsetMs }
            val durSecF = (clip.effectiveDurationMs / 1000.0).coerceAtLeast(0.25)

            val dxPx = ((lastKf.posX - firstKf.posX) * evenW)
            val startXPx = (firstKf.posX * evenW)
            val dyPx = ((lastKf.posY - firstKf.posY) * evenH)
            val startYPx = (firstKf.posY * evenH)
            val meanScale = ((firstKf.scale + lastKf.scale) / 2f).coerceIn(0.5f, 2.5f)
            val scaledW = ((evenW * meanScale).toInt() / 2) * 2
            val scaledH = ((evenH * meanScale).toInt() / 2) * 2

            val kfg = "[vkf_fg$clipIndex]"
            val kbg = "[vkf_bg$clipIndex]"
            filters.add(
                "${framedLabel}scale=$scaledW:$scaledH:flags=lanczos,format=rgba$kfg"
            )
            filters.add(
                "color=c=#101727:s=${evenW}x${evenH}:r=$targetFps:d=$effectiveDurSec,format=rgba$kbg"
            )
            val xExpr = String.format(Locale.US, "(W-w)/2+%.1f+(%.1f)*(t/%.3f)", startXPx, dxPx, durSecF)
            val yExpr = String.format(Locale.US, "(H-h)/2+%.1f+(%.1f)*(t/%.3f)", startYPx, dyPx, durSecF)
            filters.add(
                if (abs(avgX) < 0.001f && abs(avgY) < 0.001f && abs(meanScale - 1.0f) < 0.02f) {
                    "${framedLabel}format=yuv420p$outLabel"
                } else {
                    "${kbg}${kfg}overlay=x='$xExpr':y='$yExpr':shortest=1,format=yuv420p$outLabel"
                }
            )
        } else {
            filters.add("${framedLabel}null$outLabel")
        }

        return filters
    }

    fun buildClipAudioChain(
        inputIndex: Int,
        clipIndex: Int,
        clip: TimelineVideoClip,
        hasAudioStream: Boolean
    ): String {
        val durSec = formatSec(clip.effectiveDurationMs)
        val hasAudio = hasAudioStream && !clip.isMuted && !clip.audioDetached && !clip.isFreezeFrame && clip.effectiveVolume > 0f
        if (!hasAudio) {
            return "anullsrc=r=44100:cl=stereo,atrim=duration=$durSec[va$clipIndex]"
        }

        val startSec = formatSec(clip.trimStartMs)
        val endSec = formatSec(clip.trimEndMs.coerceAtLeast(clip.trimStartMs + 250L))
        val volStr = formatFloat(clip.effectiveVolume)
        val audioFilters = mutableListOf(
            "atrim=start=$startSec:end=$endSec",
            "asetpts=PTS-STARTPTS",
            "aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo"
        )
        if (clip.isReversed) {
            audioFilters.add("areverse")
        }
        // Pitch-preserving tempo chain for speed control (0.5x..4.0x)
        var remainingSpeed = clip.speed.coerceIn(0.25f, 4.0f)
        while (remainingSpeed > 2.0f) {
            audioFilters.add("atempo=2.0")
            remainingSpeed /= 2.0f
        }
        while (remainingSpeed < 0.5f) {
            audioFilters.add("atempo=0.5")
            remainingSpeed /= 0.5f
        }
        if (abs(remainingSpeed - 1.0f) > 0.01f) {
            audioFilters.add(String.format(Locale.US, "atempo=%.3f", remainingSpeed))
        }

        audioFilters.add("volume=$volStr")
        if (clip.fadeInMs > 0L) {
            val fadeInSec = formatSec(clip.fadeInMs.coerceAtMost(clip.effectiveDurationMs / 2))
            audioFilters.add("afade=t=in:st=0:d=$fadeInSec")
        }
        if (clip.fadeOutMs > 0L) {
            val fadeOutDurMs = clip.fadeOutMs.coerceAtMost(clip.effectiveDurationMs / 2)
            val fadeOutStartMs = (clip.effectiveDurationMs - fadeOutDurMs).coerceAtLeast(0L)
            audioFilters.add("afade=t=out:st=${formatSec(fadeOutStartMs)}:d=${formatSec(fadeOutDurMs)}")
        }
        return "[$inputIndex:a]${audioFilters.joinToString(",")}[va$clipIndex]"
    }

    fun buildTextOverlayFilter(
        baseLabel: String,
        overlayInputIndex: Int,
        overlay: TimelineTextOverlay,
        stepIndex: Int,
        outLabel: String
    ): List<String> {
        val filters = mutableListOf<String>()
        val st = overlay.timelineStartMs / 1000.0
        val dur = (overlay.durationMs / 1000.0).coerceAtLeast(0.3)
        val end = st + dur
        val stStr = String.format(Locale.US, "%.3f", st)
        val endStr = String.format(Locale.US, "%.3f", end)

        val ovTag = "[ovsrc$stepIndex]"
        when (overlay.animation) {
            TextAnimationType.NONE -> {
                filters.add("[$overlayInputIndex:v]format=rgba$ovTag")
            }
            TextAnimationType.FADE -> {
                val fadeD = minOf(0.35, dur / 3.0)
                val outSt = (end - fadeD).coerceAtLeast(st)
                filters.add(
                    String.format(
                        Locale.US,
                        "[$overlayInputIndex:v]format=rgba,fade=t=in:st=%.3f:d=%.3f:alpha=1,fade=t=out:st=%.3f:d=%.3f:alpha=1%s",
                        st, fadeD, outSt, fadeD, ovTag
                    )
                )
            }
            TextAnimationType.SLIDE_UP -> {
                val fadeD = minOf(0.3, dur / 3.0)
                filters.add(
                    String.format(
                        Locale.US,
                        "[$overlayInputIndex:v]format=rgba,fade=t=in:st=%.3f:d=%.3f:alpha=1%s",
                        st, fadeD, ovTag
                    )
                )
            }
            TextAnimationType.TYPEWRITER -> {
                val revealDur = minOf(dur * 0.7, 2.0).coerceAtLeast(0.25)
                filters.add(
                    String.format(
                        Locale.US,
                        "[$overlayInputIndex:v]format=rgba,crop=w='max(4,iw*min(1,(t-%.3f)/%.3f))':h=ih:x=0:y=0%s",
                        st, revealDur, ovTag
                    )
                )
            }
        }

        val xExpr = String.format(Locale.US, "(W*%.3f)-(w/2)", overlay.posX.coerceIn(0.05f, 0.95f))
        val yExpr = if (overlay.animation == TextAnimationType.SLIDE_UP) {
            String.format(
                Locale.US,
                "(H*%.3f)-(h/2)+max(0,(1-(t-%s)/0.40))*42",
                overlay.posY.coerceIn(0.05f, 0.95f),
                stStr
            )
        } else {
            String.format(Locale.US, "(H*%.3f)-(h/2)", overlay.posY.coerceIn(0.05f, 0.95f))
        }

        filters.add(
            "${baseLabel}${ovTag}overlay=x='$xExpr':y='$yExpr':enable='between(t,$stStr,$endStr)':eof_action=pass$outLabel"
        )
        return filters
    }

    fun buildStickerOverlayFilter(
        baseLabel: String,
        stickerInputIndex: Int,
        sticker: TimelineSticker,
        stepIndex: Int,
        outLabel: String
    ): String {
        val stStr = formatSec(sticker.timelineStartMs)
        val endStr = formatSec(sticker.timelineEndMs)
        val xExpr = String.format(Locale.US, "(W*%.3f)-(w/2)", sticker.posX.coerceIn(0.05f, 0.95f))
        val yExpr = String.format(Locale.US, "(H*%.3f)-(h/2)", sticker.posY.coerceIn(0.05f, 0.95f))
        return "${baseLabel}[$stickerInputIndex:v]overlay=x='$xExpr':y='$yExpr':enable='between(t,$stStr,$endStr)':eof_action=pass$outLabel"
    }

    fun formatSec(ms: Long): String {
        val sec = ms.coerceAtLeast(0L) / 1000.0
        return String.format(Locale.US, "%.3f", sec)
    }

    fun formatFloat(v: Float): String {
        return String.format(Locale.US, "%.2f", v)
    }
}
