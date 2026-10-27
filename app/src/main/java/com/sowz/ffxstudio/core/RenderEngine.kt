package com.sowz.ffxstudio.core

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics

/**
 * LookEngine.Plan'in ffmpeg filtre grafigini GERCEK bir FFmpegKit cagrisina
 * cevirir. Hem foto hem video girisini destekler - ikisi de ayni FFmpeg
 * "-vf" filtre zincirinden geciyor, tek fark video icin ses/codec/konteyner
 * ayarlari.
 */
object RenderEngine {

    sealed class Progress {
        data class PercentUpdate(val percent: Int) : Progress()
        data class Done(val outputPath: String) : Progress()
        object Cancelled : Progress()
        data class Failed(val message: String) : Progress()
    }

    /** Video icin: ses korunur (-c:a copy basarisiz olursa aac'ye duser -
     *  bazi kaynak codec'ler mp4 konteynerinde "copy" ile uyumsuz olabilir). */
    fun renderVideo(
        inputPath: String,
        outputPath: String,
        filterGraph: String,
        durationMsHint: Long,
        onProgress: (Progress) -> Unit
    ): FFmpegSession {
        val vf = if (filterGraph == "null") "" else "-vf \"$filterGraph\""
        val command = "-y -i \"$inputPath\" $vf -c:v libx264 -crf 18 -preset medium -pix_fmt yuv420p -c:a copy \"$outputPath\""
        return FFmpegKit.executeAsync(
            command,
            { session ->
                when {
                    ReturnCode.isSuccess(session.returnCode) -> onProgress(Progress.Done(outputPath))
                    ReturnCode.isCancel(session.returnCode) -> onProgress(Progress.Cancelled)
                    else -> {
                        // "-c:a copy" bazi kaynaklarda konteynerle uyusmayabilir - aac'ye
                        // dusup tek seferlik yeniden dene (sessiz "basarisiz" yerine).
                        val retryCommand = "-y -i \"$inputPath\" $vf -c:v libx264 -crf 18 -preset medium -pix_fmt yuv420p -c:a aac -b:a 192k \"$outputPath\""
                        val retrySession = FFmpegKit.execute(retryCommand)
                        if (ReturnCode.isSuccess(retrySession.returnCode)) {
                            onProgress(Progress.Done(outputPath))
                        } else {
                            onProgress(Progress.Failed("Render basarisiz oldu (kod ${session.returnCode})."))
                        }
                    }
                }
            },
            { },
            { stats: Statistics ->
                if (durationMsHint > 0) {
                    val pct = ((stats.time.toDouble() / durationMsHint.toDouble()) * 100).toInt().coerceIn(0, 99)
                    onProgress(Progress.PercentUpdate(pct))
                }
            }
        )
    }

    /** Foto icin: tek kare, ses yok, duration ilerleme yuzdesi anlamsiz oldugu
     *  icin basla/bitti olarak ele alinir. */
    fun renderImage(
        inputPath: String,
        outputPath: String,
        filterGraph: String,
        onProgress: (Progress) -> Unit
    ): FFmpegSession {
        val vf = if (filterGraph == "null") "" else "-vf \"$filterGraph\""
        val command = "-y -i \"$inputPath\" $vf \"$outputPath\""
        return FFmpegKit.executeAsync(
            command,
            { session ->
                when {
                    ReturnCode.isSuccess(session.returnCode) -> onProgress(Progress.Done(outputPath))
                    ReturnCode.isCancel(session.returnCode) -> onProgress(Progress.Cancelled)
                    else -> onProgress(Progress.Failed("Render basarisiz oldu (kod ${session.returnCode})."))
                }
            },
            { },
            { }
        )
    }

    fun cancel(session: FFmpegSession?) {
        if (session != null) {
            FFmpegKit.cancel(session.sessionId)
        }
    }
}
