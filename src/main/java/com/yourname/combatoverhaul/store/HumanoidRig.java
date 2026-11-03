package com.yourname.combatoverhaul.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.combatoverhaul.CombatOverhaulMod;

/**
 * 通用人形骨架（rig）：从已扫描的 {@code minecraft:player} 模型派生一份独立模型，
 * 用于「做一次动画，驱动所有人形生物」。
 *
 * <p>为什么是派生而不是合成：合成骨架只能凭空造立方体，UV 只能填占位块，也拿不到原版的
 * 双层皮肤（hat / jacket / sleeve / pants）。而扫描出来的 player 模型是反射读取
 * {@code ModelPart.Cube} 多边形得到的，<b>逐面 UV 与原版皮肤布局完全一致</b>，两层骨骼也都在。
 * 直接派生可以零风险拿到正确外观。
 *
 * <p>未扫描过 player 模型时，退回合成的 6 骨骼骨架（无外层皮肤、UV 为占位），保证功能可用。
 */
public final class HumanoidRig {

    /** 骨架模型 id */
    public static final String MODID = "combatoverhaul";
    public static final String PATH = "humanoid";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private HumanoidRig() {
    }

    /** 是否是通用人形骨架（导入动画到它时自动授权给所有人形生物） */
    public static boolean isRig(String modid, String path) {
        return MODID.equals(modid) && PATH.equals(path);
    }

    /** 是否可派生（已扫描过 player 模型） */
    public static boolean sourceAvailable() {
        return ModelStore.read("minecraft", "player") != null;
    }

    /** 生成骨架 JSON：优先从 player 派生，否则退回合成骨架 */
    public static String generate() {
        String src = ModelStore.read("minecraft", "player");
        if (src != null) {
            try {
                JsonObject json = JsonParser.parseString(src).getAsJsonObject().deepCopy();
                json.addProperty("entity", MODID + ":" + PATH);
                json.addProperty("modid", MODID);
                json.addProperty("source", "derived");
                json.addProperty("note", "由 minecraft:player 派生：骨骼、逐面 UV、双层皮肤"
                        + "（hat/jacket/sleeve/pants）均取自扫描结果，可直接用于动画编辑。");
                // 动作不继承：player 的动作不该被复制进骨架
                json.remove("actions");

                // 骨骼改名为语义名（f_102810_ -> body），这样在 Blockbench 与 build_rig14 里都直接可读。
                // 别名表随之清空：名字本身就是语义名，不再需要映射。
                if (json.has("bone_aliases") && json.get("bone_aliases").isJsonObject()) {
                    java.util.Map<String, String> alias = new java.util.HashMap<>();
                    for (var e : json.getAsJsonObject("bone_aliases").entrySet()) {
                        if (e.getValue().isJsonPrimitive()) {
                            alias.put(e.getKey(), e.getValue().getAsString());
                        }
                    }
                    if (!alias.isEmpty() && json.has("roots") && json.get("roots").isJsonObject()) {
                        json.add("roots", renameTree(json.getAsJsonObject("roots"), alias));
                        // 自动蒙皮后 player 可能已有 mesh：权重里的真实骨骼名也要跟着改，
                        // 否则派生骨架保存后会出现“引用不存在骨骼”。
                        renameWeights(json, alias);
                        renameSourceBones(json, alias);
                        renameBoneArray(json, "bind_bones", alias);
                        json.remove("bone_aliases");
                    }
                }
                return GSON.toJson(json);
            } catch (RuntimeException e) {
                CombatOverhaulMod.LOGGER.warn("[CombatOverhaul] 派生骨架失败，退回合成骨架", e);
            }
        }
        return synthetic();
    }

    /** 写入骨架文件（覆盖），返回是否成功 */
    public static boolean write() {
        try {
            return ModelStore.write(MODID, PATH, generate());
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 语义改名

    /** 按别名表把整棵骨骼树的键名与 name 字段改成语义名 */
    private static JsonObject renameTree(JsonObject nodes, java.util.Map<String, String> alias) {
        JsonObject out = new JsonObject();
        for (var e : nodes.entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject node = e.getValue().getAsJsonObject().deepCopy();
            String semantic = alias.getOrDefault(e.getKey(), e.getKey());
            node.addProperty("name", semantic);
            if (node.has("follow") && node.get("follow").isJsonPrimitive()) {
                String oldFollow = node.get("follow").getAsString();
                node.addProperty("follow", alias.getOrDefault(oldFollow, oldFollow));
            }
            if (node.has("children") && node.get("children").isJsonObject()) {
                node.add("children", renameTree(node.getAsJsonObject("children"), alias));
            }
            out.add(semantic, node);
        }
        return out;
    }

    /** 把 weights[].bone 的真实骨骼名映射为语义名（原地修改） */
    private static void renameWeights(JsonObject json, java.util.Map<String, String> alias) {
        if (!json.has("weights") || !json.get("weights").isJsonArray()) {
            return;
        }
        for (var row : json.getAsJsonArray("weights")) {
            if (!row.isJsonArray()) {
                continue;
            }
            for (var item : row.getAsJsonArray()) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject w = item.getAsJsonObject();
                if (w.has("bone") && w.get("bone").isJsonPrimitive()) {
                    String old = w.get("bone").getAsString();
                    String semantic = alias.getOrDefault(old, old);
                    if (!semantic.equals(old)) {
                        w.addProperty("bone", semantic);
                    }
                }
            }
        }
    }

    /** 把 source_bones / bind_bones 里的真实骨骼名映射为语义名（原地修改） */
    private static void renameSourceBones(JsonObject json, java.util.Map<String, String> alias) {
        renameBoneArray(json, "source_bones", alias);
    }

    private static void renameBoneArray(JsonObject json, String key, java.util.Map<String, String> alias) {
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            return;
        }
        var arr = json.getAsJsonArray(key);
        for (int i = 0; i < arr.size(); i++) {
            var item = arr.get(i);
            if (!item.isJsonPrimitive()) {
                continue;
            }
            String old = item.getAsString();
            if (old.isBlank()) {
                continue;
            }
            String semantic = alias.getOrDefault(old, old);
            if (!semantic.equals(old)) {
                arr.set(i, new com.google.gson.JsonPrimitive(semantic));
            }
        }
    }

    // ------------------------------------------------------------------ 合成回退

    /** 合成 6 骨骼骨架：无外层皮肤、UV 为占位块，仅在未扫描 player 时使用 */
    private static String synthetic() {
        JsonObject json = new JsonObject();
        json.addProperty("format", ModelStore.FORMAT_VERSION);
        json.addProperty("entity", MODID + ":" + PATH);
        json.addProperty("modid", MODID);
        json.addProperty("uv_normalized", true);
        json.addProperty("source", "generated");
        json.addProperty("note", "合成骨架（未找到 minecraft:player 扫描结果）：仅 6 根骨骼、"
                + "无外层皮肤、UV 为占位。先扫描一次玩家模型再执行本指令可获得完整骨架。");
        json.addProperty("texture", "minecraft:textures/entity/player/wide/steve.png");

        JsonObject roots = new JsonObject();
        addBone(roots, "head", 0f, 0f, 0f, -4f, -8f, -4f, 8f, 8f, 8f);
        addBone(roots, "body", 0f, 0f, 0f, -4f, 0f, -2f, 8f, 12f, 4f);
        addBone(roots, "right_arm", 5f, 2f, 0f, -1f, -2f, -2f, 4f, 12f, 4f);
        addBone(roots, "left_arm", -5f, 2f, 0f, -1f, -2f, -2f, 4f, 12f, 4f);
        addBone(roots, "right_leg", 1.9f, 12f, 0f, -2f, 0f, -2f, 4f, 12f, 4f);
        addBone(roots, "left_leg", -1.9f, 12f, 0f, -2f, 0f, -2f, 4f, 12f, 4f);
        json.add("roots", roots);
        return GSON.toJson(json);
    }

    private static void addBone(JsonObject roots, String name,
                                float px, float py, float pz,
                                float ox, float oy, float oz,
                                float sx, float sy, float sz) {
        JsonObject b = new JsonObject();
        b.addProperty("name", name);
        b.add("pivot", arr(px, py, pz));
        b.add("rotation_deg", arr(0, 0, 0));
        b.add("scale", arr(1, 1, 1));
        b.addProperty("visible", true);

        JsonObject cube = new JsonObject();
        cube.add("origin", arr(ox, oy, oz));
        cube.add("size", arr(sx, sy, sz));
        JsonObject faces = new JsonObject();
        for (String f : new String[]{"down", "up", "west", "east", "north", "south"}) {
            JsonArray quad = new JsonArray();
            quad.add(uv(0f, 0f));
            quad.add(uv(0f, 0.125f));
            quad.add(uv(0.125f, 0.125f));
            quad.add(uv(0.125f, 0f));
            faces.add(f, quad);
        }
        cube.add("faces", faces);
        JsonArray cubes = new JsonArray();
        cubes.add(cube);
        b.add("cubes", cubes);
        b.add("children", new JsonObject());
        roots.add(name, b);
    }

    private static JsonArray arr(float a, float b, float c) {
        JsonArray x = new JsonArray();
        x.add(a);
        x.add(b);
        x.add(c);
        return x;
    }

    private static JsonArray uv(float u, float v) {
        JsonArray a = new JsonArray();
        a.add(round(u));
        a.add(round(v));
        return a;
    }

    private static double round(float v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
