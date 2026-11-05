package com.lyricsworld;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Press K in game to open this. Tabs along the top, one row per setting.
 * Click a row to change it. On choice rows, click the left half to go back and the right half to go forward.
 *
 * Everything is drawn by hand (rectangles and text) so it can animate. All the numbers below are in
 * "panel space": (0, 0) is the top-left corner of the panel.
 */
public final class SettingsScreen extends Screen {
	private static final int PW = 340;
	private static final int PH = 268;
	private static final int PAD = 14;
	private static final int CW = PW - PAD * 2;
	private static final int TAB_Y = 40;
	private static final int TAB_H = 20;
	private static final int CONTENT_Y = 68;
	private static final int ROW_H = 21;
	private static final int PREVIEW_H = 46;
	private static final int ROW_GAP = 3;
	private static final int CONTROL_W = 176;
	private static final int REGION_R = PAD + CW - 8;
	private static final int REGION_L = REGION_R - CONTROL_W;
	private static final int SWATCH = 12;
	private static final int SWATCH_GAP = 3;
	private static final int DONE_W = 70;
	private static final int DONE_H = 18;
	private static final int DONE_X = PAD + CW - DONE_W;
	private static final int DONE_Y = PH - 26;
	private static final String[] TAB_NAMES = {"General", "Look", "Colours", "Music"};
	private static final float TAB_W = CW / (float) TAB_NAMES.length;

	private enum Kind {
		TOGGLE, CHOICE, ACTION, SWATCHES, PREVIEW
	}

	private static final class Row {
		final Kind kind;
		final String label;
		int y;
		int h = ROW_H;
		BooleanSupplier on = () -> false;
		Supplier<String> value = () -> "";
		Runnable prev = () -> {
		};
		Runnable next = () -> {
		};
		BooleanSupplier canPrev = () -> true;
		BooleanSupplier canNext = () -> true;
		BooleanSupplier dimmed = () -> false;
		IntSupplier swatchGet = () -> 0;
		IntConsumer swatchSet = v -> {
		};
		float hover;
		float knob;
		float flash;
		final float[] swatchGrow = new float[Config.COLOURS.length];

		Row(Kind kind, String label) {
			this.kind = kind;
			this.label = label;
		}
	}

	private final List<List<Row>> tabs = new ArrayList<>();
	private final float[] tabHover = new float[TAB_NAMES.length];
	private int tab = 0;
	private float pillX = PAD;
	private float doneHover = 0;
	private final long openNs = System.nanoTime();
	private long tabNs = openNs;
	private long lastNs = 0;

	// where the panel was drawn last frame, so clicks can be turned back into panel space
	private float panelX = 0;
	private float panelY = 0;
	private float scale = 1f;
	private float mouseLX = -1000;
	private float mouseLY = -1000;

	public SettingsScreen() {
		super(Component.literal("Lyrics World"));
	}

	// ------------------------------------------------------------ building the rows

	private static Row toggle(String label, BooleanSupplier get, Runnable flip) {
		Row r = new Row(Kind.TOGGLE, label);
		r.on = get;
		r.next = flip;
		return r;
	}

	private static Row choice(String label, Supplier<String> value, Runnable prev, Runnable next) {
		Row r = new Row(Kind.CHOICE, label);
		r.value = value;
		r.prev = prev;
		r.next = next;
		return r;
	}

	private static Row action(String label, Supplier<String> value, Runnable run) {
		Row r = new Row(Kind.ACTION, label);
		r.value = value;
		r.next = run;
		return r;
	}

	private static int wrap(int v, int n) {
		return ((v % n) + n) % n;
	}

	/** A row that loops through a list of names. */
	private static Row cycle(String label, String[] names, IntSupplier get, IntConsumer set) {
		return choice(label, () -> names[wrap(get.getAsInt(), names.length)],
				() -> set.accept(wrap(get.getAsInt() - 1, names.length)),
				() -> set.accept(wrap(get.getAsInt() + 1, names.length)));
	}

	/** A whole-number row that stops at both ends. */
	private static Row stepInt(String label, Supplier<String> text, IntSupplier get, IntConsumer set, int step, int min, int max) {
		Row r = choice(label, text,
				() -> set.accept(Math.max(min, get.getAsInt() - step)),
				() -> set.accept(Math.min(max, get.getAsInt() + step)));
		r.canPrev = () -> get.getAsInt() > min;
		r.canNext = () -> get.getAsInt() < max;
		return r;
	}

	/** A decimal row that stops at both ends. */
	private static Row stepFloat(String label, Supplier<String> text, DoubleSupplier get, DoubleConsumer set, double step, double min, double max) {
		Row r = choice(label, text,
				() -> set.accept(Math.max(min, get.getAsDouble() - step)),
				() -> set.accept(Math.min(max, get.getAsDouble() + step)));
		r.canPrev = () -> get.getAsDouble() > min + 0.001;
		r.canNext = () -> get.getAsDouble() < max - 0.001;
		return r;
	}

	private static String f1(double v) {
		return String.format(Locale.ROOT, "%.1f", v);
	}

	private void buildTabs() {
		Config c = Config.get();
		tabs.clear();

		List<Row> general = new ArrayList<>();
		general.add(toggle("Lyrics", () -> c.enabled, () -> c.enabled = !c.enabled));
		general.add(toggle("Now-playing bar", () -> c.showBar, () -> c.showBar = !c.showBar));
		general.add(toggle("Menu animations", () -> c.menuAnimations, () -> c.menuAnimations = !c.menuAnimations));
		general.add(cycle("Menu font", Config.FONT_NAMES, () -> c.menuFont, v -> c.menuFont = v));
		general.add(cycle("Lyrics font", Config.FONT_NAMES, () -> c.lyricsFont, v -> c.lyricsFont = v));
		tabs.add(general);

		List<Row> look = new ArrayList<>();
		look.add(cycle("Intensity", Config.INTENSITY_NAMES, () -> c.intensity, v -> c.intensity = v));
		look.add(cycle("Placement", Config.PLACEMENT_NAMES, () -> c.placement, v -> c.placement = v));
		look.add(stepInt("Words per line", () -> String.valueOf(c.wordsPerLine), () -> c.wordsPerLine, v -> c.wordsPerLine = v, 1, 1, 8));
		look.add(stepFloat("Lines stay for", () -> f1(c.staySeconds) + " s", () -> c.staySeconds, v -> c.staySeconds = (float) v, 0.5, 1.5, 8));
		look.add(stepFloat("Distance", () -> f1(c.distance) + " blocks", () -> c.distance, v -> c.distance = (float) v, 0.5, 2, 8));
		look.add(stepInt("Size", () -> c.sizePercent + "%", () -> c.sizePercent, v -> c.sizePercent = v, 10, 50, 200));
		look.add(toggle("Glow", () -> c.glow, () -> c.glow = !c.glow));
		tabs.add(look);

		List<Row> colours = new ArrayList<>();
		colours.add(choice("Colour mode", () -> c.gradient ? "Gradient" : "Solid",
				() -> c.gradient = !c.gradient, () -> c.gradient = !c.gradient));
		Row first = new Row(Kind.SWATCHES, "Colour");
		first.swatchGet = () -> c.colour;
		first.swatchSet = v -> c.colour = v;
		colours.add(first);
		Row second = new Row(Kind.SWATCHES, "Second colour");
		second.swatchGet = () -> c.colour2;
		second.swatchSet = v -> c.colour2 = v;
		second.dimmed = () -> !c.gradient;
		colours.add(second);
		Row preview = new Row(Kind.PREVIEW, "Preview");
		preview.h = PREVIEW_H;
		colours.add(preview);
		tabs.add(colours);

		List<Row> music = new ArrayList<>();
		music.add(cycle("Source", Config.SOURCE_NAMES, () -> c.source, v -> {
			c.source = v;
			Spotify.track = null;
			Spotify.status = "";
			WindowsMedia.track = null;
			WindowsMedia.status = "";
			WindowsMedia.apply();
		}));
		music.add(action("Spotify", () -> Spotify.connected() ? "Connected - log out" : "Set up", () -> {
			if (Spotify.connected()) {
				Spotify.logout();
			} else {
				this.minecraft.gui.setScreen(new ConnectScreen(this));
			}
		}));
		music.add(toggle("Demo song", () -> LyricsWorldClient.demo, () -> LyricsWorldClient.setDemo(!LyricsWorldClient.demo)));
		music.add(stepInt("Sync offset", () -> (c.syncOffsetMs > 0 ? "+" : "") + c.syncOffsetMs + " ms", () -> c.syncOffsetMs, v -> c.syncOffsetMs = v, 50, -1000, 1000));
		tabs.add(music);

		for (List<Row> rows : tabs) {
			int y = CONTENT_Y;
			for (Row r : rows) {
				r.y = y;
				y += r.h + ROW_GAP;
			}
		}
	}

	@Override
	protected void init() {
		buildTabs();
		pillX = PAD + tab * TAB_W;
	}

	// ------------------------------------------------------------ small helpers

	static float clamp01(float v) {
		return v < 0f ? 0f : Math.min(v, 1f);
	}

	private static float easeOutCubic(float x) {
		float q = 1f - x;
		return 1f - q * q * q;
	}

	private static float easeOutBack(float x) {
		float c1 = 1.70158f;
		float c3 = c1 + 1f;
		float q = x - 1f;
		return 1f + c3 * q * q * q + c1 * q * q;
	}

	/** Moves a value part of the way to its target each frame; looks the same at any frame rate. */
	private static float approach(float cur, float target, float dt, float speed) {
		return cur + (target - cur) * (1f - (float) Math.exp(-dt * speed));
	}

	static int mix(int a, int b, float t) {
		t = clamp01(t);
		int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
		return (r << 16) | (g << 8) | bl;
	}

	static int argb(int rgb, float alpha) {
		return ((int) (clamp01(alpha) * 255f) << 24) | (rgb & 0xFFFFFF);
	}

	/** A rectangle with its corners cut off. Rows never overlap, so see-through colours stay even. */
	static void roundRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
		if (((color >>> 24) & 0xFF) == 0 || w <= 0 || h <= 0) {
			return;
		}
		r = Math.min(r, Math.min(w, h) / 2);
		for (int i = 0; i < r; i++) {
			int inset = r - i;
			g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
			g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
		}
		g.fill(x, y + r, x + w, y + h - r, color);
	}

	private void txt(GuiGraphicsExtractor g, String s, int x, int y, int rgb, float alpha, boolean shadow, int fontIndex) {
		// Minecraft draws text with a very low alpha as fully solid, so anything that faint is skipped
		if (alpha < 0.05f || s.isEmpty()) {
			return;
		}
		Fonts.draw(g, this.font, s, x, y, argb(rgb, alpha), shadow, fontIndex);
	}

	private int tw(String s, int fontIndex) {
		return Fonts.width(this.font, s, fontIndex);
	}

	private String fit(String s, int maxW, int fontIndex) {
		if (tw(s, fontIndex) <= maxW) {
			return s;
		}
		String cut = s;
		while (cut.length() > 1 && tw(cut + "..", fontIndex) > maxW) {
			cut = cut.substring(0, cut.length() - 1);
		}
		return cut + "..";
	}

	private static boolean inside(float x, float y, float rx, float ry, float rw, float rh) {
		return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
	}

	private static int swatchStartX() {
		int n = Config.COLOURS.length;
		return PAD + CW - 10 - (n * SWATCH + (n - 1) * SWATCH_GAP);
	}

	/** Which colour square is under this x position, or -1. */
	private static int swatchAt(float x) {
		int n = Config.COLOURS.length;
		int start = swatchStartX();
		float cell = SWATCH + SWATCH_GAP;
		if (x < start - SWATCH_GAP / 2f || x >= start + n * cell - SWATCH_GAP / 2f) {
			return -1;
		}
		return Math.min(n - 1, Math.max(0, (int) ((x - start + SWATCH_GAP / 2f) / cell)));
	}

	// ------------------------------------------------------------ drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		this.extractBackground(g, mouseX, mouseY, delta);
		super.extractRenderState(g, mouseX, mouseY, delta);

		Config c = Config.get();
		boolean anim = c.menuAnimations;
		long now = System.nanoTime();
		float dt = lastNs == 0 ? 0.016f : Math.min(0.05f, (now - lastNs) / 1e9f);
		lastNs = now;
		float t = (now - openNs) / 1e9f;
		int mf = Fonts.clamp(c.menuFont);
		int accent = Config.COLOURS[Math.floorMod(c.colour, Config.COLOURS.length)];
		int accent2 = Config.COLOURS[Math.floorMod(c.colour2, Config.COLOURS.length)];

		// opening animation: grows from slightly smaller, slides up a little and fades in
		float open = anim ? clamp01(t / 0.35f) : 1f;
		float fade = anim ? clamp01(t / 0.22f) : 1f;
		float fit = Math.min(1f, Math.min((this.height - 12f) / PH, (this.width - 12f) / PW));
		float s = fit * (anim ? 0.88f + 0.12f * easeOutBack(open) : 1f);
		scale = s;
		panelX = this.width / 2f - PW * s / 2f;
		panelY = this.height / 2f - PH * s / 2f + (anim ? (1f - easeOutCubic(open)) * 16f : 0f);
		mouseLX = (mouseX - panelX) / s;
		mouseLY = (mouseY - panelY) / s;

		drawParticles(g, t, c, fade);

		g.pose().pushMatrix();
		g.pose().translate(panelX, panelY);
		g.pose().scale(s, s);

		// panel: soft shadow, body, thin top glow
		for (int i = 1; i <= 6; i++) {
			roundRect(g, -i, -i, PW + 2 * i, PH + 2 * i, 3, argb(0x000000, (0.075f - i * 0.011f) * fade));
		}
		roundRect(g, 0, 0, PW, PH, 3, argb(0x11111A, 0.95f * fade));
		roundRect(g, 0, 0, PW, 1, 0, argb(accent, 0.55f * fade));

		drawHeader(g, t, anim, mf, accent, accent2, fade);
		drawTabs(g, dt, anim, mf, accent, fade);

		List<Row> rows = tabs.get(tab);
		float since = (now - tabNs) / 1e9f;
		for (int i = 0; i < rows.size(); i++) {
			// each row slides in a moment after the one above it
			float p = anim ? easeOutCubic(clamp01((since - i * 0.04f) / 0.25f)) : 1f;
			if (p > 0.01f) {
				drawRow(g, rows.get(i), p * fade, p, t, dt, anim, mf, c, accent, accent2);
			}
		}

		drawFooter(g, dt, anim, mf, accent, fade);

		g.pose().popMatrix();
	}

	/** Little notes and dots drifting up behind the panel. */
	private void drawParticles(GuiGraphicsExtractor g, float t, Config c, float fade) {
		if (!c.menuAnimations) {
			return;
		}
		int w = this.width;
		int h = this.height;
		for (int i = 0; i < 16; i++) {
			float speed = 10f + (i % 5) * 5f;
			float y = h + 12f - ((t * speed + i * 37f) % (h + 24f));
			float x = ((i * 0.6180339f) % 1f) * w + (float) Math.sin(t * 0.8f + i) * 10f;
			float a = Math.min(1f, Math.min(y / 40f, (h - y) / 40f)) * 0.30f * fade;
			if (a < 0.05f) {
				continue;
			}
			int rgb = Config.COLOURS[Math.floorMod(i % 2 == 0 ? c.colour : c.colour2, Config.COLOURS.length)];
			if (i % 3 == 0) {
				g.text(this.font, "♪", (int) x, (int) y, argb(rgb, a), false);
			} else {
				g.fill((int) x, (int) y, (int) x + 2, (int) y + 2, argb(rgb, a));
			}
		}
	}

	private void drawHeader(GuiGraphicsExtractor g, float t, boolean anim, int mf, int accent, int accent2, float fade) {
		// title: each letter slides between your two colours, like a light passing over it
		String title = "Lyrics World";
		g.pose().pushMatrix();
		g.pose().translate(PAD, 9f);
		g.pose().scale(1.3f, 1.3f);
		int x = 0;
		for (int i = 0; i < title.length(); i++) {
			String ch = title.substring(i, i + 1);
			float f = anim ? (float) (Math.sin(t * 2.2f - i * 0.45f) * 0.5 + 0.5) : i / (float) (title.length() - 1);
			txt(g, ch, x, 0, mix(accent, accent2, f), fade, true, mf);
			x += tw(ch, mf);
		}
		g.pose().popMatrix();

		String st = LyricsWorldClient.statusText();
		if (!st.isEmpty()) {
			String shown = fit(st, 150, 0);
			txt(g, shown, PW - PAD - tw(shown, 0), 15, 0x9A9AAE, fade, false, 0);
		}

		// accent line with a bright spot that sweeps across
		int step = 3;
		float sweep = anim ? (t * 0.5f) % 1.6f - 0.3f : -1f;
		for (int px = 0; px < CW; px += step) {
			float f = px / (float) CW;
			int col = mix(accent, accent2, f);
			col = mix(col, 0xFFFFFF, Math.max(0f, 1f - Math.abs(f - sweep) / 0.15f) * 0.7f);
			g.fill(PAD + px, 33, PAD + Math.min(px + step, CW), 35, argb(col, 0.95f * fade));
		}
	}

	private void drawTabs(GuiGraphicsExtractor g, float dt, boolean anim, int mf, int accent, float fade) {
		float target = PAD + tab * TAB_W;
		pillX = anim ? approach(pillX, target, dt, 16f) : target;
		int px = Math.round(pillX);
		int pw = Math.round(TAB_W);
		roundRect(g, px + 1, TAB_Y, pw - 2, TAB_H, 3, argb(accent, 0.20f * fade));
		g.fill(px + 8, TAB_Y + TAB_H - 2, px + pw - 8, TAB_Y + TAB_H, argb(accent, 0.95f * fade));

		for (int i = 0; i < TAB_NAMES.length; i++) {
			boolean over = inside(mouseLX, mouseLY, PAD + i * TAB_W, TAB_Y, TAB_W, TAB_H);
			float want = over ? 1f : 0f;
			tabHover[i] = anim ? approach(tabHover[i], want, dt, 14f) : want;
			float lit = Math.max(i == tab ? 1f : 0f, tabHover[i] * 0.75f);
			String name = TAB_NAMES[i];
			int x = Math.round(PAD + i * TAB_W + (TAB_W - tw(name, mf)) / 2f);
			txt(g, name, x, TAB_Y + (TAB_H - 9) / 2 - 1, mix(0x8A8AA0, 0xFFFFFF, lit), fade, false, mf);
		}
	}

	private void drawRow(GuiGraphicsExtractor g, Row r, float a, float slide, float t, float dt, boolean anim, int mf, Config c, int accent, int accent2) {
		boolean over = inside(mouseLX, mouseLY, PAD, r.y, CW, r.h);
		float want = over ? 1f : 0f;
		r.hover = anim ? approach(r.hover, want, dt, 14f) : want;
		r.flash = Math.max(0f, r.flash - dt * 4f);
		if (r.dimmed.getAsBoolean()) {
			a *= 0.45f;
		}

		int x0 = PAD + Math.round((1f - slide) * 18f);
		int right = x0 + CW - 10;
		int textY = r.y + (r.h - 9) / 2;

		roundRect(g, x0, r.y, CW, r.h, 2, argb(mix(0x1B1B27, 0x2C2C42, r.hover), 0.92f * a));
		g.fill(x0, r.y + 3, x0 + 2, r.y + r.h - 3, argb(accent, r.hover * a));
		if (r.flash > 0f) {
			roundRect(g, x0, r.y, CW, r.h, 2, argb(0xFFFFFF, r.flash * 0.18f * a));
		}

		if (r.kind == Kind.PREVIEW) {
			txt(g, r.label, x0 + 10, r.y + 6, mix(0xB4B4C8, 0xFFFFFF, r.hover), a, false, mf);
			drawPreview(g, r, x0, t, anim, c, a);
			return;
		}
		txt(g, r.label, x0 + 10, textY, mix(0xB4B4C8, 0xFFFFFF, r.hover), a, false, mf);

		switch (r.kind) {
			case TOGGLE -> {
				boolean on = r.on.getAsBoolean();
				float k = on ? 1f : 0f;
				r.knob = anim ? approach(r.knob, k, dt, 18f) : k;
				int trackW = 30;
				int trackH = 12;
				int tx = right - trackW;
				int ty = r.y + (r.h - trackH) / 2;
				roundRect(g, tx, ty, trackW, trackH, 2, argb(mix(0x3A3A4C, accent, r.knob), a));
				int knobX = Math.round(tx + 2 + r.knob * (trackW - trackH));
				roundRect(g, knobX, ty + 2, trackH - 4, trackH - 4, 1, argb(0xFFFFFF, a));
				String word = on ? "On" : "Off";
				txt(g, word, tx - 7 - tw(word, mf), textY, on ? accent : 0x8A8AA0, a, false, mf);
			}
			case CHOICE -> {
				boolean prevSide = over && mouseLX >= REGION_L && mouseLX < REGION_L + CONTROL_W / 2f;
				boolean nextSide = over && !prevSide;
				int prevCol = !r.canPrev.getAsBoolean() ? 0x4A4A5C : mix(0x8A8AA0, accent, prevSide ? 1f : 0f);
				int nextCol = !r.canNext.getAsBoolean() ? 0x4A4A5C : mix(0x8A8AA0, accent, nextSide ? 1f : 0f);
				int pad = prevSide ? 2 : 4;
				int padN = nextSide ? 2 : 4;
				int leftX = x0 + (REGION_L - PAD);
				int rightX = x0 + (REGION_R - PAD);
				txt(g, "<", leftX + pad, textY, prevCol, a, false, 0);
				txt(g, ">", rightX - padN - tw(">", 0), textY, nextCol, a, false, 0);
				String v = fit(r.value.get(), CONTROL_W - 36, mf);
				txt(g, v, leftX + (CONTROL_W - tw(v, mf)) / 2, textY, 0xF0F0FF, a, false, mf);
			}
			case ACTION -> {
				String v = fit(r.value.get(), CONTROL_W - 16, mf);
				int bw = tw(v, mf) + 14;
				int bx = right - bw;
				roundRect(g, bx, r.y + 3, bw, r.h - 6, 2, argb(accent, 0.16f + 0.22f * r.hover));
				txt(g, v, bx + 7, textY, mix(accent, 0xFFFFFF, r.hover), a, false, mf);
			}
			case SWATCHES -> {
				int n = Config.COLOURS.length;
				int start = x0 + (swatchStartX() - PAD);
				int selected = r.swatchGet.getAsInt();
				int under = over ? swatchAt(mouseLX) : -1;
				for (int k = 0; k < n; k++) {
					float grow = (k == under ? 1.5f : 0f) + (k == selected ? 1f : 0f);
					r.swatchGrow[k] = anim ? approach(r.swatchGrow[k], grow, dt, 18f) : grow;
					int gr = Math.round(r.swatchGrow[k]);
					int sx = start + k * (SWATCH + SWATCH_GAP);
					int sy = r.y + (r.h - SWATCH) / 2;
					if (k == selected) {
						roundRect(g, sx - gr - 1, sy - gr - 1, SWATCH + 2 * gr + 2, SWATCH + 2 * gr + 2, 2, argb(0xFFFFFF, a));
					}
					roundRect(g, sx - gr, sy - gr, SWATCH + 2 * gr, SWATCH + 2 * gr, 1, argb(Config.COLOURS[k], a));
				}
			}
			default -> {
			}
		}
	}

	/** Shows the chosen colours and font on a sample line. */
	private void drawPreview(GuiGraphicsExtractor g, Row r, int x0, float t, boolean anim, Config c, float a) {
		int lf = Fonts.clamp(c.lyricsFont);
		String sample = "Feel the music";
		int n = sample.length();
		int total = 0;
		int[] w = new int[n];
		for (int i = 0; i < n; i++) {
			w[i] = tw(sample.substring(i, i + 1), lf);
			total += w[i];
		}
		float pulse = anim ? 1.55f + 0.06f * (float) Math.sin(t * 3f) : 1.55f;
		int c1 = Config.COLOURS[Math.floorMod(c.colour, Config.COLOURS.length)];
		int c2 = Config.COLOURS[Math.floorMod(c.colour2, Config.COLOURS.length)];
		g.pose().pushMatrix();
		g.pose().translate(x0 + CW / 2f + 24f, r.y + r.h / 2f);
		g.pose().scale(pulse, pulse);
		int x = -total / 2;
		for (int i = 0; i < n; i++) {
			float f = n <= 1 ? 0f : i / (float) (n - 1);
			int rgb = c.gradient ? mix(c1, c2, f) : c1;
			String ch = sample.substring(i, i + 1);
			txt(g, ch, x - 1, -4, rgb, a * 0.33f, false, lf);
			txt(g, ch, x + 1, -4, rgb, a * 0.33f, false, lf);
			txt(g, ch, x, -4, rgb, a, false, lf);
			x += w[i];
		}
		g.pose().popMatrix();
	}

	private void drawFooter(GuiGraphicsExtractor g, float dt, boolean anim, int mf, int accent, float fade) {
		g.fill(PAD, PH - 32, PAD + CW, PH - 31, argb(0xFFFFFF, 0.07f * fade));
		txt(g, "Click a row to change it", PAD, DONE_Y + 5, 0x70708A, fade, false, 0);

		boolean over = inside(mouseLX, mouseLY, DONE_X, DONE_Y, DONE_W, DONE_H);
		float want = over ? 1f : 0f;
		doneHover = anim ? approach(doneHover, want, dt, 14f) : want;
		roundRect(g, DONE_X, DONE_Y, DONE_W, DONE_H, 2, argb(accent, (0.20f + 0.55f * doneHover) * fade));
		String label = "Done";
		txt(g, label, DONE_X + (DONE_W - tw(label, mf)) / 2, DONE_Y + (DONE_H - 9) / 2, mix(0xE8E8F8, 0xFFFFFF, doneHover), fade, false, mf);
	}

	// ------------------------------------------------------------ clicking

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && !tabs.isEmpty()) {
			float lx = (float) ((event.x() - panelX) / scale);
			float ly = (float) ((event.y() - panelY) / scale);
			if (handleClick(lx, ly)) {
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private boolean handleClick(float lx, float ly) {
		if (inside(lx, ly, PAD, TAB_Y, CW, TAB_H)) {
			int i = Math.min(TAB_NAMES.length - 1, (int) ((lx - PAD) / TAB_W));
			if (i != tab) {
				tab = i;
				tabNs = System.nanoTime();
			}
			return true;
		}
		if (inside(lx, ly, DONE_X, DONE_Y, DONE_W, DONE_H)) {
			this.onClose();
			return true;
		}
		for (Row r : tabs.get(tab)) {
			if (inside(lx, ly, PAD, r.y, CW, r.h)) {
				clickRow(r, lx);
				return true;
			}
		}
		return false;
	}

	private void clickRow(Row r, float lx) {
		r.flash = 1f;
		switch (r.kind) {
			case SWATCHES -> {
				int k = swatchAt(lx);
				if (k >= 0) {
					r.swatchSet.accept(k);
				}
			}
			case CHOICE -> {
				if (lx >= REGION_L && lx < REGION_L + CONTROL_W / 2f) {
					r.prev.run();
				} else {
					r.next.run();
				}
			}
			case PREVIEW -> {
			}
			default -> r.next.run();
		}
	}

	@Override
	public void onClose() {
		Config.save();
		super.onClose();
	}
}
