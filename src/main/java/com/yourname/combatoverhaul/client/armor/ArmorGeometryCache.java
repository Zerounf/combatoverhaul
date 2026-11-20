package com.yourname.combatoverhaul.client.armor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** 装备 + 槽位 + inner/outer 的几何缓存；禁止每帧重新解析模型。 */
public final class ArmorGeometryCache {

    private static final Map<String, ArmorGeometry> CACHE = new ConcurrentHashMap<>();

    private ArmorGeometryCache() {
    }

    public static ArmorGeometry get(String itemId, String slot, boolean inner,
                                    Supplier<ArmorGeometry> loader) {
        String key = itemId + "|" + slot + "|" + inner;
        ArmorGeometry cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        ArmorGeometry loaded = loader == null ? null : loader.get();
        if (loaded == null) {
            return null;
        }
        ArmorGeometry previous = CACHE.putIfAbsent(key, loaded);
        return previous == null ? loaded : previous;
    }

    public static void clear() {
        CACHE.clear();
    }
}
