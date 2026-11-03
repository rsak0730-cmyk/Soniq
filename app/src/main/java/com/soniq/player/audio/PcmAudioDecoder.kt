package com.soniq.player.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

object PcmAudioDecoder {

    // Optimized: Only decode the first 12 seconds instead of 120s (10x faster)
    private const val MAX_DECODE_DURATION_US = 12_000_000L
    private const val TIMEOUT_US = 2500L

    suspend fun extractFingerprintData(context: Context, audioUri: Uri): Pair<Int, String>? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, audioUri, null)
            var audioTrackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex == -1 || format == null) return@withContext null

            extractor.selectTrack(audioTrackIndex)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            val totalSeconds = (durationUs / 1_000_000L).toInt().coerceAtLeast(1)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmDataStream = ByteArrayOutputStream(32 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            var isEOS = false

            while (!isEOS) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    val sampleTime = extractor.sampleTime

                    if (sampleSize < 0 || sampleTime > MAX_DECODE_DURATION_US) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0)
                        extractor.advance()
                    }
                }

                var outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                while (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        pcmDataStream.write(chunk)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                }
            }

            val pcmBytes = pcmDataStream.toByteArray()
            if (pcmBytes.isEmpty()) return@withContext null

            val fingerprintString = generateFastFingerprint(pcmBytes)
            Pair(totalSeconds, fingerprintString)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            runCatching {
                codec?.stop()
                codec?.release()
                extractor.release()
            }
        }
    }

    private fun generateFastFingerprint(pcmAudio: ByteArray): String {
        val shortBuffer = ByteBuffer.wrap(pcmAudio).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val totalSamples = shortBuffer.remaining()
        val step = (totalSamples / 512).coerceAtLeast(1)

        val intHashes = ArrayList<Int>(512)
        var currentHash = 0x55555555

        for (i in 0 until totalSamples step step) {
            val sample = shortBuffer.get(i).toInt()
            currentHash = (currentHash shl 4) xor (currentHash ushr 28) xor sample
            intHashes.add(currentHash)
        }

        val rawFingerprintBytes = ByteBuffer.allocate(intHashes.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (h in intHashes) {
            rawFingerprintBytes.putInt(h)
        }

        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(rawFingerprintBytes.array())
        deflater.finish()

        val outputStream = ByteArrayOutputStream(1024)
        val buffer = ByteArray(512)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            outputStream.write(buffer, 0, count)
        }
        deflater.end()

        return Base64.encodeToString(outputStream.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
    }
}
