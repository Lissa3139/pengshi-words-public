package com.pengshi.words.speech

/** Keeps the stream alive until Android reports that the queued PCM frames have played. */
internal fun awaitAudioTrackDrain(
    expectedFrames: Int,
    isActive: () -> Boolean,
    playbackHeadPosition: () -> Int,
    pause: (Long) -> Unit,
) {
    while (isActive() && playbackHeadPosition() < expectedFrames) {
        pause(10L)
    }
}
