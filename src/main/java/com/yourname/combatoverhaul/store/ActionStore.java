package com.yourname.combatoverhaul.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.combatoverhaul.client.model.ParsedBone;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import net.minecraft.util.Mth;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动作（动画）存储：&lt;模型文件名&gt;.actions.json，与模型文件同目录分离存放，防止互相覆盖。
 *
 * <p>结构：
 * <pre>{ "format": 1, "actions": [ {
 *   "name": "attack1", "lengthTicks": 10, "loop": false,
 *   "keyframes": [ { "time": 0, "bones": { "body": {
 *       "pos": [0,0,0], "rot_deg": [20,0,0], "scale": [1,1,1] } } } ] } ] }</pre>
 *
 * <ul>
 *   <li>pos：骨骼枢轴偏移（模型单位）；rot_deg：旋转偏移（度）；scale：缩放乘数。</li>
 *   <li>播放：相邻关键帧线性插值；动作未覆盖的骨骼保持基础姿态。</li>
 *   <li>loop：播放到末尾后从最后一个关键帧平滑插回第一个关键帧。</li>
 * </ul>
 *
 * <p>P3 战斗扩展（format 2，旧文件无 combat 字段自动按默认值迁移，保留全部关键帧）：
 * <pre>{ "format": 2, "actions": [ {
 *   "name": "attack1", "lengthTicks": 10, "loop": false, "cooldownTicks": 10,
 *   "hitboxes": [ { "name": "blade", "bone": "right_arm", "anchorBone": "right_arm",
 *       "size": [16,16,16], "offset": [0,0,-8], "startTick": 2, "endTick": 6,
 *       "multipliers": {"fire": 1.2, "blunt": 1.0}, "maxTargets": 5,
 *       "extraKnockback": 0.0, "damageScale": 1.0 } ],
 *   "iframes": { "startTick": 0, "endTick": 8, "fullImmunity": false,
 *       "blockCustomHitboxes": true } } ] }</pre>
 *
 * <ul>
 *   <li>hitboxes：判定框；bone 为骨骼名（实体动作→实体模型骨骼；武器动作→武器模型骨骼）；
 *       anchorBone 仅武器动作使用：判定框武器根挂到该实体骨骼上跟随运动。</li>
 *   <li>multipliers：命中后对各伤害类型面板的独立倍率（如 fire 1.2 = 武器 fire 面板 ×1.2）。</li>
 *   <li>iframes：动作期间的无敌帧窗口，fullImmunity=免全部伤害，blockCustomHitboxes=仅免疫本模组判定框。</li>
 *   <li>cooldownTicks：动作触发的服务端冷却（tick）。</li>
 * </ul>
 *
 * <p>P4 绑定扩展（仍 format 2，旧文件无这两个字段按空列表迁移）：
 * <ul>
 *   <li>weapons：绑定武器物品 id 列表；持有者主手为其中物品时优先触发本动作，
 *       空列表动作作为徒手/通用兜底。</li>
 *   <li>entities：授权生物实体 id 列表；被勾选生物的 AI 自动获得本动作（默认权重 1.0），
 *       combat_actions.json 的 mobs 段仅用于权重/距离等精细覆盖（同名动作 mobs 段优先）。
 *       仅实体模型动作有意义。</li>
 * </ul>
 */
public final class ActionStore {
    public static final int FORMAT_VERSION = 2;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** 解析失败过的模型：禁止后续 save 把“读出来的不完整动作列表”写回并删掉坏条目 */
    private static final Set<String> LOAD_FAILED = ConcurrentHashMap.newKeySet();

    private static String loadKey(String modid, String entityPath, boolean weapon) {
        return (weapon ? "W|" : "E|") + modid + "|" + entityPath;
    }

    private ActionStore() {
    }

    // ------------------------------------------------------------------ 数据类

    /** 单骨骼通道值 */
    public static final class BoneChannel {
        public float[] pos = {0, 0, 0};
        public float[] rotDeg = {0, 0, 0};
        public float[] scale = {1, 1, 1};

        public static BoneChannel copy(BoneChannel c) {
            BoneChannel n = new BoneChannel();
            n.pos = c.pos.clone();
            n.rotDeg = c.rotDeg.clone();
            n.scale = c.scale.clone();
            return n;
        }

        public static BoneChannel lerp(BoneChannel a, BoneChannel b, float f) {
            BoneChannel n = new BoneChannel();
            for (int i = 0; i < 3; i++) {
                n.pos[i] = a.pos[i] + (b.pos[i] - a.pos[i]) * f;
                n.rotDeg[i] = a.rotDeg[i] + (b.rotDeg[i] - a.rotDeg[i]) * f;
                n.scale[i] = a.scale[i] + (b.scale[i] - a.scale[i]) * f;
            }
            return n;
        }
    }

    /** 关键帧：时间点 + 各骨骼通道 + 可选锚点（不动点） */
    public static final class Keyframe {
        public float time;
        public final Map<String, BoneChannel> bones = new LinkedHashMap<>();
        /** 锚点骨骼名：播放时该骨骼保持在此关键帧指定的位置不动；null=无锚点 */
        public String anchorBone;
        /** 锚点目标位置（模型单位/像素，模型根空间），配合 anchorBone 使用 */
        public float[] anchorPos;

        public boolean isEmpty() {
            return bones.isEmpty() && anchorBone == null;
        }
    }

    /** 判定箱类型：肉搏（绑实体骨骼，默认） */
    public static final String KIND_MELEE = "melee";
    /** 判定箱类型：武器（实体动作挂手持点，武器动作挂武器骨骼；尺寸/偏移手动配置） */
    public static final String KIND_WEAPON = "weapon";

    /** 判定框定义：绑定骨骼、尺寸/偏移（模型单位）、生效时间、伤害类型倍率 */
    public static final class HitboxDef {
        public String name = "hitbox";
        /** 类型：{@link #KIND_MELEE} 肉搏 / {@link #KIND_WEAPON} 武器（旧文件缺省=melee） */
        public String kind = KIND_MELEE;
        /** 骨骼名（实体动作=实体模型骨骼；武器动作=武器模型骨骼；武器类实体动作不使用） */
        public String bone = "";
        /** 仅武器动作：武器模型根挂接的实体骨骼名；为空则挂在实体原点 */
        public String anchorBone = "";
        /** 尺寸（模型单位，1/16 方块） */
        public float[] size = {8, 8, 8};
        /** 骨骼局部空间中心偏移（模型单位） */
        public float[] offset = {0, 0, 0};
        public float startTick = 0;
        public float endTick = 0;
        /** 全伤害类型独立倍率（type → 倍率；未配置的类型=1.0） */
        public final Map<String, Float> multipliers = new LinkedHashMap<>();
        /** 单次动作最多命中数量（<=0 不限） */
        public int maxTargets = 0;
        /** 命中后的额外击退强度（0=仅原版击退） */
        public float extraKnockback = 0;
        /** 攻击伤害总量缩放（乘在攻击面板总伤上） */
        public float damageScale = 1.0f;
        /** 自动几何：忽略 size/offset，判定箱=武器模型立方体（含立方体自转）经骨骼链变换的世界包围盒。
         *  仅武器动作生效；bone 为空=整把武器，非空=该骨骼子树；武器无模型/无立方体时回退手动尺寸。 */
        public boolean autoGeom = false;
    }

    /** 无敌帧窗口：动作期间免伤配置 */
    public static final class IframesDef {
        public float startTick = 0;
        public float endTick = 0;
        /** 免疫一切伤害 */
        public boolean fullImmunity = false;
        /** 仅免疫本模组判定框伤害 */
        public boolean blockCustomHitboxes = true;
    }

    /** 动作层混合参数（全局 enter/exit；逐骨骼 curve 可覆盖） */
    public static final class Blend {
        public float enterTicks = 0f;
        public float exitTicks = 0f;
        /** linear / smooth / ease_in / ease_out / ease_in_out */
        public String interpolation = "smooth";

        public float factor(float localTick, float clipLength) {
            float f = 1f;
            if (enterTicks > 0f && localTick < enterTicks) {
                f = Math.min(f, localTick / enterTicks);
            }
            float remain = clipLength - localTick;
            if (exitTicks > 0f && remain < exitTicks) {
                f = Math.min(f, remain / exitTicks);
            }
            f = Math.max(0f, Math.min(1f, f));
            return switch (interpolation == null ? "smooth" : interpolation.toLowerCase()) {
                case "linear" -> f;
                case "ease_in" -> f * f;
                case "ease_out" -> 1f - (1f - f) * (1f - f);
                default -> f * f * (3f - 2f * f);
            };
        }
    }

    /** 单骨骼/子树 blend 覆盖：星火 ActionBoneBlendCurve 的简化版 */
    public static final class BoneBlendCurve {
        public String bone = "";
        public boolean includeDescendants = true;
        public float enterTicks = 0f;
        public float exitTicks = 0f;
        public String interpolation = "smooth";
    }

    /**
     * 组合动作中的一个动画片段：引用同一模型动作表里的另一个 action。
     * 片段只提供动画，命中框/无敌帧/冷却由组合动作自身负责。
     */
    public static final class Segment {
        public String clip = "";
        /** 空 = 继承 clip 自身的 zone；显式 full = 全身独占。 */
        public String zone = "";
        public float weight = 1f;
        public float startTick = 0f;
        public boolean loop = false;
        public Blend blend = new Blend();
        public final List<BoneBlendCurve> boneCurves = new ArrayList<>();
    }

    /** 动作：名字 + 时长 + 循环 + 关键帧列表（按时间升序）+ P3 战斗扩展 + P4 绑定 */
    public static final class Action {
        public String name = "action";
        public float lengthTicks = 10;
        public boolean loop = false;
        /** 单动作分区；无 segments 时直接按该 zone 裁剪。默认 full。 */
        public String zone = "full";
        /** 仅作为组合片段使用：技能/编辑器列表可隐藏。 */
        public boolean internal = false;
        /** 组合片段；非空时本动作是组合动作，自身 keyframes 不直接播放。 */
        public final List<Segment> segments = new ArrayList<>();
        /** 服务端触发冷却（tick） */
        public int cooldownTicks = 0;
        public final List<HitboxDef> hitboxes = new ArrayList<>();
        public IframesDef iframes = null;
        public final List<Keyframe> keyframes = new ArrayList<>();
        /** 绑定武器（物品 id 列表，如 minecraft:diamond_sword）；空=徒手/通用兜底 */
        public final List<String> weapons = new ArrayList<>();
        /** 授权生物（实体 id 列表）：被勾选的生物 AI 自动获得该动作（仅实体模型动作有意义） */
        public final List<String> entities = new ArrayList<>();
        /** 追踪：播放/判定时对标记 track_pitch 的骨骼按视线俯仰角叠加抬头/低头偏移 */
        public boolean trackPitch = false;
        /** 追踪最大上下偏移（度，0~90）；视线超出部分被钳制 */
        public float maxPitchOffset = 30f;
        /** 动画来源文件名（Blockbench 导入时写入；空 = 游戏内手工编辑） */
        public String animSourceFile = "";
        /** 该动作的关键帧是否经过逐 tick 烘焙（源动画含非线性缓动） */
        public boolean animBaked = false;

        public void sort() {
            keyframes.sort((a, b) -> Float.compare(a.time, b.time));
        }

        /** 找指定时间的关键帧，无则返回 null */
        public Keyframe at(float time) {
            for (Keyframe kf : keyframes) {
                if (Math.abs(kf.time - time) < 0.01f) {
                    return kf;
                }
            }
            return null;
        }

        /** 找指定时间的关键帧，无则创建并保持有序 */
        public Keyframe atOrCreate(float time) {
            Keyframe kf = at(time);
            if (kf == null) {
                kf = new Keyframe();
                kf.time = time;
                keyframes.add(kf);
                sort();
            }
            return kf;
        }
    }

    // ------------------------------------------------------------------ 文件


    /** 读取全部动作；文件不存在/解析失败返回空列表 */
    public static List<Action> load(String modid, String entityPath, boolean weapon) {
        List<Action> out = new ArrayList<>();
        if (!weapon) {
            out.addAll(ActionLibrary.loadFor(modid, entityPath));
        }
        String content = weapon ? ModelStore.readWeapon(modid, entityPath) : ModelStore.read(modid, entityPath);
        String key = loadKey(modid, entityPath, weapon);
        if (content == null) {
            LOAD_FAILED.remove(key);
            return out;
        }
        try {
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();
            if (!json.has("actions") || !json.get("actions").isJsonArray()) {
                LOAD_FAILED.remove(key);
                return out;
            }
            for (var e : json.getAsJsonArray("actions")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject o = e.getAsJsonObject();
                Action a = new Action();
                if (o.has("name") && o.get("name").isJsonPrimitive()) {
                    a.name = o.get("name").getAsString();
                }
                if (o.has("lengthTicks") && o.get("lengthTicks").isJsonPrimitive()) {
                    a.lengthTicks = Math.max(1, o.get("lengthTicks").getAsFloat());
                }
                if (o.has("loop") && o.get("loop").isJsonPrimitive()) {
                    a.loop = o.get("loop").getAsBoolean();
                }
                if (o.has("zone") && o.get("zone").isJsonPrimitive()) {
                    a.zone = o.get("zone").getAsString();
                }
                if (o.has("internal") && o.get("internal").isJsonPrimitive()) {
                    a.internal = o.get("internal").getAsBoolean();
                }
                if (o.has("segments") && o.get("segments").isJsonArray()) {
                    for (var se : o.getAsJsonArray("segments")) {
                        if (se.isJsonObject()) {
                            a.segments.add(segmentFromJson(se.getAsJsonObject()));
                        }
                    }
                }
                if (o.has("cooldownTicks") && o.get("cooldownTicks").isJsonPrimitive()) {
                    a.cooldownTicks = Math.max(0, o.get("cooldownTicks").getAsInt());
                }
                if (o.has("track_pitch") && o.get("track_pitch").isJsonPrimitive()) {
                    a.trackPitch = o.get("track_pitch").getAsBoolean();
                }
                if (o.has("track_max_pitch") && o.get("track_max_pitch").isJsonPrimitive()) {
                    a.maxPitchOffset = Mth.clamp(o.get("track_max_pitch").getAsFloat(), 0f, 90f);
                }
                if (o.has("anim_source") && o.get("anim_source").isJsonObject()) {
                    JsonObject as = o.getAsJsonObject("anim_source");
                    if (as.has("file") && as.get("file").isJsonPrimitive()) {
                        a.animSourceFile = as.get("file").getAsString();
                    }
                    if (as.has("baked") && as.get("baked").isJsonPrimitive()) {
                        a.animBaked = as.get("baked").getAsBoolean();
                    }
                }
                if (o.has("hitboxes") && o.get("hitboxes").isJsonArray()) {
                    for (var he : o.getAsJsonArray("hitboxes")) {
                        if (he.isJsonObject()) {
                            a.hitboxes.add(hitboxFromJson(he.getAsJsonObject()));
                        }
                    }
                }
                if (o.has("iframes") && o.get("iframes").isJsonObject()) {
                    a.iframes = iframesFromJson(o.getAsJsonObject("iframes"));
                }
                stringList(o, "weapons", a.weapons);
                stringList(o, "entities", a.entities);
                if (o.has("keyframes") && o.get("keyframes").isJsonArray()) {
                    for (var ke : o.getAsJsonArray("keyframes")) {
                        if (!ke.isJsonObject()) {
                            continue;
                        }
                        Keyframe kf = new Keyframe();
                        JsonObject ko = ke.getAsJsonObject();
                        if (ko.has("time") && ko.get("time").isJsonPrimitive()) {
                            kf.time = Math.max(0, ko.get("time").getAsFloat());
                        }
                        if (ko.has("bones") && ko.get("bones").isJsonObject()) {
                            for (var be : ko.getAsJsonObject("bones").entrySet()) {
                                if (!be.getValue().isJsonObject()) {
                                    continue;
                                }
                                kf.bones.put(be.getKey(), channelFromJson(be.getValue().getAsJsonObject()));
                            }
                        }
                        if (ko.has("anchor") && ko.get("anchor").isJsonObject()) {
                            JsonObject an = ko.getAsJsonObject("anchor");
                            if (an.has("bone") && an.get("bone").isJsonPrimitive()) {
                                String bn = an.get("bone").getAsString();
                                if (!bn.isEmpty()) {
                                    kf.anchorBone = bn;
                                    kf.anchorPos = floats(an, "pos");
                                }
                            }
                        }
                        a.keyframes.add(kf);
                    }
                }
                a.sort();
                out.add(a);
            }
            LOAD_FAILED.remove(key);
        } catch (RuntimeException ex) {
            LOAD_FAILED.add(key);
            com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.error(
                    "[CombatOverhaul] 动作文件解析失败（本次会话禁止保存该模型的动作，避免删掉坏条目）: {}/{}",
                    modid, entityPath, ex);
        }
        return out;
    }

    /** 读取字符串数组字段（缺失/非法按空列表，旧文件自动迁移） */
    private static void stringList(JsonObject o, String key, List<String> out) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            return;
        }
        for (var e : o.getAsJsonArray(key)) {
            if (e.isJsonPrimitive()) {
                String v = e.getAsString().trim();
                if (!v.isEmpty() && !out.contains(v)) {
                    out.add(v);
                }
            }
        }
    }

    private static BoneChannel channelFromJson(JsonObject o) {
        BoneChannel c = new BoneChannel();
        c.pos = floats(o, "pos");
        c.rotDeg = floats(o, "rot_deg");
        // floats() 对缺失的 scale 返回 {1,1,1}；显式写 0 是合法值（隐藏/塌缩），不要替换成 1
        c.scale = floats(o, "scale");
        return c;
    }

    private static Segment segmentFromJson(JsonObject o) {
        Segment s = new Segment();
        if (o.has("clip") && o.get("clip").isJsonPrimitive()) {
            s.clip = o.get("clip").getAsString();
        }
        if (o.has("zone") && o.get("zone").isJsonPrimitive()) {
            s.zone = o.get("zone").getAsString();
        }
        if (o.has("weight") && o.get("weight").isJsonPrimitive()) {
            s.weight = Mth.clamp(o.get("weight").getAsFloat(), 0f, 4f);
        }
        if (o.has("start") && o.get("start").isJsonPrimitive()) {
            s.startTick = Math.max(0f, o.get("start").getAsFloat());
        }
        if (o.has("loop") && o.get("loop").isJsonPrimitive()) {
            s.loop = o.get("loop").getAsBoolean();
        }
        if (o.has("blend") && o.get("blend").isJsonObject()) {
            s.blend = blendFromJson(o.getAsJsonObject("blend"));
        }
        if (o.has("bone_curves") && o.get("bone_curves").isJsonArray()) {
            for (var ce : o.getAsJsonArray("bone_curves")) {
                if (!ce.isJsonObject()) {
                    continue;
                }
                JsonObject co = ce.getAsJsonObject();
                BoneBlendCurve c = new BoneBlendCurve();
                if (co.has("bone") && co.get("bone").isJsonPrimitive()) {
                    c.bone = co.get("bone").getAsString();
                }
                if (co.has("include_descendants") && co.get("include_descendants").isJsonPrimitive()) {
                    c.includeDescendants = co.get("include_descendants").getAsBoolean();
                }
                c.enterTicks = floatOr(co, "enter", 0f);
                c.exitTicks = floatOr(co, "exit", 0f);
                if (co.has("interpolation") && co.get("interpolation").isJsonPrimitive()) {
                    c.interpolation = co.get("interpolation").getAsString();
                }
                if (!c.bone.isEmpty()) {
                    s.boneCurves.add(c);
                }
            }
        }
        return s;
    }

    private static Blend blendFromJson(JsonObject o) {
        Blend b = new Blend();
        b.enterTicks = Math.max(0f, floatOr(o, "enter", 0f));
        b.exitTicks = Math.max(0f, floatOr(o, "exit", 0f));
        if (o.has("interpolation") && o.get("interpolation").isJsonPrimitive()) {
            b.interpolation = o.get("interpolation").getAsString();
        }
        return b;
    }

    private static float floatOr(JsonObject o, String key, float def) {
        if (o.has(key) && o.get(key).isJsonPrimitive()) {
            try {
                return o.get(key).getAsFloat();
            } catch (RuntimeException ignored) {
            }
        }
        return def;
    }

    private static JsonObject segmentToJson(Segment s) {
        JsonObject o = new JsonObject();
        o.addProperty("clip", s.clip);
        if (s.zone != null && !s.zone.isEmpty() && !"full".equals(s.zone)) {
            o.addProperty("zone", s.zone);
        }
        o.addProperty("weight", round(s.weight));
        if (s.startTick != 0f) {
            o.addProperty("start", round(s.startTick));
        }
        if (s.loop) {
            o.addProperty("loop", true);
        }
        if (s.blend != null && (s.blend.enterTicks > 0f || s.blend.exitTicks > 0f
                || !"smooth".equals(s.blend.interpolation))) {
            JsonObject b = new JsonObject();
            b.addProperty("enter", round(s.blend.enterTicks));
            b.addProperty("exit", round(s.blend.exitTicks));
            if (!"smooth".equals(s.blend.interpolation)) {
                b.addProperty("interpolation", s.blend.interpolation);
            }
            o.add("blend", b);
        }
        if (!s.boneCurves.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (BoneBlendCurve c : s.boneCurves) {
                JsonObject co = new JsonObject();
                co.addProperty("bone", c.bone);
                if (!c.includeDescendants) {
                    co.addProperty("include_descendants", false);
                }
                co.addProperty("enter", round(c.enterTicks));
                co.addProperty("exit", round(c.exitTicks));
                if (!"smooth".equals(c.interpolation)) {
                    co.addProperty("interpolation", c.interpolation);
                }
                arr.add(co);
            }
            o.add("bone_curves", arr);
        }
        return o;
    }

    private static float[] floats(JsonObject o, String key) {
        float[] out = key.equals("scale") ? new float[]{1, 1, 1} : new float[3];
        if (o.has(key) && o.get(key).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(key);
            for (int i = 0; i < 3 && i < a.size(); i++) {
                try {
                    out[i] = a.get(i).getAsFloat();
                } catch (RuntimeException ignored) {
                }
            }
        }
        return out;
    }

    /**
     * 保存全部动作。
     *
     * <p>动作存放在模型 JSON 文件内（与 roots/texture/entity 等字段共存），
     * 因此必须先读取原文件、仅替换 "actions" 数组合并写回；
     * 直接新建 JsonObject 覆盖会抹掉模型几何与纹理字段。
     */
    public static boolean save(String modid, String entityPath, boolean weapon, List<Action> actions) {
        if (actions == null) {
            return false;
        }
        String key = loadKey(modid, entityPath, weapon);
        if (LOAD_FAILED.contains(key)) {
            com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.error(
                    "[CombatOverhaul] 拒绝保存动作：上次读取 {}/{} 时 JSON 解析失败，先修复文件再保存",
                    modid, entityPath);
            return false;
        }
        JsonObject json = null;
        String existing = weapon ? ModelStore.readWeapon(modid, entityPath) : ModelStore.read(modid, entityPath);
        boolean fileExists = weapon ? ModelStore.weaponExists(modid, entityPath) : ModelStore.exists(modid, entityPath);
        if (fileExists) {
            // 文件存在但读不出来 / 不是合法 JSON 对象：绝不能新建一个只有 actions 的对象覆盖它，
            // 否则会把几何、纹理、bone_aliases 等全部抹掉。宁可保存失败，让用户先修复 JSON。
            if (existing == null) {
                com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.error(
                        "[CombatOverhaul] 拒绝保存动作：模型文件存在但读取失败 {}/{}", modid, entityPath);
                return false;
            }
            try {
                json = JsonParser.parseString(existing).getAsJsonObject().deepCopy();
            } catch (RuntimeException ex) {
                com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.error(
                        "[CombatOverhaul] 拒绝保存动作：模型 JSON 解析失败，避免覆盖几何 {}/{}", modid, entityPath, ex);
                return false;
            }
        }
        if (json == null) {
            json = new JsonObject();
        }
        if (!json.has("format")) {
            json.addProperty("format", FORMAT_VERSION);
        }
        JsonArray arr = new JsonArray();
        for (Action a : actions) {
            JsonObject o = new JsonObject();
            o.addProperty("name", a.name);
            o.addProperty("lengthTicks", round(a.lengthTicks));
            o.addProperty("loop", a.loop);
            if (a.zone != null && !a.zone.isEmpty() && !"full".equals(a.zone)) {
                o.addProperty("zone", a.zone);
            }
            if (a.internal) {
                o.addProperty("internal", true);
            }
            if (!a.segments.isEmpty()) {
                JsonArray segs = new JsonArray();
                for (Segment s : a.segments) {
                    segs.add(segmentToJson(s));
                }
                o.add("segments", segs);
            }
            if (a.cooldownTicks > 0) {
                o.addProperty("cooldownTicks", a.cooldownTicks);
            }
            if (a.trackPitch) {
                o.addProperty("track_pitch", true);
                o.addProperty("track_max_pitch", round(a.maxPitchOffset));
            }
            if (!a.animSourceFile.isEmpty()) {
                JsonObject as = new JsonObject();
                as.addProperty("file", a.animSourceFile);
                as.addProperty("baked", a.animBaked);
                o.add("anim_source", as);
            }
            if (!a.hitboxes.isEmpty()) {
                JsonArray hbs = new JsonArray();
                for (HitboxDef h : a.hitboxes) {
                    JsonObject ho = new JsonObject();
                    ho.addProperty("name", h.name);
                    if (KIND_WEAPON.equals(h.kind)) {
                        ho.addProperty("kind", KIND_WEAPON);
                    }
                    ho.addProperty("bone", h.bone);
                    if (!h.anchorBone.isEmpty()) {
                        ho.addProperty("anchorBone", h.anchorBone);
                    }
                    ho.add("size", arr(h.size));
                    ho.add("offset", arr(h.offset));
                    ho.addProperty("startTick", round(h.startTick));
                    ho.addProperty("endTick", round(h.endTick));
                    if (!h.multipliers.isEmpty()) {
                        JsonObject mo = new JsonObject();
                        for (Map.Entry<String, Float> me : h.multipliers.entrySet()) {
                            mo.addProperty(me.getKey(), round(me.getValue()));
                        }
                        ho.add("multipliers", mo);
                    }
                    if (h.maxTargets > 0) {
                        ho.addProperty("maxTargets", h.maxTargets);
                    }
                    if (h.extraKnockback != 0) {
                        ho.addProperty("extraKnockback", round(h.extraKnockback));
                    }
                    if (h.damageScale != 1.0f) {
                        ho.addProperty("damageScale", round(h.damageScale));
                    }
                    if (h.autoGeom) {
                        ho.addProperty("auto_geom", true);
                    }
                    hbs.add(ho);
                }
                o.add("hitboxes", hbs);
            }
            if (a.iframes != null) {
                JsonObject io = new JsonObject();
                io.addProperty("startTick", round(a.iframes.startTick));
                io.addProperty("endTick", round(a.iframes.endTick));
                io.addProperty("fullImmunity", a.iframes.fullImmunity);
                io.addProperty("blockCustomHitboxes", a.iframes.blockCustomHitboxes);
                o.add("iframes", io);
            }
            if (!a.weapons.isEmpty()) {
                JsonArray wa = new JsonArray();
                for (String w : a.weapons) {
                    wa.add(w);
                }
                o.add("weapons", wa);
            }
            if (!a.entities.isEmpty()) {
                JsonArray ea = new JsonArray();
                for (String en : a.entities) {
                    ea.add(en);
                }
                o.add("entities", ea);
            }
            JsonArray kfs = new JsonArray();
            for (Keyframe kf : a.keyframes) {
                if (kf.isEmpty()) {
                    continue;
                }
                JsonObject ko = new JsonObject();
                ko.addProperty("time", round(kf.time));
                JsonObject bo = new JsonObject();
                for (Map.Entry<String, BoneChannel> e : kf.bones.entrySet()) {
                    BoneChannel c = e.getValue();
                    JsonObject ch = new JsonObject();
                    ch.add("pos", arr(c.pos));
                    ch.add("rot_deg", arr(c.rotDeg));
                    ch.add("scale", arr(c.scale));
                    bo.add(e.getKey(), ch);
                }
                ko.add("bones", bo);
                if (kf.anchorBone != null && kf.anchorPos != null) {
                    JsonObject an = new JsonObject();
                    an.addProperty("bone", kf.anchorBone);
                    an.add("pos", arr(kf.anchorPos));
                    ko.add("anchor", an);
                }
                kfs.add(ko);
            }
            o.add("keyframes", kfs);
            arr.add(o);
        }
        json.add("actions", arr);
        String content = GSON.toJson(json);
        return weapon ? ModelStore.writeWeapon(modid, entityPath, content)
                : ModelStore.write(modid, entityPath, content);
    }

    private static HitboxDef hitboxFromJson(JsonObject o) {
        HitboxDef h = new HitboxDef();
        if (o.has("name") && o.get("name").isJsonPrimitive()) {
            h.name = o.get("name").getAsString();
        }
        if (o.has("kind") && o.get("kind").isJsonPrimitive()
                && KIND_WEAPON.equals(o.get("kind").getAsString())) {
            h.kind = KIND_WEAPON;
        }
        // 旧 autoBounds 键读取时忽略（写回自动丢弃；尺寸/偏移全部手动）
        if (o.has("bone") && o.get("bone").isJsonPrimitive()) {
            h.bone = o.get("bone").getAsString();
        }
        if (o.has("anchorBone") && o.get("anchorBone").isJsonPrimitive()) {
            h.anchorBone = o.get("anchorBone").getAsString();
        }
        float[] s = floats(o, "size");
        if (o.has("size") && o.get("size").isJsonArray()) {
            h.size = s;
        }
        float[] off = floats(o, "offset");
        if (o.has("offset") && o.get("offset").isJsonArray()) {
            h.offset = off;
        }
        if (o.has("startTick") && o.get("startTick").isJsonPrimitive()) {
            h.startTick = Math.max(0, o.get("startTick").getAsFloat());
        }
        if (o.has("endTick") && o.get("endTick").isJsonPrimitive()) {
            h.endTick = Math.max(0, o.get("endTick").getAsFloat());
        }
        if (o.has("multipliers") && o.get("multipliers").isJsonObject()) {
            for (var e : o.getAsJsonObject("multipliers").entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    try {
                        h.multipliers.put(e.getKey(), e.getValue().getAsFloat());
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
        if (o.has("maxTargets") && o.get("maxTargets").isJsonPrimitive()) {
            h.maxTargets = Math.max(0, o.get("maxTargets").getAsInt());
        }
        if (o.has("extraKnockback") && o.get("extraKnockback").isJsonPrimitive()) {
            h.extraKnockback = Math.max(0, o.get("extraKnockback").getAsFloat());
        }
        if (o.has("damageScale") && o.get("damageScale").isJsonPrimitive()) {
            // 倍率 <=0 = 该判定框不造成伤害（运行时跳过伤害结算），不再钳成 1
            h.damageScale = Math.max(0f, o.get("damageScale").getAsFloat());
        }
        if (o.has("auto_geom") && o.get("auto_geom").isJsonPrimitive()) {
            h.autoGeom = o.get("auto_geom").getAsBoolean();
        }
        return h;
    }

    private static IframesDef iframesFromJson(JsonObject o) {
        IframesDef f = new IframesDef();
        if (o.has("startTick") && o.get("startTick").isJsonPrimitive()) {
            f.startTick = Math.max(0, o.get("startTick").getAsFloat());
        }
        if (o.has("endTick") && o.get("endTick").isJsonPrimitive()) {
            f.endTick = Math.max(0, o.get("endTick").getAsFloat());
        }
        if (o.has("fullImmunity") && o.get("fullImmunity").isJsonPrimitive()) {
            f.fullImmunity = o.get("fullImmunity").getAsBoolean();
        }
        if (o.has("blockCustomHitboxes") && o.get("blockCustomHitboxes").isJsonPrimitive()) {
            f.blockCustomHitboxes = o.get("blockCustomHitboxes").getAsBoolean();
        }
        return f;
    }

    private static float round(float v) {
        return Math.round(v * 100f) / 100f;
    }

    private static JsonArray arr(float... values) {
        JsonArray a = new JsonArray();
        for (float v : values) {
            a.add(round(v));
        }
        return a;
    }

    // ------------------------------------------------------------------ 播放

    /**
     * 把动作在 tick 时刻的姿态写入模型骨骼的动画叠加状态。
     * 单动作直接采样；组合动作按 segments 的 zone/weight/blend 合成。
     */
    public static void apply(List<Action> actions, int actionIndex, float tick, ParsedModel model) {
        for (ParsedBone b : model.bones) {
            b.resetAnim();
        }
        if (actionIndex < 0 || actionIndex >= actions.size()) {
            return;
        }
        Action a = actions.get(actionIndex);
        if (a.segments.isEmpty()) {
            if (a.keyframes.isEmpty()) {
                return;
            }
            Map<String, BoneChannel> channels = sampleKeyframes(a, normalizeTime(a, tick));
            canonicalize(channels, model);
            applyZoneMask(channels, a.zone, model);
            writeChannels(model, channels);
            return;
        }
        Map<String, BoneChannel> composed = compose(a, actions, normalizeTime(a, tick), model,
                new java.util.HashSet<>());
        canonicalize(composed, model);
        applyZoneMask(composed, a.zone, model);
        writeChannels(model, composed);
    }

    /** 两个动作的局部姿态按 alpha 混合；alpha=0 全为 from，alpha=1 全为 to。 */
    public static void applyBlend(List<Action> actions, int fromIndex, float fromTick,
                                  int toIndex, float toTick, float alpha, ParsedModel model) {
        if (actions == null || model == null || fromIndex < 0 || toIndex < 0
                || fromIndex >= actions.size() || toIndex >= actions.size()) {
            return;
        }
        for (ParsedBone b : model.bones) {
            b.resetAnim();
        }
        float a = Math.max(0f, Math.min(1f, alpha));
        Action fromAction = actions.get(fromIndex);
        Action toAction = actions.get(toIndex);
        Map<String, BoneChannel> from = sampleKeyframes(fromAction,
                normalizeTime(fromAction, fromTick));
        Map<String, BoneChannel> to = sampleKeyframes(toAction,
                normalizeTime(toAction, toTick));
        canonicalize(from, model);
        canonicalize(to, model);
        Map<String, BoneChannel> out = new LinkedHashMap<>();
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(from.keySet());
        keys.addAll(to.keySet());
        for (String key : keys) {
            BoneChannel x = from.get(key);
            BoneChannel y = to.get(key);
            if (x == null) {
                out.put(key, BoneChannel.copy(y));
                continue;
            }
            if (y == null) {
                out.put(key, BoneChannel.copy(x));
                continue;
            }
            BoneChannel n = new BoneChannel();
            for (int i = 0; i < 3; i++) {
                n.pos[i] = x.pos[i] + (y.pos[i] - x.pos[i]) * a;
                n.rotDeg[i] = x.rotDeg[i] + (y.rotDeg[i] - x.rotDeg[i]) * a;
                n.scale[i] = x.scale[i] + (y.scale[i] - x.scale[i]) * a;
            }
            out.put(key, n);
        }
        writeChannels(model, out);
    }

    private static float normalizeTime(Action a, float tick) {
        float length = Math.max(1, a.lengthTicks);
        return a.loop ? ((tick % length) + length) % length : Math.max(0, Math.min(tick, length));
    }

    /** 组合动作：按 segment 采样子 action，再按 zone mask / weight / blend 合成。 */
    private static Map<String, BoneChannel> compose(Action a, List<Action> actions, float tick,
                                                   ParsedModel model, java.util.Set<String> stack) {
        Map<String, BoneChannel> out = new LinkedHashMap<>();
        if (a == null || !stack.add(a.name)) {
            return out;
        }
        boolean hasFull = false;
        for (Segment s : a.segments) {
            Action child = findAction(actions, s.clip);
            if (child == null) {
                continue;
            }
            String zone = effectiveZone(s, child);
            if (s.weight > 0f && isFullZone(zone)) {
                hasFull = true;
                break;
            }
        }
        Map<String, Accumulator> acc = new LinkedHashMap<>();
        for (Segment s : a.segments) {
            Action child = findAction(actions, s.clip);
            if (child == null) {
                continue;
            }
            String zone = effectiveZone(s, child);
            if (s.weight <= 0f || (hasFull && !isFullZone(zone))) {
                continue;
            }
            float local = tick - s.startTick;
            if (local < 0f) {
                continue;
            }
            float length = Math.max(1f, child.lengthTicks);
            boolean loop = s.loop || child.loop;
            if (loop) {
                local = ((local % length) + length) % length;
            } else {
                local = Math.min(local, length);
            }
            float baseWeight = s.weight * (s.blend == null ? 1f : s.blend.factor(local, length));
            if (baseWeight <= 0f) {
                continue;
            }
            Map<String, BoneChannel> childPose;
            if (child.segments.isEmpty()) {
                childPose = sampleKeyframes(child, local);
            } else {
                childPose = compose(child, actions, local, model, stack);
            }
            canonicalize(childPose, model);
            applyZoneMask(childPose, zone, model);
            for (Map.Entry<String, BoneChannel> e : childPose.entrySet()) {
                float weight = baseWeight * boneCurveFactor(s, e.getKey(), local, length, model);
                if (weight <= 0f) {
                    continue;
                }
                acc.computeIfAbsent(e.getKey(), k -> new Accumulator()).add(e.getValue(), weight);
            }
        }
        stack.remove(a.name);
        for (Map.Entry<String, Accumulator> e : acc.entrySet()) {
            out.put(e.getKey(), e.getValue().finish());
        }
        return out;
    }

    private static Action findAction(List<Action> actions, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (Action a : actions) {
            if (a.name.equals(name)) {
                return a;
            }
        }
        return null;
    }

    /** 关键帧里可能写 alias（left_arm）；先映射到模型真实骨骼名，zone mask 才能命中。 */
    private static void canonicalize(Map<String, BoneChannel> channels, ParsedModel model) {
        if (channels.isEmpty()) {
            return;
        }
        Map<String, BoneChannel> canonical = new LinkedHashMap<>();
        for (Map.Entry<String, BoneChannel> e : channels.entrySet()) {
            ParsedBone bone = model.findBone(e.getKey());
            canonical.put(bone == null ? e.getKey() : bone.name, e.getValue());
        }
        channels.clear();
        channels.putAll(canonical);
    }

    private static boolean isFullZone(String zone) {
        return zone == null || zone.isEmpty() || "full".equalsIgnoreCase(zone);
    }

    private static String effectiveZone(Segment s, Action child) {
        if (s.zone != null && !s.zone.isEmpty()) {
            return s.zone;
        }
        return child == null || child.zone == null ? "full" : child.zone;
    }

    private static void applyZoneMask(Map<String, BoneChannel> channels, String zone, ParsedModel model) {
        if (isFullZone(zone)) {
            return;
        }
        java.util.Set<String> allowed = model.zoneBones(zone);
        channels.keySet().removeIf(bone -> !allowed.contains(bone));
    }

    private static float boneCurveFactor(Segment s, String boneName, float local, float length,
                                         ParsedModel model) {
        if (s.boneCurves.isEmpty()) {
            return 1f;
        }
        ParsedBone bone = model.findBone(boneName);
        if (bone == null) {
            return 1f;
        }
        float factor = 1f;
        for (BoneBlendCurve c : s.boneCurves) {
            ParsedBone target = model.findBone(c.bone);
            if (target == null || !isSameOrDescendant(bone, target, c.includeDescendants)) {
                continue;
            }
            float f = blendFactor(c.enterTicks, c.exitTicks, c.interpolation, local, length);
            factor *= f;
        }
        return factor;
    }

    private static boolean isSameOrDescendant(ParsedBone bone, ParsedBone target, boolean includeDescendants) {
        if (bone == target) {
            return true;
        }
        if (!includeDescendants) {
            return false;
        }
        for (ParsedBone p = bone.parent; p != null; p = p.parent) {
            if (p == target) {
                return true;
            }
        }
        return false;
    }

    private static float blendFactor(float enter, float exit, String interpolation, float local, float length) {
        Blend b = new Blend();
        b.enterTicks = Math.max(0f, enter);
        b.exitTicks = Math.max(0f, exit);
        b.interpolation = interpolation;
        return b.factor(local, length);
    }

    /** 单 action 的关键帧采样（不含 zone/segment）；返回骨骼局部通道。 */
    private static Map<String, BoneChannel> sampleKeyframes(Action a, float tick) {
        Map<String, BoneChannel> out = new LinkedHashMap<>();
        if (a == null || a.keyframes.isEmpty()) {
            return out;
        }
        float length = Math.max(1, a.lengthTicks);
        float t = a.loop ? ((tick % length) + length) % length : Math.max(0, Math.min(tick, length));

        // 骨骼名 -> (时间, 通道) 有序时间线
        Map<String, TreeMap<Float, BoneChannel>> timelines = new LinkedHashMap<>();
        for (Keyframe kf : a.keyframes) {
            for (Map.Entry<String, BoneChannel> e : kf.bones.entrySet()) {
                timelines.computeIfAbsent(e.getKey(), k -> new TreeMap<>()).put(kf.time, e.getValue());
            }
        }

        for (Map.Entry<String, TreeMap<Float, BoneChannel>> entry : timelines.entrySet()) {
            TreeMap<Float, BoneChannel> tl = entry.getValue();
            BoneChannel result;
            Map.Entry<Float, BoneChannel> floor = tl.floorEntry(t);
            Map.Entry<Float, BoneChannel> ceiling = tl.ceilingEntry(t);
            Map.Entry<Float, BoneChannel> first = tl.firstEntry();
            Map.Entry<Float, BoneChannel> last = tl.lastEntry();

            if (floor == null) {
                result = ceiling.getValue();
            } else if (ceiling == null || floor.getKey().equals(ceiling.getKey())) {
                // t >= 最后关键帧：循环时从最后帧平滑插回第一帧
                if (a.loop && floor == last && first != last && t > first.getKey()) {
                    float nextT = first.getKey() + length;
                    float f = (t - floor.getKey()) / (nextT - floor.getKey());
                    result = BoneChannel.lerp(floor.getValue(), first.getValue(), f);
                } else {
                    result = floor.getValue();
                }
            } else {
                float f = (t - floor.getKey()) / (ceiling.getKey() - floor.getKey());
                result = BoneChannel.lerp(floor.getValue(), ceiling.getValue(), f);
            }
            out.put(entry.getKey(), result);
        }
        return out;
    }

    /** 重叠区加权平均；权重来自 segment.weight × blend × 逐骨骼 curve。 */
    private static final class Accumulator {
        double weight;
        double[] pos = new double[3];
        double[] rot = new double[3];
        double[] scale = new double[3];

        void add(BoneChannel c, float w) {
            weight += w;
            for (int i = 0; i < 3; i++) {
                pos[i] += c.pos[i] * (double) w;
                rot[i] += c.rotDeg[i] * (double) w;
                scale[i] += c.scale[i] * (double) w;
            }
        }

        BoneChannel finish() {
            BoneChannel c = new BoneChannel();
            if (weight <= 1.0e-9) {
                return c;
            }
            for (int i = 0; i < 3; i++) {
                c.pos[i] = (float) (pos[i] / weight);
                c.rotDeg[i] = (float) (rot[i] / weight);
                c.scale[i] = (float) (scale[i] / weight);
            }
            return c;
        }
    }

    private static void writeChannels(ParsedModel model, Map<String, BoneChannel> channels) {
        for (Map.Entry<String, BoneChannel> e : channels.entrySet()) {
            ParsedBone bone = model.findBone(e.getKey());
            if (bone == null) {
                continue;
            }
            BoneChannel result = e.getValue();
            bone.animPosX = result.pos[0];
            bone.animPosY = result.pos[1];
            bone.animPosZ = result.pos[2];
            bone.animRotX = (float) Math.toRadians(result.rotDeg[0]);
            bone.animRotY = (float) Math.toRadians(result.rotDeg[1]);
            bone.animRotZ = (float) Math.toRadians(result.rotDeg[2]);
            bone.animScaleX = result.scale[0];
            bone.animScaleY = result.scale[1];
            bone.animScaleZ = result.scale[2];
        }
    }

    // ------------------------------------------------------------------ 锚点（不动点）位移反推

    /**
     * 计算 tick 时刻的锚点位移：{@code 锚点目标位置 - 锚骨骼当前世界位置}（模型单位/像素）。
     *
     * <p>规则：取 t 前后最近的两个带锚点的关键帧；同骨骼则锚点位置线性插值（锚点滑动产生位移），
     * 不同骨骼则沿用前一帧锚点；仅一侧有锚点则取该侧。循环动作跨尾首帧插值。
     *
     * <p>调用前必须先 {@link #apply} 同一时刻的姿态（骨骼动画叠加状态参与世界位置计算）。
     *
     * @return float[6] = {offX, offY, offZ, targetX, targetY, targetZ}；无锚点/骨骼不存在返回 null
     */
    public static float[] anchorInfo(List<Action> actions, int actionIndex, float tick, ParsedModel model) {
        if (actionIndex < 0 || actionIndex >= actions.size()) {
            return null;
        }
        Action a = actions.get(actionIndex);
        float length = Math.max(1, a.lengthTicks);
        float t = a.loop ? ((tick % length) + length) % length : Math.max(0, Math.min(tick, length));

        Keyframe prev = null, next = null;
        Keyframe firstAnchor = null, lastAnchor = null;
        for (Keyframe kf : a.keyframes) {
            if (kf.anchorBone == null || kf.anchorPos == null) {
                continue;
            }
            if (firstAnchor == null) {
                firstAnchor = kf;
            }
            lastAnchor = kf;
            if (kf.time <= t + 0.0001f) {
                prev = kf;
            } else if (next == null) {
                next = kf;
            }
        }
        // 循环：尾→首跨段插值
        float prevTime, nextTime;
        if (prev == null && a.loop && lastAnchor != null) {
            prev = lastAnchor;
            prevTime = lastAnchor.time - length;
        } else {
            prevTime = prev != null ? prev.time : 0;
        }
        if (next == null && a.loop && firstAnchor != null) {
            next = firstAnchor;
            nextTime = firstAnchor.time + length;
        } else {
            nextTime = next != null ? next.time : 0;
        }

        String boneName;
        float[] target;
        if (prev == null && next == null) {
            return null;
        } else if (prev == null) {
            boneName = next.anchorBone;
            target = next.anchorPos;
        } else if (next == null) {
            boneName = prev.anchorBone;
            target = prev.anchorPos;
        } else if (prev == next || !prev.anchorBone.equals(next.anchorBone)) {
            // 锚骨骼切换：沿用前一帧（换足点瞬间跳变，与步态语义一致）
            boneName = prev.anchorBone;
            target = prev.anchorPos;
        } else {
            float span = nextTime - prevTime;
            float f = span > 0.0001f ? (t - prevTime) / span : 0;
            boneName = prev.anchorBone;
            target = new float[3];
            for (int i = 0; i < 3; i++) {
                target[i] = prev.anchorPos[i] + (next.anchorPos[i] - prev.anchorPos[i]) * f;
            }
        }

        float[] world = model.boneWorldPos(boneName);
        if (world == null) {
            return null;
        }
        return new float[]{
                target[0] - world[0], target[1] - world[1], target[2] - world[2],
                target[0], target[1], target[2]};
    }
}
