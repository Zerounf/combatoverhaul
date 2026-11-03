package com.yourname.combatoverhaul.combat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.combatoverhaul.CombatOverhaulMod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 战斗触发配置：config/combatoverhaul/combat_actions.json。
 *
 * <pre>{
 *   "player_model": "minecraft:player",
 *   "skills":  [ {"action": "attack1", "cooldownTicks": 10} ],            // 按键技能（skill1 键触发第 i 项）
 *   "melee_override": {
 *     "enabled": true,
 *     "auto_weapons": true,                                               // 任何已有武器模型动作的武器自动接管
 *     "weapons": { "minecraft:diamond_sword": {"action": "attack1"} }     // 显式指定
 *   },
 *   "mobs": {
 *     "minecraft:zombie": { "actions": [ {"action": "attack1", "weight": 1.0,
 *         "minDist": 0.0, "maxDist": 3.0, "cooldownTicks": 20,
 *         "requireOnGround": true, "requireLineOfSight": true,
 *         "targetAbove": null, "goalPriority": 1 } ] }
 *   },
 *   "debug_hitboxes": false
 * }</pre>
 *
 * <p>文件缺失时自动生成默认骨架；字段缺失按默认值，旧文件无需手动迁移。
 *
 * <p>自动迁移：melee_override.weapons 的旧显式条目在加载时合并进对应武器动作文件
 * （weapons/&lt;ns&gt;/&lt;path&gt;.actions.json 中同名动作的 weapons 列表，冷却取较大值），
 * 迁移成功后从本文件移除该条目并回写；目标动作文件/动作缺失时保留条目并告警。
 * 此后武器 → 动作绑定以动作文件为准。
 */
public final class CombatConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 默认技能槽（0..4，与四个攻击键 + 翻滚对应）；文件缺失/无 skills 段时使用 */
    private static final String[] DEFAULT_SKILL_ACTIONS = {
            "attack1", "attack_left_light", "attack_right_heavy", "attack_left_heavy",
            "dodge_forward_light"
    };

    /** 按键技能条目 */
    public static final class SkillEntry {
        public String action = "";
        public String model = ""; // 空=player_model
        public int cooldownTicks = 0;
    }

    /** 近战接管条目 */
    public static final class MeleeEntry {
        public String action = "";
        public int cooldownTicks = 0;
    }

    /** 生物 AI 动作条目 */
    public static final class MobActionEntry {
        public String action = "";
        public double weight = 1.0;
        public double minDist = 0.0;
        public double maxDist = 3.0;
        public int cooldownTicks = 20;
        public boolean requireOnGround = true;
        public boolean requireLineOfSight = true;
        /** true=仅目标更高时 / false=仅目标更低时 / null=不限制 */
        public Boolean targetAbove = null;
        public int goalPriority = 1;
    }

    public static class EntityActions {
        public final List<MobActionEntry> actions = new ArrayList<>();
    }

    public String playerModel = "minecraft:player";
    /** 走新 Bedrock quad 渲染的实体 id；默认只接管玩家，其他实体留旧 ModelPart 路径 */
    public final List<String> bedrockRenderEntities = new ArrayList<>();
    public final List<SkillEntry> skills = new ArrayList<>();
    public boolean meleeEnabled = true;
    public boolean meleeAutoWeapons = true;
    public final Map<String, MeleeEntry> meleeWeapons = new LinkedHashMap<>();
    public final Map<String, EntityActions> mobs = new LinkedHashMap<>();
    public boolean debugHitboxes = false;

    /**
     * 是否允许用热键直接打开编辑器。
     *
     * <p>默认 false：编辑器主入口改为指令 {@code /combatoverhaul open}，
     * 避免游戏中误触打断战斗。需要热键时把此项改 true，并在控制设置里绑定按键。
     */
    public boolean openEditorHotkey = false;

    /**
     * Blockbench 模型导出目录。绝对路径，或相对游戏目录的路径；
     * 空 = 默认 {@code config/combatoverhaul/blockbench}。
     */
    public String exportDir = "";

    /**
     * 动画导入源目录（放 Blockbench 导出的 .animation.json）。
     * 绝对路径，或相对游戏目录的路径；空 = 默认 {@code config/combatoverhaul/anim}。
     */
    public String animDir = "";

    /**
     * 导出给 Blockbench 时是否使用人形通用骨骼名（head / right_arm …）。
     *
     * <p>只影响导出的 .geo.json 里骨骼叫什么；动作文件里存的始终是模型真实骨骼名，
     * 所以关掉它不会破坏已有动作，只是 Blockbench 里又变回 SRG 名。
     */
    public boolean humanoidAliases = true;

    private static CombatConfig instance;

    public static synchronized CombatConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /** 编辑器保存动作后调用：下次读取重新加载 */
    public static synchronized void invalidate() {
        instance = null;
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("combatoverhaul").resolve("combat_actions.json");
    }

    private static CombatConfig load() {
        CombatConfig cfg = new CombatConfig();
        cfg.bedrockRenderEntities.add("minecraft:player");
        // 内存默认值必须与 writeDefault 写出的 5 个槽一致；否则首次启动本会话里
        // skills[1..4] 全部越界，右键/重击/翻滚直到重启都无效。
        for (String action : DEFAULT_SKILL_ACTIONS) {
            SkillEntry s = new SkillEntry();
            s.action = action;
            s.cooldownTicks = 10;
            cfg.skills.add(s);
        }
        try {
            Path f = file();
            if (!Files.isRegularFile(f)) {
                cfg.writeDefault(f);
                return cfg;
            }
            String content = Files.readString(f, StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();
            if (json.has("player_model") && json.get("player_model").isJsonPrimitive()) {
                cfg.playerModel = json.get("player_model").getAsString();
            }
            if (json.has("bedrock_render_entities") && json.get("bedrock_render_entities").isJsonArray()) {
                cfg.bedrockRenderEntities.clear();
                for (var e : json.getAsJsonArray("bedrock_render_entities")) {
                    if (e.isJsonPrimitive()) {
                        String id = e.getAsString().trim();
                        if (!id.isEmpty() && !cfg.bedrockRenderEntities.contains(id)) {
                            cfg.bedrockRenderEntities.add(id);
                        }
                    }
                }
            }
            if (json.has("debug_hitboxes") && json.get("debug_hitboxes").isJsonPrimitive()) {
                cfg.debugHitboxes = json.get("debug_hitboxes").getAsBoolean();
            }
            if (json.has("open_editor_hotkey") && json.get("open_editor_hotkey").isJsonPrimitive()) {
                cfg.openEditorHotkey = json.get("open_editor_hotkey").getAsBoolean();
            }
            if (json.has("export_dir") && json.get("export_dir").isJsonPrimitive()) {
                cfg.exportDir = json.get("export_dir").getAsString();
            }
            if (json.has("anim_dir") && json.get("anim_dir").isJsonPrimitive()) {
                cfg.animDir = json.get("anim_dir").getAsString();
            }
            if (json.has("humanoid_aliases") && json.get("humanoid_aliases").isJsonPrimitive()) {
                cfg.humanoidAliases = json.get("humanoid_aliases").getAsBoolean();
            }
            if (json.has("skills") && json.get("skills").isJsonArray()) {
                cfg.skills.clear();
                for (var e : json.getAsJsonArray("skills")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    SkillEntry s = new SkillEntry();
                    if (o.has("action") && o.get("action").isJsonPrimitive()) s.action = o.get("action").getAsString();
                    if (o.has("model") && o.get("model").isJsonPrimitive()) s.model = o.get("model").getAsString();
                    if (o.has("cooldownTicks") && o.get("cooldownTicks").isJsonPrimitive()) {
                        s.cooldownTicks = Math.max(0, o.get("cooldownTicks").getAsInt());
                    }
                    if (!s.action.isEmpty()) cfg.skills.add(s);
                }
            }
            if (json.has("melee_override") && json.get("melee_override").isJsonObject()) {
                JsonObject mo = json.getAsJsonObject("melee_override");
                if (mo.has("enabled") && mo.get("enabled").isJsonPrimitive()) cfg.meleeEnabled = mo.get("enabled").getAsBoolean();
                if (mo.has("auto_weapons") && mo.get("auto_weapons").isJsonPrimitive()) cfg.meleeAutoWeapons = mo.get("auto_weapons").getAsBoolean();
                if (mo.has("weapons") && mo.get("weapons").isJsonObject()) {
                    for (var e : mo.getAsJsonObject("weapons").entrySet()) {
                        if (!e.getValue().isJsonObject()) continue;
                        JsonObject o = e.getValue().getAsJsonObject();
                        MeleeEntry me = new MeleeEntry();
                        if (o.has("action") && o.get("action").isJsonPrimitive()) me.action = o.get("action").getAsString();
                        if (o.has("cooldownTicks") && o.get("cooldownTicks").isJsonPrimitive()) {
                            me.cooldownTicks = Math.max(0, o.get("cooldownTicks").getAsInt());
                        }
                        cfg.meleeWeapons.put(e.getKey(), me);
                    }
                }
            }
            if (json.has("mobs") && json.get("mobs").isJsonObject()) {
                for (var e : json.getAsJsonObject("mobs").entrySet()) {
                    if (!e.getValue().isJsonObject()) continue;
                    EntityActions ea = new EntityActions();
                    JsonObject eo = e.getValue().getAsJsonObject();
                    if (eo.has("actions") && eo.get("actions").isJsonArray()) {
                        for (var ae : eo.getAsJsonArray("actions")) {
                            if (!ae.isJsonObject()) continue;
                            JsonObject o = ae.getAsJsonObject();
                            MobActionEntry m = new MobActionEntry();
                            if (o.has("action") && o.get("action").isJsonPrimitive()) m.action = o.get("action").getAsString();
                            if (o.has("weight") && o.get("weight").isJsonPrimitive()) m.weight = o.get("weight").getAsDouble();
                            if (o.has("minDist") && o.get("minDist").isJsonPrimitive()) m.minDist = o.get("minDist").getAsDouble();
                            if (o.has("maxDist") && o.get("maxDist").isJsonPrimitive()) m.maxDist = o.get("maxDist").getAsDouble();
                            if (o.has("cooldownTicks") && o.get("cooldownTicks").isJsonPrimitive()) {
                                m.cooldownTicks = Math.max(0, o.get("cooldownTicks").getAsInt());
                            }
                            if (o.has("requireOnGround") && o.get("requireOnGround").isJsonPrimitive()) {
                                m.requireOnGround = o.get("requireOnGround").getAsBoolean();
                            }
                            if (o.has("requireLineOfSight") && o.get("requireLineOfSight").isJsonPrimitive()) {
                                m.requireLineOfSight = o.get("requireLineOfSight").getAsBoolean();
                            }
                            if (o.has("targetAbove")) {
                                if (o.get("targetAbove").isJsonPrimitive()) m.targetAbove = o.get("targetAbove").getAsBoolean();
                            }
                            if (o.has("goalPriority") && o.get("goalPriority").isJsonPrimitive()) {
                                m.goalPriority = o.get("goalPriority").getAsInt();
                            }
                            if (!m.action.isEmpty()) ea.actions.add(m);
                        }
                    }
                    if (!ea.actions.isEmpty()) cfg.mobs.put(e.getKey(), ea);
                }
            }
            migrateMeleeWeapons(cfg, f, json);
            ensureDodgeSlot(cfg);
        } catch (Exception ex) {
            CombatOverhaulMod.LOGGER.error("[CombatOverhaul] combat_actions.json 解析失败，使用默认配置", ex);
        }
        return cfg;
    }

    /**
     * 旧配置只有 4 个攻击槽时补上第 5 个翻滚槽（索引 4，默认 V 键使用）。
     * 动作名优先从玩家模型里找含 "dodge" 的动作（兼容 imported.dodge_forward_light 这类导入名）。
     * 只补 4→5，不动其它数量，避免猜测用户自定义布局。
     */
    private static void ensureDodgeSlot(CombatConfig cfg) {
        if (cfg.skills.size() != 4) {
            return;
        }
        String action = "dodge_forward_light";
        String[] pm = cfg.playerModel.split(":", 2);
        if (pm.length == 2) {
            for (var a : com.yourname.combatoverhaul.store.ActionStore.load(pm[0], pm[1], false)) {
                if (a.name.toLowerCase(java.util.Locale.ROOT).contains("dodge")) {
                    action = a.name;
                    break;
                }
            }
        }
        SkillEntry s = new SkillEntry();
        s.action = action;
        s.cooldownTicks = 10;
        cfg.skills.add(s);
        CombatOverhaulMod.LOGGER.info("[CombatOverhaul] 旧配置缺少翻滚槽，已补 skills[4] = {}", action);
    }

    /**
     * 把 melee_override.weapons 旧条目迁移进武器动作文件的 weapons 绑定列表。
     * 成功迁移的条目从 combat_actions.json 移除并回写文件（保留其余内容）。
     */
    private static void migrateMeleeWeapons(CombatConfig cfg, Path f, JsonObject json) {
        if (cfg.meleeWeapons.isEmpty()) {
            return;
        }
        List<String> migrated = new ArrayList<>();
        for (Map.Entry<String, MeleeEntry> e : cfg.meleeWeapons.entrySet()) {
            String itemId = e.getKey();
            MeleeEntry me = e.getValue();
            if (me.action.isEmpty()) {
                continue;
            }
            String[] parts = itemId.split(":", 2);
            String ns = parts.length == 2 ? parts[0] : "minecraft";
            String path = parts.length == 2 ? parts[1] : parts[0];
            List<com.yourname.combatoverhaul.store.ActionStore.Action> actions =
                    com.yourname.combatoverhaul.store.ActionStore.load(ns, path, true);
            com.yourname.combatoverhaul.store.ActionStore.Action target = null;
            for (var a : actions) {
                if (a.name.equals(me.action)) {
                    target = a;
                    break;
                }
            }
            if (target == null) {
                CombatOverhaulMod.LOGGER.warn(
                        "[CombatOverhaul] 迁移跳过 {} -> {}：武器动作文件或动作不存在（条目保留在 combat_actions.json）",
                        itemId, me.action);
                continue;
            }
            boolean changed = false;
            if (!target.weapons.contains(itemId)) {
                target.weapons.add(itemId);
                changed = true;
            }
            if (me.cooldownTicks > target.cooldownTicks) {
                target.cooldownTicks = me.cooldownTicks;
                changed = true;
            }
            if (changed
                    && !com.yourname.combatoverhaul.store.ActionStore.save(ns, path, true, actions)) {
                CombatOverhaulMod.LOGGER.warn("[CombatOverhaul] 迁移 {} 写入武器动作文件失败（条目保留）", itemId);
                continue;
            }
            ActionCache.invalidate(ns, path, true);
            migrated.add(itemId);
            CombatOverhaulMod.LOGGER.info("[CombatOverhaul] 已迁移近战绑定 {} -> 动作 {}（见武器动作文件 weapons 列表）",
                    itemId, me.action);
        }
        if (migrated.isEmpty()) {
            return;
        }
        for (String id : migrated) {
            cfg.meleeWeapons.remove(id);
        }
        // 回写：从 melee_override.weapons 中移除已迁移条目，其余内容原样保留
        try {
            if (json.has("melee_override") && json.get("melee_override").isJsonObject()) {
                JsonObject mo = json.getAsJsonObject("melee_override");
                if (mo.has("weapons") && mo.get("weapons").isJsonObject()) {
                    JsonObject w = mo.getAsJsonObject("weapons");
                    for (String id : migrated) {
                        w.remove(id);
                    }
                }
            }
            Files.writeString(f, GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            CombatOverhaulMod.LOGGER.error("[CombatOverhaul] 迁移后回写 combat_actions.json 失败", ex);
        }
    }

    private void writeDefault(Path f) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("player_model", playerModel);
            JsonArray bedrock = new JsonArray();
            bedrock.add("minecraft:player");
            json.add("bedrock_render_entities", bedrock);
            json.addProperty("debug_hitboxes", debugHitboxes);
            json.addProperty("open_editor_hotkey", openEditorHotkey);
            json.addProperty("export_dir", exportDir.isEmpty()
                    ? "config/combatoverhaul/blockbench" : exportDir);
            json.addProperty("anim_dir", animDir.isEmpty()
                    ? "config/combatoverhaul/anim" : animDir);
            json.addProperty("humanoid_aliases", humanoidAliases);
            JsonArray skills = new JsonArray();
            // 五个槽位：右手轻 / 左手轻 / 右手重 / 左手重 / 翻滚（与热键一一对应）
            for (String action : DEFAULT_SKILL_ACTIONS) {
                JsonObject s = new JsonObject();
                s.addProperty("action", action);
                s.addProperty("cooldownTicks", 10);
                skills.add(s);
            }
            json.add("skills", skills);
            JsonObject melee = new JsonObject();
            melee.addProperty("enabled", true);
            melee.addProperty("auto_weapons", true);
            melee.add("weapons", new JsonObject());
            json.add("melee_override", melee);
            json.add("mobs", new JsonObject());
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            CombatOverhaulMod.LOGGER.error("[CombatOverhaul] 写入默认 combat_actions.json 失败", ex);
        }
    }
}
