package com.nos.ridedaemon.core.hud

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import kotlin.concurrent.Volatile

class HudEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val onSampleEncoded: (ByteArray) -> Unit
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(
        MediaFormat.MIMETYPE_VIDEO_AVC)
    val inputSurface: Surface

    @Volatile
    private var running = false
    private var drainThread: Thread? = null

    init {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
    }

    fun start() {
        if (running) return
        running = true
        codec.start()
        drainThread = Thread(::drainLoop, "HudEncoderDrain").apply {
            start()
        }
    }

    fun stop() {
        running = false
        drainThread?.join()
        codec.stop()
        codec.release()
    }

    private fun drainLoop() {
        val bufferInfo = MediaCodec.BufferInfo()

        while (running) {
            val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)

            if (!running) break

            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // no output atm - skip
                    Thread.sleep(1) // optional backoff
                    continue
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    Log.i("HudEncoder", "output format changed: $format")
                    continue
                }
                index <0 -> {
                    continue
                }
                else -> {
                    if (bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(index)
                        if (outputBuffer != null) {
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(data)

                            // skip codec config buffers
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                onSampleEncoded(data)
                            }
                        }
                    }

                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
    }
}