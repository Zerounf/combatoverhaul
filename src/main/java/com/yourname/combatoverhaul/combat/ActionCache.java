package com.yourname.combatoverhaul.combat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.combatoverhaul.CombatOverhaulMod;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import com.yourname.combatoverhaul.store.ActionStore;
import com.yourname.combatoverhaul.store.ModelStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型+动作缓存（服务端与客户端共用，纯数据无渲染依赖）。
 *
 * <p>modelKey 编码："E|&lt;modid&gt;|&lt;path&gt;"（实体模型）或 "W|&lt;ns&gt;|&lt;path&gt;"（武器模型）。
 * 编辑器保存后调用 {@link #invalidate} 使缓存失效。
 *
 * <p>跨实体/跨武器动作授权：动作文件里的 entities 勾选使该动作授权给勾选的生物（如把玩家模型
 * 的通用人形动作勾选给僵尸/骷髅）；武器模型动作的 weapons 绑定同理——白模等武器模型的动作
 * 勾选了若干武器物品后，这些物品的 W| 键自动合并获得该动作（同名时武器自身动作优先），
 * 动画按骨骼名匹配。加载实体键与武器键时都自动把其它模型文件中授权给该目标的
 * 动作合并进动作列表。
 */
public final class ActionCache {

    public static final class Entry {
        public final ParsedModel model;
        public final List<ActionStore.Action> actions;

        Entry(ParsedModel model, List<ActionStore.Action> actions) {
            this.model = model;
            this.actions = actions;
        }
    }

    /** 外部勾选授权：源文件（modid|path）+ 源类型（实体/武器）+ 动作名 */
    private record Grant(String srcModid, String srcPath, boolean srcWeapon, String actionName) {
    }

    /** 通配授权键：动作的 entities 里写 "*" 表示授权给所有「人形」生物（模型带 bone_aliases） */
    public static final String WILDCARD_ENTITY = "*";

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    /** entities 勾选反向索引：目标实体 id（ns:path）→ 授权列表（懒构建，任何缓存失效后重建） */
    private static volatile Map<String, List<Grant>> GRANTS;

    private ActionCache() {
    }

    public static Entry get(String modelKey) {
        return CACHE.computeIfAbsent(modelKey, ActionCache::load);
    }

    public static void invalidate(String modelKey) {
        // 任何文件的动作/entities 勾选变化都可能影响其它实体的合并结果，全部失效重建
        CACHE.clear();
        GRANTS = null;
    }

    public static void invalidate(String modid, String path, boolean weapon) {
        CACHE.clear();
        GRANTS = null;
        ComboStore.invalidate(modid, path, weapon);
    }

    public static String keyFor(String modid, String path, boolean weapon) {
        return (weapon ? "W|" : "E|") + modid + "|" + path;
    }

    /** 解析 "E|modid|path" / "W|ns|path" → {modid, path, weapon}，格式错误返回 null */
    public static String[] parseKey(String modelKey) {
        if (modelKey == null) {
            return null;
        }
        String[] parts = modelKey.split("\\|");
        if (parts.length != 3 || (!parts[0].equals("E") && !parts[0].equals("W"))) {
            return null;
        }
        return parts;
    }

    private static Entry load(String modelKey) {
        String[] k = parseKey(modelKey);
        ParsedModel model = null;
        List<ActionStore.Action> actions = new ArrayList<>();
        if (k != null) {
            boolean weapon = k[0].equals("W");
            String content = weapon ? ModelStore.readWeapon(k[1], k[2]) : ModelStore.read(k[1], k[2]);
            if (content != null) {
                try {
                    JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                    model = ParsedModel.parse(json);
                } catch (RuntimeException ignored) {
                }
            }
            actions.addAll(ActionStore.load(k[1], k[2], weapon));
            mergeGrants(actions, k[1], k[2], model);
        }
        return new Entry(model, actions);
    }

    /** 把其它模型文件中 entities 勾选了该实体的动作并入（同名动作以自身为准） */
    private static void mergeGrants(List<ActionStore.Action> actions, String modid, String path,
                                    ParsedModel model) {
        List<Grant> refs = new ArrayList<>();
        List<Grant> exact = grants().get(modid + ":" + path);
        if (exact != null) {
            refs.addAll(exact);
        }
        // 通配授权（entities 含 "*"）：只对人形模型生效——有 bone_aliases 说明它是 HumanoidModel，
        // 语义骨骼名才能解析到真实骨骼，动作里写的 head / right_arm 才有意义。
        if (model != null && !model.boneAliases.isEmpty()) {
            List<Grant> wild = grants().get(WILDCARD_ENTITY);
            if (wild != null) {
                refs.addAll(wild);
            }
        }
        if (refs.isEmpty()) {
            return;
        }
        Set<String> named = new HashSet<>();
        for (ActionStore.Action a : actions) {
            named.add(a.name);
        }
        Map<String, List<ActionStore.Action>> srcCache = new HashMap<>();
        for (Grant ref : refs) {
            if (named.contains(ref.actionName())) {
                continue;
            }
            String srcKey = (ref.srcWeapon() ? "W|" : "E|") + ref.srcModid() + "|" + ref.srcPath();
            List<ActionStore.Action> src = srcCache.computeIfAbsent(
                    srcKey,
                    kk -> ActionStore.load(ref.srcModid(), ref.srcPath(), ref.srcWeapon()));
            for (ActionStore.Action a : src) {
                if (a.name.equals(ref.actionName())) {
                    actions.add(a);
                    named.add(a.name);
                    break;
                }
            }
        }
    }

    /** entities/weapons 勾选反向索引（懒构建）：扫描实体与武器模型目录，收集动作授权关系 */
    private static Map<String, List<Grant>> grants() {
        Map<String, List<Grant>> g = GRANTS;
        if (g == null) {
            synchronized (ActionCache.class) {
                if (GRANTS == null) {
                    GRANTS = buildGrants();
                }
                g = GRANTS;
            }
        }
        return g;
    }

    private static Map<String, List<Grant>> buildGrants() {
        Map<String, List<Grant>> out = new HashMap<>();
        // 实体模型目录：动作 entities 授权给生物
        scanGrantDir(ModelStore.entitiesRoot(), false, out);
        // 武器模型目录：动作 weapons 授权给武器物品（白模批量套动作）
        scanGrantDir(ModelStore.weaponsRoot(), true, out);
        return out;
    }

    /** 扫描某模型根目录（按 modid 子目录分层的 .json 模型文件），解析其动作的授权列表 */
    private static void scanGrantDir(Path root, boolean weapon, Map<String, List<Grant>> out) {
        try {
            if (!Files.isDirectory(root)) {
                return;
            }
            try (var mods = Files.list(root)) {
                for (Path modDir : mods.toList()) {
                    if (!Files.isDirectory(modDir)) {
                        continue;
                    }
                    String srcModid = modDir.getFileName().toString();
                    try (var files = Files.list(modDir)) {
                        for (Path f : files.toList()) {
                            String name = f.getFileName().toString();
                            if (!name.endsWith(".json") || name.endsWith(".actions.json")) {
                                continue;
                            }
                            parseGrants(srcModid, name.substring(0, name.length() - 5), weapon, f, out);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            CombatOverhaulMod.LOGGER.warn("[CombatOverhaul] 动作授权索引构建失败（{}）", root, ex);
        }
    }

    /** 轻量解析单个模型文件的动作授权（只读 name 与授权列表，不解析关键帧） */
    private static void parseGrants(String srcModid, String srcPath, boolean srcWeapon, Path file,
                                    Map<String, List<Grant>> out) {
        // 实体动作读 entities（授权生物），武器动作读 weapons（授权武器物品）
        String field = srcWeapon ? "weapons" : "entities";
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();
            if (!json.has("actions") || !json.get("actions").isJsonArray()) {
                return;
            }
            for (var ae : json.getAsJsonArray("actions")) {
                if (!ae.isJsonObject()) {
                    continue;
                }
                JsonObject a = ae.getAsJsonObject();
                if (!a.has("name") || !a.get("name").isJsonPrimitive()
                        || !a.has(field) || !a.get(field).isJsonArray()) {
                    continue;
                }
                String actionName = a.get("name").getAsString();
                if (actionName.isEmpty()) {
                    continue;
                }
                for (var te : a.getAsJsonArray(field)) {
                    if (!te.isJsonPrimitive()) {
                        continue;
                    }
                    String target = te.getAsString().trim();
                    if (target.isEmpty() || target.equals(srcModid + ":" + srcPath)) {
                        continue; // 跳过自引
                    }
                    out.computeIfAbsent(target, kk -> new ArrayList<>())
                            .add(new Grant(srcModid, srcPath, srcWeapon, actionName));
                }
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            // 单个文件损坏不阻塞索引
        }
    }
}
