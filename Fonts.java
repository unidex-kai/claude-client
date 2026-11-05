package com.lyricsworld;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * Draws text in one of the bundled fonts. Index 0 is Minecraft's own font, so it never touches any
 * of the font code at all. The other indexes match Config.FONT_NAMES and the font-*.json files.
 *
 * All the "font by name" Minecraft calls live in this one file on purpose: if a future Minecraft
 * version renames something, this is the only place that needs fixing.
 */
public final class Fonts {
	/** Same order as Config.FONT_NAMES. The names match assets/lyricsworld/font/<name>.json */
	private static final String[] IDS = {"", "poppins", "pixel", "orbitron"};
	private static final Style[] STYLES = new Style[IDS.length];

	private Fonts() {
	}

	public static int clamp(int index) {
		return index < 0 || index >= IDS.length ? 0 : index;
	}

	private static Style style(int index) {
		Style s = STYLES[index];
		if (s == null) {
			s = Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("lyricsworld", IDS[index])));
			STYLES[index] = s;
		}
		return s;
	}

	private static Component make(String text, int index) {
		return Component.literal(text).withStyle(style(index));
	}

	public static int width(Font font, String text, int index) {
		index = clamp(index);
		return index == 0 ? font.width(text) : font.width(make(text, index));
	}

	public static void draw(GuiGraphicsExtractor g, Font font, String text, int x, int y, int argb, boolean shadow, int index) {
		index = clamp(index);
		if (index == 0) {
			g.text(font, text, x, y, argb, shadow);
		} else {
			g.text(font, make(text, index), x, y, argb, shadow);
		}
	}
}
