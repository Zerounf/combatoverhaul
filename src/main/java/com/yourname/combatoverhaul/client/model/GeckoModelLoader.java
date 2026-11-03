package com.yourname.combatoverhaul.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GeckoLib（1.20.1 / 4.x）几何提取（仅客户端）：
 *
 * <ul>
 *   <li>从实体渲染器反射取得 {@code GeoModel} 实例；</li>
 *   <li>调用 {@code getModelResource}/{@code getTextureResource} 取 geo.json 与贴图的
 *       {@link ResourceLocation}（参数为临时实体实例，多数实现忽略该参数）；</li>
 *   <li>读取并解析 Bedrock 几何（{@code minecraft:geometry} 或旧版 {@code geometry.*}），
 *       转换为编辑器内部格式（roots + 骨骼 + 立方体 + 逐面 UV + 立方体自身旋转）；</li>
 *   <li>坐标系转换为编辑器（= 原版 ModelPart）模型空间，已用 Bedrock 版
 *       {@code statue/player.geo.json} 与原版 {@code player.json} 导出逐字段比对验证：
 *       <ul>
 *       <li>绝对位置：{@code editor = (bx, 24 − by, bz)}（x 不变、y 取负再加 24、z 不变）；</li>
 *       <li>骨骼枢轴：编辑器存<b>相对父级</b>值（Bedrock 为绝对枢轴，需相减）；</li>
 *       <li>立方体 origin / face_verts / rot_pivot：编辑器存<b>相对本骨骼枢轴</b>的局部值；</li>
 *       <li>旋转：编辑器直接取 Bedrock 原值 (rx, ry, rz)（骨骼与立方体皆然，
 *           GeckoLib 的 X/Y 取负与其自身 x 镜像空间相互抵消）；</li>
 *       <li>面槽位：GeckoLib 方向 d → 编辑器槽位 west→east、east→west、
 *           up→down、down→up、north/south 不变（x 镜像 + y 翻转所致）。</li>
 *       </ul></li>
 *   <li>盒式 UV 按 GeckoLib 的 {@code BakedModelFactory#buildQuad} 布局展开为逐面归一化 UV
 *       （UV 区域与顶点几何成对写入，与 {@code GeoQuad#build} 逐条一致）。</li>
 * </ul>
 *
 * <p>任何一步失败都返回 null，调用方回退为占位导出（不抛异常）。
 */
public final class GeckoModelLoader {

    /** 提取结果：内部格式几何 JSON（含 roots 字段）+ 贴图路径（可为 null） */
    public static final class Result {
        public final JsonObject geometry;
        public final String textureSpec;

        Result(JsonObject geometry, String textureSpec) {
            this.geometry = geometry;
            this.textureSpec = textureSpec;
        }
    }

    private GeckoModelLoader() {
    }

    /** 从 GeckoLib 渲染器提取几何与贴图；失败返回 null。 */
    public static Result load(Minecraft mc, EntityType<?> type, Object renderer) {
        try {
            if (mc == null || renderer == null) {
                return null;
            }
            Object geoModel = findGeoModel(renderer);
            if (geoModel == null) {
                return null;
            }
            Object animatable = null;
            if (type != null && mc.level != null) {
                try {
                    animatable = type.create(mc.level);
                } catch (RuntimeException ignored) {
                    // 部分实体需要更多上下文，拿不到实例就用 null 调资源方法
                }
            }
            ResourceLocation modelRl = invokeResource(geoModel, animatable,
                    "getModelResource", "getModelLocation");
            if (modelRl == null) {
                return null;
            }
            JsonObject geo = readGeo(mc, modelRl);
            if (geo == null) {
                return null;
            }
            JsonObject geometry = convert(geo);
            if (geometry == null || !geometry.has("roots")) {
                return null;
            }
            ResourceLocation texRl = invokeResource(geoModel, animatable,
                    "getTextureResource", "getTextureLocation");
            return new Result(geometry, texRl == null ? null : texRl.toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 反射

    /** 从渲染器找 GeoModel 实例：优先无参方法，其次类型名含 GeoModel 的字段。 */
    private static Object findGeoModel(Object renderer) {
        for (String name : new String[]{"getGeoModel", "getModel"}) {
            Object v = invokeNoArg(renderer, name);
            if (v != null && typeNameContains(v.getClass(), "GeoModel")) {
                return v;
            }
        }
        for (Class<?> k = renderer.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!f.getType().getName().contains("GeoModel")) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(renderer);
                    if (v != null) {
                        return v;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // 继续找
                }
            }
        }
        return null;
    }

    private static boolean typeNameContains(Class<?> cls, String needle) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static Object invokeNoArg(Object target, String name) {
        for (Class<?> k = target.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != 0) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    return m.invoke(target);
                } catch (Throwable ignored) {
                    // 继续
                }
            }
        }
        return null;
    }

    /** 调用单参资源方法（getModelResource / getTextureResource 等），返回 ResourceLocation。 */
    private static ResourceLocation invokeResource(Object geoModel, Object animatable, String... names) {
        for (String name : names) {
            for (Class<?> k = geoModel.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Method m : k.getDeclaredMethods()) {
                    if (!m.getName().equals(name) || m.getParameterCount() != 1) {
                        continue;
                    }
                    if (animatable != null) {
                        ResourceLocation r = tryInvokeResource(m, geoModel, animatable);
                        if (r != null) {
                            return r;
                        }
                    }
                    ResourceLocation r = tryInvokeResource(m, geoModel, null);
                    if (r != null) {
                        return r;
                    }
                }
            }
        }
        return null;
    }

    private static ResourceLocation tryInvokeResource(Method m, Object geoModel, Object arg) {
        try {
            m.setAccessible(true);
            Object r = m.invoke(geoModel, arg);
            return r instanceof ResourceLocation rl ? rl : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 读取 geo.json

    private static JsonObject readGeo(Minecraft mc, ResourceLocation rl) {
        JsonObject o = tryReadJson(mc, rl);
        if (o != null) {
            return o;
        }
        // GeckoLib 4.4+ 亦支持 assets/<ns>/geckolib/models/<path> 的放置方式
        if (!rl.getPath().startsWith("geckolib/")) {
            try {
                ResourceLocation alt = new ResourceLocation(rl.getNamespace(), "geckolib/models/" + rl.getPath());
                o = tryReadJson(mc, alt);
            } catch (RuntimeException ignored) {
                // 路径非法，忽略
            }
        }
        return o;
    }

    private static JsonObject tryReadJson(Minecraft mc, ResourceLocation rl) {
        try (InputStream in = mc.getResourceManager().open(rl)) {
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonElement el = JsonParser.parseString(s);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Bedrock → 内部格式

    public static JsonObject convert(JsonObject geo) {
        JsonObject def = firstDefinition(geo);
        if (def == null) {
            return null;
        }
        int texW = 16;
        int texH = 16;
        JsonObject desc = def.has("description") && def.get("description").isJsonObject()
                ? def.getAsJsonObject("description") : null;
        if (desc != null) {
            texW = intOr(desc, "texture_width", texW);
            texH = intOr(desc, "texture_height", texH);
        } else {
            texW = intOr(def, "texturewidth", texW);
            texH = intOr(def, "textureheight", texH);
        }
        texW = Math.max(1, texW);
        texH = Math.max(1, texH);

        if (!def.has("bones") || !def.get("bones").isJsonArray()) {
            return null;
        }
        JsonArray bones = def.getAsJsonArray("bones");

        Map<String, JsonObject> byName = new LinkedHashMap<>();
        Map<String, String> parentOf = new HashMap<>();
        // Bedrock 绝对枢轴（模型空间原值）
        Map<String, float[]> absPivot = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (JsonElement be : bones) {
            if (!be.isJsonObject()) {
                continue;
            }
            JsonObject b = be.getAsJsonObject();
            String name = strOr(b, "name", null);
            if (name == null || name.isEmpty() || byName.containsKey(name)) {
                continue;
            }
            float[] pivot = floats3(b, "pivot");
            absPivot.put(name, pivot);
            JsonObject bj = new JsonObject();
            bj.addProperty("name", name);
            // pivot 稍后按"最终父级"的相对值填充（见下）
            bj.add("pivot", arr3(0, 0, 0));
            // 旋转：编辑器直接取 Bedrock 原值
            float[] rot = floats3(b, "rotation");
            bj.add("rotation_deg", arr3(rot[0], rot[1], rot[2]));
            bj.add("scale", arr3(1, 1, 1));
            bj.addProperty("visible", true);
            JsonArray cubes = new JsonArray();
            float boneInflate = floatOr(b, "inflate", 0f);
            if (b.has("cubes") && b.get("cubes").isJsonArray()) {
                for (JsonElement ce : b.getAsJsonArray("cubes")) {
                    if (!ce.isJsonObject()) {
                        continue;
                    }
                    JsonObject cube = convertCube(ce.getAsJsonObject(), texW, texH, pivot, boneInflate);
                    if (cube != null) {
                        cubes.add(cube);
                    }
                }
            }
            if (cubes.size() > 0) {
                bj.add("cubes", cubes);
            }
            byName.put(name, bj);
            order.add(name);
            String p = strOr(b, "parent", null);
            if (p != null && !p.isEmpty() && !p.equals(name)) {
                parentOf.put(name, p);
            }
        }
        if (byName.isEmpty()) {
            return null;
        }

        // 按 parent 组装树；出现环（child 是 parent 的祖先）时退化为根，避免无限嵌套
        JsonObject roots = new JsonObject();
        Set<String> rootNames = new LinkedHashSet<>();
        Map<String, String> finalParent = new HashMap<>();
        for (String n : order) {
            String p = parentOf.get(n);
            boolean attach = p != null && byName.containsKey(p) && !isAncestor(n, p, parentOf);
            if (attach) {
                JsonObject pj = byName.get(p);
                JsonObject ch = pj.has("children") && pj.get("children").isJsonObject()
                        ? pj.getAsJsonObject("children") : new JsonObject();
                ch.add(n, byName.get(n));
                pj.add("children", ch);
                finalParent.put(n, p);
            } else {
                roots.add(n, byName.get(n));
                rootNames.add(n);
            }
        }
        if (rootNames.isEmpty()) {
            // 全部互相为父（环）：强制第一个为根
            String first = order.get(0);
            roots.add(first, byName.get(first));
        }

        // 骨骼枢轴转"相对父级"：编辑器沿父链累加枢轴，而 Bedrock 枢轴为绝对坐标。
        // 子 = 自身编辑器绝对枢轴 − 父级编辑器绝对枢轴；根相对编辑器原点 (0,0,0)。
        for (String n : order) {
            float[] self = editorAbsPivot(absPivot.get(n));
            String p = finalParent.get(n);
            float[] par = p == null ? new float[]{0, 0, 0} : editorAbsPivot(absPivot.get(p));
            byName.get(n).add("pivot", arr3(self[0] - par[0], self[1] - par[1], self[2] - par[2]));
        }

        JsonObject out = new JsonObject();
        out.add("roots", roots);
        return out;
    }

    /** Bedrock 绝对枢轴 → 编辑器绝对枢轴：{@code (bx, 24 − by, bz)}（x/z 不变，y 取负加 24）。 */
    private static float[] editorAbsPivot(float[] bed) {
        if (bed == null) {
            return new float[]{0, 0, 0};
        }
        return new float[]{bed[0], 24.0f - bed[1], bed[2]};
    }

    /** n 是否为 p 的祖先（沿 parent 链向上能否遇到 n）；用于环检测 */
    private static boolean isAncestor(String n, String p, Map<String, String> parentOf) {
        String cur = parentOf.get(p);
        int guard = 0;
        while (cur != null && guard++ < 4096) {
            if (cur.equals(n)) {
                return true;
            }
            cur = parentOf.get(cur);
        }
        return false;
    }

    /** 取第一个几何定义：新版 minecraft:geometry 数组，或旧版 geometry.<name> 对象。 */
    private static JsonObject firstDefinition(JsonObject geo) {
        if (geo.has("minecraft:geometry") && geo.get("minecraft:geometry").isJsonArray()) {
            for (JsonElement e : geo.getAsJsonArray("minecraft:geometry")) {
                if (e.isJsonObject()) {
                    return e.getAsJsonObject();
                }
            }
        }
        for (Map.Entry<String, JsonElement> e : geo.entrySet()) {
            if (e.getKey().startsWith("geometry.") && e.getValue().isJsonObject()) {
                return e.getValue().getAsJsonObject();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 立方体

    /**
     * Bedrock 立方体 → 编辑器立方体（骨骼枢轴局部空间）。
     *
     * <p>先在 GeckoLib 的绝对空间（记作 G 帧：{@code x = -(ox+sx)}，y/z 不变）算 8 个角点
     * （含 inflate），编码 {@code ix*4+iy*2+iz}（ix=0 为 G 空间 x 最小侧）——该编码与
     * {@code VertexSet}/{@code QUAD_*} 顶点序一致，保证 UV 与顶点正确配对。
     * 再把 G 绝对坐标映射到编辑器局部空间：{@code (x,y,z) → (-x - bx, by - y, z - bz)}，
     * 其中 {@code (bx,by,bz)} 为<b>本骨骼的 Bedrock 绝对枢轴</b>（编辑器立方体坐标相对骨骼枢轴）。
     * 每面 4 个顶点显式写入 {@code face_verts}（渲染端优先使用存储顶点），UV 一一对应，杜绝顺序错位。
     */
    private static JsonObject convertCube(JsonObject bed, int texW, int texH, float[] bonePivot,
                                         float boneInflate) {
        float[] origin = floats3(bed, "origin");
        float[] size = floats3(bed, "size");
        // GeckoLib 的语义：cube.inflate 覆盖 bone.inflate；两者都缺省为 0
        float inflate = floatOr(bed, "inflate", boneInflate);
        boolean mirror = boolOr(bed, "mirror", false);
        float[] rot = floats3(bed, "rotation");
        boolean hasPivotKey = bed.has("pivot") && bed.get("pivot").isJsonArray();
        float[] cp = hasPivotKey ? floats3(bed, "pivot") : new float[]{0, 0, 0};
        float bx = bonePivot[0];
        float by = bonePivot[1];
        float bz = bonePivot[2];

        // G 帧 8 角点（含 inflate）：编码 ix*4+iy*2+iz，ix=0 为 G 空间 x 最小侧
        double gx0 = -(origin[0] + size[0] + inflate);   // G x 最小
        double gx1 = -(origin[0] - inflate);             // G x 最大
        double gy0 = origin[1] - inflate;
        double gy1 = origin[1] + size[1] + inflate;
        double gz0 = origin[2] - inflate;
        double gz1 = origin[2] + size[2] + inflate;
        double[][] gv = new double[8][3];
        for (int code = 0; code < 8; code++) {
            gv[code][0] = ((code >> 2) & 1) == 0 ? gx0 : gx1;
            gv[code][1] = ((code >> 1) & 1) == 0 ? gy0 : gy1;
            gv[code][2] = (code & 1) == 0 ? gz0 : gz1;
        }

        JsonObject out = new JsonObject();
        // 编辑器局部包围盒（骨骼枢轴局部空间）
        out.add("origin", arr3(-gx1 - bx, by - gy1, gz0 - bz));
        out.add("size", arr3(gx1 - gx0, gy1 - gy0, gz1 - gz0));
        // 顶点由 face_verts 显式给出，不依赖渲染端镜像翻转
        out.addProperty("mirror", false);
        if (rot[0] != 0 || rot[1] != 0 || rot[2] != 0) {
            // 编辑器直接取 Bedrock 原值；旋转枢轴转为骨骼枢轴局部坐标
            out.add("rot_deg", arr3(rot[0], rot[1], rot[2]));
            out.add("rot_pivot", arr3(cp[0] - bx, by - cp[1], cp[2] - bz));
        }
        JsonObject faces = new JsonObject();
        JsonObject faceVerts = new JsonObject();
        buildCubeFaces(bed.get("uv"), size, texW, texH, mirror, gv, bx, by, bz, faces, faceVerts);
        if (faces.size() > 0) {
            out.add("faces", faces);
            out.add("face_verts", faceVerts);
        }
        return out;
    }

    /**
     * 逐面 UV + 显式顶点。面槽位按 G→编辑器映射 {@code (x,y,z) → (−x, −y, z)} 后的法线方向对应：
     * our east←G west、west←G east、down←G up、up←G down、north/south 不变。
     * 顶点按 {@code (−gv.x − bx, by − gv.y, gv.z − bz)} 写入编辑器<b>骨骼枢轴局部</b>坐标。
     */
    private static void buildCubeFaces(JsonElement uv, float[] size, int texW, int texH, boolean mirror,
                                       double[][] gv, float bx, float by, float bz,
                                       JsonObject faces, JsonObject faceVerts) {
        if (uv != null && uv.isJsonArray()) {
            JsonArray a = uv.getAsJsonArray();
            double bu = a.size() > 0 && a.get(0).isJsonPrimitive() ? a.get(0).getAsDouble() : 0;
            double bv = a.size() > 1 && a.get(1).isJsonPrimitive() ? a.get(1).getAsDouble() : 0;
            for (int d = 0; d < 6; d++) {
                emitFace(d, boxRect(d, bu, bv, size), 0, true, mirror, texW, texH, gv, bx, by, bz, faces, faceVerts);
            }
            return;
        }
        if (uv != null && uv.isJsonObject()) {
            JsonObject o = uv.getAsJsonObject();
            for (int d = 0; d < 6; d++) {
                String name = G_DIR_NAMES[d];
                if (!o.has(name) || !o.get(name).isJsonObject()) {
                    continue;
                }
                JsonObject fo = o.getAsJsonObject(name);
                if (!fo.has("uv") || !fo.get("uv").isJsonArray()) {
                    continue;
                }
                double[] uvp = doubles2(fo, "uv");
                double[] uvs = doubles2(fo, "uv_size");
                if (uvs == null) {
                    uvs = dirFaceSize(d, size);
                }
                emitFace(d, new double[]{uvp[0], uvp[1], uvs[0], uvs[1]},
                        intOr(fo, "uv_rotation", 0), false, mirror, texW, texH, gv, bx, by, bz,
                        faces, faceVerts);
            }
            return;
        }
        // 无 UV 定义：退化为盒式 UV 原点 (0,0)
        for (int d = 0; d < 6; d++) {
            emitFace(d, boxRect(d, 0, 0, size), 0, true, mirror, texW, texH, gv, bx, by, bz, faces, faceVerts);
        }
    }

    /** GeckoLib 四边形方向的名称（G 帧）：0=west 1=east 2=north 3=south 4=up 5=down */
    private static final String[] G_DIR_NAMES = {"west", "east", "north", "south", "up", "down"};

    /** GeckoLib 方向 → 编辑器面槽位（G 帧 → 编辑器为 x、y 双取负，故 west/east、up/down 互换） */
    private static int slotForGDir(int d) {
        return switch (d) {
            case 0 -> 3;   // west  → our east
            case 1 -> 2;   // east  → our west
            case 2 -> 4;   // north → our north
            case 3 -> 5;   // south → our south
            case 4 -> 0;   // up    → our down
            default -> 1;  // down  → our up
        };
    }

    /**
     * GeckoLib 盒式 UV 布局：按方向求 (u0, v0, du, dv)（像素）。
     * 对应 {@code BakedModelFactory#buildQuad} 的 uvData（方向枚举顺序 WEST/EAST/NORTH/SOUTH/UP/DOWN）。
     */
    private static double[] boxRect(int d, double bu, double bv, float[] size) {
        double sx = Math.floor(size[0]);
        double sy = Math.floor(size[1]);
        double sz = Math.floor(size[2]);
        return switch (d) {
            case 0 -> new double[]{bu + sz + sx, bv + sz, sz, sy};       // west
            case 1 -> new double[]{bu, bv + sz, sz, sy};                 // east
            case 2 -> new double[]{bu + sz, bv + sz, sx, sy};            // north
            case 3 -> new double[]{bu + 2 * sz + sx, bv + sz, sx, sy};   // south
            case 4 -> new double[]{bu + sz, bv, sx, sz};                 // up
            default -> new double[]{bu + sz + sx, bv + sz, sx, -sz};     // down
        };
    }

    /** 逐面 UV 缺 uv_size 时的回退：用该面的自然尺寸（像素，按 G 帧方向） */
    private static double[] dirFaceSize(int d, float[] size) {
        return switch (d) {
            case 0, 1 -> new double[]{size[2], size[1]};   // west/east: sz × sy
            case 2, 3 -> new double[]{size[0], size[1]};   // north/south: sx × sy
            default -> new double[]{size[0], size[2]};     // up/down: sx × sz
        };
    }

    // GeckoLib VertexSet 8 角编码（G 帧）：ix*4+iy*2+iz，ix=0 为 x 最小侧
    private static final int[] QUAD_WEST = {3, 2, 0, 1};
    private static final int[] QUAD_EAST = {6, 7, 5, 4};
    private static final int[] QUAD_NORTH = {2, 6, 4, 0};
    private static final int[] QUAD_SOUTH = {7, 3, 1, 5};
    private static final int[] QUAD_UP = {3, 7, 6, 2};
    private static final int[] QUAD_DOWN = {0, 4, 5, 1};

    /** 对应 GeckoLib {@code VertexSet#verticesForQuad(direction, boxUv, mirror)}（0=WEST..5=DOWN） */
    private static int[] vertsForQuad(int d, boolean boxUv, boolean mirror) {
        return switch (d) {
            case 0 -> mirror ? QUAD_EAST : QUAD_WEST;                    // west
            case 1 -> mirror ? QUAD_WEST : QUAD_EAST;                    // east
            case 2 -> QUAD_NORTH;                                         // north
            case 3 -> QUAD_SOUTH;                                         // south
            case 4 -> (mirror && !boxUv) ? QUAD_DOWN : QUAD_UP;           // up
            default -> (mirror && !boxUv) ? QUAD_UP : QUAD_DOWN;          // down
        };
    }

    /**
     * 生成某方向的 4 个 (顶点, UV) 对并写入对应面槽位。
     * UV 四角分配顺序与 GeckoLib {@code GeoQuad#build} 完全一致（非镜像交换 u 高低端）；
     * 顶点取 G 帧角点再映射到编辑器骨骼枢轴局部坐标 {@code (−gv.x − bx, by − gv.y, gv.z − bz)}。
     */
    private static void emitFace(int d, double[] rect, int uvRotDeg, boolean boxUv, boolean mirror,
                                 int texW, int texH, double[][] gv, float bx, float by, float bz,
                                 JsonObject faces, JsonObject faceVerts) {
        double uLow = rect[0] / texW;
        double uHigh = (rect[0] + rect[2]) / texW;
        double vLow = rect[1] / texH;
        double vHigh = (rect[1] + rect[3]) / texH;
        double a;
        double c;
        if (!mirror) {
            a = uHigh;
            c = uLow;
        } else {
            a = uLow;
            c = uHigh;
        }
        double[][] corners;
        switch (((uvRotDeg % 360) + 360) % 360) {
            case 90 -> corners = new double[][]{{c, vLow}, {c, vHigh}, {a, vHigh}, {a, vLow}};
            case 180 -> corners = new double[][]{{c, vHigh}, {a, vHigh}, {a, vLow}, {c, vLow}};
            case 270 -> corners = new double[][]{{a, vHigh}, {a, vLow}, {c, vLow}, {c, vHigh}};
            default -> corners = new double[][]{{a, vLow}, {c, vLow}, {c, vHigh}, {a, vHigh}};
        }
        int[] verts = vertsForQuad(d, boxUv, mirror);
        JsonArray uvArr = new JsonArray();
        JsonArray vertArr = new JsonArray();
        for (int i = 0; i < 4; i++) {
            JsonArray p = new JsonArray();
            p.add(round4(corners[i][0]));
            p.add(round4(corners[i][1]));
            uvArr.add(p);
            int code = verts[i];
            JsonArray v = new JsonArray();
            v.add(round4(-gv[code][0] - bx));
            v.add(round4(by - gv[code][1]));
            v.add(round4(gv[code][2] - bz));
            vertArr.add(v);
        }
        String slot = ParsedCube.FACE_NAMES[slotForGDir(d)];
        faces.add(slot, uvArr);
        faceVerts.add(slot, vertArr);
    }

    // ------------------------------------------------------------------ JSON 工具

    private static JsonArray arr3(double a, double b, double c) {
        JsonArray arr = new JsonArray();
        arr.add(round4(a));
        arr.add(round4(b));
        arr.add(round4(c));
        return arr;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static float[] floats3(JsonObject o, String key) {
        float[] out = {0, 0, 0};
        if (o.has(key) && o.get(key).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(key);
            for (int i = 0; i < 3 && i < a.size(); i++) {
                if (a.get(i).isJsonPrimitive()) {
                    out[i] = a.get(i).getAsFloat();
                }
            }
        }
        return out;
    }

    private static double[] doubles2(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            return null;
        }
        JsonArray a = o.getAsJsonArray(key);
        if (a.size() < 2) {
            return null;
        }
        return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble()};
    }

    private static int intOr(JsonObject o, String key, int def) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static float floatOr(JsonObject o, String key, float def) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsFloat() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static boolean boolOr(JsonObject o, String key, boolean def) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static String strOr(JsonObject o, String key, String def) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }
}
