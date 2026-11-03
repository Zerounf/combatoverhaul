package com.yourname.combatoverhaul.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 解析后的完整模型：骨骼森林 + 纹理线索。
 *
 * <p>解析自导出 JSON（store/ModelStore 格式 v1），保留原始 JsonObject，
 * 保存编辑时合并回写以保留未知字段（note/source 等）。
 */
public final class ParsedModel {

    public final List<ParsedBone> roots = new ArrayList<>();
    /** 可选：连续蒙皮网格（format=2）；无则为普通 cube 模型。 */
    public com.yourname.combatoverhaul.client.skinned.SkinnedMesh skinnedMesh;
    /** 平铺骨骼表（前序遍历，含根） */
    public final List<ParsedBone> bones = new ArrayList<>();

    /** JSON "texture" 字段原文（如 "minecraft:textures/entity/pig/pig.png"），可为 null */
    public String textureSpec;
    /** 自定义模型纹理像素尺寸；UV 写像素坐标时用于归一化。 */
    public float textureWidth = 64f;
    public float textureHeight = 64f;
    /** true=faces 里的 UV 是纹理像素坐标，需要 /textureWidth,Height。 */
    public boolean uvPixelSpace;
    /**
     * pivot 的坐标系约定：
     * <ul>
     *   <li>{@code parent_rest}：pivot 是父级 rest 局部坐标（renderer 期望的格式）；</li>
     *   <li>null/其他：旧版 canonical 文件把 pivot 写成模型空间偏移，加载时需 rebase。</li>
     * </ul>
     */
    public String pivotSpace;
    /** JSON "entity" 字段（注册名，用于反射取渲染器默认纹理），可为 null */
    public String entityId;
    /** 渲染器解析后的最终纹理（缓存） */
    public ResourceLocation resolvedTexture;

    /**
     * 单条手持配置：骨骼 + 物品 + 可选挂点偏移/附加旋转。
     * 同一骨骼最多一条（重复设置覆盖）；不同骨骼可同时各持一件物品。
     */
    public static final class HeldEntry {
        /** 挂接骨骼名 */
        public String bone = "";
        /** 物品注册名（如 minecraft:iron_sword） */
        public String item = "";
        /** 挂点偏移（模型单位，骨骼局部空间）；null=自动（包围盒最长轴远端端面中心） */
        public float[] offset;
        /** 附加旋转（度，XYZ，与 rotationZYX 同序）；null=不旋转 */
        public float[] rotDeg;
    }

    /** 手持物品列表（held_items 数组；旧单对象 held_item 解析时自动迁移为首元素） */
    public final List<HeldEntry> heldItems = new ArrayList<>();

    /**
     * 模型自定义动作分区：zone 名 → 骨骼名/别名列表。
     * 与内置 upper/lower/full 合并使用；每个条目默认包含整棵子树。
     */
    public final Map<String, List<String>> actionZones = new LinkedHashMap<>();

    /**
     * 人形骨骼别名：真实骨骼名（SRG，如 f_102808_）→ 语义名（head / right_arm / …）。
     *
     * <p>扫描人形模型（{@code HumanoidModel} 及其子类）时自动写入；用于两件事：
     * 导出给 Blockbench 时显示可读骨骼名、导入动画时把语义名映射回各模型自己的真实骨骼名。
     * 非人形模型为空表。
     */
    public final Map<String, String> boneAliases = new LinkedHashMap<>();

    /** 语义名 → 真实骨骼名（{@link #boneAliases} 的逆表，用到时构建一次） */
    private Map<String, String> aliasToReal;

    /** 点状 rig 的注册名；空=该模型没有 rig，仍用自身骨骼。 */
    public String rigId = "";
    /** rig 点名 -> 本模型真实骨骼名（一一绑定，玩家/生物/盔甲模型各自填写）。 */
    public final Map<String, String> rigPointBones = new LinkedHashMap<>();
    /** 盔甲/外部模型源骨名 -> 目标骨架骨名（自动蒙皮前重映射）。 */
    public final Map<String, String> sourceMap = new LinkedHashMap<>();
    private com.yourname.combatoverhaul.rig.Rig rig;
    private boolean rigResolved;

    private JsonObject originalJson;

    public static ParsedModel parse(JsonObject json) {
        ParsedModel m = new ParsedModel();
        m.originalJson = json != null ? json.deepCopy() : null;
        if (json != null) {
            if (json.has("texture") && json.get("texture").isJsonPrimitive()) {
                m.textureSpec = json.get("texture").getAsString();
            }
            if (json.has("entity") && json.get("entity").isJsonPrimitive()) {
                m.entityId = json.get("entity").getAsString();
            }
            if (json.has("texture_width") && json.get("texture_width").isJsonPrimitive()) {
                m.textureWidth = Math.max(1f, json.get("texture_width").getAsFloat());
            }
            if (json.has("texture_height") && json.get("texture_height").isJsonPrimitive()) {
                m.textureHeight = Math.max(1f, json.get("texture_height").getAsFloat());
            }
            if (json.has("uv_space") && json.get("uv_space").isJsonPrimitive()) {
                m.uvPixelSpace = "pixel".equalsIgnoreCase(json.get("uv_space").getAsString());
            }
            if (json.has("rig") && json.get("rig").isJsonPrimitive()) {
                m.rigId = json.get("rig").getAsString();
            }
            if (json.has("rig_points") && json.get("rig_points").isJsonObject()) {
                for (var e : json.getAsJsonObject("rig_points").entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        m.rigPointBones.put(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
            if (json.has("source_map") && json.get("source_map").isJsonObject()) {
                for (var e : json.getAsJsonObject("source_map").entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        m.sourceMap.put(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
            if (json.has("bone_aliases") && json.get("bone_aliases").isJsonObject()) {
                for (var e : json.getAsJsonObject("bone_aliases").entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        m.boneAliases.put(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
            if (json.has("action_zones") && json.get("action_zones").isJsonObject()) {
                for (var e : json.getAsJsonObject("action_zones").entrySet()) {
                    if (!e.getValue().isJsonArray()) {
                        continue;
                    }
                    List<String> names = new ArrayList<>();
                    for (var n : e.getValue().getAsJsonArray()) {
                        if (n.isJsonPrimitive()) {
                            String s = n.getAsString().trim();
                            if (!s.isEmpty()) {
                                names.add(s);
                            }
                        }
                    }
                    if (!names.isEmpty()) {
                        m.actionZones.put(e.getKey(), names);
                    }
                }
            }
            if (json.has("held_items") && json.get("held_items").isJsonArray()) {
                for (var e : json.getAsJsonArray("held_items")) {
                    if (e.isJsonObject()) {
                        HeldEntry he = heldFromJson(e.getAsJsonObject());
                        if (he != null) {
                            m.heldItems.add(he);
                        }
                    }
                }
            } else if (json.has("held_item") && json.get("held_item").isJsonObject()) {
                // 旧格式迁移：单对象 held_item → held_items 首元素（写回时删除旧键）
                HeldEntry he = heldFromJson(json.getAsJsonObject("held_item"));
                if (he != null) {
                    m.heldItems.add(he);
                }
            }
            if (json.has("pivot_space") && json.get("pivot_space").isJsonPrimitive()) {
                m.pivotSpace = json.get("pivot_space").getAsString();
            }
            if (json.has("roots") && json.get("roots").isJsonObject()) {
                for (var e : json.getAsJsonObject("roots").entrySet()) {
                    if (e.getValue().isJsonObject()) {
                        m.roots.add(ParsedBone.fromJson(e.getKey(), e.getValue().getAsJsonObject(), null));
                    }
                }
            }
        }
        // 旧版 canonical ER 模型：pivot 是模型空间偏移，rest_matrix 却按父级局部旋转使用。
        // 在进入渲染/关节/蒙皮前 rebase 到 parent_rest，避免两套坐标空间混用。
        if (!"parent_rest".equals(m.pivotSpace) && hasAnyRestMatrix(m.roots)) {
            m.migrateLegacyRestPivots();
            m.pivotSpace = "parent_rest";
        }
        m.skinnedMesh = com.yourname.combatoverhaul.client.skinned.SkinnedMesh.parse(json);
        m.flatten();
        if (m.skinnedMesh != null) {
            // 加载期只记录缺失骨骼，不阻止打开模型；保存时会再次严格校验。
            m.skinnedMesh.validateBones(m, false);
        }
        return m;
    }

    private static boolean hasAnyRestMatrix(List<ParsedBone> roots) {
        for (ParsedBone root : roots) {
            if (hasAnyRestMatrix(root)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyRestMatrix(ParsedBone bone) {
        if (bone.restMatrix != null && bone.restMatrix.length >= 9) {
            return true;
        }
        for (ParsedBone child : bone.children) {
            if (hasAnyRestMatrix(child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 旧版 canonical 模型把 pivot 写成“模型空间位移”，但 renderer 把 pivot 当父级
     * rest 局部坐标使用。这里按父级全局 rest 线性变换的逆，把 pivot rebase 到
     * parent_rest；不改变顶点、权重、动作骨骼名。
     */
    private void migrateLegacyRestPivots() {
        for (ParsedBone root : roots) {
            migrateLegacyRestPivots(root, new org.joml.Matrix4f());
        }
    }

    private static void migrateLegacyRestPivots(ParsedBone bone, org.joml.Matrix4f parentGlobal) {
        org.joml.Vector3f offset = new org.joml.Vector3f(bone.pivotX, bone.pivotY, bone.pivotZ);
        org.joml.Vector3f local = new org.joml.Vector3f(offset);
        try {
            org.joml.Matrix4f inv = new org.joml.Matrix4f(parentGlobal).invert();
            local = inv.transformDirection(new org.joml.Vector3f(offset));
        } catch (RuntimeException ignored) {
            // 零缩放/不可逆父矩阵：保留原 pivot，避免迁移把模型读挂。
        }
        bone.pivotX = local.x;
        bone.pivotY = local.y;
        bone.pivotZ = local.z;

        org.joml.Matrix4f localM = new org.joml.Matrix4f()
                .translate(local.x, local.y, local.z);
        if (bone.restMatrix != null && bone.restMatrix.length >= 9) {
            localM.mul(restMatrix4f(bone.restMatrix));
        } else {
            localM.rotateZYX(bone.rotZ, bone.rotY, bone.rotX);
        }
        localM.scale(bone.scaleX, bone.scaleY, bone.scaleZ);
        org.joml.Matrix4f global = new org.joml.Matrix4f(parentGlobal).mul(localM);
        for (ParsedBone child : bone.children) {
            migrateLegacyRestPivots(child, global);
        }
    }

    private static org.joml.Matrix4f restMatrix4f(float[] m) {
        return new org.joml.Matrix4f().set3x3(new org.joml.Matrix3f(
                m[0], m[3], m[6],
                m[1], m[4], m[7],
                m[2], m[5], m[8]));
    }

    /** 空骨架：单根骨骼（用于无模型实体 / 新建武器模型 / 解析失败） */
    public static ParsedModel emptyDefault(String entityLabel) {
        JsonObject json = new JsonObject();
        json.addProperty("format", 1);
        json.addProperty("entity", entityLabel == null ? "" : entityLabel);
        ParsedBone root = new ParsedBone("root");
        json.addProperty("placeholder", true);
        JsonObject roots = new JsonObject();
        roots.add("root", root.toJson());
        json.add("roots", roots);
        return parse(json);
    }

    /** 解析单条手持配置（held_items 元素 / 旧 held_item 对象）；item 缺失返回 null */
    private static HeldEntry heldFromJson(JsonObject h) {
        if (!h.has("item") || !h.get("item").isJsonPrimitive()) {
            return null;
        }
        String it = h.get("item").getAsString().trim();
        if (it.isEmpty()) {
            return null;
        }
        HeldEntry he = new HeldEntry();
        he.item = it;
        if (h.has("bone") && h.get("bone").isJsonPrimitive()) {
            he.bone = h.get("bone").getAsString().trim();
        }
        he.offset = vecOrNull(h, "offset");
        he.rotDeg = vecOrNull(h, "rot_deg");
        return he;
    }

    /** 指定骨骼的手持配置；无返回 null（同一骨骼最多一条） */
    public HeldEntry findHeld(String boneName) {
        if (boneName == null) {
            return null;
        }
        for (HeldEntry he : heldItems) {
            if (he.bone.equals(boneName)) {
                return he;
            }
        }
        return null;
    }

    /** 设置/替换指定骨骼的手持配置（同骨骼已有时就地更新物品并重置挂点为自动） */
    public HeldEntry setHeld(String boneName, String itemId) {
        HeldEntry he = findHeld(boneName);
        if (he == null) {
            he = new HeldEntry();
            he.bone = boneName;
            heldItems.add(he);
        }
        he.item = itemId;
        he.offset = null;
        he.rotDeg = null;
        return he;
    }

    /** 清除指定骨骼的手持配置；返回是否有改动 */
    public boolean clearHeld(String boneName) {
        HeldEntry he = findHeld(boneName);
        return he != null && heldItems.remove(he);
    }

    /** 读取三分量数组字段；缺失/非法返回 null（与缺省语义区分：null=自动） */
    private static float[] vecOrNull(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            return null;
        }
        var a = o.getAsJsonArray(key);
        if (a.size() < 3) {
            return null;
        }
        try {
            return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 手持挂点偏移（骨骼局部空间，模型单位）：
     * 配置值优先；未配置时取挂接骨骼包围盒最长轴的远端端面中心
     * （如手臂枢轴在肩部时自动落到手掌一端）。
     */
    public float[] heldAttachOffset(HeldEntry he) {
        if (he == null) {
            return new float[]{0, 0, 0};
        }
        if (he.offset != null) {
            return he.offset;
        }
        ParsedBone b = findBone(he.bone);
        float[] cb = b != null ? b.cubeBounds() : null;
        if (cb == null) {
            return new float[]{0, 0, 0};
        }
        float ex = cb[3] - cb[0], ey = cb[4] - cb[1], ez = cb[5] - cb[2];
        float cx = (cb[0] + cb[3]) / 2, cy = (cb[1] + cb[4]) / 2, cz = (cb[2] + cb[5]) / 2;
        if (ex >= ey && ex >= ez) {
            return new float[]{Math.abs(cb[3]) >= Math.abs(cb[0]) ? cb[3] : cb[0], cy, cz};
        }
        if (ey >= ez) {
            return new float[]{cx, Math.abs(cb[4]) >= Math.abs(cb[1]) ? cb[4] : cb[1], cz};
        }
        return new float[]{cx, cy, Math.abs(cb[5]) >= Math.abs(cb[2]) ? cb[5] : cb[2]};
    }

    public void flatten() {
        bones.clear();
        for (ParsedBone r : roots) {
            r.collectAll(bones);
        }
    }

    /**
     * 骨骼跟随：跟随者根骨和“按子骨索引的相对路径”子树完全复制目标的基础姿态与动画。
     *
     * <p>调用方在渲染/蒙皮前调用；编辑器里目标 pivot/rotation/关键帧变化后，
     * 下一次 sync 会把值同步给跟随者。跟随者必须已重挂到目标父级（applyFollow 完成），
     * 这样同样的局部姿态才会得到同样的世界矩阵。
     */
    public void syncFollow() {
        if (bones.isEmpty()) {
            return;
        }
        for (ParsedBone follower : bones) {
            if (follower.follow == null || follower.follow.isBlank()) {
                continue;
            }
            ParsedBone target = resolveFollowTarget(follower);
            if (target == null || target == follower || isDescendant(target, follower)) {
                continue;
            }
            copyFollowTree(target, follower, 0, new java.util.HashSet<>());
        }
    }

    private ParsedBone resolveFollowTarget(ParsedBone follower) {
        ParsedBone target = findBone(follower.follow);
        int guard = 0;
        while (target != null && target.follow != null && !target.follow.isBlank()
                && guard++ < 32) {
            ParsedBone next = findBone(target.follow);
            if (next == null || next == target) {
                break;
            }
            target = next;
        }
        return target;
    }

    private void copyFollowTree(ParsedBone target, ParsedBone follower, int depth,
                                java.util.Set<ParsedBone> visited) {
        if (target == null || follower == null || depth > 32 || !visited.add(follower)) {
            return;
        }
        follower.pivotX = target.pivotX;
        follower.pivotY = target.pivotY;
        follower.pivotZ = target.pivotZ;
        follower.rotX = target.rotX;
        follower.rotY = target.rotY;
        follower.rotZ = target.rotZ;
        follower.scaleX = target.scaleX;
        follower.scaleY = target.scaleY;
        follower.scaleZ = target.scaleZ;
        follower.restMatrix = target.restMatrix == null ? null : target.restMatrix.clone();
        follower.hasPivot = target.hasPivot;
        follower.softJoint = target.softJoint;
        follower.softAxis = target.softAxis;
        follower.animPosX = target.animPosX;
        follower.animPosY = target.animPosY;
        follower.animPosZ = target.animPosZ;
        follower.animRotX = target.animRotX;
        follower.animRotY = target.animRotY;
        follower.animRotZ = target.animRotZ;
        follower.animScaleX = target.animScaleX;
        follower.animScaleY = target.animScaleY;
        follower.animScaleZ = target.animScaleZ;
        int n = Math.min(target.children.size(), follower.children.size());
        for (int i = 0; i < n; i++) {
            ParsedBone fc = follower.children.get(i);
            // 显式子骨映射由 syncFollow 自己的循环处理，避免随后被索引覆盖。
            if (fc.follow != null && !fc.follow.isBlank()) {
                continue;
            }
            ParsedBone tc = matchFollowChild(target, fc, i);
            if (tc != null) {
                copyFollowTree(tc, fc, depth + 1, visited);
            }
        }
    }

    /** 旧数据没有显式子骨 follow 时，优先按 _2/_3 分段序号匹配，再退回索引。 */
    private static ParsedBone matchFollowChild(ParsedBone target, ParsedBone followerChild, int index) {
        int ordinal = splitOrdinal(followerChild.name);
        if (ordinal > 0) {
            for (ParsedBone tc : target.children) {
                if (splitOrdinal(tc.name) == ordinal) {
                    return tc;
                }
            }
        }
        return index < target.children.size() ? target.children.get(index) : null;
    }

    private static int splitOrdinal(String name) {
        if (name == null || name.isEmpty()) {
            return -1;
        }
        int i = name.length();
        while (i > 0 && Character.isDigit(name.charAt(i - 1))) {
            i--;
        }
        if (i == name.length()) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(i));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isDescendant(ParsedBone node, ParsedBone ancestor) {
        for (ParsedBone p = node.parent; p != null; p = p.parent) {
            if (p == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** 语义名 → 真实骨骼名；非别名/未配置时原样返回 */
    public String resolveBoneName(String nameOrAlias) {
        if (nameOrAlias == null || boneAliases.isEmpty()) {
            return nameOrAlias;
        }
        if (!boneAliases.containsValue(nameOrAlias)) {
            return nameOrAlias;
        }
        if (aliasToReal == null) {
            Map<String, String> inv = new HashMap<>();
            for (Map.Entry<String, String> e : boneAliases.entrySet()) {
                inv.putIfAbsent(e.getValue(), e.getKey());
            }
            aliasToReal = inv;
        }
        return aliasToReal.getOrDefault(nameOrAlias, nameOrAlias);
    }

    /** 真实骨骼名 → 语义名；没有别名时原样返回 */
    public String semanticName(String realName) {
        return realName == null ? null : boneAliases.getOrDefault(realName, realName);
    }

    public ParsedBone findBone(String name) {
        if (name == null) {
            return null;
        }
        for (ParsedBone b : bones) {
            if (b.name.equals(name)) {
                return b;
            }
        }
        // 通用语义名（head / right_arm…）：按别名表映射到本模型的真实骨骼名。
        // 这是「通用人形骨架做的动作能驱动任意人形生物」的运行时基础。
        String real = resolveBoneName(name);
        if (!real.equals(name)) {
            for (ParsedBone b : bones) {
                if (b.name.equals(real)) {
                    return b;
                }
            }
        }
        return null;
    }

    /**
     * 解析动作分区 → 骨骼名集合。
     *
     * <ul>
     *   <li>{@code full}/空：全部骨骼；</li>
     *   <li>模型 JSON {@code action_zones} 中同名项：列出的骨骼/alias + 整棵子树；</li>
     *   <li>内置 {@code upper}/{@code lower}：按人形 alias/名称推断，包含子树；</li>
     *   <li>未知 zone：回退全部（避免动作静默不播）。</li>
     * </ul>
     */
    public Set<String> zoneBones(String zone) {
        Set<String> out = new HashSet<>();
        if (zone == null || zone.isEmpty() || "full".equalsIgnoreCase(zone)) {
            for (ParsedBone b : bones) {
                out.add(b.name);
            }
            return out;
        }
        List<String> custom = actionZones.get(zone);
        if (custom != null && !custom.isEmpty()) {
            for (String n : custom) {
                ParsedBone b = findBone(n);
                if (b != null) {
                    collectSubtree(b, out);
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        boolean upper = "upper".equalsIgnoreCase(zone)
                || "upper_body".equalsIgnoreCase(zone)
                || "上半身".equals(zone);
        boolean lower = "lower".equalsIgnoreCase(zone)
                || "lower_body".equalsIgnoreCase(zone)
                || "下半身".equals(zone);
        if (upper || lower) {
            for (ParsedBone b : bones) {
                String semantic = semanticName(b.name);
                String s = semantic == null ? "" : semantic.toLowerCase(Locale.ROOT);
                boolean match = upper
                        ? (s.contains("head") || s.contains("hat") || s.contains("body")
                        || s.contains("chest") || s.contains("torso") || s.contains("spine")
                        || s.contains("arm") || s.contains("sleeve") || s.contains("hand"))
                        : (s.contains("leg") || s.contains("pants") || s.contains("thigh")
                        || s.contains("shin") || s.contains("foot") || s.contains("hip")
                        || s.contains("root") || s.contains("pelvis"));
                if (match) {
                    collectSubtree(b, out);
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        // 未知自定义 zone / 非人形模型：回退全部，保持旧行为
        for (ParsedBone b : bones) {
            out.add(b.name);
        }
        return out;
    }

    private static void collectSubtree(ParsedBone bone, Set<String> out) {
        if (bone == null) {
            return;
        }
        out.add(bone.name);
        for (ParsedBone c : bone.children) {
            collectSubtree(c, out);
        }
    }

    /**
     * 骨骼枢轴点的模型空间世界位置（模型单位/像素），含全部父链的
     * 平移（pivot+animPos）、旋转（基础 rot+animRot，原版 Z→Y→X 顺序）与缩放。
     * 用于锚点位移反推。骨骼不存在返回 null。
     */
    public float[] boneWorldPos(String name) {
        ParsedBone target = findBone(name);
        if (target == null) {
            return null;
        }
        List<ParsedBone> chain = new ArrayList<>();
        for (ParsedBone b = target; b != null; b = b.parent) {
            chain.add(0, b);
        }
        org.joml.Matrix4f m = new org.joml.Matrix4f();
        for (ParsedBone b : chain) {
            // 与 SkinnedMeshRenderer 的 animated 链一致：
            // translate → (rest_matrix 或 ZYX) → 动画 ZYX → scale
            m.translate(b.pivotX + b.animPosX, b.pivotY + b.animPosY, b.pivotZ + b.animPosZ);
            if (b.restMatrix != null && b.restMatrix.length >= 9) {
                m.mul(restMatrix4f(b.restMatrix));
            }
            m.rotateZYX(b.fz(), b.fy(), b.fx());
            float sx = b.scaleX * b.animScaleX;
            float sy = b.scaleY * b.animScaleY;
            float sz = b.scaleZ * b.animScaleZ;
            if (sx != 1 || sy != 1 || sz != 1) {
                m.scale(sx, sy, sz);
            }
        }
        org.joml.Vector3f p = new org.joml.Vector3f();
        m.transformPosition(p);
        return new float[]{p.x, p.y, p.z};
    }

    /**
     * 基础姿态（不含动画叠加）的根→骨骼矩阵链（模型单位），
     * 用于重挂接时换算新父级相对枢轴以保持世界位置。
     * transformPosition(0,0,0) 即骨骼枢轴点的模型空间位置。骨骼不存在返回 null。
     */
    public org.joml.Matrix4f boneBaseMatrix(String name) {
        ParsedBone target = findBone(name);
        if (target == null) {
            return null;
        }
        List<ParsedBone> chain = new ArrayList<>();
        for (ParsedBone b = target; b != null; b = b.parent) {
            chain.add(0, b);
        }
        org.joml.Matrix4f m = new org.joml.Matrix4f();
        for (ParsedBone b : chain) {
            // 与 SkinnedMeshRenderer 的 rest 链一致：rest_matrix 分支不再叠加 base 欧拉角。
            m.translate(b.pivotX, b.pivotY, b.pivotZ);
            if (b.restMatrix != null && b.restMatrix.length >= 9) {
                m.mul(restMatrix4f(b.restMatrix));
            } else {
                m.rotateZYX(b.rotZ, b.rotY, b.rotX);
            }
            if (b.scaleX != 1 || b.scaleY != 1 || b.scaleZ != 1) {
                m.scale(b.scaleX, b.scaleY, b.scaleZ);
            }
        }
        return m;
    }

    /** 唯一骨骼名（bone_N 递增） */
    public String nextBoneName() {
        int n = bones.size();
        while (findBone("bone_" + n) != null) {
            n++;
        }
        return "bone_" + n;
    }

    /**
     * 包围盒（模型单位，忽略旋转，按枢轴累加的立方体绝对角点）。
     * 返回 {minX, minY, minZ, maxX, maxY, maxZ}；无立方体时全 0。
     */
    public float[] bounds() {
        float[] out = {0, 0, 0, 0, 0, 0};
        boolean any = false;
        for (ParsedBone root : roots) {
            float[] acc = {0, 0, 0};
            any |= boundsWalk(root, acc, out);
        }
        if (skinnedMesh != null) {
            for (com.yourname.combatoverhaul.client.skinned.SkinnedMesh.Vertex v : skinnedMesh.vertices) {
                if (!any) {
                    out[0] = out[3] = v.x;
                    out[1] = out[4] = v.y;
                    out[2] = out[5] = v.z;
                    any = true;
                } else {
                    out[0] = Math.min(out[0], v.x);
                    out[1] = Math.min(out[1], v.y);
                    out[2] = Math.min(out[2], v.z);
                    out[3] = Math.max(out[3], v.x);
                    out[4] = Math.max(out[4], v.y);
                    out[5] = Math.max(out[5], v.z);
                }
            }
        }
        if (!any) {
            return new float[6];
        }
        return out;
    }

    private boolean boundsWalk(ParsedBone bone, float[] acc, float[] out) {
        float ax = acc[0] + bone.pivotX;
        float ay = acc[1] + bone.pivotY;
        float az = acc[2] + bone.pivotZ;
        boolean any = false;
        for (ParsedCube c : bone.cubes) {
            for (int f = 0; f < 6; f++) {
                for (int k = 0; k < 4; k++) {
                    float x = ax + c.cornerX(f, k);
                    float y = ay + c.cornerY(f, k);
                    float z = az + c.cornerZ(f, k);
                    if (!any) {
                        out[0] = out[3] = x;
                        out[1] = out[4] = y;
                        out[2] = out[5] = z;
                        any = true;
                    } else {
                        out[0] = Math.min(out[0], x);
                        out[1] = Math.min(out[1], y);
                        out[2] = Math.min(out[2], z);
                        out[3] = Math.max(out[3], x);
                        out[4] = Math.max(out[4], y);
                        out[5] = Math.max(out[5], z);
                    }
                }
            }
        }
        float[] childAcc = {ax, ay, az};
        for (ParsedBone ch : bone.children) {
            any |= boundsWalk(ch, childAcc, out);
        }
        return any;
    }

    /** 解析并缓存 rig；模型引用无效时返回 null。 */
    public com.yourname.combatoverhaul.rig.Rig rig() {
        if (!rigResolved) {
            rigResolved = true;
            if (rigId != null && !rigId.isBlank()) {
                try {
                    rig = com.yourname.combatoverhaul.rig.RigStore.get(
                            new ResourceLocation(rigId));
                } catch (RuntimeException e) {
                    rig = null;
                }
            }
        }
        return rig;
    }

    /**
     * rig 点名（或点语义名）→ 本模型真实骨骼名。
     * 没有填写 rig_points 时回退到同名骨骼 / 语义别名，便于旧模型渐进迁移。
     */
    public String rigBone(String pointOrSemantic) {
        if (pointOrSemantic == null || pointOrSemantic.isBlank()) {
            return null;
        }
        String mapped = rigPointBones.get(pointOrSemantic);
        if (mapped != null && !mapped.isBlank()) {
            return findBone(mapped) != null ? mapped : null;
        }
        com.yourname.combatoverhaul.rig.Rig r = rig();
        if (r != null) {
            com.yourname.combatoverhaul.rig.Rig.Point p = r.point(pointOrSemantic);
            if (p == null) {
                p = r.bySemantic(pointOrSemantic);
            }
            if (p != null) {
                mapped = rigPointBones.get(p.name);
                if (mapped != null && !mapped.isBlank() && findBone(mapped) != null) {
                    return mapped;
                }
            }
        }
        ParsedBone b = findBone(pointOrSemantic);
        return b == null ? null : b.name;
    }

    public void setRigId(String id) {
        rigId = id == null ? "" : id;
        rig = null;
        rigResolved = false;
    }

    public void bindRigPoint(String point, String bone) {
        if (point != null && !point.isBlank() && bone != null && !bone.isBlank()) {
            rigPointBones.put(point, bone);
        }
    }

    /**
     * 序列化为 JSON：在原始 JsonObject 基础上替换 roots，
     * 保留 format/entity/modid/note/source/texture 等全部既有字段。
     */
    public JsonObject toJson() {
        JsonObject json;
        if (originalJson != null) {
            json = originalJson.deepCopy();
        } else {
            json = new JsonObject();
            json.addProperty("format", 1);
            json.addProperty("entity", entityId == null ? "" : entityId);
        }
        if (textureSpec != null) {
            json.addProperty("texture", textureSpec);
        }
        if (uvPixelSpace || textureWidth != 64f || textureHeight != 64f) {
            json.addProperty("texture_width", textureWidth);
            json.addProperty("texture_height", textureHeight);
            json.addProperty("uv_space", uvPixelSpace ? "pixel" : "normalized");
        }
        if (pivotSpace != null && !pivotSpace.isBlank()) {
            json.addProperty("pivot_space", pivotSpace);
        }
        if (rigId != null && !rigId.isBlank()) {
            json.addProperty("rig", rigId);
        } else {
            json.remove("rig");
        }
        if (!sourceMap.isEmpty()) {
            JsonObject sm = new JsonObject();
            for (var e : sourceMap.entrySet()) {
                sm.addProperty(e.getKey(), e.getValue());
            }
            json.add("source_map", sm);
        } else {
            json.remove("source_map");
        }
        if (!rigPointBones.isEmpty()) {
            JsonObject rp = new JsonObject();
            for (var e : rigPointBones.entrySet()) {
                rp.addProperty(e.getKey(), e.getValue());
            }
            json.add("rig_points", rp);
        } else {
            json.remove("rig_points");
        }
        // 网格/权重必须由内存数据重新写出：否则自动蒙皮/权重重绑的结果保存后会丢失。
        if (skinnedMesh != null) {
            skinnedMesh.validateBones(this, true);
            JsonObject meshJson = skinnedMesh.toJson();
            json.add("vertices", meshJson.get("vertices"));
            json.add("faces", meshJson.get("faces"));
            json.add("weights", meshJson.get("weights"));
            if (meshJson.has("source_bones")) {
                json.add("source_bones", meshJson.get("source_bones"));
            } else {
                json.remove("source_bones");
            }
            if (meshJson.has("bind_bones")) {
                json.add("bind_bones", meshJson.get("bind_bones"));
            } else {
                json.remove("bind_bones");
            }
        }
        // 旧单对象键始终废弃（已迁移到 held_items）
        json.remove("held_item");
        if (!heldItems.isEmpty()) {
            com.google.gson.JsonArray list = new com.google.gson.JsonArray();
            for (HeldEntry he : heldItems) {
                JsonObject h = new JsonObject();
                h.addProperty("item", he.item);
                h.addProperty("bone", he.bone == null ? "" : he.bone);
                if (he.offset != null) {
                    com.google.gson.JsonArray a = new com.google.gson.JsonArray();
                    for (float v : he.offset) {
                        a.add(Math.round(v * 100f) / 100f);
                    }
                    h.add("offset", a);
                }
                if (he.rotDeg != null) {
                    com.google.gson.JsonArray a = new com.google.gson.JsonArray();
                    for (float v : he.rotDeg) {
                        a.add(Math.round(v * 100f) / 100f);
                    }
                    h.add("rot_deg", a);
                }
                list.add(h);
            }
            json.add("held_items", list);
        } else {
            json.remove("held_items");
        }
        json.remove("roots");
        JsonObject rootsJson = new JsonObject();
        for (ParsedBone r : roots) {
            rootsJson.add(r.name, r.toJson());
        }
        json.add("roots", rootsJson);
        if (!actionZones.isEmpty()) {
            JsonObject zones = new JsonObject();
            for (Map.Entry<String, List<String>> e : actionZones.entrySet()) {
                JsonArray arr = new JsonArray();
                for (String b : e.getValue()) {
                    arr.add(b);
                }
                zones.add(e.getKey(), arr);
            }
            json.add("action_zones", zones);
        } else {
            json.remove("action_zones");
        }
        return json;
    }

    // ------------------------------------------------------------------ 原版 ModelPart 重建

    private static final Direction[] FACE_DIRS = {
            Direction.DOWN, Direction.UP, Direction.WEST,
            Direction.EAST, Direction.NORTH, Direction.SOUTH
    };

    /**
     * 将骨骼树重建为原版 {@link ModelPart}，以便直接调用 {@code ModelPart.render()}
     * 复用原版完整渲染管线（6 面 UV、镜像、光照、translateAndRotate）。
     *
     * <p>每个 {@link ParsedBone} 转为一个 ModelPart：pivot→x/y/z，旋转→xRot/yRot/zRot，
     * 缩放→scaleX/Y/Z；每个 {@link ParsedCube} 转为一个 ModelPart.Cube（通过
     * {@link ParsedCube#inferTexParams()} 反推 texU/texV/texW/texH）。
     */
    public ModelPart toModelPart() {
        Map<String, ModelPart> rootChildren = new HashMap<>();
        for (ParsedBone b : roots) {
            rootChildren.put(b.name, buildPart(b));
        }
        return new ModelPart(new ArrayList<>(), rootChildren);
    }

    private ModelPart buildPart(ParsedBone b) {
        List<ModelPart.Cube> cubes = new ArrayList<>();
        for (ParsedCube c : b.cubes) {
            ModelPart.Cube cube = buildCube(c);
            if (cube != null) {
                cubes.add(cube);
            }
        }
        Map<String, ModelPart> children = new HashMap<>();
        for (ParsedBone ch : b.children) {
            children.put(ch.name, buildPart(ch));
        }
        ModelPart part = new ModelPart(cubes, children);
        part.x = b.pivotX + b.animPosX;
        part.y = b.pivotY + b.animPosY;
        part.z = b.pivotZ + b.animPosZ;
        part.xRot = b.fx();
        part.yRot = b.fy();
        part.zRot = b.fz();
        part.xScale = b.scaleX * b.animScaleX;
        part.yScale = b.scaleY * b.animScaleY;
        part.zScale = b.scaleZ * b.animScaleZ;
        part.visible = b.visible;
        return part;
    }

    private ModelPart.Cube buildCube(ParsedCube c) {
        float[] tp = c.inferTexParams();
        if (tp == null) {
            return null;
        }
        int texW = Math.max(1, Math.round(tp[2]));
        int texH = Math.max(1, Math.round(tp[3]));
        EnumSet<Direction> faceSet = EnumSet.noneOf(Direction.class);
        for (int f = 0; f < 6; f++) {
            if (c.hasFace(f)) {
                faceSet.add(FACE_DIRS[f]);
            }
        }
        if (faceSet.isEmpty()) {
            return null;
        }
        // 1.20.1 ModelPart.Cube 构造器实参语义（经 CubeDefinition.bake 字节码核实，与旧版本不同）：
        //   Cube(int texOffsU, int texOffsV,                 ← 前两个 int 是像素 UV 偏移
        //        float originX,Y,Z, float sizeX,Y,Z, float inflateX,Y,Z,
        //        boolean mirror,
        //        float texWidth, float texHeight,            ← mirror 之后才是纹理像素宽高
        //        Set<Direction> visibleFaces)
        // 早期误把纹理宽高放偏移位、像素偏移放宽高位，UV 被放大数十倍后 GL_REPEAT 反复复制。
        return new ModelPart.Cube(
                Math.round(tp[0]), Math.round(tp[1]),
                c.originX, c.originY, c.originZ,
                c.sizeX, c.sizeY, c.sizeZ,
                0.0f, 0.0f, 0.0f, c.mirror,
                (float) texW, (float) texH, faceSet
        );
    }
}
