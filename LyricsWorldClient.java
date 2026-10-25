package com.lyricsworld;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class LyricsWorldClient implements ClientModInitializer {
	/** True while the built-in demo song is playing instead of Spotify. */
	public static volatile boolean demo = false;

	private static KeyMapping openKey;

	/** The song to show: from Windows media or from Spotify, depending on the Source setting. */
	public static Track activeTrack() {
		return Config.get().source == 0 ? WindowsMedia.track : Spotify.track;
	}

	public static String statusText() {
		return Config.get().source == 0 ? WindowsMedia.status : Spotify.status;
	}

	public static void setDemo(boolean on) {
		demo = on;
		if (on) {
			Demo.restart();
		}
	}

	@Override
	public void onInitializeClient() {
		Config.load();
		Spotify.start();
		WindowsMedia.apply();

		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.lyricsworld.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, KeyMapping.Category.MISC));

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("lyricsworld", "lyrics"), new LyricsHud());

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.consumeClick()) {
				if (client.gui.screen() == null) {
					client.gui.setScreen(new SettingsScreen());
				}
			}
		});
	}
}
