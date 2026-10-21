package com.magirri.lume

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录像：画面带影调 → H.264，麦克风 → AAC，两条轨合成一个 MP4。
 *
 * 画面由 GL 直接渲进编码器的输入 Surface，不经过 CPU、不掉画质；
 * 音频用 AudioRecord 读 PCM 喂给 AAC 编码器。没有录音权限时自动降级成无声录像。
 *
 * 先写应用私有目录的临时文件，停止时再拷进相册——直接写 MediaStore 中途失败
 * 会留下一个 0 秒的坏条目。
 */
class VideoRecorder(private val context: Context) {

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var tempFile: File? = null

    private val lock = Any()
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false

    private var videoDrain: Thread? = null
    private var audioThread: Thread? = null
    private var audioDrain: Thread? = null

    @Volatile private var running = false

    var inputSurface: Surface? = null
        private set

    /** 失败原因，给 UI 显示 */
    var lastError: String? = null
        private set

    val isRecording: Boolean get() = running

    /** 开始录像，返回编码器输入 Surface（交给 GL 渲染） */
    fun start(width: Int, height: Int, fps: Int): Surface? {
        if (running) return null
        val file = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "rec_${System.currentTimeMillis()}.mp4"
        )
        tempFile = file
        val bitrate = (width.toLong() * height * fps * 0.12).toInt().coerceIn(2_000_000, 40_000_000)
        return try {
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            running = true

            val vFormat = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceAtLeast(24))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // 实时优先级：让编码器优先保时延，而不是攒着凑画质
                try {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                } catch (_: Exception) {
                }
                // 注意：不要设 KEY_COLOR_STANDARD/TRANSFER/RANGE，个别编码器见到不认识的
                // 组合会退化到软件路径，表现就是编码极慢、取景器卡死。
            }
            val vCodec = MediaCodec.createEncoderByType(VIDEO_MIME)
            vCodec.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = vCodec.createInputSurface()
            vCodec.start()
            videoEncoder = vCodec
            inputSurface = surface
            startVideoDrain(vCodec)

            startAudio()
            Log.i(TAG, "录像开始 ${width}x$height @$fps bitrate=$bitrate 音频=${audioEncoder != null}")
            surface
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "录像启动失败: ${e.message}")
            releaseQuietly()
            null
        }
    }

    // ---------------- 视频 ----------------

    private fun startVideoDrain(codec: MediaCodec) {
        videoDrain = Thread {
            val info = MediaCodec.BufferInfo()
            try {
                while (true) {
                    val ix = codec.dequeueOutputBuffer(info, 10_000)
                    if (ix == MediaCodec.INFO_TRY_AGAIN_LATER) continue
                    if (ix == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoFormat = codec.outputFormat
                        maybeStartMuxer()
                        continue
                    }
                    if (ix < 0) continue
                    val buf = codec.getOutputBuffer(ix)
                    if (buf != null) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
                        if (info.size > 0) writeSample(true, buf, info)
                    }
                    codec.releaseOutputBuffer(ix, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "视频收尾异常: ${e.message}")
            }
        }.apply { start() }
    }

    // ---------------- 音频 ----------------

    private fun startAudio() {
        if (!hasAudioPermission()) {
            Log.i(TAG, "没有录音权限，录无声视频")
            return
        }
        try {
            val rate = 44_100
            val channelMask = AudioFormat.CHANNEL_IN_MONO
            val minBuf = AudioRecord.getMinBufferSize(
                rate, channelMask, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = if (minBuf > 0) minBuf * 4 else 16_384
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate, channelMask, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord 初始化失败，录无声视频")
                rec.release()
                return
            }

            val format = MediaFormat.createAudioFormat(AUDIO_MIME, rate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val codec = MediaCodec.createEncoderByType(AUDIO_MIME)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioEncoder = codec
            audioRecord = rec
            rec.startRecording()
            startAudioDrain(codec)
            startAudioPump(rec, codec, rate)
        } catch (e: Exception) {
            Log.w(TAG, "音频启动失败: ${e.message}")
            try {
                audioRecord?.release()
            } catch (_: Exception) {
            }
            audioRecord = null
            audioEncoder = null
        }
    }

    /** PCM → 编码器输入 */
    private fun startAudioPump(rec: AudioRecord, codec: MediaCodec, rate: Int) {
        audioThread = Thread {
            val pcm = ByteArray(8_192)
            var samples = 0L
            try {
                while (running) {
                    val n = rec.read(pcm, 0, pcm.size)
                    if (n <= 0) continue
                    var offset = 0
                    while (offset < n && running) {
                        val inIx = codec.dequeueInputBuffer(10_000)
                        if (inIx < 0) break
                        val inBuf = codec.getInputBuffer(inIx) ?: break
                        inBuf.clear()
                        val len = minOf(inBuf.remaining(), n - offset)
                        inBuf.put(pcm, offset, len)
                        val pts = samples * 1_000_000L / rate
                        codec.queueInputBuffer(inIx, 0, len, pts, 0)
                        samples += len / 2
                        offset += len
                    }
                }
                val inIx = codec.dequeueInputBuffer(50_000)
                if (inIx >= 0) {
                    codec.queueInputBuffer(
                        inIx, 0, 0, samples * 1_000_000L / rate,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "音频采集异常: ${e.message}")
            }
        }.apply { start() }
    }

    private fun startAudioDrain(codec: MediaCodec) {
        audioDrain = Thread {
            val info = MediaCodec.BufferInfo()
            try {
                while (true) {
                    val ix = codec.dequeueOutputBuffer(info, 10_000)
                    if (ix == MediaCodec.INFO_TRY_AGAIN_LATER) continue
                    if (ix == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        audioFormat = codec.outputFormat
                        maybeStartMuxer()
                        continue
                    }
                    if (ix < 0) continue
                    val buf = codec.getOutputBuffer(ix)
                    if (buf != null) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
                        if (info.size > 0) writeSample(false, buf, info)
                    }
                    codec.releaseOutputBuffer(ix, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "音频收尾异常: ${e.message}")
            }
        }.apply { start() }
    }

    // ---------------- 封装 ----------------

    /** 两条轨的格式都到齐才开 muxer */
    private fun maybeStartMuxer() {
        synchronized(lock) {
            if (muxerStarted) return
            val vf = videoFormat ?: return
            val m = muxer ?: return
            videoTrack = m.addTrack(vf)
            audioFormat?.let { audioTrack = m.addTrack(it) }
            m.start()
            muxerStarted = true
        }
    }

    private fun writeSample(video: Boolean, buf: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (!muxerStarted) return
            val track = if (video) videoTrack else audioTrack
            if (track < 0) return
            try {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                muxer?.writeSampleData(track, buf, info)
            } catch (e: Exception) {
                Log.w(TAG, "写入样本失败: ${e.message}")
            }
        }
    }

    /** 停止并返回相册里的 Uri（失败返回 null） */
    fun stop(): Uri? {
        if (!running) return null
        running = false
        return try {
            try {
                audioRecord?.stop()
            } catch (_: Exception) {
            }
            videoEncoder?.signalEndOfInputStream()
            videoDrain?.join(3000)
            audioThread?.join(1500)
            audioDrain?.join(1500)
            releaseQuietly()

            val f = tempFile
            tempFile = null
            if (f == null || !f.exists() || f.length() < 1024) {
                if (lastError == null) lastError = "编码器没有输出有效帧"
                f?.delete()
                null
            } else {
                val uri = publish(f)
                f.delete()
                Log.i(TAG, "录像完成 -> $uri")
                uri
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "停止录像失败: ${e.message}")
            releaseQuietly()
            null
        }
    }

    private fun publish(file: File): Uri? {
        val name = "ARRI_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MAGIRRI Lume")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "MAGIRRI Lume"
            ).apply { mkdirs() }
            val dst = File(dir, name)
            file.copyTo(dst, overwrite = true)
            Uri.fromFile(dst)
        }
    }

    private fun hasAudioPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun releaseQuietly() {
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        for (codec in listOf(videoEncoder, audioEncoder)) {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
        }
        videoEncoder = null
        audioEncoder = null
        inputSurface = null
        synchronized(lock) {
            try {
                if (muxerStarted) muxer?.stop()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            muxer = null
            muxerStarted = false
            videoTrack = -1
            audioTrack = -1
            videoFormat = null
            audioFormat = null
        }
    }

    companion object {
        private const val TAG = "arccam"
        private const val VIDEO_MIME = "video/avc"
        private const val AUDIO_MIME = "audio/mp4a-latm"
    }
}
