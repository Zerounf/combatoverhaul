package com.yourname.combatoverhaul.client.screen.ui;

/** 编辑器输入框共用的数值解析。 */
public final class EditorParse {

    private EditorParse() {
    }

    public static float parse(String text, float def, float min, float max) {
        try {
            return Math.max(min, Math.min(max, Float.parseFloat(text.trim())));
        } catch (RuntimeException e) {
            return def;
        }
    }
}
