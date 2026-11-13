package com.yourname.combatoverhaul.client.screen.ui;

import net.minecraft.client.gui.GuiGraphics;

/** 编辑器共用的基础绘制。 */
public final class EditorDraw {

    private EditorDraw() {
    }

    /** Bresenham 细线，和旧 ModelEditorScreen/RigEditorScreen 的实现一致。 */
    public static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            g.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) {
                break;
            }
            int e2 = err * 2;
            if (e2 >= dy) {
                err += dy;
                x0 += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    public static void vline(GuiGraphics g, int x, int y0, int y1, int color) {
        g.fill(x, y0, x + 1, y1, color);
    }
}
