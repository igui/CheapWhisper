package com.example.smartnotetaker

/**
 * A provider's real-time transcription session. The keyboard feeds it raw audio as it is
 * captured and displays whatever text comes back while the user is still speaking.
 *
 * Audio format the keyboard supplies: 16 kHz, mono, 16-bit little-endian PCM, in buffers
 * of a few hundred to a few thousand bytes, roughly every 20–160 ms. If a provider needs a
 * different format the implementation must convert.
 *
 * Implementations must invoke their `onTranscript(finalText, interim)` callback on the main
 * thread: `finalText` is everything locked in so far (space-joined), `interim` is the
 * provider's current guess for the phrase in progress ("" when none).
 */
interface LiveTranscriber {
    /** Opens the connection. Must not block; failures surface later from [finish]. */
    fun start()

    /** Sends [len] bytes of PCM from [pcm]. Called on the recorder thread; copy the buffer. */
    fun send(pcm: ByteArray, len: Int)

    /**
     * Signals end of audio, waits (bounded) for the provider to flush its last results and
     * returns the complete final transcript. Throws if the session failed, so the caller
     * can fall back to a one-shot request on the recorded file.
     */
    suspend fun finish(): String

    /** Drops the connection immediately, discarding pending results. Idempotent. */
    fun cancel()
}
