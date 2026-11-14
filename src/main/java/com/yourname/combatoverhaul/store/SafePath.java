package com.yourname.combatoverhaul.store;

/** 把 namespace/path 文件名限制在安全字符集内。 */
public final class SafePath {

    private SafePath() {
    }

    public static String safe(String s) {
        if (s == null) {
            return "_";
        }
        String v = s.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
        return v.isBlank() || v.equals(".") || v.equals("..") ? "_" : v;
    }
}
