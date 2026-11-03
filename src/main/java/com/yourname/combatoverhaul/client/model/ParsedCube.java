package com.yourname.combatoverhaul.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 解析后的模型立方体：origin + size + 六面 UV（归一化 0~1）。
 *
 * <p>每面 4 个 UV 按原始多边形顶点顺序存储。角点几何顺序与 1.20.1
 * {@code ModelPart.Cube} 构造中的多边形顶点一致（未镜像情形），
 * 因此导出文件中的 UV 可与这里给出的角点直接配对，还原正确的贴图朝向。
 */
public final class ParsedCube {

    /** 面名（与导出 JSON 键一致，按多边形法线命名） */
    public static final String[] FACE_NAMES = {"down", "up", "west", "east", "north", "south"};
    /** 模型空间法线（与 Direction.step 一致） */
    public static final float[][] FACE_NORMALS = {
            {0, -1, 0}, {0, 1, 0}, {-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}
    };
    /** 每面 4 角的 (min/max) 选择：索引与 FACE_NAMES 语义一致（3=east、4=north），
     * 每面内部顶点顺序与 vanilla ModelPart.Cube 对应多边形的顶点顺序一致（未镜像情形），
     * 因此导出文件中的 UV 可与这里给出的角点直接配对，还原正确的贴图朝向。 */
    static final int[][][] CORNER_LAYOUT = {
            {{1, 0, 1}, {0, 0, 1}, {0, 0, 0}, {1, 0, 0}},   // down:  V101,V001,V000,V100
            {{1, 1, 0}, {0, 1, 0}, {0, 1, 1}, {1, 1, 1}},   // up:    V110,V010,V011,V111
            {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},   // west:  V000,V001,V011,V010
            {{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}},   // east:  V101,V100,V110,V111
            {{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}},   // north: V100,V000,V010,V110
            {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},   // south: V001,V101,V111,V011
    };
    /** 缺失面 UV 回退：纹理中心一小块 */
    private static final float[][] FALLBACK_UV = {
            {0.4375f, 0.4375f}, {0.4375f, 0.5625f}, {0.5625f, 0.5625f}, {0.5625f, 0.4375f}
    };

    public float originX, originY, originZ;
    public float sizeX, sizeY, sizeZ;
    /** 原版立方体镜像标志：true 时 x 轴翻转（与 ModelPart.Cube 构造器 mirror 参数一致） */
    public boolean mirror;
    /**
     * 立方体自身旋转（弧度，绕 pivotX/Y/Z）；Z→Y→X 顺序，与渲染端 rotationZYX 一致。
     * 全零视为无旋转（默认）。用于承载 Bedrock/GeckoLib 的 cube rotation。
     */
    public float rotX, rotY, rotZ;
    /** 立方体自身旋转枢轴（模型单位，骨骼局部空间）；仅当 hasRot() 时有意义 */
    public float pivotX, pivotY, pivotZ;
    /** faces[i] = FACE_NAMES[i] 对应的 4 组 [u,v]（0~1），按原始顶点顺序；缺失面为 null */
    public final float[][][] faces = new float[6][][];
    /**
     * 可选：faceVerts[i] = FACE_NAMES[i] 对应面的 4 个顶点几何坐标 [x,y,z]（模型单位），
     * 顺序与 faces[i] 的 UV 一一对应。导出实体模型时填充（完整还原原版顶点顺序，含镜像）；
     * 编辑器新建立方体为 null，渲染端回退 CORNER_LAYOUT 推导。
     */
    public final float[][][] faceVerts = new float[6][][];

    public ParsedCube() {
    }

    public ParsedCube(float ox, float oy, float oz, float sx, float sy, float sz) {
        this.originX = ox;
        this.originY = oy;
        this.originZ = oz;
        this.sizeX = sx;
        this.sizeY = sy;
        this.sizeZ = sz;
    }

    // ------------------------------------------------------------------ 角点

    public float cornerX(int face, int corner) {
        return sel(CORNER_LAYOUT[face][corner][0], originX, originX + sizeX);
    }

    public float cornerY(int face, int corner) {
        return sel(CORNER_LAYOUT[face][corner][1], originY, originY + sizeY);
    }

    public float cornerZ(int face, int corner) {
        return sel(CORNER_LAYOUT[face][corner][2], originZ, originZ + sizeZ);
    }

    private static float sel(int i, float min, float max) {
        return i == 0 ? min : max;
    }

    public boolean hasFace(int face) {
        return faces[face] != null;
    }

    /** 是否存在立方体自身旋转（全零视为无） */
    public boolean hasRot() {
        return rotX != 0 || rotY != 0 || rotZ != 0;
    }

    /** 该面是否有存储的顶点坐标（决定渲染端用存储几何还是 CORNER_LAYOUT 推导） */
    public boolean hasFaceVerts(int face) {
        float[][] fv = faceVerts[face];
        return fv != null && fv.length == 4 && fv[0] != null && fv[0].length == 3;
    }

    /** 取存储的面顶点坐标分量；axis: 0=x 1=y 2=z。调用前需 hasFaceVerts(face)==true */
    public float vertComp(int face, int corner, int axis) {
        return faceVerts[face][corner][axis];
    }

    /** 取某面某角的 UV；缺失时回退中心块 */
    /** 把像素 UV 缩放为 0..1 纹理坐标。 */
    public void scaleUv(float su, float sv) {
        for (int f = 0; f < 6; f++) {
            if (faces[f] == null) {
                continue;
            }
            for (int k = 0; k < faces[f].length; k++) {
                if (faces[f][k] != null && faces[f][k].length >= 2) {
                    faces[f][k][0] *= su;
                    faces[f][k][1] *= sv;
                }
            }
        }
    }

    public float[] uv(int face, int corner) {
        float[][] f = faces[face];
        if (f == null || f.length != 4 || f[corner] == null || f[corner].length != 2) {
            return FALLBACK_UV[corner];
        }
        return f[corner];
    }

    /** 按 (x↔u / z↔u, y↔v) 规则为六面填 UV（用于编辑器生成的新立方体，如武器体素） */
    public void fillFacesFromTexel(float u0, float v0, float u1, float v1) {
        for (int f = 0; f < 6; f++) {
            float[][] uv = new float[4][2];
            for (int k = 0; k < 4; k++) {
                float cx = cornerX(f, k);
                float cy = cornerY(f, k);
                float cz = cornerZ(f, k);
                boolean maxX = cx > originX + sizeX / 2f;
                boolean maxY = cy > originY + sizeY / 2f;
                boolean maxZ = cz > originZ + sizeZ / 2f;
                float u;
                float v;
                switch (f) {
                    case 0, 1 -> { // down/up：u↔x, v↔z
                        u = maxX ? u1 : u0;
                        v = maxZ ? v1 : v0;
                    }
                    case 2, 3 -> { // west/east：u↔z, v↔y
                        u = maxZ ? u1 : u0;
                        v = maxY ? v1 : v0;
                    }
                    default -> { // north/south：u↔x, v↔y；正背面 UV 一致，背面看呈镜像（原版行为）
                        u = maxX ? u1 : u0;
                        v = maxY ? v1 : v0;
                    }
                }
                uv[k][0] = u;
                uv[k][1] = v;
            }
            faces[f] = uv;
        }
    }

    /**
     * 原版物品模型式 UV（参考 ItemModelGenerator）：一个水平像素段 [u0,u1)×[v0,v1)。
     * 正/背面（south/north）映射整段贴图；四个侧面采样段边缘像素列/行的中心，
     * 从而把边缘像素颜色拉满整个侧面——即 MC 物品“薄板 + 边缘拉伸侧面”的经典观感。
     *
     * @param texU 单像素 U 宽（1/贴图宽）
     * @param texV 单像素 V 高（1/贴图高）
     */
    public void fillFacesFromSpan(float u0, float v0, float u1, float v1, float texU, float texV) {
        float edgeWestU = u0 + texU * 0.5f;  // 左边缘像素列中心
        float edgeEastU = u1 - texU * 0.5f;  // 右边缘像素列中心
        float edgeUpV = v0 + texV * 0.5f;    // 上边缘像素行中心
        float edgeDownV = v1 - texV * 0.5f;  // 下边缘像素行中心
        for (int f = 0; f < 6; f++) {
            float[][] uv = new float[4][2];
            for (int k = 0; k < 4; k++) {
                boolean maxX = cornerX(f, k) > originX + sizeX / 2f;
                boolean maxY = cornerY(f, k) > originY + sizeY / 2f;
                float u;
                float v;
                switch (f) {
                    case 0 -> { // down = 薄板上缘（模型 +Y 朝下，originY 是最小 y）→ 取图片上边缘行
                        u = maxX ? u1 : u0;
                        v = edgeUpV;
                    }
                    case 1 -> { // up = 薄板下缘 → 取图片下边缘行
                        u = maxX ? u1 : u0;
                        v = edgeDownV;
                    }
                    case 2 -> { // west：u 恒为左边缘像素列中心，v 铺满
                        u = edgeWestU;
                        v = maxY ? v1 : v0;
                    }
                    case 3 -> { // east：u 恒为右边缘像素列中心，v 铺满
                        u = edgeEastU;
                        v = maxY ? v1 : v0;
                    }
                    default -> { // north/south：整段贴图，正背面 UV 完全一致（从背后看自然呈镜像，与原版一致）
                        u = maxX ? u1 : u0;
                        v = maxY ? v1 : v0;
                    }
                }
                uv[k][0] = u;
                uv[k][1] = v;
            }
            faces[f] = uv;
        }
    }

    /**
     * faceVerts 存在时（导出实体模型会写入），把已存储顶点按"原点平移 + 尺寸缩放"重映射到
     * 新的 origin/size，使"编辑 origin/size"在渲染端（优先使用 faceVerts）同样生效。
     *
     * @param oldOrigin 编辑前的 origin 三分量
     * @param oldSize   编辑前的 size 三分量
     */
    public void remapFaceVerts(float[] oldOrigin, float[] oldSize) {
        if (!hasAnyFaceVerts()) {
            return;
        }
        for (int f = 0; f < 6; f++) {
            float[][] fv = faceVerts[f];
            if (fv == null) {
                continue;
            }
            for (int k = 0; k < 4; k++) {
                if (fv[k] == null) {
                    continue;
                }
                fv[k][0] = remapComp(fv[k][0], oldOrigin[0], originX, oldSize[0], sizeX);
                fv[k][1] = remapComp(fv[k][1], oldOrigin[1], originY, oldSize[1], sizeY);
                fv[k][2] = remapComp(fv[k][2], oldOrigin[2], originZ, oldSize[2], sizeZ);
            }
        }
    }

    private boolean hasAnyFaceVerts() {
        for (int f = 0; f < 6; f++) {
            if (faceVerts[f] != null) {
                return true;
            }
        }
        return false;
    }

    private static float remapComp(float v, float oldO, float newO, float oldS, float newS) {
        float t = Math.abs(oldS) < 1.0e-6f ? 0f : (v - oldO) / oldS;
        return newO + t * newS;
    }

    public ParsedCube copy() {
        ParsedCube c = new ParsedCube(originX, originY, originZ, sizeX, sizeY, sizeZ);
        c.mirror = mirror;
        c.rotX = rotX;
        c.rotY = rotY;
        c.rotZ = rotZ;
        c.pivotX = pivotX;
        c.pivotY = pivotY;
        c.pivotZ = pivotZ;
        for (int f = 0; f < 6; f++) {
            if (faces[f] != null) {
                c.faces[f] = new float[4][2];
                for (int k = 0; k < 4; k++) {
                    c.faces[f][k][0] = faces[f][k][0];
                    c.faces[f][k][1] = faces[f][k][1];
                }
            }
            if (faceVerts[f] != null) {
                c.faceVerts[f] = new float[4][3];
                for (int k = 0; k < 4; k++) {
                    c.faceVerts[f][k][0] = faceVerts[f][k][0];
                    c.faceVerts[f][k][1] = faceVerts[f][k][1];
                    c.faceVerts[f][k][2] = faceVerts[f][k][2];
                }
            }
        }
        return c;
    }

    // ------------------------------------------------------------------ 原版 ModelPart.Cube 重建

    /**
     * 从 faces UV 反推原版 {@link net.minecraft.client.model.geom.ModelPart.Cube}
     * 构造所需的纹理参数 {texU, texV, texW, texH}。
     *
     * <p>面轴对应：down/up: u↔x, v↔z；west/east: u↔z, v↔y；north/south: u↔x, v↔y。
     * UV 偏移：down/up/north/south 的 u 含 sizeZ 偏移，west/east 不含；
     * down/up 的 v 不含 sizeZ 偏移，其余含。
     *
     * @return {texU, texV, texW, texH}；无法反推返回 null
     */
    public float[] inferTexParams() {
        float texW = -1, texH = -1;
        float texU = 0, texV = 0;
        for (int f = 0; f < 6; f++) {
            if (!hasFace(f)) {
                continue;
            }
            float uMin = Float.MAX_VALUE, uMax = -Float.MAX_VALUE;
            float vMin = Float.MAX_VALUE, vMax = -Float.MAX_VALUE;
            for (int k = 0; k < 4; k++) {
                float u = uv(f, k)[0];
                float v = uv(f, k)[1];
                if (u < uMin) uMin = u;
                if (u > uMax) uMax = u;
                if (v < vMin) vMin = v;
                if (v > vMax) vMax = v;
            }
            float uSpan = uMax - uMin;
            float vSpan = vMax - vMin;
            if (uSpan <= 0 || vSpan <= 0) {
                continue;
            }
            float uSize = (f == 2 || f == 3) ? sizeZ : sizeX;
            float vSize = (f == 0 || f == 1) ? sizeZ : sizeY;
            if (texW < 0) {
                texW = uSize / uSpan;
            }
            if (texH < 0) {
                texH = vSize / vSpan;
                float uOffset = (f == 2 || f == 3) ? 0 : sizeZ;
                texU = uMin * texW - uOffset;
                float vOffset = (f == 0 || f == 1) ? 0 : sizeZ;
                texV = vMin * texH - vOffset;
            }
        }
        if (texW <= 0 || texH <= 0) {
            return null;
        }
        return new float[]{texU, texV, texW, texH};
    }

    // ------------------------------------------------------------------ JSON

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.add("origin", arr(originX, originY, originZ));
        o.add("size", arr(sizeX, sizeY, sizeZ));
        o.addProperty("mirror", mirror);
        if (hasRot()) {
            o.add("rot_deg", arr((float) Math.toDegrees(rotX),
                    (float) Math.toDegrees(rotY), (float) Math.toDegrees(rotZ)));
            o.add("rot_pivot", arr(pivotX, pivotY, pivotZ));
        }
        JsonObject fs = new JsonObject();
        boolean any = false;
        for (int f = 0; f < 6; f++) {
            if (faces[f] == null) {
                continue;
            }
            JsonArray uv = new JsonArray();
            for (int k = 0; k < 4; k++) {
                JsonArray p = new JsonArray();
                p.add(round(faces[f][k][0]));
                p.add(round(faces[f][k][1]));
                uv.add(p);
            }
            fs.add(FACE_NAMES[f], uv);
            any = true;
        }
        if (any) {
            o.add("faces", fs);
        }
        // 可选顶点坐标（导出实体模型时填充，与 faces UV 顺序一一对应）
        JsonObject fvs = new JsonObject();
        boolean anyVerts = false;
        for (int f = 0; f < 6; f++) {
            if (!hasFaceVerts(f)) {
                continue;
            }
            JsonArray verts = new JsonArray();
            for (int k = 0; k < 4; k++) {
                JsonArray p = new JsonArray();
                p.add(round(faceVerts[f][k][0]));
                p.add(round(faceVerts[f][k][1]));
                p.add(round(faceVerts[f][k][2]));
                verts.add(p);
            }
            fvs.add(FACE_NAMES[f], verts);
            anyVerts = true;
        }
        if (anyVerts) {
            o.add("face_verts", fvs);
        }
        return o;
    }

    public static ParsedCube fromJson(JsonObject o) {
        ParsedCube c = new ParsedCube();
        float[] origin = floats(o, "origin");
        float[] size = floats(o, "size");
        c.originX = origin[0];
        c.originY = origin[1];
        c.originZ = origin[2];
        c.sizeX = size[0];
        c.sizeY = size[1];
        c.sizeZ = size[2];
        if (o.has("mirror") && o.get("mirror").isJsonPrimitive()) {
            c.mirror = o.get("mirror").getAsBoolean();
        }
        if (o.has("rot_deg") && o.get("rot_deg").isJsonArray()) {
            float[] r = floats(o, "rot_deg");
            c.rotX = (float) Math.toRadians(r[0]);
            c.rotY = (float) Math.toRadians(r[1]);
            c.rotZ = (float) Math.toRadians(r[2]);
        }
        if (o.has("rot_pivot") && o.get("rot_pivot").isJsonArray()) {
            float[] pp = floats(o, "rot_pivot");
            c.pivotX = pp[0];
            c.pivotY = pp[1];
            c.pivotZ = pp[2];
        }
        if (o.has("faces") && o.get("faces").isJsonObject()) {
            JsonObject fs = o.getAsJsonObject("faces");
            for (int f = 0; f < 6; f++) {
                if (!fs.has(FACE_NAMES[f]) || !fs.get(FACE_NAMES[f]).isJsonArray()) {
                    continue;
                }
                JsonArray uv = fs.getAsJsonArray(FACE_NAMES[f]);
                if (uv.size() < 4) {
                    continue;
                }
                float[][] corners = new float[4][2];
                for (int k = 0; k < 4; k++) {
                    if (!uv.get(k).isJsonArray() || uv.get(k).getAsJsonArray().size() < 2) {
                        continue;
                    }
                    JsonArray p = uv.get(k).getAsJsonArray();
                    corners[k][0] = p.get(0).getAsFloat();
                    corners[k][1] = p.get(1).getAsFloat();
                }
                c.faces[f] = corners;
            }
        }
        if (o.has("face_verts") && o.get("face_verts").isJsonObject()) {
            JsonObject fvs = o.getAsJsonObject("face_verts");
            for (int f = 0; f < 6; f++) {
                if (!fvs.has(FACE_NAMES[f]) || !fvs.get(FACE_NAMES[f]).isJsonArray()) {
                    continue;
                }
                JsonArray verts = fvs.getAsJsonArray(FACE_NAMES[f]);
                if (verts.size() < 4) {
                    continue;
                }
                float[][] pos = new float[4][3];
                for (int k = 0; k < 4; k++) {
                    if (!verts.get(k).isJsonArray() || verts.get(k).getAsJsonArray().size() < 3) {
                        continue;
                    }
                    JsonArray p = verts.get(k).getAsJsonArray();
                    pos[k][0] = p.get(0).getAsFloat();
                    pos[k][1] = p.get(1).getAsFloat();
                    pos[k][2] = p.get(2).getAsFloat();
                }
                c.faceVerts[f] = new float[4][3];
                for (int k = 0; k < 4; k++) {
                    c.faceVerts[f][k] = pos[k];
                }
            }
        }
        return c;
    }

    // ------------------------------------------------------------------ 工具

    static JsonArray arr(float... values) {
        JsonArray a = new JsonArray();
        for (float v : values) {
            a.add(round(v));
        }
        return a;
    }

    static float[] floats(JsonObject o, String key) {
        float[] out = {0, 0, 0};
        if (o.has(key) && o.get(key).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(key);
            for (int i = 0; i < 3 && i < a.size(); i++) {
                out[i] = a.get(i).getAsFloat();
            }
        }
        return out;
    }

    static float round(float v) {
        return Math.round(v * 10000f) / 10000f;
    }
}
