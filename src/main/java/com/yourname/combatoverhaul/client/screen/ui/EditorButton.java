package com.yourname.combatoverhaul.client.screen.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** 深色 IDE 风格按钮；替代原版灰色 Button，供所有编辑器页面统一使用。 */
public class EditorButton extends Button {

    private final boolean primary;

    public EditorButton(int x, int y, int w, int h, Component text, OnPress onPress) {
        this(x, y, w, h, text, onPress, false);
    }

    public EditorButton(int x, int y, int w, int h, Component text, OnPress onPress, boolean primary) {
        super(x, y, w, h, text, onPress, DEFAULT_NARRATION);
        this.primary = primary;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean hover = this.isHoveredOrFocused();
        int bg = primary
                ? (hover ? 0xFF2F6FB5 : EditorTheme.ACCENT_SOFT)
                : (hover ? EditorTheme.BG_ROW_HOVER : EditorTheme.BG_PANEL_ALT);
        int border = primary ? EditorTheme.ACCENT : EditorTheme.BORDER;
        g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), bg);
        g.renderOutline(getX(), getY(), getWidth(), getHeight(), border);
        int color = this.active ? (primary ? 0xFFFFFFFF : EditorTheme.TEXT) : EditorTheme.TEXT_DIM;
        g.drawCenteredString(net.minecraft.client.Minecraft.getInstance().font,
                getMessage(), getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, color);
    }
}
