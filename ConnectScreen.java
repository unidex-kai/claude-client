package com.lyricsworld;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Asks for your Spotify "Client ID", then opens the browser to approve the login. */
public final class ConnectScreen extends Screen {
	private static final int PAD = 14;
	private static final int PH = 190;

	private final Screen parent;
	private final long openNs = System.nanoTime();
	private EditBox input;
	private int px;
	private int py;
	private int pw;

	public ConnectScreen(Screen parent) {
		super(Component.literal("Connect Spotify"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		pw = Math.min(400, this.width - 16);
		px = (this.width - pw) / 2;
		py = (this.height - PH) / 2;
		int w = pw - PAD * 2;
		int x = px + PAD;

		this.input = new EditBox(this.font, x, py + 112, w, 20, Component.literal("Client ID"));
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
		}).pos(x, py + 138).size(w / 2 - 2, 20).build());

		this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose())
				.pos(x + w / 2 + 2, py + 138).size(w / 2 - 2, 20).build());
	}

	private void line(GuiGraphicsExtractor g, String s, int x, int y, int rgb) {
		Fonts.draw(g, this.font, s, x, y, 0xFF000000 | rgb, false, 0);
	}

	private String fit(String s, int maxW) {
		if (this.font.width(s) <= maxW) {
			return s;
		}
		String cut = s;
		while (cut.length() > 1 && this.font.width(cut + "..") > maxW) {
			cut = cut.substring(0, cut.length() - 1);
		}
		return cut + "..";
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		this.extractBackground(g, mouseX, mouseY, delta);

		Config c = Config.get();
		int accent = Config.COLOURS[Math.floorMod(c.colour, Config.COLOURS.length)];
		int accent2 = Config.COLOURS[Math.floorMod(c.colour2, Config.COLOURS.length)];
		float t = (System.nanoTime() - openNs) / 1e9f;
		boolean anim = c.menuAnimations;
		int mf = Fonts.clamp(c.menuFont);

		// panel goes behind the text box and buttons, so it is drawn before them
		for (int i = 1; i <= 6; i++) {
			SettingsScreen.roundRect(g, px - i, py - i, pw + 2 * i, PH + 2 * i, 3, SettingsScreen.argb(0x000000, 0.075f - i * 0.011f));
		}
		SettingsScreen.roundRect(g, px, py, pw, PH, 3, SettingsScreen.argb(0x11111A, 0.95f));
		SettingsScreen.roundRect(g, px, py, pw, 1, 0, SettingsScreen.argb(accent, 0.55f));
		int step = 3;
		int barW = pw - PAD * 2;
		float sweep = anim ? (t * 0.5f) % 1.6f - 0.3f : -1f;
		for (int bx = 0; bx < barW; bx += step) {
			float f = bx / (float) barW;
			int col = SettingsScreen.mix(accent, accent2, f);
			col = SettingsScreen.mix(col, 0xFFFFFF, Math.max(0f, 1f - Math.abs(f - sweep) / 0.15f) * 0.7f);
			g.fill(px + PAD + bx, py + 33, px + PAD + Math.min(bx + step, barW), py + 35, SettingsScreen.argb(col, 0.95f));
		}

		super.extractRenderState(g, mouseX, mouseY, delta);

		// title, letter by letter, sliding between the two colours
		String title = "Connect Spotify";
		g.pose().pushMatrix();
		g.pose().translate(px + PAD, py + 9f);
		g.pose().scale(1.3f, 1.3f);
		int tx = 0;
		for (int i = 0; i < title.length(); i++) {
			String ch = title.substring(i, i + 1);
			float f = anim ? (float) (Math.sin(t * 2.2f - i * 0.45f) * 0.5 + 0.5) : i / (float) (title.length() - 1);
			Fonts.draw(g, this.font, ch, tx, 0, 0xFF000000 | SettingsScreen.mix(accent, accent2, f), true, mf);
			tx += Fonts.width(this.font, ch, mf);
		}
		g.pose().popMatrix();

		int x = px + PAD;
		int maxW = pw - PAD * 2;
		int y = py + 42;
		String[][] steps = {
				{"1", "Open developer.spotify.com/dashboard and create a free app."},
				{"2", "Add this Redirect URI:"},
				{"", Spotify.REDIRECT},
				{"3", "Tick \"Web API\", save, then copy the app's Client ID."},
				{"4", "Paste it below. Your browser opens to approve the login."}
		};
		for (String[] s : steps) {
			if (s[0].isEmpty()) {
				line(g, fit(s[1], maxW - 12), x + 12, y, accent);
			} else {
				line(g, s[0], x, y, accent);
				line(g, fit(s[1], maxW - 12), x + 12, y, 0xD8D8E8);
			}
			y += 13;
		}
		line(g, fit("No password or secret is stored, only a login token on your PC.", maxW), x, py + 166, 0x80809A);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}
