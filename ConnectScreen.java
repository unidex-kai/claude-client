package com.lyricsworld;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Asks for your Spotify "Client ID", then opens the browser to approve the login. */
public final class ConnectScreen extends Screen {
	private final Screen parent;
	private EditBox input;

	public ConnectScreen(Screen parent) {
		super(Component.literal("Connect Spotify"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int w = 260;
		int x = this.width / 2 - w / 2;
		this.input = new EditBox(this.font, x, 108, w, 20, Component.literal("Client ID"));
		this.input.setMaxLength(100);
		this.input.setValue(Config.get().clientId);
		this.addRenderableWidget(this.input);

		this.addRenderableWidget(Button.builder(Component.literal("Save and log in"), b -> {
			String id = this.input.getValue().trim();
			if (id.isEmpty()) {
				return;
			}
			Config.get().clientId = id;
			Config.save();
			Spotify.login(id);
			this.minecraft.gui.setScreen(this.parent);
		}).pos(x, 134).size(w / 2 - 2, 20).build());

		this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose())
				.pos(x + w / 2 + 2, 134).size(w / 2 - 2, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		this.extractBackground(g, mouseX, mouseY, delta);
		super.extractRenderState(g, mouseX, mouseY, delta);
		String[] lines = {
				"Connect Spotify",
				"1. Open developer.spotify.com/dashboard and create an app (free).",
				"2. Add this Redirect URI:  " + Spotify.REDIRECT,
				"3. Tick \"Web API\", save, then copy the app's Client ID.",
				"4. Paste it below. Your browser opens to approve the login.",
				"No password or secret is stored, only a login token on your PC."
		};
		int y = 20;
		for (int i = 0; i < lines.length; i++) {
			int color = i == 0 ? 0xFFFFFFFF : (i == lines.length - 1 ? 0xFF909090 : 0xFFD8D8D8);
			g.text(this.font, lines[i], (this.width - this.font.width(lines[i])) / 2, y, color, true);
			y += i == 0 ? 16 : 12;
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}
