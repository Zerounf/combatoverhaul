package com.yourname.combatoverhaul.client.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.yourname.combatoverhaul.CombatOverhaulMod;
import com.yourname.combatoverhaul.store.ModelStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 实体模型扫描导出（仅客户端）：
 *
 * <ul>
 *   <li>遍历 {@link ForgeRegistries#ENTITY_TYPES}，取每个实体对应的 {@link EntityRenderer}；</li>
 *   <li>活体渲染器经 {@code RenderLayerParent#getModel()} 取 {@link EntityModel}，
 *       层级模型经 {@link HierarchicalModel#root()} 取 {@link ModelPart} 树；</li>
 *   <li>ModelPart 的 cubes/children 字段为私有，按字段类型（List/Map）反射读取，与混淆映射无关；</li>
 *   <li>GeckoLib 渲染器按父类类名识别并标记，其几何在资源包 geo.json 中；</li>
 *   <li>导出结果写 {@link ModelStore}：config/combatoverhaul/entities/&lt;modid&gt;/&lt;entity&gt;.json，
 *       已存在的文件默认跳过（保留手动编辑），可对单个实体执行覆盖导出。</li>
 * </ul>
 *
 * <p>说明：1.20.1 的模型系统不存储方块级旋转（构造时已烘进顶点），UV 在烘焙时被纹理尺寸归一化，
 * 因此导出的 UV 为 0~1 归一化坐标。
 */
public final class EntityModelScanner {

    /** 单个实体的扫描结果 */
    public static final class ScanResult {
        public final String modid;
        public final String path;
        public final String source; // vanilla / geckolib / none
        public final JsonObject json;
        public boolean exported;

        ScanResult(String modid, String path, String source, JsonObject json) {
            this.modid = modid;
            this.path = path;
            this.source = source;
            this.json = json;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 玩家两种皮肤模型变体的导出路径（EntityType 只有 player 一个，wide/slim 按变体拆成两个模型文件） */
    private static final String PATH_PLAYER_WIDE = "player";
    private static final String PATH_PLAYER_SLIM = "player_slim";
    /** 变体默认贴图（原版内置资源，避免回落到本机玩家自定义皮肤） */
    private static final String TEX_PLAYER_WIDE = "minecraft:textures/entity/player/wide/steve.png";
    private static final String TEX_PLAYER_SLIM = "minecraft:textures/entity/player/slim/alex.png";

    private EntityModelScanner() {
    }

    /** 扫描全部实体并导出缺失文件（不覆盖已存在文件）。 */
    public static List<ScanResult> scanAndExportMissing() {
        List<ScanResult> results = scanAll();
        for (ScanResult r : results) {
            if (!r.exported) {
                r.exported = ModelStore.write(r.modid, r.path, GSON.toJson(r.json));
            }
        }
        return results;
    }

    /** 扫描全部实体（只扫描 + 标记文件存在性，不写文件）。 */
    public static List<ScanResult> scanAll() {
        List<ScanResult> results = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        Map<EntityType<?>, EntityRenderer<?>> renderers = findRenderers(mc.getEntityRenderDispatcher());

        for (Map.Entry<ResourceKey<EntityType<?>>, EntityType<?>> entry : ForgeRegistries.ENTITY_TYPES.getEntries()) {
            ResourceLocation key = entry.getKey().location();
            // 玩家：渲染器不在 renderers 表，且无法 create 实例探针。
            // 按皮肤模型变体（default=wide 史蒂夫 / slim=Alex）拆成两个模型文件分别导出。
            if (entry.getValue() == EntityType.PLAYER) {
                Map<String, EntityRenderer<?>> playerRenderers =
                        findPlayerRenderers(mc.getEntityRenderDispatcher());
                if (playerRenderers != null && !playerRenderers.isEmpty()) {
                    for (Map.Entry<String, EntityRenderer<?>> pe : playerRenderers.entrySet()) {
                        boolean slim = pe.getKey().toLowerCase(Locale.ROOT).contains("slim");
                        ScanResult r = scanOne(key.getNamespace(), slim ? PATH_PLAYER_SLIM : PATH_PLAYER_WIDE,
                                entry.getValue(), pe.getValue(), slim ? TEX_PLAYER_SLIM : TEX_PLAYER_WIDE);
                        r.exported = ModelStore.exists(r.modid, r.path);
                        results.add(r);
                    }
                    continue;
                }
                // 反射失败：降级走下方 mc.player 探针（仅当前玩家变体，无固定贴图）
            }
            EntityRenderer<?> renderer = renderers == null ? null : renderers.get(entry.getValue());
            // renderers 表不含玩家（其渲染器按皮肤模型名单独存放）。
            // 这里用 dispatcher.getRenderer(实体) 兜底：该方法内部会按 getModelName() 取玩家渲染器。
            if (renderer == null && mc.level != null) {
                try {
                    // 玩家无法用 create 构造（工厂返回 null）：直接用本地玩家实例当探针
                    Entity probe = entry.getValue() == EntityType.PLAYER && mc.player != null
                            ? mc.player
                            : entry.getValue().create(mc.level);
                    if (probe != null) {
                        EntityRenderer<? super Entity> r = mc.getEntityRenderDispatcher().getRenderer(probe);
                        if (r != null) {
                            renderer = r;
                        }
                    }
                } catch (RuntimeException ignored) {
                    // 个别实体需要额外上下文，拿不到实例就保持 null
                }
            }
            ScanResult r = scanOne(key.getNamespace(), key.getPath(), entry.getValue(), renderer, null);
            // 是否“已导出”只看文件是否存在：不能用 renderer != null 作为条件。
            // 反射取渲染器失败时 renderer 会全是 null，若这里判 false，
            // scanAndExportMissing() 会把每一份已有模型文件都用空占位 JSON 覆盖（曾造成整目录数据丢失风险）。
            r.exported = ModelStore.exists(r.modid, r.path);
            results.add(r);
        }

        results.sort(Comparator.comparing((ScanResult s) -> s.modid).thenComparing(s -> s.path));
        return results;
    }

    /** 覆盖导出单个实体（几何重置为扫描结果；保留原文件中的 actions 动作段与 texture 字段） */
    public static boolean exportOverwrite(ScanResult r) {
        JsonObject out = r.json.deepCopy();
        String existing = ModelStore.read(r.modid, r.path);
        if (existing != null) {
            try {
                JsonObject old = com.google.gson.JsonParser.parseString(existing).getAsJsonObject();
                // 扫描不到几何（renderer 解析失败/实体不可渲染）时，绝不能用空占位覆盖已有模型。
                if (hasRoots(old) && !hasRoots(out)) {
                    CombatOverhaulMod.LOGGER.warn(
                            "[CombatOverhaul] 拒绝覆盖 {}:{}：本次扫描没有拿到几何，已有模型保持不动",
                            r.modid, r.path);
                    return false;
                }
                // 保留动作段：动作编辑（关键帧/判定框/绑定）不因重新导出而丢失
                if (old.has("actions") && old.get("actions").isJsonArray()) {
                    out.add("actions", old.get("actions"));
                }
                // 用户自定义动作分区不随几何重新扫描丢失
                if (old.has("action_zones") && old.get("action_zones").isJsonObject()) {
                    out.add("action_zones", old.get("action_zones"));
                }
                // 保留手动指定的纹理路径
                if (old.has("texture") && old.get("texture").isJsonPrimitive()) {
                    out.add("texture", old.get("texture"));
                }
                // 手持挂点（含手调 offset/rot_deg）是用户数据，重新扫描绝不覆盖
                if (old.has("held_items") && old.get("held_items").isJsonArray()) {
                    out.add("held_items", old.get("held_items"));
                } else if (old.has("held_item") && old.get("held_item").isJsonObject()) {
                    out.add("held_item", old.get("held_item"));
                }
            } catch (RuntimeException ex) {
                CombatOverhaulMod.LOGGER.warn(
                        "[CombatOverhaul] 拒绝覆盖 {}:{}：原模型 JSON 无法解析，避免损坏几何",
                        r.modid, r.path, ex);
                return false;
            }
        }
        boolean ok = ModelStore.write(r.modid, r.path, GSON.toJson(out));
        r.exported = ok;
        return ok;
    }

    /** 是否包含非空 roots（有可渲染几何） */
    private static boolean hasRoots(JsonObject json) {
        return json.has("roots") && json.get("roots").isJsonObject()
                && json.getAsJsonObject("roots").size() > 0;
    }

    // ------------------------------------------------------------------ 扫描

    private static ScanResult scanOne(String modid, String path, EntityType<?> type,
                                      EntityRenderer<?> renderer, String textureSpec) {
        JsonObject json = new JsonObject();
        json.addProperty("format", ModelStore.FORMAT_VERSION);
        json.addProperty("entity", modid + ":" + path);
        json.addProperty("modid", modid);
        json.addProperty("uv_normalized", true);
        json.addProperty("note", "Combat Overhaul 自动导出：角度单位为度，UV 为 0~1 归一化坐标；"
                + "1.20.1 模型系统不存储方块级旋转（烘入顶点）。");

        if (renderer == null) {
            return new ScanResult(modid, path, "none", json);
        }
        if (isGeckoLib(renderer.getClass())) {
            // GeckoLib：反射取 geo.json + 贴图，解析为内部几何；失败则回退占位标记
            GeckoModelLoader.Result res = GeckoModelLoader.load(Minecraft.getInstance(), type, renderer);
            json.addProperty("source", "geckolib");
            if (res != null && res.geometry != null && res.geometry.has("roots")) {
                if (res.textureSpec != null) {
                    json.addProperty("texture", res.textureSpec);
                }
                json.add("roots", res.geometry.getAsJsonObject("roots"));
                return new ScanResult(modid, path, "geckolib", json);
            }
            json.addProperty("hint", "GeckoLib 模型：几何数据位于该 mod 资源包 assets/<modid>/geckolib/models/ 下的 geo.json，本文件仅作占位标记。");
            return new ScanResult(modid, path, "geckolib", json);
        }

        JsonObject roots = extractRoots(renderer);
        if (roots.size() == 0) {
            json.addProperty("source", "none");
        } else {
            json.addProperty("source", "vanilla");
            // 固定默认贴图（如玩家变体的 steve/alex），渲染端优先读该字段，不回落到实例皮肤
            if (textureSpec != null && !textureSpec.isEmpty()) {
                json.addProperty("texture", textureSpec);
            }
            json.add("roots", roots);
            // 人形通用骨骼别名表：真实字段名 → head/body/right_arm…（自动识别，无需人工对照）
            JsonObject aliases = humanoidAliases(renderer, roots);
            if (aliases != null) {
                // 玩家外层（hat/jacket/sleeve/pants）在 ModelPart 里是独立根；
                // 挂回 head/body/arm/leg 后，原生动作旋转手臂时外层才会跟着动。
                if (renderer instanceof LivingEntityRenderer<?, ?> ler
                        && ler.getModel() instanceof net.minecraft.client.model.PlayerModel<?>) {
                    reparentPlayerOuterLayers(roots, aliases);
                    removePlayerHelperParts(roots, aliases);
                    zeroPlayerBasePose(roots);
                    json.addProperty("outer_layers_parented", true);
                    json.addProperty("player_pose_clean", true);
                    json.addProperty("player_helpers_removed", true);
                }
                json.add("bone_aliases", aliases);
            }
        }
        return new ScanResult(modid, path, roots.size() == 0 ? "none" : "vanilla", json);
    }

    /** 把玩家外层骨骼挂回对应主骨骼，pivot 转成相对父级坐标。 */
    private static void reparentPlayerOuterLayers(JsonObject roots, JsonObject aliases) {
        Map<String, JsonObject> bySemantic = new LinkedHashMap<>();
        Map<String, String> nameBySemantic = new LinkedHashMap<>();
        for (String field : roots.keySet()) {
            String semantic = aliases.has(field) ? aliases.get(field).getAsString() : field;
            JsonObject part = roots.getAsJsonObject(field);
            bySemantic.put(semantic, part);
            nameBySemantic.put(semantic, field);
        }
        String[][] pairs = {
                {"head", "hat"},
                {"body", "jacket"},
                {"right_arm", "right_sleeve"},
                {"left_arm", "left_sleeve"},
                {"right_leg", "right_pants"},
                {"left_leg", "left_pants"}
        };
        for (String[] pair : pairs) {
            JsonObject parent = bySemantic.get(pair[0]);
            JsonObject child = bySemantic.get(pair[1]);
            String childField = nameBySemantic.get(pair[1]);
            if (parent == null || child == null || childField == null || parent == child) {
                continue;
            }
            roots.remove(childField);
            JsonArray pp = parent.getAsJsonArray("pivot");
            JsonArray cp = child.getAsJsonArray("pivot");
            if (pp != null && cp != null && pp.size() == 3 && cp.size() == 3) {
                JsonArray local = new JsonArray();
                for (int i = 0; i < 3; i++) {
                    local.add(cp.get(i).getAsFloat() - pp.get(i).getAsFloat());
                }
                child.add("pivot", local);
            }
            JsonObject children = parent.getAsJsonObject("children");
            if (children == null) {
                children = new JsonObject();
                parent.add("children", children);
            }
            children.add(childField, child);
        }
    }

    /**
     * PlayerModel 的 cloak / 左右耳是渲染辅助骨骼，不是皮肤几何：
     * 导出后会被当成贴图方块，没披风也会画一块假披风。直接移除。
     */
    private static void removePlayerHelperParts(JsonObject roots, JsonObject aliases) {
        List<String> remove = new ArrayList<>();
        for (String field : roots.keySet()) {
            if (aliases.has(field)) {
                continue;
            }
            JsonObject part = roots.getAsJsonObject(field);
            JsonArray cubes = part.getAsJsonArray("cubes");
            if (cubes == null || cubes.isEmpty()) {
                remove.add(field);
                continue;
            }
            JsonObject cube = cubes.get(0).getAsJsonObject();
            JsonArray size = cube.getAsJsonArray("size");
            if (size != null && size.size() == 3) {
                float sx = size.get(0).getAsFloat();
                float sy = size.get(1).getAsFloat();
                float sz = size.get(2).getAsFloat();
                boolean cloak = Math.abs(sx - 10f) < 0.01f && Math.abs(sy - 16f) < 0.01f
                        && Math.abs(sz - 1f) < 0.01f;
                boolean ear = Math.abs(sx - 6f) < 0.01f && Math.abs(sy - 6f) < 0.01f
                        && Math.abs(sz - 1f) < 0.01f;
                if (cloak || ear) {
                    remove.add(field);
                }
            }
        }
        for (String field : remove) {
            roots.remove(field);
        }
    }

    private static void zeroPlayerBasePose(JsonObject roots) {        for (String key : roots.keySet()) {
            zeroBonePose(roots.getAsJsonObject(key));
        }
    }

    private static void zeroBonePose(JsonObject bone) {
        JsonArray zero = new JsonArray();
        zero.add(0f);
        zero.add(0f);
        zero.add(0f);
        bone.add("rotation_deg", zero);
        JsonArray one = new JsonArray();
        one.add(1f);
        one.add(1f);
        one.add(1f);
        bone.add("scale", one);
        JsonObject children = bone.getAsJsonObject("children");
        if (children != null) {
            for (String key : children.keySet()) {
                zeroBonePose(children.getAsJsonObject(key));
            }
        }
    }

    private static JsonObject extractRoots(EntityRenderer<?> renderer) {        JsonObject roots = new JsonObject();
        IdentityHashMap<ModelPart, Boolean> visited = new IdentityHashMap<>();

        // 1) 活体渲染器：接口方法直接拿模型（无反射）
        EntityModel<?> model = null;
        if (renderer instanceof LivingEntityRenderer<?, ?> ler) {
            model = ler.getModel();
        }
        // 2) 非活体渲染器：字段里找 EntityModel（如末影龙）
        if (model == null) {
            model = (EntityModel<?>) findFieldValue(renderer, EntityModel.class);
        }
        // 2.5) 模型藏在 Map 字段里（如船：Map<Boat.Type, Pair<ResourceLocation, ListModel>>）
        if (model == null) {
            model = findModelInMapFields(renderer);
        }
        if (model != null) {
            if (model instanceof HierarchicalModel<?> hm && hm.root() != null) {
                roots.add("root", partTree(hm.root(), "root", visited));
                return roots;
            }
            // 非层级模型：优先用名为 root 的 ModelPart 作为唯一根（船等）；否则收集所有 ModelPart 字段
            ModelPart rootPart = findNamedPart(model, "root");
            if (rootPart != null) {
                roots.add("root", partTree(rootPart, "root", visited));
                return roots;
            }
            collectFieldParts(model, roots, visited);
            if (roots.size() > 0) {
                return roots;
            }
        }

        // 3) 渲染器字段中直接的 ModelPart（船、唤魔者尖牙等）
        collectFieldParts(renderer, roots, visited);
        return roots;
    }

    /**
     * 人形模型通用骨骼别名：真实字段名（生产环境为 SRG，如 f_102808_）→ 语义名。
     *
     * <p>直接字段引用（{@code hm.head} 等）在 reobf 后会自动换成 SRG 名，所以这里用
     * 对象身份比对就能把「哪个字段是头」确定下来，不需要任何人工对照表或映射文件。
     * 非人形模型返回 null。
     */
    private static JsonObject humanoidAliases(EntityRenderer<?> renderer, JsonObject roots) {
        EntityModel<?> model = null;
        if (renderer instanceof LivingEntityRenderer<?, ?> ler) {
            model = ler.getModel();
        }
        if (model == null) {
            model = (EntityModel<?>) findFieldValue(renderer, EntityModel.class);
        }
        if (!(model instanceof HumanoidModel<?> hm)) {
            return null;
        }

        Map<ModelPart, String> semantic = new IdentityHashMap<>();
        semantic.put(hm.head, "head");
        semantic.put(hm.hat, "hat");
        semantic.put(hm.body, "body");
        semantic.put(hm.rightArm, "right_arm");
        semantic.put(hm.leftArm, "left_arm");
        semantic.put(hm.rightLeg, "right_leg");
        semantic.put(hm.leftLeg, "left_leg");
        if (model instanceof PlayerModel<?> pm) {
            semantic.put(pm.leftSleeve, "left_sleeve");
            semantic.put(pm.rightSleeve, "right_sleeve");
            semantic.put(pm.leftPants, "left_pants");
            semantic.put(pm.rightPants, "right_pants");
            semantic.put(pm.jacket, "jacket");
        }

        JsonObject out = new JsonObject();
        for (String key : roots.keySet()) {
            ModelPart part = findNamedPart(model, key);
            if (part == null) {
                continue;
            }
            String s = semantic.get(part);
            if (s != null && !out.has(key)) {
                out.addProperty(key, s);
            }
        }
        return out.size() == 0 ? null : out;
    }

    private static void collectFieldParts(Object holder, JsonObject roots, IdentityHashMap<ModelPart, Boolean> visited) {
        for (Class<?> k = holder.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!ModelPart.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object value = f.get(holder);
                    if (value instanceof ModelPart part && !visited.containsKey(part) && !roots.has(f.getName())) {
                        roots.add(f.getName(), partTree(part, f.getName(), visited));
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // 单个字段失败不阻塞整体导出
                }
            }
        }
    }

    /** 在对象字段（含父类）中查找指定名称的 ModelPart。 */
    private static ModelPart findNamedPart(Object holder, String name) {
        for (Class<?> k = holder.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!ModelPart.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(holder);
                    if (value instanceof ModelPart && f.getName().equals(name)) {
                        return (ModelPart) value;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ ModelPart 树

    private static JsonObject partTree(ModelPart part, String name, IdentityHashMap<ModelPart, Boolean> visited) {
        visited.put(part, Boolean.TRUE);
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.add("pivot", floats(part.x, part.y, part.z));
        o.add("rotation_deg", floats(
                Math.toDegrees(part.xRot), Math.toDegrees(part.yRot), Math.toDegrees(part.zRot)));
        o.add("scale", floats(part.xScale, part.yScale, part.zScale));
        o.addProperty("visible", part.visible);
        o.add("cubes", cubesArray(part));
        o.add("children", childrenObject(part, visited));
        return o;
    }

    private static JsonArray cubesArray(ModelPart part) {
        JsonArray arr = new JsonArray();
        List<?> cubes = (List<?>) readTypedField(part, List.class);
        if (cubes != null) {
            for (Object c : cubes) {
                if (c instanceof ModelPart.Cube cube) {
                    arr.add(cubeJson(cube));
                }
            }
        }
        return arr;
    }

    private static JsonObject cubeJson(ModelPart.Cube cube) {
        JsonObject o = new JsonObject();
        o.add("origin", floats(cube.minX, cube.minY, cube.minZ));
        o.add("size", floats(cube.maxX - cube.minX, cube.maxY - cube.minY, cube.maxZ - cube.minZ));
        JsonObject[] fv = facesJson(cube);
        if (fv != null) {
            o.add("faces", fv[0]);
            o.add("face_verts", fv[1]);
            if (fv.length >= 3 && fv[2] != null && fv[2].has("mirror")) {
                o.addProperty("mirror", fv[2].get("mirror").getAsBoolean());
            }
        }
        return o;
    }

    private static JsonObject childrenObject(ModelPart part, IdentityHashMap<ModelPart, Boolean> visited) {
        JsonObject children = new JsonObject();
        Map<?, ?> map = (Map<?, ?>) readTypedField(part, Map.class);
        if (map != null) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object value = e.getValue();
                if (e.getKey() instanceof String childName && value instanceof ModelPart child
                        && !visited.containsKey(child)) {
                    children.add(childName, partTree(child, childName, visited));
                }
            }
        }
        return children;
    }

    // ------------------------------------------------------------------ UV 面（附加信息，失败可省略）

    /**
     * 导出每面 UV（0~1）与顶点坐标，均保持原版 Polygon 顶点的原始数组顺序（含镜像反转后的顺序）。
     * 渲染端按同一顺序画 QUADS，与原版逐顶点一致，无需任何重排——彻底规避镜像/顺序错乱。
     *
     * <p>面归类优先按 {@code Polygon.normal}（原版构造时写入的模型空间法线）。旧几何归类对
     * 零厚度立方体（鳕鱼鳍、监守者触角、末影龙翅膜等）会把 4 个共面的退化面
     * （north/south/west/east 顶点 y 极差≈0）与真实可见面（down/up）归到同一键互相覆盖，
     * 导致真实可见面的 UV 被退化面（v 跨度=0）摧毁、装饰不显示；按法线归类彻底避免。
     *
     * @return 长度 2 的数组：[faces UV 对象, face_verts 顶点坐标对象]；失败返回 null
     */
    private static JsonObject[] facesJson(ModelPart.Cube cube) {
        try {
            Object polygonsObj = readDeclaredField(cube, "ModelPart$Polygon array");
            Object[] polys = polygonsObj instanceof Object[] arr ? arr : null;
            if (polys == null || polys.length == 0) {
                return null;
            }
            Class<?> polyClass = polys.getClass().getComponentType();
            Field verticesF = null;
            for (Field f : polyClass.getDeclaredFields()) {
                if (f.getType().isArray() && f.getType().getComponentType().getSimpleName().equals("Vertex")) {
                    verticesF = f;
                    break;
                }
            }
            if (verticesF == null) {
                return null;
            }
            verticesF.setAccessible(true);
            // Polygon.normal：模型空间面法线（Polygon 类唯一的 Vector3f 字段）
            Field normalF = null;
            for (Field f : polyClass.getDeclaredFields()) {
                if (f.getType() == Vector3f.class) {
                    normalF = f;
                    break;
                }
            }

            float minX = cube.minX, minY = cube.minY, minZ = cube.minZ;
            float maxX = cube.maxX, maxY = cube.maxY, maxZ = cube.maxZ;
            JsonObject faces = new JsonObject();
            JsonObject faceVerts = new JsonObject();
            for (Object poly : polys) {
                Object[] verts = (Object[]) verticesF.get(poly);
                if (verts == null || verts.length != 4) {
                    continue;
                }
                float[][] pos = new float[4][];
                float[][] uv = new float[4][];
                boolean ok = true;
                for (int k = 0; k < 4; k++) {
                    pos[k] = vertexPos(verts[k]);
                    uv[k] = vertexUv(verts[k]);
                    if (pos[k] == null || Float.isNaN(uv[k][0]) || Float.isNaN(uv[k][1])) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
                float[] normal = normalF != null ? normalOf(normalF, poly) : null;
                int face = classifyFace(pos, normal, minX, minY, minZ, maxX, maxY, maxZ);
                if (face < 0) {
                    continue;
                }
                // 原始顺序存 UV 与顶点坐标（一一对应，渲染端原样回放）
                JsonArray uvArr = new JsonArray();
                JsonArray vertArr = new JsonArray();
                for (int k = 0; k < 4; k++) {
                    JsonArray uvp = new JsonArray();
                    uvp.add(round(uv[k][0]));
                    uvp.add(round(uv[k][1]));
                    uvArr.add(uvp);
                    JsonArray pp = new JsonArray();
                    pp.add(round(pos[k][0]));
                    pp.add(round(pos[k][1]));
                    pp.add(round(pos[k][2]));
                    vertArr.add(pp);
                }
                faces.add(ParsedCube.FACE_NAMES[face], uvArr);
                faceVerts.add(ParsedCube.FACE_NAMES[face], vertArr);
            }
            if (faces.size() == 0) {
                return null;
            }
            JsonObject mirrorObj = new JsonObject();
            // 扫描结果一律带 face_verts（逐面精确顶点+UV），UV 已经是镜像后的最终值；
            // 再写 mirror=true 会让下游（GeoExporter/Blockbench）二次翻转 U，反而导出错误。
            // 因此这里固定 false，镜像信息由 face_verts 本身承载。
            mirrorObj.addProperty("mirror", false);
            return new JsonObject[]{faces, faceVerts, mirrorObj};
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null; // UV 为附加信息，失败不阻塞导出
        }
    }

    /** 多边形自身共面判定容差（CubeDeformation 膨胀会把顶点推出包围盒，不能用固定 EPS 比对 min/max） */
    private static final float COPLANAR_EPS = 0.01f;

    /**
     * 面归类：先找 4 顶点共面的轴（该轴上顶点坐标极差≈0），再比较共面坐标与包围盒两端距离取近端。
     * 0=down 1=up 2=west 3=east 4=north 5=south（与 ParsedCube.FACE_NAMES 一致）。
     * 对任意 CubeDeformation 膨胀量鲁棒（如马躯干 grow=0.05 时顶点超出 minX/maxX 仍能归类）。
     */
    private static int geometricFace(float[][] pos, float minX, float minY, float minZ,
                                     float maxX, float maxY, float maxZ) {
        float[] bounds = {minX, minY, minZ, maxX, maxY, maxZ};
        for (int axis = 0; axis < 3; axis++) {
            float pMin = Float.MAX_VALUE, pMax = -Float.MAX_VALUE;
            for (int k = 0; k < 4; k++) {
                pMin = Math.min(pMin, pos[k][axis]);
                pMax = Math.max(pMax, pos[k][axis]);
            }
            if (pMax - pMin > COPLANAR_EPS) {
                continue;
            }
            float mid = (pMin + pMax) / 2f;
            boolean nearerMin = Math.abs(mid - bounds[axis]) <= Math.abs(mid - bounds[axis + 3]);
            return switch (axis) {
                case 0 -> nearerMin ? 2 : 3;
                case 1 -> nearerMin ? 0 : 1;
                default -> nearerMin ? 4 : 5;
            };
        }
        return -1;
    }

    /**
     * 面归类（法线优先）：Y/Z 轴面直接按法线符号归类（down/up、north/south），
     * 任何厚度的立方体都不会再把共面退化面覆盖到真实可见面上。
     * X 轴面（west/east）：mirror 只翻转 normal.x 而不交换 minX/maxX，法线符号不可靠，
     * 故按顶点几何侧归类（均值更近 minX→west / maxX→east）；平局（dx≈0 的零厚度退化面）
     * 两端等距、无真实几何侧，回退法线符号（该面 UV 跨度必为 0，归类结果无视觉影响）。
     * 法线不可读时回退旧几何归类 {@link #geometricFace}。
     */
    private static int classifyFace(float[][] pos, float[] normal, float minX, float minY, float minZ,
                                    float maxX, float maxY, float maxZ) {
        if (normal == null || (normal[0] == 0 && normal[1] == 0 && normal[2] == 0)) {
            return geometricFace(pos, minX, minY, minZ, maxX, maxY, maxZ);
        }
        float ax = Math.abs(normal[0]);
        float ay = Math.abs(normal[1]);
        float az = Math.abs(normal[2]);
        if (ay >= ax && ay >= az) {
            return normal[1] < 0 ? 0 : 1;
        }
        if (az >= ax) {
            return normal[2] < 0 ? 4 : 5;
        }
        float avg = 0;
        for (float[] p : pos) {
            avg += p[0];
        }
        avg /= pos.length;
        float distMin = Math.abs(avg - minX);
        float distMax = Math.abs(avg - maxX);
        if (Math.abs(distMin - distMax) <= COPLANAR_EPS) {
            return normal[0] < 0 ? 2 : 3;
        }
        return distMin < distMax ? 2 : 3;
    }

    /** 读取 Polygon 的法线向量 {x, y, z}；不可读返回 null。 */
    private static float[] normalOf(Field normalF, Object poly) {
        try {
            normalF.setAccessible(true);
            Vector3f n = (Vector3f) normalF.get(poly);
            return n == null ? null : new float[]{n.x(), n.y(), n.z()};
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }

    /** 读取顶点位置。Vertex 类唯一的 Vector3f 字段即 pos（按类型查找，免字段名依赖）。 */
    private static float[] vertexPos(Object vertex) {
        for (Field f : vertex.getClass().getDeclaredFields()) {
            if (f.getType() != Vector3f.class) {
                continue;
            }
            try {
                f.setAccessible(true);
                Vector3f v = (Vector3f) f.get(vertex);
                return v == null ? null : new float[]{v.x(), v.y(), v.z()};
            } catch (IllegalAccessException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 读取顶点 UV。Vertex 仅两个 float 字段 u、v。优先按字段名（u/v 及 SRG 形式）匹配，
     * 否则回退声明顺序前两个。返回 {u, v}。
     */
    private static float[] vertexUv(Object vertex) throws IllegalAccessException {
        Float u = null;
        Float v = null;
        float first = Float.NaN;
        float second = Float.NaN;
        int idx = 0;
        for (Field f : vertex.getClass().getDeclaredFields()) {
            if (f.getType() != float.class) {
                continue;
            }
            f.setAccessible(true);
            float value = f.getFloat(vertex);
            String n = f.getName().toLowerCase(Locale.ROOT);
            // 官方名 u/v；SRG 名形如 f_104378_ / f_104379_，无法用名区分时靠声明顺序回退
            if (n.equals("u")) {
                u = value;
            } else if (n.equals("v")) {
                v = value;
            }
            if (idx == 0) {
                first = value;
            } else if (idx == 1) {
                second = value;
            }
            idx++;
        }
        if (u != null && v != null) {
            return new float[]{u, v};
        }
        return new float[]{first, second};
    }

    // ------------------------------------------------------------------ 反射工具

    /** 在对象字段（含父类）中找第一个指定类型的实例值。 */
    private static Object findFieldValue(Object holder, Class<?> type) {
        for (Class<?> k = holder.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!type.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object value = f.get(holder);
                    if (type.isInstance(value)) {
                        return value;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // 继续
                }
            }
        }
        return null;
    }

    /**
     * 在对象字段（含父类）的 Map 中查找 EntityModel：
     * 遍历 Map 的 value，若 value 是 Pair 则取其 right 元素，若是 EntityModel 则返回。
     * 用于船等渲染器——模型藏在 Map<Enum, Pair<ResourceLocation, EntityModel>> 里。
     */
    private static EntityModel<?> findModelInMapFields(Object holder) {
        for (Class<?> k = holder.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Map<?, ?> map = (Map<?, ?>) f.get(holder);
                    if (map == null) continue;
                    for (Object v : map.values()) {
                        EntityModel<?> m = extractModelFromValue(v);
                        if (m != null) return m;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // 继续
                }
            }
        }
        return null;
    }

    /** 从任意值中提取 EntityModel：直接实例、Pair 的 right、嵌套 Collection/Map 里的首个。 */
    @SuppressWarnings("unchecked")
    private static EntityModel<?> extractModelFromValue(Object v) {
        if (v == null) return null;
        if (v instanceof EntityModel<?>) return (EntityModel<?>) v;
        if (v instanceof com.mojang.datafixers.util.Pair<?, ?> pair) {
            Object right = pair.getSecond();
            if (right instanceof EntityModel<?>) return (EntityModel<?>) right;
            // 部分 Pair 的 left 才是模型
            Object left = pair.getFirst();
            if (left instanceof EntityModel<?>) return (EntityModel<?>) left;
        }
        if (v instanceof java.util.Collection<?> coll) {
            for (Object e : coll) {
                EntityModel<?> m = extractModelFromValue(e);
                if (m != null) return m;
            }
        }
        if (v instanceof Map<?, ?> m2) {
            for (Object e : m2.values()) {
                EntityModel<?> m = extractModelFromValue(e);
                if (m != null) return m;
            }
        }
        return null;
    }

    /** 按字段类型读取对象字段值（cubes: List / children: Map），与混淆映射无关。 */
    private static Object readTypedField(Object holder, Class<?> fieldType) {
        try {
            for (Field f : holder.getClass().getDeclaredFields()) {
                if (fieldType.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f.get(holder);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 返回 null
        }
        return null;
    }

    /** 读取 Cube 的多边形数组（包私有类型，按组件类名识别）。 */
    private static Object readDeclaredField(Object cube, String desc) throws IllegalAccessException {
        for (Field f : cube.getClass().getDeclaredFields()) {
            if (f.getType().isArray() && f.getType().getComponentType().getSimpleName().contains("Polygon")) {
                f.setAccessible(true);
                return f.get(cube);
            }
        }
        return null;
    }

    /** 取渲染调度器的 renderers 表（按泛型签名识别，映射无关）。 */
    @SuppressWarnings("unchecked")
    private static Map<EntityType<?>, EntityRenderer<?>> findRenderers(EntityRenderDispatcher dispatcher) {
        try {
            for (Field f : EntityRenderDispatcher.class.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())
                        && f.getGenericType().getTypeName().contains("EntityRenderer")) {
                    f.setAccessible(true);
                    return (Map<EntityType<?>, EntityRenderer<?>>) f.get(dispatcher);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            CombatOverhaulMod.LOGGER.error("[CombatOverhaul] 无法访问实体渲染器表，模型导出不可用", e);
        }
        return null;
    }

    /** 按父类类名识别 GeckoLib 渲染器。 */
    private static boolean isGeckoLib(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().toLowerCase(Locale.ROOT).contains("geckolib")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取玩家渲染器表（key = "default"/"slim"，Map&lt;String, PlayerRenderer&gt;）。
     * 泛型签名不被混淆，按签名含 "PlayerRenderer" 识别字段。不可得返回 null。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, EntityRenderer<?>> findPlayerRenderers(EntityRenderDispatcher dispatcher) {
        // 不能靠泛型字符串判断：该字段实际签名是 Map<String, EntityRenderer<? extends Player>>，
        // 里面不含 "PlayerRenderer" 子串（此前 checks contains("PlayerRenderer") 恒为 false，
        // 导致永远退回 getRenderer(mc.player) 单变体探针 → Steve/Alex 只导出一个且文件名恒为 player）。
        // 改为逐个 Map 字段取值、按元素实际类型判断。
        for (Field f : EntityRenderDispatcher.class.getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(f.getType())) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object value = f.get(dispatcher);
                if (value instanceof Map<?, ?> map && !map.isEmpty()) {
                    Object first = map.values().iterator().next();
                    if (first instanceof PlayerRenderer) {
                        return (Map<String, EntityRenderer<?>>) (Map<?, ?>) map;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 单个字段失败继续找下一个
            }
        }
        CombatOverhaulMod.LOGGER.warn("[CombatOverhaul] 无法访问玩家渲染器表，玩家模型将仅导出当前变体");
        return null;
    }

    // ------------------------------------------------------------------ JSON 工具

    private static JsonArray floats(double a, double b, double c) {
        JsonArray arr = new JsonArray();
        arr.add(round(a));
        arr.add(round(b));
        arr.add(round(c));
        return arr;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
