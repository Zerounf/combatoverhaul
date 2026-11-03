package com.yourname.combatoverhaul.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.combatoverhaul.CombatOverhaulMod;
import com.yourname.combatoverhaul.combat.ActionCache;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Blockbench / GeckoLib 动画导入：{@code .animation.json}（Bedrock 动画格式）→ 本模组动作。
 *
 * <p>设计要点：
 *
 * <ul>
 *   <li><b>只写关键帧与时长</b>：合并进模型文件的 {@code actions} 段时，只替换同名动作的
 *       {@code keyframes} / {@code lengthTicks} / {@code loop}，判定框、无敌帧、冷却、武器与
 *       生物绑定等战斗字段原样保留（Blockbench 管动画，游戏内管战斗，互不覆盖）。</li>
 *   <li><b>烘焙</b>：Bedrock 的 {@code easing}（linear / catmullrom / step）本模组运行时不支持，
 *       线性段直接按关键帧采样，出现非线性缓动时按 20fps 逐 tick 采样折算，保证与 Blockbench
 *       预览一致。</li>
 *   <li><b>坐标空间</b>：编辑器空间 Y 朝下、Bedrock 空间 Y 朝上，两者差一个镜面反射
 *       （{@code y_editor = 24 − y_bedrock}，与 {@link com.yourname.combatoverhaul.client.model.GeckoModelLoader}
 *       一致）。反射会把绕 X / Z 的旋转取反、位移 Y 取反；{@code flipSpace} 控制是否做这步补偿。</li>
 * </ul>
 */
public final class AnimImporter {

    /** Bedrock 动画是秒，本模组是 tick */
    public static final float TICKS_PER_SECOND = 20f;

    private AnimImporter() {
    }

    /** 导入结果 */
    public static final class Result {
        public boolean ok;
        public String error;
        public final List<String> imported = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public boolean baked;
    }

    /** 动画文件目录（可在 combat_actions.json 的 anim_dir 配置，支持绝对路径） */
    public static Path animDir() {
        return ModelStore.resolveConfigured(
                com.yourname.combatoverhaul.combat.CombatConfig.get().animDir,
                "config/combatoverhaul/anim");
    }

    /** 列出可导入的动画文件名（*.json，不含子目录）；目录不存在时自动创建 */
    public static List<String> listFiles() {
        Path dir = animDir();
        try {
            // 目录被用户删掉时自动重建，避免“找不到入口”
            Files.createDirectories(dir);
        } catch (IOException e) {
            CombatOverhaulMod.LOGGER.warn("[CombatOverhaul] 无法创建动画目录 {}", dir, e);
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
            CombatOverhaulMod.LOGGER.error("[CombatOverhaul] 读取动画目录失败", e);
            return List.of();
        }
    }

    /**
     * 导入一个动画文件到指定模型。
     *
     * @param modid     模型命名空间（如 minecraft）
     * @param entityPath 模型路径（如 player）
     * @param weapon    是否为武器模型（weapons/ 下）
     * @param fileName  config/combatoverhaul/anim/ 下的文件名
     * @param flipSpace 是否做 Y 轴镜面补偿（非线性空间的正确换算）
     */
    public static Result importFile(String modid, String entityPath, boolean weapon,
                                    String fileName, boolean flipSpace) {
        Result r = new Result();
        Path dir = animDir().normalize();
        Path animFile = dir.resolve(fileName).normalize();
        if (!animFile.startsWith(dir)) {
            r.error = "动画文件名非法（不能使用绝对路径或 .. 跳出动画目录）: " + fileName;
            return r;
        }
        // 允许省略扩展名：dodge_forward_light -> dodge_forward_light.animation.json / .json
        if (!Files.isRegularFile(animFile) && !fileName.toLowerCase().endsWith(".json")) {
            Path withExt = dir.resolve(fileName + ".animation.json").normalize();
            if (!withExt.startsWith(dir)) {
                r.error = "动画文件名非法: " + fileName;
                return r;
            }
            if (Files.isRegularFile(withExt)) {
                animFile = withExt;
            } else {
                withExt = dir.resolve(fileName + ".json").normalize();
                if (!withExt.startsWith(dir)) {
                    r.error = "动画文件名非法: " + fileName;
                    return r;
                }
                if (Files.isRegularFile(withExt)) {
                    animFile = withExt;
                }
            }
        }
        if (!Files.isRegularFile(animFile)) {
            r.error = "找不到动画文件: " + fileName + "（应放在 " + animDir() + "）";
            return r;
        }
        String text;
        try {
            text = Files.readString(animFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            r.error = "读取失败: " + e.getMessage();
            return r;
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            r.error = "JSON 解析失败: " + e.getMessage();
            return r;
        }

        if (!root.has("animations") || !root.get("animations").isJsonObject()) {
            r.error = "不是 Bedrock 动画文件（缺少 animations 段）";
            return r;
        }

        // 目标模型必须已导出：否则 save() 会写出一份只有 actions、没有几何的空模型
        String modelText = weapon
                ? ModelStore.readWeapon(modid, entityPath)
                : ModelStore.read(modid, entityPath);
        if (modelText == null) {
            r.error = "目标模型还没导出过：" + modid + ":" + entityPath + "（先在编辑器里导出该实体模型）";
            return r;
        }
        Set<String> modelBones = collectBoneNames(modelText);

        // 解析全部动画
        JsonObject anims = root.getAsJsonObject("animations");
        List<ActionStore.Action> parsed = new ArrayList<>();
        boolean anyBaked = false;
        // 人形语义骨骼名 → 本模型真实骨骼名（同一套人形动画可复用到任意人形生物的关键）
        Map<String, String> aliasToReal = readAliasToReal(modelText);

        for (Map.Entry<String, JsonElement> e : anims.entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            ActionStore.Action a = parseAnimation(e.getKey(), e.getValue().getAsJsonObject(),
                    flipSpace, r, aliasToReal);
            if (a == null) {
                continue;
            }
            a.animSourceFile = fileName;
            // 骨架上的动作默认授权给所有人形生物（运行时按各模型 bone_aliases 解析语义骨骼名）
            if (HumanoidRig.isRig(modid, entityPath)
                    && !a.entities.contains(ActionCache.WILDCARD_ENTITY)) {
                a.entities.add(ActionCache.WILDCARD_ENTITY);
            }
            parsed.add(a);
            anyBaked |= a.animBaked;
        }
        if (parsed.isEmpty()) {
            r.error = "文件里没有可导入的动画（检查 bones 段）";
            return r;
        }

        // 骨骼名对不上就会全程静默无效果，这里必须提示
        Set<String> missing = new LinkedHashSet<>();
        for (ActionStore.Action a : parsed) {
            for (ActionStore.Keyframe kf : a.keyframes) {
                for (String bone : kf.bones.keySet()) {
                    if (!modelBones.contains(bone)) {
                        missing.add(bone);
                    }
                }
            }
        }
        if (!missing.isEmpty()) {
            r.warnings.add("这些骨骼在模型里不存在，对应通道不会生效：" + String.join(", ", missing)
                    + "（人形模型可直接用 head / right_arm 等通用名，导入时按模型的 bone_aliases 自动映射；"
                    + "若模型是旧文件没有该表，删掉 config/combatoverhaul/entities 后重新扫描一次）");
        }
        r.baked = anyBaked;

        // 与模型现有动作合并：同名替换关键帧/时长，战斗字段保留
        List<ActionStore.Action> actions = ActionStore.load(modid, entityPath, weapon);
        for (ActionStore.Action incoming : parsed) {
            ActionStore.Action target = null;
            for (ActionStore.Action exist : actions) {
                if (exist.name.equals(incoming.name)) {
                    target = exist;
                    break;
                }
            }
            if (target == null) {
                actions.add(incoming);
                r.imported.add(incoming.name + "（新建，" + fmt(incoming.lengthTicks) + " tick）");
                continue;
            }
            // 判定框窗口超出新长度时告警（不改用户的战斗配置，只提示）
            for (ActionStore.HitboxDef hb : target.hitboxes) {
                if (hb.endTick > incoming.lengthTicks + 0.01f) {
                    r.warnings.add("动作 " + target.name + " 的判定框 " + hb.name
                            + " 结束 tick " + fmt(hb.endTick) + " 超过新时长 "
                            + fmt(incoming.lengthTicks) + "，可能不再触发");
                }
            }
            if (target.iframes != null && target.iframes.endTick > incoming.lengthTicks + 0.01f) {
                r.warnings.add("动作 " + target.name + " 的无敌帧结束 tick 超过新时长");
            }
            target.lengthTicks = incoming.lengthTicks;
            if (HumanoidRig.isRig(modid, entityPath)
                    && !target.entities.contains(ActionCache.WILDCARD_ENTITY)) {
                target.entities.add(ActionCache.WILDCARD_ENTITY);
            }
            target.loop = incoming.loop;
            target.keyframes.clear();
            target.keyframes.addAll(incoming.keyframes);
            target.animSourceFile = incoming.animSourceFile;
            target.animBaked = incoming.animBaked;
            r.imported.add(target.name + "（更新，" + fmt(target.lengthTicks) + " tick，保留战斗数据）");
        }

        if (!ActionStore.save(modid, entityPath, weapon, actions)) {
            r.error = "写入模型文件失败";
            return r;
        }
        ActionCache.invalidate(modid, entityPath, weapon);
        // 动作名/列表变了：下次 CombatConfig.get() 重新加载（翻滚槽按名字查找、技能表同步）
        com.yourname.combatoverhaul.combat.CombatConfig.invalidate();
        r.ok = true;
        return r;
    }

    private static String fmt(float v) {
        return String.valueOf(Math.round(v * 100f) / 100f);
    }

    /** 读模型的 bone_aliases 并转成「语义名 → 真实名」 */
    private static Map<String, String> readAliasToReal(String modelText) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            JsonObject root = JsonParser.parseString(modelText).getAsJsonObject();
            if (root.has("bone_aliases") && root.get("bone_aliases").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("bone_aliases").entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        out.putIfAbsent(e.getValue().getAsString(), e.getKey());
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    /** 从模型 JSON 的 roots/children 收集骨骼名（不依赖 ParsedModel，避免把客户端类拖进 store 包） */
    private static Set<String> collectBoneNames(String modelText) {
        Set<String> names = new LinkedHashSet<>();
        try {
            JsonObject root = JsonParser.parseString(modelText).getAsJsonObject();
            if (root.has("roots") && root.get("roots").isJsonObject()) {
                walkBones(root.getAsJsonObject("roots"), names);
            }
        } catch (RuntimeException ignored) {
        }
        return names;
    }

    private static void walkBones(JsonObject nodes, Set<String> out) {
        for (Map.Entry<String, JsonElement> e : nodes.entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject o = e.getValue().getAsJsonObject();
            out.add(e.getKey());
            if (o.has("name") && o.get("name").isJsonPrimitive()) {
                out.add(o.get("name").getAsString());
            }
            if (o.has("children") && o.get("children").isJsonObject()) {
                walkBones(o.getAsJsonObject("children"), out);
            }
        }
    }

    // ------------------------------------------------------------------ 解析

    /** 一条采样：时间（秒）+ 三分量值 + 该段缓动 */
    private static final class Sample {
        float time;
        float[] v;
        String easing = "linear";
    }

    private static ActionStore.Action parseAnimation(String key, JsonObject o, boolean flip, Result r,
                                                     Map<String, String> aliasToReal) {
        String name = key.startsWith("animation.") ? key.substring("animation.".length()) : key;
        if (name.isEmpty()) {
            return null;
        }
        if (!o.has("bones") || !o.get("bones").isJsonObject()) {
            r.warnings.add(name + "：没有 bones 段，跳过");
            return null;
        }

        ActionStore.Action a = new ActionStore.Action();
        a.name = name;
        a.loop = readLoop(o.get("loop"));
        float lengthSec = o.has("animation_length") && o.get("animation_length").isJsonPrimitive()
                ? o.get("animation_length").getAsFloat() : 0f;

        JsonObject bones = o.getAsJsonObject("bones");
        // 骨骼 → 通道类型 → 采样序列
        Map<String, Map<String, List<Sample>>> data = new LinkedHashMap<>();
        Set<Float> keyTimes = new LinkedHashSet<>();

        for (Map.Entry<String, JsonElement> be : bones.entrySet()) {
            // head / right_arm 等通用语义名 → 本模型的真实骨骼名
            String boneName = aliasToReal.getOrDefault(be.getKey(), be.getKey());
            if (!be.getValue().isJsonObject()) {
                continue;
            }
            JsonObject bone = be.getValue().getAsJsonObject();
            Map<String, List<Sample>> chans = new LinkedHashMap<>();
            for (String kind : new String[]{"rotation", "position", "scale"}) {
                if (!bone.has(kind) || !bone.get(kind).isJsonObject()) {
                    continue;
                }
                List<Sample> list = parseChannel(kind, bone.getAsJsonObject(kind), name, boneName, r);
                if (!list.isEmpty()) {
                    chans.put(kind, list);
                    for (Sample s : list) {
                        keyTimes.add(s.time);
                    }
                }
            }
            if (!chans.isEmpty()) {
                data.put(boneName, chans);
            }
        }

        if (data.isEmpty()) {
            r.warnings.add(name + "：所有通道都无有效数值，跳过");
            return null;
        }

        // 时长：优先文件声明，否则取最大关键帧时间
        float maxTime = 0f;
        for (float t : keyTimes) {
            maxTime = Math.max(maxTime, t);
        }
        if (lengthSec <= 0f) {
            lengthSec = maxTime;
        }
        a.lengthTicks = Math.max(1f, Math.round(lengthSec * TICKS_PER_SECOND));

        // 有非线性缓动就逐 tick 烘焙，否则保留稀疏关键帧
        boolean needBake = false;
        for (Map<String, List<Sample>> chans : data.values()) {
            for (List<Sample> list : chans.values()) {
                for (Sample s : list) {
                    if (!"linear".equals(s.easing)) {
                        needBake = true;
                        break;
                    }
                }
            }
        }
        a.animBaked = needBake;

        List<Float> sampleTimes = new ArrayList<>();
        if (needBake) {
            int n = Math.round(a.lengthTicks);
            for (int t = 0; t <= n; t++) {
                sampleTimes.add(t / TICKS_PER_SECOND);
            }
        } else {
            List<Float> sorted = new ArrayList<>(keyTimes);
            sorted.sort(Float::compare);
            sampleTimes.addAll(sorted);
        }

        for (float t : sampleTimes) {
            float tick = Math.round(t * TICKS_PER_SECOND);
            ActionStore.Keyframe kf = a.atOrCreate(tick);
            for (Map.Entry<String, Map<String, List<Sample>>> be : data.entrySet()) {
                ActionStore.BoneChannel ch = kf.bones.get(be.getKey());
                if (ch == null) {
                    ch = new ActionStore.BoneChannel();
                    kf.bones.put(be.getKey(), ch);
                }
                List<Sample> rot = be.getValue().get("rotation");
                if (rot != null) {
                    float[] v = evaluate(rot, t);
                    ch.rotDeg[0] = flip ? -v[0] : v[0];
                    ch.rotDeg[1] = v[1];
                    ch.rotDeg[2] = flip ? -v[2] : v[2];
                }
                List<Sample> pos = be.getValue().get("position");
                if (pos != null) {
                    float[] v = evaluate(pos, t);
                    ch.pos[0] = v[0];
                    ch.pos[1] = flip ? -v[1] : v[1];
                    ch.pos[2] = v[2];
                }
                List<Sample> sc = be.getValue().get("scale");
                if (sc != null) {
                    float[] v = evaluate(sc, t);
                    // scale=0 是合法的“隐藏/塌缩”值，不能替换成 1
                    ch.scale[0] = v[0];
                    ch.scale[1] = v[1];
                    ch.scale[2] = v[2];
                }
            }
        }
        // 扩展块：Bedrock 格式没有无敌帧/位移锚点，用自定义键承载
        // "combatoverhaul": { "iframes": {...}, "anchorBone": "body", "anchor": [{"tick":0,"pos":[0,0,0]}] }
        if (o.has("combatoverhaul") && o.get("combatoverhaul").isJsonObject()) {
            JsonObject ext = o.getAsJsonObject("combatoverhaul");
            if (ext.has("iframes") && ext.get("iframes").isJsonObject()) {
                JsonObject io = ext.getAsJsonObject("iframes");
                ActionStore.IframesDef f = new ActionStore.IframesDef();
                f.startTick = io.has("startTick") ? io.get("startTick").getAsFloat() : 0f;
                f.endTick = io.has("endTick") ? io.get("endTick").getAsFloat() : 0f;
                f.fullImmunity = !io.has("fullImmunity") || io.get("fullImmunity").getAsBoolean();
                f.blockCustomHitboxes = !io.has("blockCustomHitboxes")
                        || io.get("blockCustomHitboxes").getAsBoolean();
                a.iframes = f;
            }
            if (ext.has("anchor") && ext.get("anchor").isJsonArray()) {
                String bone = ext.has("anchorBone") && ext.get("anchorBone").isJsonPrimitive()
                        ? ext.get("anchorBone").getAsString() : "body";
                for (JsonElement ae : ext.getAsJsonArray("anchor")) {
                    if (!ae.isJsonObject()) {
                        continue;
                    }
                    JsonObject ao = ae.getAsJsonObject();
                    float tick = ao.has("tick") ? ao.get("tick").getAsFloat() : 0f;
                    float[] pos = new float[3];
                    if (ao.has("pos") && ao.get("pos").isJsonArray()) {
                        JsonArray pa = ao.getAsJsonArray("pos");
                        for (int k = 0; k < 3 && k < pa.size(); k++) {
                            pos[k] = pa.get(k).getAsFloat();
                        }
                    }
                    ActionStore.Keyframe kf = a.atOrCreate(tick);
                    kf.anchorBone = bone;
                    kf.anchorPos = pos;
                }
            }
        }
        a.sort();
        return a;
    }

    private static boolean readLoop(JsonElement e) {
        if (e == null) {
            return false;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean();
        }
        if (e.isJsonPrimitive()) {
            String s = e.getAsString();
            return "true".equalsIgnoreCase(s) || "loop".equalsIgnoreCase(s);
        }
        return false;
    }

    /** 解析一个通道的时间表：{"0.0":[x,y,z], "0.5":{"vector":[..],"easing":"catmullrom"}} */
    private static List<Sample> parseChannel(String kind, JsonObject o, String animName,
                                             String boneName, Result r) {
        List<Sample> out = new ArrayList<>();
        int dropped = 0;
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            float t;
            try {
                t = Float.parseFloat(e.getKey());
            } catch (NumberFormatException ex) {
                continue;
            }
            JsonElement val = e.getValue();
            String easing = "linear";
            float[] v = null;

            if (val.isJsonPrimitive()) {
                v = uniform(val);
            } else if (val.isJsonArray()) {
                v = vec(val.getAsJsonArray());
            } else if (val.isJsonObject()) {
                JsonObject ko = val.getAsJsonObject();
                // Blockbench/GeckoLib 的平滑关键帧用 "lerp_mode":"catmullrom"，
                // "easing" 是另一套写法；两者都要读，否则 Catmull-Rom 会被当成线性。
                if (ko.has("lerp_mode") && ko.get("lerp_mode").isJsonPrimitive()) {
                    easing = ko.get("lerp_mode").getAsString().toLowerCase();
                }
                if (ko.has("easing") && ko.get("easing").isJsonPrimitive()) {
                    easing = ko.get("easing").getAsString().toLowerCase();
                }
                if (ko.has("vector") && ko.get("vector").isJsonArray()) {
                    v = vec(ko.getAsJsonArray("vector"));
                } else if (ko.has("post") && ko.get("post").isJsonArray()) {
                    v = vec(ko.getAsJsonArray("post"));
                } else if (ko.has("pre") && ko.get("pre").isJsonArray()) {
                    v = vec(ko.getAsJsonArray("pre"));
                }
            }
            if (v == null) {
                dropped++;
                continue;
            }
            Sample s = new Sample();
            s.time = Math.max(0f, t);
            s.v = v;
            s.easing = normalizeEasing(easing);
            out.add(s);
        }
        out.sort((x, y) -> Float.compare(x.time, y.time));
        if (out.isEmpty()) {
            r.warnings.add(animName + "/" + boneName + "/" + kind + "：无有效数值（Molang 表达式不支持）");
        } else if (dropped > 0) {
            r.warnings.add(animName + "/" + boneName + "/" + kind + "：跳过 " + dropped
                    + " 个 Molang 表达式关键帧（本模组只支持常量数值）");
        }
        return out;
    }

    /**
     * 归一化 Bedrock easing 名称。只保留真正会参与求值的名字；
     * 不认识的写法退回 linear（避免“看起来烘焙了、实际全程线性”）。
     */
    private static String normalizeEasing(String raw) {
        if (raw == null) {
            return "linear";
        }
        String s = raw.trim().toLowerCase();
        return switch (s) {
            case "linear", "step", "catmullrom",
                 "easeinsine", "easeoutsine", "easeinoutsine",
                 "easeinquad", "easeoutquad", "easeinoutquad",
                 "easeincubic", "easeoutcubic", "easeinoutcubic",
                 "easeinquart", "easeoutquart", "easeinoutquart",
                 "easeinquint", "easeoutquint", "easeinoutquint",
                 "easeinexpo", "easeoutexpo", "easeinoutexpo",
                 "easeincirc", "easeoutcirc", "easeinoutcirc",
                 "easeinback", "easeoutback", "easeinoutback",
                 "easeinelastic", "easeoutelastic", "easeinoutelastic",
                 "easeinbounce", "easeoutbounce", "easeinoutbounce" -> s;
            default -> "linear";
        };
    }

    private static float[] uniform(JsonElement val) {
        try {
            float n = val.getAsFloat();
            return new float[]{n, n, n};
        } catch (RuntimeException e) {
            return null;    // Molang 字符串
        }
    }

    private static float[] vec(JsonArray a) {
        if (a.size() < 3) {
            return null;
        }
        try {
            return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 采样

    /** 在时间 t（秒）对通道求值：分段线性 / 阶跃 / Catmull-Rom */
    private static float[] evaluate(List<Sample> list, float t) {
        if (list.isEmpty()) {
            return new float[]{0, 0, 0};
        }
        if (t <= list.get(0).time) {
            return list.get(0).v;
        }
        Sample last = list.get(list.size() - 1);
        if (t >= last.time) {
            return last.v;
        }
        int i = 0;
        while (i < list.size() - 1 && list.get(i + 1).time <= t) {
            i++;
        }
        Sample a = list.get(i);
        Sample b = list.get(i + 1);
        float span = b.time - a.time;
        float f = span > 1e-6f ? (t - a.time) / span : 0f;

        if ("step".equals(a.easing)) {
            return a.v;
        }
        if ("catmullrom".equals(a.easing)) {
            float[] p0 = i > 0 ? list.get(i - 1).v : a.v;
            float[] p3 = i + 2 < list.size() ? list.get(i + 2).v : b.v;
            float[] out = new float[3];
            for (int k = 0; k < 3; k++) {
                out[k] = catmull(p0[k], a.v[k], b.v[k], p3[k], f);
            }
            return out;
        }
        float[] out = new float[3];
        float eased = ease(a.easing, f);
        for (int k = 0; k < 3; k++) {
            out[k] = a.v[k] + (b.v[k] - a.v[k]) * eased;
        }
        return out;
    }

    /**
     * Bedrock easing 曲线：输入段内归一化时间 f∈[0,1]，输出缓动后的 f。
     *
     * <p>Bedrock 的 easing 名称在导入时统一转小写（easeInOutSine → easeinoutsine）。
     * 运行时只做线性插值，所以所有非线性段必须逐 tick 采样；这里就是采样时的曲线。
     */
    private static float ease(String name, float t) {
        if (t <= 0f) {
            return 0f;
        }
        if (t >= 1f) {
            return 1f;
        }
        return switch (name) {
            case "easeinsine" -> 1f - (float) Math.cos(t * Math.PI / 2f);
            case "easeoutsine" -> (float) Math.sin(t * Math.PI / 2f);
            case "easeinoutsine" -> -(float) (Math.cos(Math.PI * t) - 1f) / 2f;

            case "easeinquad" -> t * t;
            case "easeoutquad" -> 1f - (1f - t) * (1f - t);
            case "easeinoutquad" -> t < 0.5f ? 2f * t * t : 1f - (float) Math.pow(-2f * t + 2f, 2f) / 2f;

            case "easeincubic" -> t * t * t;
            case "easeoutcubic" -> 1f - (float) Math.pow(1f - t, 3f);
            case "easeinoutcubic" -> t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3f) / 2f;

            case "easeinquart" -> t * t * t * t;
            case "easeoutquart" -> 1f - (float) Math.pow(1f - t, 4f);
            case "easeinoutquart" -> t < 0.5f ? 8f * t * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 4f) / 2f;

            case "easeinquint" -> t * t * t * t * t;
            case "easeoutquint" -> 1f - (float) Math.pow(1f - t, 5f);
            case "easeinoutquint" -> t < 0.5f ? 16f * t * t * t * t * t
                    : 1f - (float) Math.pow(-2f * t + 2f, 5f) / 2f;

            case "easeinexpo" -> (float) Math.pow(2f, 10f * t - 10f);
            case "easeoutexpo" -> 1f - (float) Math.pow(2f, -10f * t);
            case "easeinoutexpo" -> t < 0.5f
                    ? (float) Math.pow(2f, 20f * t - 10f) / 2f
                    : (2f - (float) Math.pow(2f, -20f * t + 10f)) / 2f;

            case "easeincirc" -> 1f - (float) Math.sqrt(1f - t * t);
            case "easeoutcirc" -> (float) Math.sqrt(1f - (t - 1f) * (t - 1f));
            case "easeinoutcirc" -> t < 0.5f
                    ? (1f - (float) Math.sqrt(1f - (2f * t) * (2f * t))) / 2f
                    : ((float) Math.sqrt(1f - (-2f * t + 2f) * (-2f * t + 2f)) + 1f) / 2f;

            case "easeinback" -> {
                float c1 = 1.70158f;
                float c3 = c1 + 1f;
                yield c3 * t * t * t - c1 * t * t;
            }
            case "easeoutback" -> {
                float c1 = 1.70158f;
                float c3 = c1 + 1f;
                float p = t - 1f;
                yield 1f + c3 * p * p * p + c1 * p * p;
            }
            case "easeinoutback" -> {
                float c1 = 1.70158f;
                float c2 = c1 * 1.525f;
                yield t < 0.5f
                        ? ((2f * t) * (2f * t) * ((c2 + 1f) * 2f * t - c2)) / 2f
                        : ((2f * t - 2f) * (2f * t - 2f) * ((c2 + 1f) * (2f * t - 2f) + c2) + 2f) / 2f;
            }

            case "easeinelastic" -> {
                float c4 = (2f * (float) Math.PI) / 3f;
                yield -(float) Math.pow(2f, 10f * t - 10f) * (float) Math.sin((t * 10f - 10.75f) * c4);
            }
            case "easeoutelastic" -> {
                float c4 = (2f * (float) Math.PI) / 3f;
                yield (float) Math.pow(2f, -10f * t) * (float) Math.sin((t * 10f - 0.75f) * c4) + 1f;
            }
            case "easeinoutelastic" -> {
                float c5 = (2f * (float) Math.PI) / 4.5f;
                yield t < 0.5f
                        ? -(float) Math.pow(2f, 20f * t - 10f) * (float) Math.sin((20f * t - 11.125f) * c5) / 2f
                        : (float) Math.pow(2f, -20f * t + 10f) * (float) Math.sin((20f * t - 11.125f) * c5) / 2f + 1f;
            }

            case "easeinbounce" -> 1f - bounceOut(1f - t);
            case "easeoutbounce" -> bounceOut(t);
            case "easeinoutbounce" -> t < 0.5f
                    ? (1f - bounceOut(1f - 2f * t)) / 2f
                    : (1f + bounceOut(2f * t - 1f)) / 2f;

            default -> t; // linear
        };
    }

    /** easeOutBounce（Robert Penner 标准公式） */
    private static float bounceOut(float t) {
        float n1 = 7.5625f;
        float d1 = 2.75f;
        if (t < 1f / d1) {
            return n1 * t * t;
        }
        if (t < 2f / d1) {
            float p = t - 1.5f / d1;
            return n1 * p * p + 0.75f;
        }
        if (t < 2.5f / d1) {
            float p = t - 2.25f / d1;
            return n1 * p * p + 0.9375f;
        }
        float p = t - 2.625f / d1;
        return n1 * p * p + 0.984375f;
    }

    private static float catmull(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return 0.5f * ((2f * p1)
                + (-p0 + p2) * t
                + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2
                + (-p0 + 3f * p1 - 3f * p2 + p3) * t3);
    }
}
