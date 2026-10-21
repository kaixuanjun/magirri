package com.magirri.app

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.SurfaceTexture
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

/**
 * 解码 → GPU 调色 → 编码 的主循环。
 *
 * 两条送帧通路：
 *  * **Surface 模式**（默认）：编码器给一块输入 Surface，渲染直接画上去，零拷贝；
 *  * **Buffer 模式**（兜底）：离屏渲染 + glReadPixels，再按编码器给的 Image 布局写 YUV。
 *
 * 音频不解码，整个采样包原样重封装；视频与音频按 pts 交错写入，避免声音跑到画面之前。
 */
class VideoTranscoder(
    private val context: Context,
    private val inputUri: Uri,
    private val outputUri: Uri,
    private val lut: Lut3D,
    private val onProgress: (Float, String) -> Unit,
    private val isCancelled: () -> Boolean,
    private val debugMode: Int = 0,
    private val forceBuffer: Boolean = false,
    private val rotMode: Int = 2,
    /** HLG 素材是否先转 SDR（关掉就按原样套 LUT，用于对比/排查） */
    private val hdrToSdr: Boolean = true,
) {

    data class Stats(
        val width: Int,
        val height: Int,
        val frames: Int,
        val codec: String,
        val durationMs: Long,
        val bitrate: Int,
        /** 源素材的 transfer（HLG/PQ 需要先转 SDR 再套 LUT） */
        val srcTransfer: Int = 0,
        /** 源素材的 primaries */
        val srcStandard: Int = 0,
        /** 是否做了 HLG→SDR 预处理 */
        val hlgConverted: Boolean = false,
    )

    private class AudioChunk(val data: ByteArray, val ptsUs: Long, val flags: Int)

    private class CreatedEncoder(val codec: MediaCodec, val inputSurface: Surface?)

    private class CancellationException : Exception()

    private val timeoutUs = 10_000L

    private var lastEncoderError: String = ""

    private var frameLogCount = 0

    fun run(): Stats {
        val resolver = context.contentResolver
        val srcPath = if (inputUri.scheme == "file") inputUri.path else null
        val outPath = if (outputUri.scheme == "file") outputUri.path else null

        val vAfd: AssetFileDescriptor? = if (srcPath != null) null else
            (resolver.openAssetFileDescriptor(inputUri, "r")
                ?: throw IllegalStateException("无法打开输入文件"))
        val aAfd: AssetFileDescriptor? = if (srcPath != null) null else
            try {
                resolver.openAssetFileDescriptor(inputUri, "r")
            } catch (e: Exception) {
                null
            }

        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var renderer: GlRenderer? = null
        var outFd: ParcelFileDescriptor? = null
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxerStarted = false

        try {
            // ---------------- 源轨 ----------------
            if (srcPath != null) {
                videoExtractor.setDataSource(srcPath)
            } else {
                videoExtractor.setDataSource(
                    vAfd!!.fileDescriptor, vAfd.startOffset, vAfd.length
                )
            }
            val vIndex = (0 until videoExtractor.trackCount).firstOrNull { i ->
                videoExtractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: throw IllegalStateException("文件里没有视频轨")

            val vFormat = videoExtractor.getTrackFormat(vIndex)
            videoExtractor.selectTrack(vIndex)

            val srcWidth = vFormat.getInteger(MediaFormat.KEY_WIDTH)
            val srcHeight = vFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (vFormat.containsKey(KEY_ROTATION)) {
                vFormat.getInteger(KEY_ROTATION)
            } else {
                0
            }
            // 色彩元数据：HLG / PQ 直接套 Log LUT 会「发灰」，必须先转 SDR
            val srcTransfer = if (vFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                vFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
            } else {
                0
            }
            val srcStandard = if (vFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                vFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD)
            } else {
                0
            }
            val hlg = hdrToSdr && srcTransfer == MediaFormat.COLOR_TRANSFER_HLG
            Log.i(TAG, "源色彩: transfer=$srcTransfer standard=$srcStandard rotation=$rotation hlg=$hlg")

            val swap = rotation == 90 || rotation == 270
            // 很多编码器要求偶数尺寸
            val outW = (if (swap) srcHeight else srcWidth) / 2 * 2
            val outH = (if (swap) srcWidth else srcHeight) / 2 * 2
            val durationUs = if (vFormat.containsKey(MediaFormat.KEY_DURATION)) {
                vFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            val fps = readFps(vFormat)

            // ---------------- 音频（原样重封装） ----------------
            var audioFormat: MediaFormat? = null
            val audioChunks = ArrayList<AudioChunk>(4096)
            if (aAfd != null || srcPath != null) {
                try {
                    if (srcPath != null) {
                        audioExtractor.setDataSource(srcPath)
                    } else {
                        audioExtractor.setDataSource(
                            aAfd!!.fileDescriptor, aAfd.startOffset, aAfd.length
                        )
                    }
                    val aIndex = (0 until audioExtractor.trackCount).firstOrNull { i ->
                        audioExtractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                            ?.startsWith("audio/") == true
                    }
                    if (aIndex != null) {
                        val af = audioExtractor.getTrackFormat(aIndex)
                        audioFormat = af
                        audioExtractor.selectTrack(aIndex)
                        val maxSize = if (af.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                            af.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(65536)
                        } else {
                            262144
                        }
                        val buf = ByteBuffer.allocateDirect(maxSize)
                        while (true) {
                            val size = audioExtractor.readSampleData(buf, 0)
                            if (size < 0) break
                            val bytes = ByteArray(size)
                            buf.position(0)
                            buf.limit(size)
                            buf.get(bytes)
                            audioChunks.add(
                                AudioChunk(bytes, audioExtractor.sampleTime, audioExtractor.sampleFlags)
                            )
                            audioExtractor.advance()
                            if (isCancelled()) throw CancellationException()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    audioFormat = null
                    audioChunks.clear()
                }
            }

            // 影调已经在烘焙时做进 LUT 里了，运行时不做任何调整
            val lutOut = lut

            // ---------------- 编码器 ----------------
            var encMime = "video/hevc"
            var surfaceMode = true
            var created: CreatedEncoder? = null
            if (!forceBuffer) {
                created = tryCreateEncoder(encMime, outW, outH, fps, 0.10, true)
                if (created == null) {
                    encMime = "video/avc"
                    created = tryCreateEncoder(encMime, outW, outH, fps, 0.16, true)
                }
            }
            if (created == null) {
                surfaceMode = false
                encMime = "video/avc"
                created = tryCreateEncoder(encMime, outW, outH, fps, 0.16, false)
                if (created == null) {
                    encMime = "video/hevc"
                    created = tryCreateEncoder(encMime, outW, outH, fps, 0.10, false)
                }
            }
            val built = created
                ?: throw IllegalStateException("没有可用的视频编码器：$lastEncoderError")

            val activeEncoder = built.codec
            val inputSurface = built.inputSurface
            val isHevc = encMime == "video/hevc"
            val bitrate = if (isHevc) {
                (outW.toDouble() * outH * fps * 0.10).toInt().coerceIn(4_000_000, 80_000_000)
            } else {
                (outW.toDouble() * outH * fps * 0.16).toInt().coerceIn(4_000_000, 80_000_000)
            }
            encoder = activeEncoder
            if (surfaceMode && inputSurface == null) {
                throw IllegalStateException("Surface 编码器没有给出输入 Surface")
            }

            var encStride = outW
            var encSlice = outH
            var useNv12 = true
            var rgbaBuf = ByteArray(0)
            var yuvBuf = ByteArray(0)
            var yuvSize = 0

            if (surfaceMode) {
                renderer = GlRenderer(lutOut, debugMode, rotMode = rotMode, hlgToSdr = hlg)
                renderer.start(inputSurface)
            } else {
                val fmt = try {
                    activeEncoder.inputFormat
                } catch (e: Exception) {
                    MediaFormat.createVideoFormat(encMime, outW, outH)
                }
                val colorFormat = if (fmt.containsKey(MediaFormat.KEY_COLOR_FORMAT)) {
                    fmt.getInteger(MediaFormat.KEY_COLOR_FORMAT)
                } else {
                    21
                }
                useNv12 = colorFormat != 19
                if (fmt.containsKey(KEY_STRIDE) && fmt.getInteger(KEY_STRIDE) > 0) {
                    encStride = fmt.getInteger(KEY_STRIDE)
                }
                if (fmt.containsKey(KEY_SLICE_HEIGHT) && fmt.getInteger(KEY_SLICE_HEIGHT) > 0) {
                    encSlice = fmt.getInteger(KEY_SLICE_HEIGHT)
                }
                renderer = GlRenderer(
                    lutOut, debugMode, offscreen = true,
                    outWidth = outW, outHeight = outH, rotMode = rotMode, hlgToSdr = hlg
                )
                renderer.start(null)
                rgbaBuf = ByteArray(outW * outH * 4)
                yuvSize = encStride * encSlice * 3 / 2
                yuvBuf = ByteArray(yuvSize)
                Log.i(
                    TAG, "buffer 模式: codec=$encMime colorFormat=0x" +
                            Integer.toHexString(colorFormat) + " stride=$encStride slice=$encSlice" +
                            " nv12=$useNv12 bytes=$yuvSize inFmt=$fmt"
                )
            }

            onProgress(
                0f,
                if (surfaceMode) "编码器：Surface 模式" else "编码器：缓冲模式（较慢）"
            )

            // ---------------- 解码器 ----------------
            val decoderSurface = renderer.createDecoderSurface()
            val decMime = vFormat.getString(MediaFormat.KEY_MIME)!!
            val decFormat = MediaFormat(vFormat).apply {
                // 解码器不许自己转：旋转统一交给渲染层，否则会转两次
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    removeKey(KEY_ROTATION)
                } else {
                    setInteger(KEY_ROTATION, 0)
                }
            }
            decoder = MediaCodec.createDecoderByType(decMime).also {
                it.configure(decFormat, decoderSurface, null, 0)
                it.start()
            }

            // ---------------- 封装 ----------------
            if (outPath != null) {
                File(outPath).parentFile?.mkdirs()
                muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } else {
                val fd = resolver.openFileDescriptor(outputUri, "rw")
                    ?: throw IllegalStateException("无法写入输出文件")
                outFd = fd
                muxer = MediaMuxer(
                    fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
            }
            val activeMuxer = muxer!!
            val audioTrackIndex = audioFormat?.let { activeMuxer.addTrack(it) } ?: -1
            var videoTrackIndex = -1
            var audioCursor = 0

            fun flushAudioBefore(ptsUs: Long) {
                if (!muxerStarted || audioTrackIndex < 0) return
                while (audioCursor < audioChunks.size &&
                    audioChunks[audioCursor].ptsUs <= ptsUs
                ) {
                    val chunk = audioChunks[audioCursor]
                    val info = MediaCodec.BufferInfo().apply {
                        set(0, chunk.data.size, chunk.ptsUs, chunk.flags)
                    }
                    activeMuxer.writeSampleData(
                        audioTrackIndex, ByteBuffer.wrap(chunk.data), info
                    )
                    audioCursor++
                }
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var eosSent = false
            var encoderDone = false
            var frames = 0
            var lastPtsUs = 0L

            while (!encoderDone) {
                if (isCancelled()) throw CancellationException()

                // 喂解码器
                if (!inputDone) {
                    val inIx = decoder.dequeueInputBuffer(timeoutUs)
                    if (inIx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIx)!!
                        val size = videoExtractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val pts = videoExtractor.sampleTime
                            decoder.queueInputBuffer(inIx, 0, size, pts, 0)
                            lastPtsUs = pts
                            videoExtractor.advance()
                        }
                    }
                }

                // 取解码结果 → 画到 GL
                if (!outputDone) {
                    val outIx = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    if (outIx >= 0) {
                        val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (!eos) {
                            decoder.releaseOutputBuffer(outIx, true)
                            renderer.onFrame(rotation)
                            val st: SurfaceTexture = renderer.surfaceTexture!!
                            val tsNanos = st.timestamp
                            renderer.swap(tsNanos)
                            if (!surfaceMode) {
                                if (renderer.readPixels(rgbaBuf)) {
                                    feedEncoderFromRgba(
                                        activeEncoder, tsNanos / 1000, rgbaBuf, yuvBuf, yuvSize,
                                        outW, outH, encStride, encSlice, useNv12
                                    )
                                } else {
                                    Log.w(TAG, "glReadPixels 失败，跳过这一帧")
                                }
                            }
                            val progress = if (durationUs > 0L) {
                                bufferInfo.presentationTimeUs.toFloat() / durationUs.toFloat()
                            } else {
                                0f
                            }
                            frames += 1
                            onProgress(
                                progress.coerceIn(0f, 1f),
                                "已处理 $frames 帧  ${fmtTime(bufferInfo.presentationTimeUs)}"
                            )
                        } else {
                            decoder.releaseOutputBuffer(outIx, false)
                            outputDone = true
                        }
                    }
                }

                // 发 EOS
                if (outputDone && !eosSent) {
                    if (surfaceMode) {
                        activeEncoder.signalEndOfInputStream()
                    } else {
                        val ix = activeEncoder.dequeueInputBuffer(200_000L)
                        if (ix >= 0) {
                            activeEncoder.queueInputBuffer(
                                ix, 0, 0, lastPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                        }
                    }
                    eosSent = true
                }

                // 收编码输出 → 写封装
                while (true) {
                    val encIx = activeEncoder.dequeueOutputBuffer(bufferInfo, 0L)
                    if (encIx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (encIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrackIndex = activeMuxer.addTrack(activeEncoder.outputFormat)
                        activeMuxer.start()
                        muxerStarted = true
                        continue
                    }
                    if (encIx < 0) continue
                    val encBuf = activeEncoder.getOutputBuffer(encIx)
                    if (encBuf == null) {
                        activeEncoder.releaseOutputBuffer(encIx, false)
                        continue
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        flushAudioBefore(bufferInfo.presentationTimeUs)
                        encBuf.position(bufferInfo.offset)
                        encBuf.limit(bufferInfo.offset + bufferInfo.size)
                        activeMuxer.writeSampleData(videoTrackIndex, encBuf, bufferInfo)
                    }
                    val encEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    activeEncoder.releaseOutputBuffer(encIx, false)
                    if (encEos) {
                        encoderDone = true
                        break
                    }
                }
            }

            flushAudioBefore(Long.MAX_VALUE)

            return Stats(
                width = outW,
                height = outH,
                frames = frames,
                codec = if (isHevc) "HEVC" else "H.264",
                durationMs = lastPtsUs / 1000,
                bitrate = bitrate,
                srcTransfer = srcTransfer,
                srcStandard = srcStandard,
                hlgConverted = hlg,
            )
        } catch (t: Throwable) {
            // 失败就别留半截文件
            try {
                if (muxerStarted) muxer?.stop()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            muxer = null
            try {
                outFd?.close()
            } catch (_: Exception) {
            }
            outFd = null
            try {
                if (outPath != null) {
                    File(outPath).delete()
                } else {
                    resolver.delete(outputUri, null, null)
                }
            } catch (_: Exception) {
            }
            throw t
        } finally {
            try {
                decoder?.stop()
            } catch (_: Exception) {
            }
            try {
                decoder?.release()
            } catch (_: Exception) {
            }
            try {
                encoder?.stop()
            } catch (_: Exception) {
            }
            try {
                encoder?.release()
            } catch (_: Exception) {
            }
            try {
                renderer?.release()
            } catch (_: Exception) {
            }
            try {
                if (muxerStarted) muxer?.stop()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            try {
                outFd?.close()
            } catch (_: Exception) {
            }
            try {
                videoExtractor.release()
            } catch (_: Exception) {
            }
            try {
                audioExtractor.release()
            } catch (_: Exception) {
            }
            try {
                vAfd?.close()
            } catch (_: Exception) {
            }
            try {
                aAfd?.close()
            } catch (_: Exception) {
            }
        }
    }

    // ---------------------------------------------------------------- 缓冲模式送帧

    private fun feedEncoderFromRgba(
        enc: MediaCodec,
        ptsUs: Long,
        rgba: ByteArray,
        yuv: ByteArray,
        yuvSize: Int,
        width: Int,
        height: Int,
        stride: Int,
        slice: Int,
        nv12: Boolean,
    ) {
        if (frameLogCount < 3) {
            val c = (height / 2 * width + width / 2) * 4
            Log.i(
                TAG, "RGBA 抽样 中心=(${rgba[c].toInt() and 0xFF}," +
                        "${rgba[c + 1].toInt() and 0xFF},${rgba[c + 2].toInt() and 0xFF}) 左上=(" +
                        "${rgba[0].toInt() and 0xFF},${rgba[1].toInt() and 0xFF}," +
                        "${rgba[2].toInt() and 0xFF})"
            )
        }
        if (nv12) {
            Yuv.rgbaToNv12(rgba, width, height, yuv, stride, slice)
        } else {
            Yuv.rgbaToI420(rgba, width, height, yuv, stride, slice)
        }
        val ix = enc.dequeueInputBuffer(timeoutUs)
        if (ix < 0) return

        val img = try {
            enc.getInputImage(ix)
        } catch (e: Exception) {
            null
        }
        if (img != null) {
            // 编码器要求特定 stride/步幅时，只有 Image 这条通路可靠
            try {
                writeRgbaToImage(rgba, width, height, img)
            } finally {
                img.close()
            }
            val cap = enc.getInputBuffer(ix)?.capacity() ?: yuvSize
            if (frameLogCount < 3) {
                Log.i(TAG, "queue(Image) 第${frameLogCount + 1}帧 cap=$cap pts=$ptsUs")
                frameLogCount += 1
            }
            enc.queueInputBuffer(ix, 0, cap, ptsUs, 0)
            return
        }

        val buf = enc.getInputBuffer(ix) ?: return
        buf.clear()
        val n = minOf(yuvSize, buf.capacity())
        if (frameLogCount < 3) {
            Log.i(
                TAG, "queue(Buffer) 第${frameLogCount + 1}帧 size=$n capacity=${buf.capacity()}" +
                        " expect=$yuvSize pts=$ptsUs"
            )
            frameLogCount += 1
        }
        buf.put(yuv, 0, n)
        enc.queueInputBuffer(ix, 0, n, ptsUs, 0)
    }

    /** 直接按编码器 Image 的 stride/pixelStride 写 BT.709 有限范围 YUV。 */
    private fun writeRgbaToImage(rgba: ByteArray, w: Int, h: Int, img: Image) {
        val planes = img.planes
        val yPlane = planes[0]
        val yBuf = yPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        for (y in 0 until h) {
            var src = y * w * 4
            var dst = y * yRow
            for (x in 0 until w) {
                val r = rgba[src].toInt() and 0xFF
                val g = rgba[src + 1].toInt() and 0xFF
                val b = rgba[src + 2].toInt() and 0xFF
                val luma = 16 + ((11965 * r + 40255 * g + 4064 * b) shr 16)
                if (dst < yBuf.capacity()) {
                    yBuf.put(dst, luma.coerceIn(16, 235).toByte())
                }
                src += 4
                dst += yPix
            }
        }
        val uPlane = planes[1]
        val vPlane = planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRow = uPlane.rowStride
        val vRow = vPlane.rowStride
        val uPix = uPlane.pixelStride
        val vPix = vPlane.pixelStride
        val hh = h / 2
        val ww = w / 2
        for (y in 0 until hh) {
            for (x in 0 until ww) {
                val i = (y * 2 * w + x * 2) * 4
                val i2 = i + 4
                val j = ((y * 2 + 1) * w + x * 2) * 4
                val j2 = j + 4
                val r = ((rgba[i].toInt() and 0xFF) + (rgba[i2].toInt() and 0xFF) +
                        (rgba[j].toInt() and 0xFF) + (rgba[j2].toInt() and 0xFF)) shr 2
                val g = ((rgba[i + 1].toInt() and 0xFF) + (rgba[i2 + 1].toInt() and 0xFF) +
                        (rgba[j + 1].toInt() and 0xFF) + (rgba[j2 + 1].toInt() and 0xFF)) shr 2
                val b = ((rgba[i + 2].toInt() and 0xFF) + (rgba[i2 + 2].toInt() and 0xFF) +
                        (rgba[j + 2].toInt() and 0xFF) + (rgba[j2 + 2].toInt() and 0xFF)) shr 2
                val u = 128 + ((-6597 * r + -22187 * g + 28784 * b) shr 16)
                val v = 128 + ((28784 * r + -26145 * g + -2637 * b) shr 16)
                val uo = y * uRow + x * uPix
                val vo = y * vRow + x * vPix
                if (uo < uBuf.capacity()) {
                    uBuf.put(uo, u.coerceIn(16, 240).toByte())
                }
                if (vo < vBuf.capacity()) {
                    vBuf.put(vo, v.coerceIn(16, 240).toByte())
                }
            }
        }
    }

    // ---------------------------------------------------------------- 编码器挑选

    private fun readFps(format: MediaFormat): Float {
        val fps = try {
            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                val v = format.getInteger(MediaFormat.KEY_FRAME_RATE)
                if (v > 0) v.toFloat() else 30f
            } else {
                30f
            }
        } catch (e: Exception) {
            try {
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    format.getFloat(MediaFormat.KEY_FRAME_RATE)
                } else {
                    30f
                }
            } catch (e2: Exception) {
                30f
            }
        }
        return fps.coerceIn(1f, 240f)
    }

    private fun tryCreateEncoder(
        mime: String,
        w: Int,
        h: Int,
        fps: Float,
        bpp: Double,
        useSurface: Boolean,
    ): CreatedEncoder? {
        val candidates = encoderCandidates(mime, useSurface)
        if (candidates.isEmpty()) {
            lastEncoderError = "$mime: 设备没有可用的编码器"
            return null
        }
        val bitrate = (w.toDouble() * h * fps * bpp).toInt().coerceIn(4_000_000, 80_000_000)
        for (name in candidates) {
            var codec: MediaCodec? = null
            try {
                val format = MediaFormat.createVideoFormat(mime, w, h).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        if (useSurface) {
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                        } else {
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                        }
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps.toInt().coerceAtLeast(24))
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    // 明确标 Rec.709 / SDR / 有限范围，避免播放器按 HDR 或全范围解释导致发灰
                    try {
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    } catch (_: Exception) {
                    }
                }
                codec = MediaCodec.createByCodecName(name)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = if (useSurface) codec.createInputSurface() else null
                codec.start()
                Log.i(TAG, "编码器选定: $name ${if (useSurface) "surface" else "buffer"} $mime")
                return CreatedEncoder(codec, surface)
            } catch (e: Exception) {
                lastEncoderError = "$name: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
                Log.w(TAG, "编码器 $name 起不来: $lastEncoderError")
                try {
                    codec?.release()
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    private fun encoderCandidates(mime: String, useSurface: Boolean): List<String> {
        val hw = ArrayList<String>()
        val sw = ArrayList<String>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            val name = info.name
            val isSoftware = name.contains("android", ignoreCase = true) ||
                    name.contains("sw", ignoreCase = true)
            if (isSoftware) sw.add(name) else hw.add(name)
        }
        // surface 模式优先硬编；缓冲模式优先软的（对 stride 更宽容）
        return if (useSurface) hw + sw else sw + hw
    }

    private fun fmtTime(us: Long): String {
        val s = us / 1_000_000
        return String.format("%d:%02d", s / 60, s % 60)
    }

    companion object {
        private const val TAG = "magirri"
        private const val KEY_ROTATION = "rotation-degrees"
        private const val KEY_STRIDE = "stride"
        private const val KEY_SLICE_HEIGHT = "slice-height"
    }
}
