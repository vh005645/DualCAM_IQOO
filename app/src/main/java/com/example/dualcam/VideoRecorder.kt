package com.example.dualcam

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

/**
 * Mã hóa khung hình đã ghép (từ GlCompositor) thành H.264 + âm thanh AAC từ micro,
 * rồi ghi vào MỘT file MP4 duy nhất.
 */
class VideoRecorder(
    private val compositor: GlCompositor,
    private val pfd: ParcelFileDescriptor,
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    audioWanted: Boolean
) {
    private var audioEnabled = audioWanted

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null

    private val lock = ReentrantLock()
    private val startedCond = lock.newCondition()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false

    @Volatile private var running = false
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private val startNanos = System.nanoTime()

    fun start() {
        try {
            val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val vc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            vc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = vc.createInputSurface()
            vc.start()
            videoCodec = vc

            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            if (audioEnabled) {
                try {
                    setupAudio()
                } catch (e: Exception) {
                    audioEnabled = false
                    releaseAudio()
                }
            }

            running = true
            if (audioEnabled) audioThread = thread(name = "AudioEnc") { audioLoop() }
            videoThread = thread(name = "VideoEnc") { videoLoop() }
            compositor.startEncoding(inputSurface!!, width, height, startNanos)
        } catch (e: Exception) {
            running = false
            releaseAll()
            throw e
        }
    }

    /** Dừng ghi, hoàn tất file. Chạy ở luồng nền; onDone(true) nếu file hợp lệ. */
    fun stop(onDone: (Boolean) -> Unit) {
        thread(name = "RecStop") {
            var ok = true
            try {
                compositor.stopEncoding()
                videoCodec?.signalEndOfInputStream()
                running = false
                videoThread?.join(4000)
                audioThread?.join(4000)
            } catch (e: Exception) {
                ok = false
            }
            lock.withLock {
                try {
                    if (muxerStarted) muxer?.stop() else ok = false
                } catch (e: Exception) {
                    ok = false
                }
            }
            releaseAll()
            onDone(ok)
        }
    }

    // ------------------------------------------------------------------

    private fun videoLoop() {
        val codec = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    onFormat(true, codec.outputFormat)
                } else if (idx >= 0) {
                    val buf = codec.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && buf != null) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        writeSample(videoTrack, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } catch (e: Exception) {
            // kết thúc luồng
        }
    }

    private fun setupAudio() {
        val rate = SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 16384)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord")
        }
        audioRecord = rec

        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        ac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        ac.start()
        audioCodec = ac
    }

    private fun audioLoop() {
        val codec = audioCodec ?: return
        val rec = audioRecord ?: return
        val info = MediaCodec.BufferInfo()
        var eosSent = false
        var done = false
        var firstUs = -1L
        var totalSamples = 0L
        var lastPts = 0L
        try {
            rec.startRecording()
            while (!done) {
                if (!eosSent) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val ib: ByteBuffer = codec.getInputBuffer(inIdx) ?: continue
                        ib.clear()
                        if (!running) {
                            codec.queueInputBuffer(inIdx, 0, 0, lastPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSent = true
                        } else {
                            val n = rec.read(ib, min(ib.capacity(), 2048))
                            if (n > 0) {
                                val samples = n / 2
                                if (firstUs < 0) {
                                    val nowUs = (System.nanoTime() - startNanos) / 1000
                                    firstUs = max(0L, nowUs - samples * 1_000_000L / SAMPLE_RATE)
                                }
                                val pts = firstUs + totalSamples * 1_000_000L / SAMPLE_RATE
                                totalSamples += samples
                                lastPts = pts
                                codec.queueInputBuffer(inIdx, 0, n, pts, 0)
                            } else {
                                codec.queueInputBuffer(inIdx, 0, 0, lastPts, 0)
                            }
                        }
                    }
                } else {
                    Thread.sleep(5)
                }

                while (true) {
                    val o = codec.dequeueOutputBuffer(info, 0)
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        onFormat(false, codec.outputFormat)
                    } else if (o >= 0) {
                        val buf = codec.getOutputBuffer(o)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && buf != null) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            writeSample(audioTrack, buf, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            done = true
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Nếu âm thanh lỗi: vẫn cho video chạy tiếp (không có tiếng)
            lock.withLock {
                audioEnabled = false
                tryStartMuxer()
            }
        }
    }

    // ------------------------------------------------------------------

    private fun onFormat(video: Boolean, format: MediaFormat) {
        lock.withLock {
            val m = muxer ?: return
            val idx = m.addTrack(format)
            if (video) videoTrack = idx else audioTrack = idx
            tryStartMuxer()
        }
    }

    // gọi khi đang giữ lock
    private fun tryStartMuxer() {
        if (!muxerStarted && videoTrack >= 0 && (!audioEnabled || audioTrack >= 0)) {
            muxer?.start()
            muxerStarted = true
            startedCond.signalAll()
        }
    }

    private fun writeSample(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (track < 0) return
        lock.withLock {
            var waited = 0
            while (!muxerStarted && waited < 100) {
                startedCond.await(50, TimeUnit.MILLISECONDS)
                waited++
            }
            if (muxerStarted) muxer?.writeSampleData(track, buf, info)
        }
    }

    private fun releaseAudio() {
        try { audioRecord?.stop() } catch (e: Exception) { }
        try { audioRecord?.release() } catch (e: Exception) { }
        try { audioCodec?.stop() } catch (e: Exception) { }
        try { audioCodec?.release() } catch (e: Exception) { }
        audioRecord = null
        audioCodec = null
    }

    private fun releaseAll() {
        releaseAudio()
        try { videoCodec?.stop() } catch (e: Exception) { }
        try { videoCodec?.release() } catch (e: Exception) { }
        try { inputSurface?.release() } catch (e: Exception) { }
        try { muxer?.release() } catch (e: Exception) { }
        try { pfd.close() } catch (e: Exception) { }
        videoCodec = null
        inputSurface = null
        muxer = null
    }

    companion object {
        private const val SAMPLE_RATE = 44100
    }
}
