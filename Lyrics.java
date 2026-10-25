package com.lyricsworld;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time-synced lyrics from LRCLIB (https://lrclib.net), a free lyrics service.
 * Spotify itself has no public lyrics API, so this is the usual substitute.
 */
public final class Lyrics {
	private static final Logger LOG = LoggerFactory.getLogger("lyricsworld");
	private static final Pattern STAMP = Pattern.compile("\\[(\\d+):(\\d+(?:[.:]\\d+)?)]");

	public record Line(long timeMs, String text) {
	}

	/** Track id these lyrics belong to. */
	public static volatile String forId = "";
	public static volatile List<Line> lines = List.of();
	public static volatile String note = "";

	private static volatile String requestedId = "";

	private Lyrics() {
	}

	/** Starts a background lookup unless we already did it for this track. */
	public static void request(Track t) {
		if (t.id().equals(requestedId)) {
			return;
		}
		requestedId = t.id();
		note = "Looking up lyrics...";
		Spotify.POOL.execute(() -> {
			List<Line> found = List.of();
			try {
				found = fetch(t);
			} catch (Exception e) {
				LOG.debug("lyrics lookup failed", e);
			}
			if (t.id().equals(requestedId)) {
				lines = found;
				forId = t.id();
				note = found.isEmpty() ? "No synced lyrics found for this song" : "";
			}
		});
	}

	private static List<Line> fetch(Track t) throws Exception {
		String get = "https://lrclib.net/api/get?track_name=" + enc(t.title())
				+ "&artist_name=" + enc(t.firstArtist())
				+ "&album_name=" + enc(t.album())
				+ "&duration=" + (t.durationMs() / 1000);
		String body = http(get);
		if (body != null) {
			List<Line> l = fromJson(JsonParser.parseString(body));
			if (!l.isEmpty()) {
				return l;
			}
		}
		String search = "https://lrclib.net/api/search?track_name=" + enc(t.title()) + "&artist_name=" + enc(t.firstArtist());
		body = http(search);
		if (body != null) {
			JsonElement root = JsonParser.parseString(body);
			if (root.isJsonArray()) {
				JsonArray a = root.getAsJsonArray();
				for (JsonElement e : a) {
					List<Line> l = fromJson(e);
					if (!l.isEmpty()) {
						return l;
					}
				}
			}
		}
		return List.of();
	}

	private static List<Line> fromJson(JsonElement e) {
		if (e == null || !e.isJsonObject()) {
			return List.of();
		}
		JsonObject o = e.getAsJsonObject();
		JsonElement s = o.get("syncedLyrics");
		if (s == null || s.isJsonNull()) {
			return List.of();
		}
		return parse(s.getAsString());
	}

	private static String http(String url) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(10))
				.header("User-Agent", "LyricsWorld/1.0 (Minecraft Fabric mod)")
				.GET().build();
		HttpResponse<String> res = Spotify.HTTP.send(req, HttpResponse.BodyHandlers.ofString());
		return res.statusCode() == 200 ? res.body() : null;
	}

	private static String enc(String s) {
		return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
	}

	/** Parses LRC text such as "[01:23.45] some words". */
	public static List<Line> parse(String lrc) {
		List<Line> out = new ArrayList<>();
		for (String raw : lrc.split("\\r?\\n")) {
			Matcher m = STAMP.matcher(raw);
			List<Long> times = new ArrayList<>();
			int end = 0;
			while (m.find() && m.start() == end) {
				long min = Long.parseLong(m.group(1));
				double sec = Double.parseDouble(m.group(2).replace(':', '.'));
				times.add(min * 60_000L + Math.round(sec * 1000));
				end = m.end();
			}
			if (times.isEmpty()) {
				continue;
			}
			String text = raw.substring(end).trim();
			for (long tm : times) {
				out.add(new Line(tm, text));
			}
		}
		out.sort(Comparator.comparingLong(Line::timeMs));
		return out;
	}
}
