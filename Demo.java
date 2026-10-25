package com.lyricsworld;

import java.util.List;

/** A built-in fake song so you can test the effect without Spotify. The words are original. */
public final class Demo {
	public static final String ID = "demo";
	public static final List<Lyrics.Line> LINES;
	public static final long DURATION_MS;

	static {
		String[] words = {
				"Words are floating all around me",
				"Drifting through the starlit night",
				"Every little line is glowing",
				"Purple, pink and silver light",
				"Dancing over fields of green",
				"Turn the volume up tonight",
				"Colours fall like falling rain",
				"Hold the moment, hold it tight",
				"Sing along and feel the beat",
				"Let the whole world hear the sound",
				"Stars are spinning overhead",
				"We are never coming down"
		};
		java.util.ArrayList<Lyrics.Line> l = new java.util.ArrayList<>();
		long t = 1500;
		for (String w : words) {
			l.add(new Lyrics.Line(t, w));
			t += 3200;
		}
		LINES = List.copyOf(l);
		DURATION_MS = t + 1500;
	}

	private static long startedAt = 0;

	private Demo() {
	}

	public static void restart() {
		startedAt = System.currentTimeMillis();
	}

	public static Track track(long now) {
		if (startedAt == 0) {
			restart();
		}
		long pos = (now - startedAt) % DURATION_MS;
		return new Track(ID, "Demo Song", "Lyrics World", "Lyrics World", "", DURATION_MS, pos, true, now);
	}
}
