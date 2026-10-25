package com.lyricsworld;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Draws the lyrics. Each group of words is pinned to a spot in the world around you,
 * then projected onto the screen every frame, so it stays put when you turn or walk.
 */
public final class LyricsHud implements HudElement {
	private static final double BASE_BLOCKS = 0.17; // height of one text line, in blocks, at 100% size
	private static final int MAX_CHUNKS = 90;

	private static final class Chunk {
		String text;
		double x, y, z;
		long bornNs;
		float tilt;
		int index, count, lineIdx;
	}

	private final List<Chunk> chunks = new ArrayList<>();
	private final Random rnd = new Random();

	private String lastTrackId = "";
	private int lastLine = -2;
	private long lastFrameNs = 0;
	private double ex, ey, ez;
	private boolean haveEye = false;

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		Config c = Config.get();
		if (!c.enabled || mc.player == null) {
			return;
		}
		long nowMs = System.currentTimeMillis();
		long nowNs = System.nanoTime();

		Track t = LyricsWorldClient.demo ? Demo.track(nowMs) : Spotify.track;
		List<Lyrics.Line> lines;
		if (t == null) {
			chunks.clear();
			lastTrackId = "";
			return;
		}
		if (LyricsWorldClient.demo) {
			lines = Demo.LINES;
		} else {
			lines = t.id().equals(Lyrics.forId) ? Lyrics.lines : List.of();
		}

		if (!t.id().equals(lastTrackId)) {
			lastTrackId = t.id();
			lastLine = -2;
			chunks.clear();
		}

		smoothEye(mc, nowNs);

		if (c.showBar) {
			drawBar(g, mc.font, t, nowMs, c);
		}
		if (!t.playing()) {
			return;
		}

		// which lyric line are we on?
		long pos = t.positionMs(nowMs) + c.syncOffsetMs;
		int idx = -1;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).timeMs() <= pos) {
				idx = i;
			} else {
				break;
			}
		}
		if (idx != lastLine) {
			boolean jumped = lastLine >= 0 && Math.abs(idx - lastLine) > 2;
			if (jumped) {
				chunks.clear();
			}
			lastLine = idx;
			if (idx >= 0) {
				Lyrics.Line ln = lines.get(idx);
				if (!ln.text().isBlank()) {
					long next = idx + 1 < lines.size() ? lines.get(idx + 1).timeMs() : ln.timeMs() + 4000;
					spawn(mc, c, idx, ln.text(), Math.min(next - ln.timeMs(), 5000), nowNs);
				}
			}
		}

		drawChunks(g, mc, c, nowNs, idx);
	}

	// ------------------------------------------------------------ spawning

	private void smoothEye(Minecraft mc, long nowNs) {
		double tx = mc.player.getX();
		double ty = mc.player.getEyeY();
		double tz = mc.player.getZ();
		if (!haveEye || Math.abs(tx - ex) + Math.abs(ty - ey) + Math.abs(tz - ez) > 10) {
			ex = tx;
			ey = ty;
			ez = tz;
			haveEye = true;
		} else {
			double dt = lastFrameNs == 0 ? 0.016 : Math.min(0.1, (nowNs - lastFrameNs) / 1e9);
			double a = 1 - Math.exp(-dt * 40);
			ex += (tx - ex) * a;
			ey += (ty - ey) * a;
			ez += (tz - ez) * a;
		}
		lastFrameNs = nowNs;
	}

	private void spawn(Minecraft mc, Config c, int lineIdx, String text, long lineDurMs, long nowNs) {
		String[] words = text.trim().split("\\s+");
		int per = Math.max(1, c.wordsPerLine);
		List<String> parts = new ArrayList<>();
		for (int i = 0; i < words.length; i += per) {
			parts.add(String.join(" ", java.util.Arrays.copyOfRange(words, i, Math.min(words.length, i + per))));
		}
		int n = parts.size();
		long stagger = Math.max(80, Math.min(450, lineDurMs / (n + 1))) * 1_000_000L;
		double yaw = Math.toRadians(mc.player.getYRot());
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);

		for (int i = 0; i < n; i++) {
			Chunk ch = new Chunk();
			ch.text = parts.get(i);
			ch.index = i;
			ch.count = n;
			ch.lineIdx = lineIdx;
			ch.bornNs = nowNs + i * stagger;
			ch.tilt = (float) Math.toRadians(rnd.nextDouble() * 12 - 6);
			double dist = c.distance + rnd.nextDouble() * 0.8 - 0.4;
			if (c.placement == 1) {
				// a tidy column straight ahead
				ch.x = ex + fx * dist;
				ch.z = ez + fz * dist;
				ch.y = ey + (n - 1) * 0.25 - i * 0.5 + 0.2;
			} else {
				double yawOff = Math.toRadians(rnd.nextDouble() * 150 - 75);
				double elev = Math.toRadians(rnd.nextDouble() * 42 - 12);
				double a = yaw + yawOff;
				double dx = -Math.sin(a), dz = Math.cos(a);
				ch.x = ex + dx * Math.cos(elev) * dist;
				ch.z = ez + dz * Math.cos(elev) * dist;
				ch.y = ey + Math.sin(elev) * dist;
			}
			chunks.add(ch);
		}
		while (chunks.size() > MAX_CHUNKS) {
			chunks.remove(0);
		}
	}

	// ------------------------------------------------------------ drawing

	private void drawChunks(GuiGraphicsExtractor g, Minecraft mc, Config c, long nowNs, int currentLine) {
		Font font = mc.font;
		int W = g.guiWidth();
		int H = g.guiHeight();
		double yaw = Math.toRadians(mc.player.getYRot());
		double pitch = Math.toRadians(mc.player.getXRot());

		// camera axes (Minecraft: yaw 0 looks toward +Z, negative pitch looks up)
		double fx = -Math.sin(yaw) * Math.cos(pitch);
		double fy = -Math.sin(pitch);
		double fz = Math.cos(yaw) * Math.cos(pitch);
		double rx = -Math.cos(yaw), ry = 0, rz = -Math.sin(yaw);
		double ux = ry * fz - rz * fy;
		double uy = rz * fx - rx * fz;
		double uz = rx * fy - ry * fx;

		double fov = mc.options.fov().get();
		double tanHalf = Math.tan(Math.toRadians(fov) / 2.0);
		double aspect = (double) W / H;
		float sizeMul = c.sizePercent / 100f;
		float life = c.staySeconds;

		for (int i = chunks.size() - 1; i >= 0; i--) {
			Chunk ch = chunks.get(i);
			double age = (nowNs - ch.bornNs) / 1e9;
			if (age > life) {
				chunks.remove(i);
				continue;
			}
			if (age < 0) {
				continue;
			}

			// animation
			double p = Math.min(1.0, age / 0.28);
			double popScale = 1.0;
			double rise = 0;
			float wobble = 0;
			if (c.intensity == 1) {
				popScale = 0.6 + 0.4 * easeOutCubic(p);
			} else if (c.intensity >= 2) {
				double q = p - 1;
				popScale = 1 + 2.70158 * q * q * q + 1.70158 * q * q; // overshoots then settles
				popScale = Math.max(0.05, popScale);
				rise = (1 - easeOutCubic(p)) * -0.35;
				wobble = (float) (Math.sin(age * 6 + ch.index) * 0.05);
			}
			double fadeIn = Math.min(1.0, age / 0.18);
			double fadeOut = Math.min(1.0, (life - age) / 0.6);
			double alpha = Math.min(fadeIn, fadeOut);
			boolean active = ch.lineIdx == currentLine;
			if (!active) {
				alpha *= 0.65;
			}
			int a8 = (int) Math.round(alpha * 255);
			if (a8 < 8) {
				continue;
			}

			// project the world position onto the screen
			double rxw = ch.x - ex, ryw = ch.y + rise - ey, rzw = ch.z - ez;
			double cz = rxw * fx + ryw * fy + rzw * fz;
			if (cz < 0.3) {
				continue;
			}
			double cx = rxw * rx + ryw * ry + rzw * rz;
			double cy = rxw * ux + ryw * uy + rzw * uz;
			double sx = W / 2.0 + (cx / (cz * tanHalf * aspect)) * (W / 2.0);
			double sy = H / 2.0 - (cy / (cz * tanHalf)) * (H / 2.0);
			double px = BASE_BLOCKS * (H / 2.0) / (cz * tanHalf);
			float scale = (float) (px / font.lineHeight * sizeMul * popScale * (active ? 1.0 : 0.85));
			if (scale < 0.15f) {
				continue;
			}
			int tw = font.width(ch.text);
			double halfW = tw * scale / 2.0 + 20;
			if (sx < -halfW || sx > W + halfW || sy < -60 || sy > H + 60) {
				continue;
			}

			float frac = ch.count <= 1 ? 0f : (float) ch.index / (ch.count - 1);
			int base = c.gradient ? mix(Config.COLOURS[c.colour], Config.COLOURS[c.colour2], frac) : Config.COLOURS[c.colour];
			if (!active) {
				base = mix(base, 0x808080, 0.45f);
			}
			int argb = (a8 << 24) | base;

			g.pose().pushMatrix();
			g.pose().translate((float) sx, (float) sy);
			g.pose().rotate(ch.tilt + wobble);
			g.pose().scale(scale, scale);
			float x0 = -tw / 2f;
			float y0 = -font.lineHeight / 2f;
			if (c.glow) {
				int glow = ((a8 / 3) << 24) | base;
				g.text(font, ch.text, (int) x0 - 1, (int) y0, glow, false);
				g.text(font, ch.text, (int) x0 + 1, (int) y0, glow, false);
				g.text(font, ch.text, (int) x0, (int) y0 - 1, glow, false);
				g.text(font, ch.text, (int) x0, (int) y0 + 1, glow, false);
			}
			g.text(font, ch.text, (int) x0, (int) y0, argb, false);
			g.pose().popMatrix();
		}
	}

	private void drawBar(GuiGraphicsExtractor g, Font font, Track t, long nowMs, Config c) {
		int W = g.guiWidth();
		long pos = t.positionMs(nowMs);
		double prog = t.durationMs() <= 0 ? 0 : (double) pos / t.durationMs();
		g.fill(0, 0, (int) (W * prog), 1, 0xFF000000 | Config.COLOURS[c.colour]);
		String label = "♪ " + t.title() + (t.artist().isEmpty() ? "" : "  ·  " + t.artist())
				+ "   " + clock(pos) + " / " + clock(t.durationMs());
		int tw = font.width(label);
		g.text(font, label, (W - tw) / 2, 4, 0xCCFFFFFF, true);
		if (!LyricsWorldClient.demo && Lyrics.forId.equals(t.id()) && Lyrics.lines.isEmpty()) {
			String n = Lyrics.note;
			if (!n.isEmpty()) {
				g.text(font, n, (W - font.width(n)) / 2, 16, 0x99FFFFFF, true);
			}
		}
	}

	// ------------------------------------------------------------ helpers

	private static String clock(long ms) {
		long s = ms / 1000;
		return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
	}

	private static double easeOutCubic(double x) {
		double q = 1 - x;
		return 1 - q * q * q;
	}

	private static int mix(int a, int b, float t) {
		int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int gr = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
		return (r << 16) | (gr << 8) | bl;
	}
}
