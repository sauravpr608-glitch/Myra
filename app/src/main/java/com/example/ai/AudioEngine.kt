package com.example.ai

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log

class AudioEngine {

    companion object {
        private const val RECORD_SAMPLE_RATE = 16000
        private const val RECORD_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val RECORD_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private const val PLAY_SAMPLE_RATE = 24000
        private const val PLAY_CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val PLAY_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private const val CHUNK_SIZE = 1024
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    // For echo suppression
    var isMyraSpeaking = false

    @SuppressLint("MissingPermission")
    fun startRecording(onAudioChunk: (ByteArray) -> Unit) {
        if (isRecording) return
        
        try {
            val minBufSize = AudioRecord.getMinBufferSize(
                RECORD_SAMPLE_RATE,
                RECORD_CHANNEL_CONFIG,
                RECORD_AUDIO_FORMAT
            )
            val bufferSize = Math.max(minBufSize, CHUNK_SIZE * 4)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RECORD_SAMPLE_RATE,
                RECORD_CHANNEL_CONFIG,
                RECORD_AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioEngine", "AudioRecord initialization failed.")
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            recordingThread = Thread {
                val buffer = ByteArray(CHUNK_SIZE)
                while (isRecording) {
                    val readBytes = audioRecord?.read(buffer, 0, CHUNK_SIZE) ?: 0
                    if (readBytes > 0) {
                        // Echo suppression check
                        if (!isMyraSpeaking) {
                            val chunk = ByteArray(readBytes)
                            System.arraycopy(buffer, 0, chunk, 0, readBytes)
                            onAudioChunk(chunk)
                        }
                    }
                }
            }.apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            Log.d("AudioEngine", "Mic Audio Recording Loop Started.")
        } catch (e: Exception) {
            Log.e("AudioEngine", "Error starting recording", e)
        }
    }

    fun stopRecording() {
        isRecording = false
        recordingThread?.join(500)
        recordingThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e("AudioEngine", "Error stopping record", e)
        }
        audioRecord = null
        Log.d("AudioEngine", "Mic Audio Recording Loop Stopped.")
    }

    fun startPlayback() {
        if (audioTrack != null) return
        try {
            val minBufSize = AudioTrack.getMinBufferSize(
                PLAY_SAMPLE_RATE,
                PLAY_CHANNEL_CONFIG,
                PLAY_AUDIO_FORMAT
            )

            // Setup audio track with standard assistance/speech configs
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                PLAY_SAMPLE_RATE,
                PLAY_CHANNEL_CONFIG,
                PLAY_AUDIO_FORMAT,
                Math.max(minBufSize, CHUNK_SIZE * 4),
                AudioTrack.MODE_STREAM
            )

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e("AudioEngine", "AudioTrack state not initialized")
                return
            }

            audioTrack?.play()
            Log.d("AudioEngine", "Speaker Audio Playback Engine Initialized.")
        } catch (e: Exception) {
            Log.e("AudioEngine", "Error starting AudioTrack", e)
        }
    }

    fun playAudioChunk(chunk: ByteArray) {
        if (audioTrack == null) {
            startPlayback()
        }
        try {
            audioTrack?.write(chunk, 0, chunk.size)
        } catch (e: Exception) {
            Log.e("AudioEngine", "Error playing chunk", e)
        }
    }

    fun stopPlayback() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e("AudioEngine", "Error stopping track", e)
        }
        audioTrack = null
        Log.d("AudioEngine", "Speaker Audio Playback Engine Stopped.")
    }
}
