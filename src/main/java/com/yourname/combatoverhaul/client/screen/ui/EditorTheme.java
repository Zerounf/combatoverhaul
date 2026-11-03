package com.yourname.combatoverhaul.client.screen.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/**
 * 内置工具的统一现代 IDE 配色/面板绘制。
 *
 * <p>深色、低饱和、细边框、单一蓝色强调色；所有编辑器页面共用，避免每个 Screen
 * 各画一套 0x88000000 + 0xFF555555 的旧面板。
 */
public final class EditorTheme {

    public static final int BG_APP = 0xFF0F1115;
    public static final int BG_HEADER = 0xFF171A20;
    public static final int BG_PANEL = 0xFF1A1E25;
    public static final int BG_PANEL_ALT = 0xFF20252E;
    public static final int BG_ROW_HOVER = 0xFF262D38;
    public static final int BG_ROW_SELECTED = 0xFF243A58;
    public static final int BORDER = 0xFF303744;
    public static final int BORDER_SOFT = 0xFF252B35;
    public static final int TEXT = 0xFFD6DEE9;
    public static final int TEXT_MUTED = 0xFF8A96A8;
    public static final int TEXT_DIM = 0xFF5F6B7C;
    public static final int ACCENT = 0xFF4EA1FF;
    public static final int ACCENT_SOFT = 0xFF2B4C73;
    public static final int OK = 0xFF6FD08C;
    public static final int WARN = 0xFFE2B86B;
    public static final int ERROR = 0xFFE06C75;

    public static final int HEADER_H = 28;
    public static final int STATUS_H = 14;
    public static final int GAP = 4;
    public static final int PAD = 6;
    public static final int ROW_H = 16;

    private EditorTheme() {
    }

    /** 实际可见逻辑宽度：窗口屏幕宽 / GUI scale；不要直接用 Screen.width（Forge 早期窗口会偏大）。 */
    public static int visibleWidth(net.minecraft.client.Minecraft mc) {
        if (mc == null || mc.getWindow() == null) {
            return 320;
        }
        var w = mc.getWindow();
        double s = Math.max(1.0, w.getGuiScale());
        return Math.max(240, (int) Math.round(w.getScreenWidth() / s));
    }

    public static int visibleHeight(net.minecraft.client.Minecraft mc) {
        if (mc == null || mc.getWindow() == null) {
            return 180;
        }
        var w = mc.getWindow();
        double s = Math.max(1.0, w.getGuiScale());
        return Math.max(140, (int) Math.round(w.getScreenHeight() / s));
    }

    public static void appBackground(GuiGraphics g, int width, int height) {
        g.fill(0, 0, width, height, BG_APP);
    }

    public static void header(GuiGraphics g, Font font, int width, Component title, String subtitle) {
        g.fill(0, 0, width, HEADER_H, BG_HEADER);
        g.fill(0, HEADER_H - 1, width, HEADER_H, BORDER);
        g.drawString(font, title, PAD, 8, TEXT, false);
        if (subtitle != null && !subtitle.isEmpty()) {
            int x = PAD + font.width(title) + 10;
            g.drawString(font, subtitle, x, 8, TEXT_MUTED, false);
        }
    }

    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, BG_PANEL);
        g.renderOutline(x, y, w, h, BORDER);
    }

    public static void section(GuiGraphics g, Font font, String text, int x, int y, int w) {
        g.fill(x, y, x + w, y + 16, BG_PANEL_ALT);
        g.drawString(font, text, x + 6, y + 4, TEXT_MUTED, false);
        g.fill(x, y + 16, x + w, y + 17, BORDER_SOFT);
    }

    public static void statusBar(GuiGraphics g, Font font, int width, int height, String left, String right) {
        int y = height - STATUS_H;
        g.fill(0, y, width, height, BG_HEADER);
        g.fill(0, y, width, y + 1, BORDER);
        g.drawString(font, left == null ? "" : left, PAD, y + 5, TEXT_MUTED, false);
        if (right != null && !right.isEmpty()) {
            g.drawString(font, right, width - PAD - font.width(right), y + 5, TEXT_DIM, false);
        }
    }

    public static void row(GuiGraphics g, Font font, int x, int y, int w, int h, String text,
                           boolean selected, boolean hover, int color) {
        if (selected) {
            g.fill(x, y, x + w, y + h, BG_ROW_SELECTED);
            g.fill(x, y, x + 2, y + h, ACCENT);
        } else if (hover) {
            g.fill(x, y, x + w, y + h, BG_ROW_HOVER);
        }
        g.drawString(font, text, x + 8, y + (h - 8) / 2, selected ? TEXT : color, false);
    }

    public static void chip(GuiGraphics g, Font font, String text, int x, int y, int color) {
        int w = font.width(text) + 8;
        g.fill(x, y, x + w, y + 14, (color & 0x00FFFFFF) | 0x33000000);
        g.renderOutline(x, y, w, 14, (color & 0x00FFFFFF) | 0x55000000);
        g.drawString(font, text, x + 4, y + 3, color, false);
    }
}
