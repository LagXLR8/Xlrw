package com.xlrw.gui;

/**
 * Colour themes for the deck (Plus feature). Each theme is built from a few palette colours; the rest
 * (hover, dim text, text on the accent...) is derived so every theme stays readable on the dark panel.
 */
public final class UiTheme {

    public final String name;
    /** swatch colours shown in the picker */
    public final int[] swatch;
    public final int bg, bg2, accent, outline, accentDeep, widget, widgetHi, textDim, cream, ink;

    private UiTheme(String name, int accent, int outline, int widgetBase, int cream, int[] swatch) {
        this(name, accent, outline, widgetBase, cream, 0xFF141416, swatch);
    }

    private UiTheme(String name, int accent, int outline, int widgetBase, int cream, int base, int[] swatch) {
        this.name = name;
        this.swatch = swatch;
        this.accent = accent | 0xFF000000;
        this.cream = cream | 0xFF000000;
        int panel = mix(base, widgetBase, 0.10f);
        this.bg = (panel & 0xFFFFFF) | 0xF2000000;
        this.bg2 = mix(panel, widgetBase, 0.18f) | 0xFF000000;
        this.widget = mix(panel, widgetBase, 0.42f) | 0xFF000000;
        // outline / hover must stand out from the widget: lift dark palette colours
        int o = outline | 0xFF000000;
        if (lum(o) < 0.22f) o = mix(o, this.cream, 0.35f);
        this.outline = o;
        this.widgetHi = mix(this.widget, o, 0.55f) | 0xFF000000;
        this.accentDeep = mix(panel, this.accent, 0.28f) | 0xFF000000;
        this.textDim = mix(this.cream, this.widget, 0.42f) | 0xFF000000;
        this.ink = lum(this.accent) > 0.45f ? 0xFF141416 : 0xFFF7F2EA;
    }

    public static final UiTheme[] ALL = {
            fixedXlrw(),
            new UiTheme("MỸ CỔ ĐIỂN", 0xDFA05D, 0xAC5045, 0x658761, 0xF2E6D0, new int[]{0xDFA05D, 0xAC5045, 0x658761}),
            new UiTheme("PHÁP CỔ ĐIỂN", 0xD3B8A6, 0x3D5D52, 0x3D5D52, 0xEDE3D9, 0xFF16130F, new int[]{0x3D5D52, 0x352D27, 0xD3B8A6}),
            new UiTheme("TÙNG BÁCH", 0x21A675, 0xEC5736, 0x2A313D, 0xE6F2EC, new int[]{0x21A675, 0xEC5736, 0x2A313D}),
            new UiTheme("ĐIỆN QUANG", 0x55D5FC, 0x5F8B98, 0x2A3238, 0xD6ECF0, 0xFF111416, new int[]{0x55D5FC, 0x1F2326, 0xD6ECF0}),
            new UiTheme("CHANH NEON", 0xB2E50F, 0x1CBBD0, 0x0F355F, 0xEAF6D2, new int[]{0xB2E50F, 0x0F355F, 0x1CBBD0}),
            new UiTheme("BƠ", 0x7FA921, 0x6E6866, 0x433F3E, 0xE2E1E6, new int[]{0x7FA921, 0x433F3E, 0xE2E1E6}),
            new UiTheme("HỒNG NEON", 0xFB1378, 0xFCA019, 0xB70095, 0xFDE6EF, new int[]{0xFB1378, 0xB70095, 0xFCA019}),
            new UiTheme("NHẬT CHỮA LÀNH", 0xEAC486, 0xDDE3F2, 0x8A93A8, 0xFFF2B9, new int[]{0xDDE3F2, 0xFFF2B9, 0xEAC486}),
            new UiTheme("KHÔNG LINH", 0xD0391B, 0x5E8476, 0x324E44, 0xEFE8CB, new int[]{0xEFE8CB, 0x324E44, 0xD0391B}),
            new UiTheme("XE ĐỎ", 0xE0281C, 0x69929A, 0x69929A, 0xFAF0D6, new int[]{0xFAF0D6, 0x69929A, 0xB9130A}),
            new UiTheme("HÀO HOA", 0xFFDB89, 0x6A6A6E, 0x2C2C2E, 0xF5EBD3, 0xFF070707, new int[]{0xFFDB89, 0x030303, 0x2C2C2E}),
            new UiTheme("NGÀY MƯA", 0xAC9B78, 0x68533A, 0x424846, 0xE8DFCB, new int[]{0xAC9B78, 0x424846, 0x68533A}),
            new UiTheme("MỸ ĐỎ", 0xD12128, 0x2E6A8A, 0x01344F, 0xFAE3AC, new int[]{0xD12128, 0x01344F, 0xFAE3AC}),
    };

    /** The original Xlrw look (always used without Plus) keeps its exact hand-picked colours instead of the derived ones. */
    private static UiTheme fixedXlrw() {
        return new UiTheme("XLRW", 0xF3701E, 0x4B607F, 0x2B3240, 0xE8D8C9, new int[]{0xF3701E, 0x4B607F, 0xE8D8C9})
                .exact(0xF21A1A1D, 0xFF232429, 0xFF4B607F, 0xFF3B2A20, 0xFF2B3240, 0xFF4B607F, 0xFF8D97AB, 0xFF141416);
    }

    private UiTheme(UiTheme t, int bg, int bg2, int outline, int accentDeep, int widget, int widgetHi, int textDim, int ink) {
        this.name = t.name;
        this.swatch = t.swatch;
        this.accent = t.accent;
        this.cream = t.cream;
        this.bg = bg;
        this.bg2 = bg2;
        this.outline = outline;
        this.accentDeep = accentDeep;
        this.widget = widget;
        this.widgetHi = widgetHi;
        this.textDim = textDim;
        this.ink = ink;
    }

    private UiTheme exact(int bg, int bg2, int outline, int accentDeep, int widget, int widgetHi, int textDim, int ink) {
        return new UiTheme(this, bg, bg2, outline, accentDeep, widget, widgetHi, textDim, ink);
    }

    public static UiTheme get(int i) {
        return ALL[i < 0 || i >= ALL.length ? 0 : i];
    }

    static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    static float lum(int c) {
        return (0.2126f * ((c >> 16) & 255) + 0.7152f * ((c >> 8) & 255) + 0.0722f * (c & 255)) / 255f;
    }
}
