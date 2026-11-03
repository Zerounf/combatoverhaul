package com.yourname.combatoverhaul.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部动作库：动作不塞进模型 JSON，而是放在 anim_library/ 下按动作文件懒加载。
 *
 * <pre>
 *   anim_library/manifest.json
 *   anim_library/a000_080000.json
 * </pre>
 */
public final class ActionLibrary {

    private static final Map<String, List<ActionStore.Action>> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> STAMP = new ConcurrentHashMap<>();

    private ActionLibrary() {
    }

    public static Path root() {
        return FMLPaths.CONFIGDIR.get().resolve("combatoverhaul").resolve("anim_library");
    }

    /** 仅对连续蒙皮玩家挂动作库，其他模型不受影响。 */
    public static List<ActionStore.Action> loadFor(String modid, String path) {
        boolean er = "combatoverhaul".equals(modid) && "skinned_player_er".equals(path);
        if (!er && !("combatoverhaul".equals(modid) && "skinned_player".equals(path))) {
            return List.of();
        }
        Path manifest = root().resolve(er ? "manifest_er20.json" : "manifest.json");
        if (!Files.isRegularFile(manifest)) {
            return List.of();
        }
        try {
            long stamp = Files.getLastModifiedTime(manifest).toMillis();
            String key = modid + ":" + path;
            List<ActionStore.Action> cached = CACHE.get(key);
            if (cached != null && STAMP.getOrDefault(key, -1L) == stamp) {
                return cached;
            }
            JsonObject m = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray list = m.has("actions") && m.get("actions").isJsonArray()
                    ? m.getAsJsonArray("actions") : new JsonArray();
            List<ActionStore.Action> out = new ArrayList<>();
            for (JsonElement e : list) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject o = e.getAsJsonObject();
                String file = o.has("file") ? o.get("file").getAsString() : null;
                if (file == null || file.isBlank()) {
                    continue;
                }
                Path f = root().resolve(file).normalize();
                if (!f.startsWith(root()) || !Files.isRegularFile(f)) {
                    continue;
                }
                try {
                    JsonObject a = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                    ActionStore.Action action = parseAction(a);
                    if (action != null) {
                        out.add(action);
                    }
                } catch (RuntimeException ex) {
                    com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.warn(
                            "[CombatOverhaul] 动作库文件解析失败 {}", f, ex);
                }
            }
            CACHE.put(key, out);
            STAMP.put(key, stamp);
            return out;
        } catch (Exception e) {
            com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.warn(
                    "[CombatOverhaul] 动作库读取失败 {}", manifest, e);
            return List.of();
        }
    }

    private static ActionStore.Action parseAction(JsonObject o) {
        ActionStore.Action a = new ActionStore.Action();
        a.name = o.has("name") ? o.get("name").getAsString() : "unnamed";
        a.lengthTicks = o.has("lengthTicks") ? Math.max(1, o.get("lengthTicks").getAsFloat()) : 1;
        a.loop = o.has("loop") && o.get("loop").getAsBoolean();
        if (o.has("keyframes") && o.get("keyframes").isJsonArray()) {
            for (JsonElement ke : o.getAsJsonArray("keyframes")) {
                if (!ke.isJsonObject()) {
                    continue;
                }
                JsonObject ko = ke.getAsJsonObject();
                ActionStore.Keyframe kf = new ActionStore.Keyframe();
                kf.time = ko.has("time") ? ko.get("time").getAsFloat() : 0f;
                if (ko.has("bones") && ko.get("bones").isJsonObject()) {
                    for (var be : ko.getAsJsonObject("bones").entrySet()) {
                        if (!be.getValue().isJsonObject()) {
                            continue;
                        }
                        JsonObject co = be.getValue().getAsJsonObject();
                        ActionStore.BoneChannel c = new ActionStore.BoneChannel();
                        c.pos = floats(co, "pos");
                        c.rotDeg = floats(co, "rot_deg");
                        c.scale = floats(co, "scale");
                        kf.bones.put(be.getKey(), c);
                    }
                }
                a.keyframes.add(kf);
            }
        }
        return a.keyframes.isEmpty() ? null : a;
    }

    private static float[] floats(JsonObject o, String key) {
        float[] out = new float[]{0f, 0f, 0f};
        if (key.equals("scale")) {
            out = new float[]{1f, 1f, 1f};
        }
        if (o.has(key) && o.get(key).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(key);
            for (int i = 0; i < Math.min(3, a.size()); i++) {
                out[i] = a.get(i).getAsFloat();
            }
        }
        return out;
    }
}
