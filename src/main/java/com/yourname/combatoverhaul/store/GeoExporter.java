package com.yourname.combatoverhaul.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.combatoverhaul.CombatOverhaulMod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 模型导出：本模组内部模型 JSON → GeckoLib / Bedrock {@code .geo.json}（供 Blockbench 导入）。
 *
 * <p>逆变换依据 {@link com.yourname.combatoverhaul.client.model.GeckoModelLoader}：
 * <ul>
 *   <li>骨骼枢轴：编辑器存「相对父级」，导出时要沿父链累加成绝对坐标，再
 *       {@code (x, 24 − y, z)} 换成 Bedrock 空间；</li>
 *   <li>立方体原点：{@code (ed.ox + bx, by − ed.oy − sy, ed.oz + bz)}；</li>
 *   <li>面槽位：down↔up、west↔east 互换，north/south 不变；</li>
 *   <li>UV：内部 0~1 归一化 → 按贴图分辨率换成像素矩形。</li>
 * </ul>
 *
 * <p>输出目录：{@code config/combatoverhaul/blockbench/<modid>/<模型>.geo.json}
 */
public final class GeoExporter {

    /** 内部面名 → Bedrock 方向名 */
    private static final String[][] FACE_MAP = {
            {"down", "up"},
            {"up", "down"},
            {"west", "east"},
            {"east", "west"},
            {"north", "north"},
            {"south", "south"},
    };

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private GeoExporter() {
    }

    /** 导出目录（可在 combat_actions.json 的 export_dir 配置，支持绝对路径） */
    public static Path exportDir() {
        return ModelStore.resolveConfigured(
                com.yourname.combatoverhaul.combat.CombatConfig.get().exportDir,
                "config/combatoverhaul/blockbench");
    }

    /** 导出结果 */
    public static final class Result {
        public int exported;
        public int skipped;
        public int failed;
        public Path dir;
        public String error;
    }

    /** 导出全部已导出的实体模型 */
    public static Result exportAll(int texW, int texH) {
        Result r = new Result();
        r.dir = exportDir();
        Path root = ModelStore.entitiesRoot();
        if (!Files.isDirectory(root)) {
            r.error = "还没有任何已导出的模型：先按 O 打开编辑器扫描一次";
            return r;
        }
        try {
            Files.createDirectories(r.dir);
        } catch (IOException e) {
            r.error = "无法创建输出目录: " + e.getMessage();
            return r;
        }
        try (Stream<Path> mods = Files.list(root)) {
            for (Path modDir : mods.filter(Files::isDirectory).toList()) {
                try (Stream<Path> files = Files.list(modDir)) {
                    for (Path f : files.filter(Files::isRegularFile).toList()) {
                        exportFile(f, r, texW, texH);
                    }
                }
            }
        } catch (IOException e) {
            r.error = "遍历模型目录失败: " + e.getMessage();
        }
        return r;
    }

    /** 导出单个模型（ns:path） */
    public static Result exportOne(String modid, String entityPath, int texW, int texH) {
        Result r = new Result();
        r.dir = exportDir();
        Path f = ModelStore.fileFor(modid, entityPath);
        if (!Files.isRegularFile(f)) {
            r.error = "模型不存在: " + modid + ":" + entityPath + "（先在编辑器里导出该实体）";
            return r;
        }
        try {
            Files.createDirectories(r.dir.resolve(ModelStore.sanitizeFileName(modid)));
        } catch (IOException e) {
            r.error = "无法创建输出目录: " + e.getMessage();
            return r;
        }
        exportFile(f, r, texW, texH);
        return r;
    }

    private static void exportFile(Path file, Result r, int texW, int texH) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".json")
                || name.endsWith(".actions.json")
                || name.endsWith(".combos.json")) {
            return;
        }
        JsonObject model;
        try {
            model = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            r.failed++;
            return;
        }
        if (!model.has("roots") || !model.get("roots").isJsonObject()) {
            r.skipped++;
            return;
        }
        String modid = file.getParent().getFileName().toString();
        String stem = name.substring(0, name.length() - ".json".length());
        String ent = model.has("entity") && model.get("entity").isJsonPrimitive()
                ? model.get("entity").getAsString() : stem;
        String ident = ("geometry." + modid + "." + ent).replace(':', '.').replace('/', '.');

        JsonObject geo = convert(model, ident, texW, texH);
        if (geo == null) {
            r.skipped++;
            return;
        }
        try {
            Path out = exportDir().resolve(modid).resolve(stem + ".geo.json");
            Files.createDirectories(out.getParent());
            Files.writeString(out, GSON.toJson(geo), StandardCharsets.UTF_8);
            r.exported++;
        } catch (IOException e) {
            r.failed++;
            CombatOverhaulMod.LOGGER.error("[CombatOverhaul] 写出 geo.json 失败: {}", file, e);
        }
    }

    // ------------------------------------------------------------------ 转换

    /** 内部模型 JSON → Bedrock 几何 JSON；没有骨骼时返回 null */
    public static JsonObject convert(JsonObject model, String identifier, int texW, int texH) {
        JsonArray bones = new JsonArray();
        Map<String, String> alias = readAliases(model);
        if (model.has("roots") && model.get("roots").isJsonObject()) {
            walkBones(model.getAsJsonObject("roots"), null,
                    new float[]{0f, 0f, 0f}, bones, texW, texH, alias);
        }
        if (bones.size() == 0) {
            return null;
        }
        JsonObject desc = new JsonObject();
        desc.addProperty("identifier", identifier);
        desc.addProperty("texture_width", texW);
        desc.addProperty("texture_height", texH);
        desc.addProperty("visible_bounds_width", 4);
        desc.addProperty("visible_bounds_height", 4);
        JsonArray vbo = new JsonArray();
        vbo.add(0);
        vbo.add(2);
        vbo.add(0);
        desc.add("visible_bounds_offset", vbo);

        JsonObject def = new JsonObject();
        def.add("description", desc);
        def.add("bones", bones);

        JsonArray geom = new JsonArray();
        geom.add(def);

        JsonObject out = new JsonObject();
        out.addProperty("format_version", "1.12.0");
        out.add("minecraft:geometry", geom);
        return out;
    }

    private static void walkBones(JsonObject nodes, String parentName, float[] parentAbs,
                                  JsonArray out, int texW, int texH, Map<String, String> alias) {
        for (Map.Entry<String, JsonElement> e : nodes.entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject node = e.getValue().getAsJsonObject();
            String name = node.has("name") && node.get("name").isJsonPrimitive()
                    ? node.get("name").getAsString() : e.getKey();
            // 人形模型导出时用语义名（head / right_arm…），Blockbench 里可读
            String outName = alias.getOrDefault(name, name);
            float relX = f(node, "pivot", 0);
            float relY = f(node, "pivot", 1);
            float relZ = f(node, "pivot", 2);
            float abx = parentAbs[0] + relX;
            float aby = parentAbs[1] + relY;
            float abz = parentAbs[2] + relZ;

            // 编辑器绝对 → Bedrock 绝对
            float bpx = abx;
            float bpy = 24f - aby;
            float bpz = abz;

            JsonObject bone = new JsonObject();
            bone.addProperty("name", outName);
            bone.add("pivot", arr(bpx, bpy, bpz));
            if (parentName != null) {
                bone.addProperty("parent", parentName);
            }
            if (anyNonZero(node, "rotation_deg")) {
                bone.add("rotation", arr(f(node, "rotation_deg", 0),
                        f(node, "rotation_deg", 1), f(node, "rotation_deg", 2)));
            }
            if (anyNotOne(node, "scale")) {
                bone.add("scale", arr(f(node, "scale", 0), f(node, "scale", 1), f(node, "scale", 2)));
            }
            if (node.has("cubes") && node.get("cubes").isJsonArray()) {
                JsonArray cubes = new JsonArray();
                for (JsonElement ce : node.getAsJsonArray("cubes")) {
                    if (ce.isJsonObject()) {
                        JsonObject c = convertCube(ce.getAsJsonObject(), bpx, bpy, bpz, texW, texH);
                        if (c != null) {
                            cubes.add(c);
                        }
                    }
                }
                if (cubes.size() > 0) {
                    bone.add("cubes", cubes);
                }
            }
            out.add(bone);

            if (node.has("children") && node.get("children").isJsonObject()) {
                walkBones(node.getAsJsonObject("children"), outName,
                        new float[]{abx, aby, abz}, out, texW, texH, alias);
            }
        }
    }

    /** 读取模型的人形骨骼别名（真实名 → 语义名）；配置关闭或没有该表时返回空表 */
    private static Map<String, String> readAliases(JsonObject model) {
        Map<String, String> out = new java.util.HashMap<>();
        if (!com.yourname.combatoverhaul.combat.CombatConfig.get().humanoidAliases) {
            return out;
        }
        if (model.has("bone_aliases") && model.get("bone_aliases").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : model.getAsJsonObject("bone_aliases").entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    out.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        return out;
    }

    private static JsonObject convertCube(JsonObject cube, float bx, float by, float bz,
                                          int texW, int texH) {
        if (!cube.has("origin") || !cube.has("size")) {
            return null;
        }
        float ox = f(cube, "origin", 0);
        float oy = f(cube, "origin", 1);
        float oz = f(cube, "origin", 2);
        float sx = f(cube, "size", 0);
        float sy = f(cube, "size", 1);
        float sz = f(cube, "size", 2);

        JsonObject o = new JsonObject();
        o.add("origin", arr(ox + bx, by - oy - sy, oz + bz));
        o.add("size", arr(sx, sy, sz));

        // 立方体自转：内部 rot_deg + rot_pivot（相对骨骼枢轴）→ Bedrock rotation + pivot（绝对）
        if (cube.has("rot_deg") && anyNonZero(cube, "rot_deg")) {
            o.add("rotation", arr(f(cube, "rot_deg", 0), f(cube, "rot_deg", 1), f(cube, "rot_deg", 2)));
            if (cube.has("rot_pivot")) {
                o.add("pivot", arr(f(cube, "rot_pivot", 0) + bx,
                        by - f(cube, "rot_pivot", 1),
                        f(cube, "rot_pivot", 2) + bz));
            } else {
                o.add("pivot", arr(bx, by, bz));
            }
        }
        if (cube.has("mirror") && cube.get("mirror").isJsonPrimitive()
                && cube.get("mirror").getAsBoolean()) {
            // 扫描导出的立方体带 face_verts：UV 已是最终镜像结果，不能再让 Bedrock 翻一次。
            boolean exactVerts = cube.has("face_verts") && cube.get("face_verts").isJsonObject()
                    && cube.getAsJsonObject("face_verts").size() > 0;
            if (!exactVerts) {
                o.addProperty("mirror", true);
            }
        }

        if (cube.has("faces") && cube.get("faces").isJsonObject()) {
            JsonObject faces = cube.getAsJsonObject("faces");
            JsonObject uvOut = new JsonObject();
            for (String[] pair : FACE_MAP) {
                String edName = pair[0];
                String bedName = pair[1];
                if (!faces.has(edName) || !faces.get(edName).isJsonArray()) {
                    continue;
                }
                JsonArray quad = faces.getAsJsonArray(edName);
                float uMin = Float.MAX_VALUE, uMax = -Float.MAX_VALUE;
                float vMin = Float.MAX_VALUE, vMax = -Float.MAX_VALUE;
                int n = 0;
                for (JsonElement pe : quad) {
                    JsonElement p = pe;
                    if (pe.isJsonObject() && pe.getAsJsonObject().has("value")) {
                        p = pe.getAsJsonObject().get("value");
                    }
                    if (!p.isJsonArray() || p.getAsJsonArray().size() < 2) {
                        continue;
                    }
                    JsonArray pa = p.getAsJsonArray();
                    float u = pa.get(0).getAsFloat();
                    float v = pa.get(1).getAsFloat();
                    uMin = Math.min(uMin, u);
                    uMax = Math.max(uMax, u);
                    vMin = Math.min(vMin, v);
                    vMax = Math.max(vMax, v);
                    n++;
                }
                if (n == 0) {
                    continue;
                }
                JsonObject face = new JsonObject();
                face.add("uv", arr(uMin * texW, vMin * texH));
                face.add("uv_size", arr((uMax - uMin) * texW, (vMax - vMin) * texH));
                uvOut.add(bedName, face);
            }
            if (uvOut.size() > 0) {
                o.add("uv", uvOut);
            }
        }
        return o;
    }

    // ------------------------------------------------------------------ 工具

    private static boolean anyNonZero(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray()
                && (Math.abs(f(o, key, 0)) > 1e-6 || Math.abs(f(o, key, 1)) > 1e-6
                || Math.abs(f(o, key, 2)) > 1e-6);
    }

    private static boolean anyNotOne(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray()
                && (Math.abs(f(o, key, 0) - 1) > 1e-6 || Math.abs(f(o, key, 1) - 1) > 1e-6
                || Math.abs(f(o, key, 2) - 1) > 1e-6);
    }

    private static float f(JsonObject o, String key, int idx) {
        if (!o.has(key)) {
            return key.equals("scale") ? 1f : 0f;
        }
        JsonElement e = o.get(key);
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            if (idx >= a.size()) {
                return 0f;
            }
            try {
                return a.get(idx).getAsFloat();
            } catch (RuntimeException ex) {
                return 0f;
            }
        }
        return 0f;
    }

    private static JsonArray arr(float... vals) {
        JsonArray a = new JsonArray();
        for (float v : vals) {
            a.add(round(v));
        }
        return a;
    }

    private static double round(float v) {
        double d = Math.round(v * 1e5) / 1e5;
        return d == 0d ? 0d : d;
    }
}
