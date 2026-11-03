package com.yourname.combatoverhaul.client.bedrock;

import com.google.gson.JsonObject;
import com.yourname.combatoverhaul.client.model.GeckoModelLoader;
import com.yourname.combatoverhaul.client.model.ParsedModel;

/**
 * Bedrock 几何入口：
 * <ul>
 *   <li>{@link #fromGeoJson(JsonObject)}：直接吃 Blockbench/GeckoLib 的 .geo.json；</li>
 *   <li>{@link #fromInternal(JsonObject)}：吃现有 config/combatoverhaul/entities/*.json；</li>
 * </ul>
 * 两者最终都变成同一份 {@link BedrockGeometry}，渲染/姿态层不再区分来源。
 *
 * <p>当前复用已经逐字段验证过的 {@link GeckoModelLoader#convert(JsonObject)} 做
 * Bedrock→内部空间转换（含逐面 UV、inflate、cube rotation、面槽位镜像），
 * 避免第一版重写坐标换算引入新的画面偏差；后续可以把 convert 替换成纯 Bedrock 解析。
 */
public final class BedrockGeometryLoader {

    private BedrockGeometryLoader() {
    }

    /** 直接加载 Bedrock .geo.json（minecraft:geometry / 旧 geometry.* 均可）。 */
    public static BedrockGeometry fromGeoJson(JsonObject geo) {
        if (geo == null) {
            return new BedrockGeometry(64, 64, java.util.List.of(), java.util.Map.of());
        }
        JsonObject internal = GeckoModelLoader.convert(geo);
        if (internal == null) {
            return new BedrockGeometry(64, 64, java.util.List.of(), java.util.Map.of());
        }
        int[] tex = readTextureSize(geo);
        ParsedModel model = ParsedModel.parse(internal);
        return BedrockGeometryAdapter.fromParsedModel(model).withTextureSize(tex[0], tex[1]);
    }

    /** 加载现有内部模型 JSON（config/combatoverhaul/entities/<modid>/<entity>.json）。 */
    public static BedrockGeometry fromInternal(JsonObject modelJson) {
        if (modelJson == null) {
            return new BedrockGeometry(64, 64, java.util.List.of(), java.util.Map.of());
        }
        return BedrockGeometryAdapter.fromParsedModel(ParsedModel.parse(modelJson));
    }

    /** 从 Bedrock description 读取 texture_width/height；缺失按 64。 */
    private static int[] readTextureSize(JsonObject geo) {
        int w = 64;
        int h = 64;
        try {
            JsonObject def = null;
            if (geo.has("minecraft:geometry") && geo.get("minecraft:geometry").isJsonArray()) {
                var arr = geo.getAsJsonArray("minecraft:geometry");
                if (arr.size() > 0 && arr.get(0).isJsonObject()) {
                    def = arr.get(0).getAsJsonObject();
                }
            } else {
                for (var e : geo.entrySet()) {
                    if (e.getKey().startsWith("geometry.") && e.getValue().isJsonObject()) {
                        def = e.getValue().getAsJsonObject();
                        break;
                    }
                }
            }
            if (def != null && def.has("description") && def.get("description").isJsonObject()) {
                JsonObject d = def.getAsJsonObject("description");
                if (d.has("texture_width")) {
                    w = d.get("texture_width").getAsInt();
                }
                if (d.has("texture_height")) {
                    h = d.get("texture_height").getAsInt();
                }
            }
        } catch (RuntimeException ignored) {
        }
        return new int[]{w, h};
    }
}
