package com.lyricsworld;

/** What Spotify (or the demo) is playing, measured at fetchedAtMs. */
public record Track(String id, String title, String artist, String firstArtist, String album,
					long durationMs, long progressMs, boolean playing, long fetchedAtMs) {

	/** Estimated position right now, in milliseconds. */
	public long positionMs(long nowMs) {
		long pos = progressMs;
		if (playing) {
			pos += nowMs - fetchedAtMs;
		}
		return Math.max(0, Math.min(pos, durationMs));
	}
}
