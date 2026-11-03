package com.yourname.combatoverhaul.combat;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.combatoverhaul.CombatOverhaulMod;
import com.yourname.combatoverhaul.store.ModelStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连段链存储与查询（服务端/客户端共用纯数据）。
 *
 * <p>每份模型（实体/武器）对应一个 {@code <模型名>.combos.json}，与模型 JSON 同目录。
 * 连段链 = 动作节点树：
 * <ul>
 *   <li>根节点 = 起手动作（由武器绑定/徒手/技能键正常触发进入）；</li>
 *   <li>子节点 = {下一段动作, 输入类型, 取消窗口[起, 止]tick}——父动作播放中、
 *       当前播放 tick 落在窗口内时按下对应输入 → 取消父动作并立即播放该子节点动作
 *       （实现动作取消与可中断后摇）。</li>
 * </ul>
 * 窗口外按下或动作自然播完后再按 → 回到起手段（无额外状态，链式状态天然随播放结束消亡）。
 */
public final class ComboStore {

    /** 节点：parent&lt;0 为根（起手段）；非根节点携带输入类型与取消窗口 */
    public static final class Node {
        public String action = "";
        public int parent = -1;
        /** attack = 攻击键（左键），skill = 技能键（skill1） */
        public String input = "attack";
        /** 取消窗口（父动作时间轴上的 tick） */
        public float windowStart, windowEnd;

        public Node() {
        }

        public Node(String action, int parent, String input, float windowStart, float windowEnd) {
            this.action = action;
            this.parent = parent;
            this.input = input;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
        }
    }

    public static final class Chain {
        public String name = "";
        public final List<Node> nodes = new ArrayList<>();

        /** 深拷贝（编辑器防串改用） */
        public Chain copy() {
            Chain c = new Chain();
            c.name = name;
            for (Node n : nodes) {
                c.nodes.add(new Node(n.action, n.parent, n.input, n.windowStart, n.windowEnd));
            }
            return c;
        }
    }

    public static final class File {
        public int format = 1;
        public final List<Chain> chains = new ArrayList<>();
    }

    private static final Map<String, File> CACHE = new ConcurrentHashMap<>();

    private ComboStore() {
    }

    // ------------------------------------------------------------------ 缓存

    public static File get(String modelKey) {
        return CACHE.computeIfAbsent(modelKey, ComboStore::load);
    }

    public static void invalidate(String modelKey) {
        if (modelKey == null) {
            CACHE.clear();
        } else {
            CACHE.remove(modelKey);
        }
    }

    public static void invalidate(String modid, String path, boolean weapon) {
        CACHE.remove(ActionCache.keyFor(modid, path, weapon));
    }

    // ------------------------------------------------------------------ 运行时查询

    /**
     * 查询接续：currentAction 播放到 tick 时按下 input，返回窗口命中的下一段动作名；无命中返回 null。
     * 遍历所有链：找到 action==currentAction 的节点，再查其子节点（parent 指向它）中
     * 输入匹配且 tick ∈ [windowStart, windowEnd] 者（同链同动作多段时按节点顺序取首个命中）。
     */
    public static String nextAction(String modelKey, String currentAction, String input, float tick) {
        if (modelKey == null || currentAction == null || currentAction.isEmpty()) {
            return null;
        }
        File f = get(modelKey);
        for (Chain chain : f.chains) {
            for (int i = 0; i < chain.nodes.size(); i++) {
                Node n = chain.nodes.get(i);
                if (!n.action.equals(currentAction)) {
                    continue;
                }
                for (Node c : chain.nodes) {
                    if (c.parent != i || !c.input.equals(input) || c.action.isEmpty()) {
                        continue;
                    }
                    float s = Math.min(c.windowStart, c.windowEnd);
                    float e = Math.max(c.windowStart, c.windowEnd);
                    if (tick >= s && tick <= e) {
                        return c.action;
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 文件 IO

    /** 连段文件路径：与模型 JSON 同目录，名为 &lt;模型名&gt;.combos.json */
    public static Path fileFor(String modid, String path, boolean weapon) {
        String name = ModelStore.sanitizeFileName(path) + ".combos.json";
        Path dir = weapon
                ? ModelStore.weaponsRoot().resolve(ModelStore.sanitizeFileName(modid))
                : ModelStore.entitiesRoot().resolve(ModelStore.sanitizeFileName(modid));
        return dir.resolve(name);
    }

    public static String read(String modid, String path, boolean weapon) {
        try {
            Path file = fileFor(modid, path, weapon);
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 写文件并失效缓存。返回是否成功。 */
    public static boolean write(String modid, String path, boolean weapon, File f) {
        try {
            Path file = fileFor(modid, path, weapon);
            Files.createDirectories(file.getParent());
            Files.writeString(file, toJson(f), StandardCharsets.UTF_8);
            CACHE.remove(ActionCache.keyFor(modid, path, weapon));
            return true;
        } catch (IOException e) {
            CombatOverhaulMod.LOGGER.error("[CombatOverhaul] 写入连段文件失败: {}/{}", modid, path, e);
            return false;
        }
    }

    private static File load(String modelKey) {
        String[] k = ActionCache.parseKey(modelKey);
        if (k == null) {
            return new File();
        }
        String content = read(k[1], k[2], k[0].equals("W"));
        if (content == null) {
            return new File();
        }
        try {
            return parse(content);
        } catch (RuntimeException e) {
            CombatOverhaulMod.LOGGER.error("[CombatOverhaul] 连段文件解析失败: {}", modelKey, e);
            return new File();
        }
    }

    // ------------------------------------------------------------------ JSON

    public static File parse(String json) {
        File f = new File();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("chains") || !root.get("chains").isJsonArray()) {
            return f;
        }
        for (var ce : root.getAsJsonArray("chains")) {
            if (!ce.isJsonObject()) {
                continue;
            }
            JsonObject cjo = ce.getAsJsonObject();
            Chain chain = new Chain();
            if (cjo.has("name") && cjo.get("name").isJsonPrimitive()) {
                chain.name = cjo.get("name").getAsString();
            }
            if (cjo.has("nodes") && cjo.get("nodes").isJsonArray()) {
                for (var ne : cjo.getAsJsonArray("nodes")) {
                    if (!ne.isJsonObject()) {
                        continue;
                    }
                    JsonObject njo = ne.getAsJsonObject();
                    Node n = new Node();
                    if (njo.has("action") && njo.get("action").isJsonPrimitive()) {
                        n.action = njo.get("action").getAsString();
                    }
                    n.parent = njo.has("parent") ? njo.get("parent").getAsInt() : -1;
                    if (njo.has("input") && njo.get("input").isJsonPrimitive()) {
                        n.input = njo.get("input").getAsString();
                    }
                    n.windowStart = njo.has("windowStart") ? njo.get("windowStart").getAsFloat() : 0f;
                    n.windowEnd = njo.has("windowEnd") ? njo.get("windowEnd").getAsFloat() : 0f;
                    chain.nodes.add(n);
                }
            }
            f.chains.add(chain);
        }
        return f;
    }

    public static String toJson(File f) {
        JsonObject root = new JsonObject();
        root.addProperty("format", f.format);
        JsonArray chains = new JsonArray();
        for (Chain chain : f.chains) {
            JsonObject cjo = new JsonObject();
            cjo.addProperty("name", chain.name);
            JsonArray nodes = new JsonArray();
            for (Node n : chain.nodes) {
                JsonObject njo = new JsonObject();
                njo.addProperty("action", n.action);
                njo.addProperty("parent", n.parent);
                if (n.parent >= 0) {
                    njo.addProperty("input", n.input);
                    njo.addProperty("windowStart", round(n.windowStart));
                    njo.addProperty("windowEnd", round(n.windowEnd));
                }
                nodes.add(njo);
            }
            cjo.add("nodes", nodes);
            chains.add(cjo);
        }
        root.add("chains", chains);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
    }

    private static float round(float v) {
        return Math.round(v * 100f) / 100f;
    }
}
