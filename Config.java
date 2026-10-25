package com.lyricsworld;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/** All settings, saved to config/lyricsworld.json. */
public final class Config {
	private static final Logger LOG = LoggerFactory.getLogger("lyricsworld");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Config instance = new Config();

	// ---- palette (matches the colour dots in the video) ----
	public static final String[] COLOUR_NAMES = {"Purple", "Lavender", "Blue", "Pink", "Red", "Orange", "Yellow", "Teal", "Green", "White"};
	public static final int[] COLOURS = {0xA78BFA, 0xC4B5FD, 0x60A5FA, 0xF472B6, 0xF87171, 0xFB923C, 0xFACC15, 0x2DD4BF, 0x4ADE80, 0xFFFFFF};
	public static final String[] INTENSITY_NAMES = {"Calm", "Normal", "Hype"};
	public static final String[] SOURCE_NAMES = {"Windows media (no login)", "Spotify login"};
	public static final String[] PLACEMENT_NAMES = {"Scattered around you", "In front of you"};

	// ---- settings ----
	public boolean enabled = true;
	public boolean showBar = true;
	public boolean glow = true;
	public boolean gradient = true;
	public int intensity = 2;
	public int placement = 0;
	public int wordsPerLine = 4;
	public float staySeconds = 3.5f;
	public float distance = 3.5f;
	public int sizePercent = 100;
	public int colour = 0;
	public int colour2 = 3;
	public int syncOffsetMs = 0;
	/** 0 = Windows media controls (no login), 1 = Spotify login. */
	public int source = 0;

	// ---- spotify (stored only on this PC) ----
	public String clientId = "";
	public String accessToken = "";
	public String refreshToken = "";
	public long expiresAtMs = 0;

	public static Config get() {
		return instance;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("lyricsworld.json");
	}

	public static void load() {
		try {
			Path p = file();
			if (Files.exists(p)) {
				Config c = GSON.fromJson(Files.readString(p), Config.class);
				if (c != null) {
					instance = c;
				}
			}
		} catch (Exception e) {
			LOG.warn("Could not read config, using defaults", e);
		}
	}

	public static synchronized void save() {
		try {
			Files.writeString(file(), GSON.toJson(instance));
		} catch (Exception e) {
			LOG.warn("Could not save config", e);
		}
	}
}
