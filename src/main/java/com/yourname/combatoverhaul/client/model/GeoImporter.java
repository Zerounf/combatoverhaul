package com.yourname.combatoverhaul.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.combatoverhaul.CombatOverhaulMod;
import com.yourname.combatoverhaul.combat.ActionCache;
import com.yourname.combatoverhaul.store.ModelStore;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Bedrock .geo.json 几何导入：把 Blockbench/GeckoLib 的 geo 转成现有内部模型文件，
 * 之后由编辑器/新 Bedrock renderer 统一加载。
 *
 * <p>文件放在 {@code config/combatoverhaul/geo/}；导入时保留原模型的
 * actions / held_items / texture，避免只换几何把用户数据洗掉。
 */
public final class GeoImporter {

    public static final class Result {
        public boolean ok;
        public String error;
        public int bones;
        public int quads;
        public final List<String> warnings = new ArrayList<>();
    }

    private GeoImporter() {
    }

    /** .geo.json 输入目录（config/combatoverhaul/geo） */
    public static Path geoDir() {
        return FMLPaths.CONFIGDIR.get().resolve("combatoverhaul").resolve("geo");
    }

    public static List<String> listFiles() {
        Path dir = geoDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            CombatOverhaulMod.LOGGER.warn("[CombatOverhaul] 无法创建 geo 目录 {}", dir, e);
        }
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase().endsWith(".json"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            CombatOverhaulMod.LOGGER.error("[CombatOverhaul] 读取 geo 目录失败", e);
            return List.of();
        }
    }

    /**
     * 导入一个 .geo.json 到指定模型。
     *
     * @param modid     模型命名空间
     * @param entityPath 模型路径
     * @param weapon     是否写入 weapons/ 目录
     * @param fileName   geo 目录下的文件名
     */
    public static Result importFile(String modid, String entityPath, boolean weapon, String fileName) {
        Result r = new Result();
        Path dir = geoDir().normalize();
        Path geoFile = dir.resolve(fileName).normalize();
        if (!geoFile.startsWith(dir)) {
            r.error = "文件名非法（不能跳出 geo 目录）: " + fileName;
            return r;
        }
        if (!Files.isRegularFile(geoFile) && !fileName.toLowerCase().endsWith(".json")) {
            Path withExt = dir.resolve(fileName + ".json").normalize();
            if (withExt.startsWith(dir) && Files.isRegularFile(withExt)) {
                geoFile = withExt;
            }
        }
        if (!Files.isRegularFile(geoFile)) {
            r.error = "找不到 geo 文件: " + fileName + "（应放在 " + dir + "）";
            return r;
        }

        JsonObject geo;
        try {
            geo = JsonParser.parseString(Files.readString(geoFile, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            r.error = "geo JSON 解析失败: " + e.getMessage();
            return r;
        }

        JsonObject converted;
        try {
            converted = GeckoModelLoader.convert(geo);
        } catch (RuntimeException e) {
            r.error = "geo 转换失败: " + e.getMessage();
            return r;
        }
        if (converted == null || !converted.has("roots")
                || !converted.get("roots").isJsonObject()
                || converted.getAsJsonObject("roots").size() == 0) {
            r.error = "geo 里没有可用骨骼/几何（检查 minecraft:geometry / bones）";
            return r;
        }

        // 统计 + 保留旧模型的用户数据
        int[] counts = countBonesAndQuads(converted.getAsJsonObject("roots"));
        r.bones = counts[0];
        r.quads = counts[1];

        JsonObject out = converted.deepCopy();
        out.addProperty("format", 1);
        out.addProperty("entity", modid + ":" + entityPath);
        out.addProperty("modid", modid);
        out.addProperty("source", "bedrock_geo");
        out.addProperty("uv_normalized", true);
        String oldText = weapon ? ModelStore.readWeapon(modid, entityPath) : ModelStore.read(modid, entityPath);
        if (oldText != null) {
            try {
                JsonObject old = JsonParser.parseString(oldText).getAsJsonObject();
                if (old.has("actions") && old.get("actions").isJsonArray()) {
                    out.add("actions", old.get("actions"));
                }
                if (old.has("action_zones") && old.get("action_zones").isJsonObject()) {
                    out.add("action_zones", old.get("action_zones"));
                }
                if (old.has("held_items") && old.get("held_items").isJsonArray()) {
                    out.add("held_items", old.get("held_items"));
                } else if (old.has("held_item") && old.get("held_item").isJsonObject()) {
                    out.add("held_item", old.get("held_item"));
                }
                if (old.has("texture") && old.get("texture").isJsonPrimitive()) {
                    out.add("texture", old.get("texture"));
                }
            } catch (RuntimeException e) {
                r.warnings.add("旧模型 JSON 损坏，未保留其 actions/held_items（几何仍会写入）");
            }
        }
        boolean ok = weapon
                ? ModelStore.writeWeapon(modid, entityPath, out.toString())
                : ModelStore.write(modid, entityPath, out.toString());
        if (!ok) {
            r.error = "写入模型文件失败（见日志）";
            return r;
        }
        ActionCache.invalidate(modid, entityPath, weapon);
        r.ok = true;
        return r;
    }

    private static int[] countBonesAndQuads(JsonObject roots) {
        int bones = 0;
        int quads = 0;
        for (var e : roots.entrySet()) {
            if (e.getValue().isJsonObject()) {
                int[] c = countNode(e.getValue().getAsJsonObject());
                bones += c[0];
                quads += c[1];
            }
        }
        return new int[]{bones, quads};
    }

    private static int[] countNode(JsonObject node) {
        int bones = 1;
        int quads = 0;
        if (node.has("cubes") && node.get("cubes").isJsonArray()) {
            JsonArray cubes = node.getAsJsonArray("cubes");
            for (JsonElement ce : cubes) {
                if (ce.isJsonObject() && ce.getAsJsonObject().has("faces")
                        && ce.getAsJsonObject().get("faces").isJsonObject()) {
                    quads += ce.getAsJsonObject().getAsJsonObject("faces").size();
                }
            }
        }
        if (node.has("children") && node.get("children").isJsonObject()) {
            for (var e : node.getAsJsonObject("children").entrySet()) {
                if (e.getValue().isJsonObject()) {
                    int[] c = countNode(e.getValue().getAsJsonObject());
                    bones += c[0];
                    quads += c[1];
                }
            }
        }
        return new int[]{bones, quads};
    }
}
