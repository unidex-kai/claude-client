package com.lyricsworld;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Press K in game to open this. Click a button to change its value; the list wraps around. */
public final class SettingsScreen extends Screen {
	private record Row(Supplier<String> text, Runnable onClick) {
	}

	private final List<Row> rows = new ArrayList<>();
	private final List<Button> buttons = new ArrayList<>();

	public SettingsScreen() {
		super(Component.literal("Lyrics World"));
	}

	private static String onOff(boolean b) {
		return b ? "On" : "Off";
	}

	@Override
	protected void init() {
		Config c = Config.get();
		rows.clear();
		buttons.clear();

		rows.add(new Row(() -> "Lyrics: " + onOff(c.enabled), () -> c.enabled = !c.enabled));
		rows.add(new Row(() -> "Spotify: " + (Spotify.connected() ? "connected (click to log out)" : "log in"), () -> {
			if (Spotify.connected()) {
				Spotify.logout();
			} else {
				this.minecraft.gui.setScreen(new ConnectScreen(this));
			}
		}));
		rows.add(new Row(() -> "Demo song: " + onOff(LyricsWorldClient.demo), () -> LyricsWorldClient.setDemo(!LyricsWorldClient.demo)));
		rows.add(new Row(() -> "Intensity: " + Config.INTENSITY_NAMES[c.intensity], () -> c.intensity = (c.intensity + 1) % Config.INTENSITY_NAMES.length));
		rows.add(new Row(() -> "Placement: " + Config.PLACEMENT_NAMES[c.placement], () -> c.placement = (c.placement + 1) % Config.PLACEMENT_NAMES.length));
		rows.add(new Row(() -> "Words per line: " + c.wordsPerLine, () -> c.wordsPerLine = c.wordsPerLine % 8 + 1));
		rows.add(new Row(() -> "Lines stay for: " + c.staySeconds + "s", () -> {
			c.staySeconds += 0.5f;
			if (c.staySeconds > 8f) {
				c.staySeconds = 1.5f;
			}
		}));
		rows.add(new Row(() -> "Distance: " + c.distance + " blocks", () -> {
			c.distance += 0.5f;
			if (c.distance > 8f) {
				c.distance = 2f;
			}
		}));
		rows.add(new Row(() -> "Size: " + c.sizePercent + "%", () -> {
			c.sizePercent += 10;
			if (c.sizePercent > 200) {
				c.sizePercent = 50;
			}
		}));
		rows.add(new Row(() -> "Colour mode: " + (c.gradient ? "Gradient" : "Solid"), () -> c.gradient = !c.gradient));
		rows.add(new Row(() -> "Colour: " + Config.COLOUR_NAMES[c.colour], () -> c.colour = (c.colour + 1) % Config.COLOURS.length));
		rows.add(new Row(() -> "Second colour: " + Config.COLOUR_NAMES[c.colour2], () -> c.colour2 = (c.colour2 + 1) % Config.COLOURS.length));
		rows.add(new Row(() -> "Glow: " + onOff(c.glow), () -> c.glow = !c.glow));
		rows.add(new Row(() -> "Now-playing bar: " + onOff(c.showBar), () -> c.showBar = !c.showBar));
		rows.add(new Row(() -> "Sync offset: " + c.syncOffsetMs + " ms", () -> {
			c.syncOffsetMs += 100;
			if (c.syncOffsetMs > 600) {
				c.syncOffsetMs = -600;
			}
		}));

		int colW = 150;
		int gap = 8;
		int left = this.width / 2 - colW - gap / 2;
		int top = 40;
		int perCol = (rows.size() + 1) / 2;
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			int col = i / perCol;
			int r = i % perCol;
			int x = left + col * (colW + gap);
			int y = top + r * 24;
			Button btn = Button.builder(Component.literal(row.text().get()), b -> {
				row.onClick().run();
				for (int k = 0; k < rows.size(); k++) {
					buttons.get(k).setMessage(Component.literal(rows.get(k).text().get()));
				}
			}).pos(x, y).size(colW, 20).build();
			buttons.add(this.addRenderableWidget(btn));
		}

		this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
				.pos(this.width / 2 - 60, top + perCol * 24 + 14).size(120, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		this.extractBackground(g, mouseX, mouseY, delta);
		super.extractRenderState(g, mouseX, mouseY, delta);
		String title = "Lyrics World";
		g.text(this.font, title, (this.width - this.font.width(title)) / 2, 14, 0xFFFFFFFF, true);
		String st = Spotify.status;
		if (!st.isEmpty()) {
			g.text(this.font, st, (this.width - this.font.width(st)) / 2, 26, 0xFFB0B0B0, false);
		}
	}

	@Override
	public void onClose() {
		Config.save();
		super.onClose();
	}
}
