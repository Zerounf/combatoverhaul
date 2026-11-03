package com.yourname.combatoverhaul.client.skinned;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.yourname.combatoverhaul.client.model.ParsedBone;
import com.yourname.combatoverhaul.client.model.ParsedModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 连续蒙皮网格：每顶点位置/UV + 多骨骼权重。
 *
 * <p>不依赖 ModelPart/Cube；渲染时按权重混合骨骼矩阵，肩/肘/髋/膝可连续弯曲。
 *
 * <p>文件格式（顶层字段）：
 * <pre>
 * "vertices": [[x,y,z,u,v], ...]
 * "faces":    [[i0,i1,i2,i3], ...] 或 [[i0,i1,i2], ...]
 * "weights":  [[{"bone":"Head","w":1.0}], ...] 每顶点一行，最多 4 个影响
 * "source_bones": ["Head", ...] 可选，CubeMeshBuilder 生成的源 cube 骨骼
 * "bind_bones":   ["Head", ...] 可选，编辑器刷选的手动主骨种子（空=自动）
 * </pre>
 * 解析阶段会归一化合法权重并记录 {@link #warnings}；保存阶段重新严格校验，
 * 非法数据抛异常而不是静默写成坏文件。
 */
public final class SkinnedMesh {

    public static final int MAX_INFLUENCES = 4;

    public static final class Vertex {
        public final float x, y, z, u, v;

        Vertex(float x, float y, float z, float u, float v) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.u = u;
            this.v = v;
        }
    }

    /** 平移全部顶点（自定义盔甲对齐玩家骨架用）。 */
    public void translate(float dx, float dy, float dz) {
        for (int i = 0; i < vertices.size(); i++) {
            Vertex v = vertices.get(i);
            vertices.set(i, new Vertex(v.x + dx, v.y + dy, v.z + dz, v.u, v.v));
        }
    }

    /** 顶点 AABB：{minX,minY,minZ,maxX,maxY,maxZ}。 */
    public float[] bounds() {
        if (vertices.isEmpty()) {
            return new float[]{0, 0, 0, 0, 0, 0};
        }
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (Vertex v : vertices) {
            b[0] = Math.min(b[0], v.x);
            b[1] = Math.min(b[1], v.y);
            b[2] = Math.min(b[2], v.z);
            b[3] = Math.max(b[3], v.x);
            b[4] = Math.max(b[4], v.y);
            b[5] = Math.max(b[5], v.z);
        }
        return b;
    }

    public final List<Vertex> vertices = new ArrayList<>();
    public final List<int[]> faces = new ArrayList<>();
    /** 每顶点最多 4 个 (boneName, weight) */
    public final List<String[]> weightBones = new ArrayList<>();
    public final List<float[]> weightValues = new ArrayList<>();
    /**
     * 可选：每个顶点来自哪个源 cube 骨骼。由 {@link CubeMeshBuilder} 生成，
     * 自动蒙皮用它防止“手臂顶点落在身体 AABB 内”时被身体抢走权重。
     * 旧网格/外部网格没有该信息，长度为 0。
     */
    public final List<String> sourceBones = new ArrayList<>();
    /**
     * 手动绑定种子：每顶点可选的主骨骼名（空=自动）。
     * 编辑器“绑定”工具刷选后写入；自动蒙皮优先使用该骨骼作为主影响。
     */
    public final List<String> bindBones = new ArrayList<>();
    /** 解析期已处理的问题（非法权重、缺失权重、非法面等），供编辑器/日志诊断 */
    public final List<String> warnings = new ArrayList<>();

    public static SkinnedMesh parse(JsonObject json) {
        if (json == null || !json.has("vertices") || !json.get("vertices").isJsonArray()) {
            return null;
        }
        SkinnedMesh mesh = new SkinnedMesh();
        int vertexIndex = 0;
        for (JsonElement e : json.getAsJsonArray("vertices")) {
            float x = 0, y = 0, z = 0, u = 0, v = 0;
            boolean bad = !e.isJsonArray();
            if (!bad) {
                JsonArray a = e.getAsJsonArray();
                bad = a.size() < 5;
                x = readFloat(a, 0);
                y = readFloat(a, 1);
                z = readFloat(a, 2);
                u = readFloat(a, 3);
                v = readFloat(a, 4);
                if (!isFinite(x) || !isFinite(y) || !isFinite(z) || !isFinite(u) || !isFinite(v)) {
                    bad = true;
                    x = isFinite(x) ? x : 0f;
                    y = isFinite(y) ? y : 0f;
                    z = isFinite(z) ? z : 0f;
                    u = isFinite(u) ? u : 0f;
                    v = isFinite(v) ? v : 0f;
                }
            }
            if (bad) {
                mesh.warnings.add("顶点 " + vertexIndex + " 缺字段/非法，已按 0 处理");
            }
            mesh.vertices.add(new Vertex(x, y, z, u, v));
            vertexIndex++;
        }
        if (json.has("source_bones") && json.get("source_bones").isJsonArray()) {
            JsonArray sb = json.getAsJsonArray("source_bones");
            if (sb.size() == mesh.vertices.size()) {
                for (JsonElement e : sb) {
                    mesh.sourceBones.add(e.isJsonPrimitive() ? e.getAsString() : "");
                }
            } else {
                mesh.warnings.add("source_bones 数量与顶点不一致，已忽略");
            }
        }
        if (json.has("bind_bones") && json.get("bind_bones").isJsonArray()) {
            JsonArray bb = json.getAsJsonArray("bind_bones");
            if (bb.size() == mesh.vertices.size()) {
                for (JsonElement e : bb) {
                    mesh.bindBones.add(e.isJsonPrimitive() ? e.getAsString() : "");
                }
            } else {
                mesh.warnings.add("bind_bones 数量与顶点不一致，已忽略");
            }
        }

        if (json.has("faces") && json.get("faces").isJsonArray()) {
            int faceIndex = 0;
            for (JsonElement e : json.getAsJsonArray("faces")) {
                if (!e.isJsonArray()) {
                    mesh.warnings.add("面 " + faceIndex + " 不是数组，已跳过");
                    faceIndex++;
                    continue;
                }
                JsonArray a = e.getAsJsonArray();
                if (a.size() != 3 && a.size() != 4) {
                    mesh.warnings.add("面 " + faceIndex + " 顶点数不是 3/4，已跳过");
                    faceIndex++;
                    continue;
                }
                int[] face = new int[]{-1, -1, -1, -1};
                boolean ok = true;
                for (int i = 0; i < a.size(); i++) {
                    int idx;
                    try {
                        idx = a.get(i).getAsInt();
                    } catch (RuntimeException ex) {
                        ok = false;
                        break;
                    }
                    if (idx < 0 || idx >= mesh.vertices.size()) {
                        ok = false;
                        break;
                    }
                    face[i] = idx;
                }
                if (ok) {
                    mesh.faces.add(face);
                } else {
                    mesh.warnings.add("面 " + faceIndex + " 引用了非法顶点，已跳过");
                }
                faceIndex++;
            }
        }

        if (json.has("weights") && json.get("weights").isJsonArray()) {
            for (JsonElement e : json.getAsJsonArray("weights")) {
                if (mesh.weightBones.size() >= mesh.vertices.size()) {
                    mesh.warnings.add("weights 行数多于顶点数，多余行已忽略");
                    break;
                }
                String[] names = new String[MAX_INFLUENCES];
                float[] vals = new float[MAX_INFLUENCES];
                if (!e.isJsonArray()) {
                    mesh.warnings.add("顶点 " + mesh.weightBones.size() + " 的 weights 不是数组，回退 root=1");
                    names[0] = "root";
                    vals[0] = 1f;
                } else {
                    parseWeightRow(e.getAsJsonArray(), mesh.weightBones.size(), names, vals, mesh.warnings);
                }
                mesh.weightBones.add(names);
                mesh.weightValues.add(vals);
            }
        }
        if (mesh.weightBones.size() < mesh.vertices.size()) {
            mesh.warnings.add("缺少 " + (mesh.vertices.size() - mesh.weightBones.size())
                    + " 个顶点的 weights，已回退 root=1");
        }
        while (mesh.weightBones.size() < mesh.vertices.size()) {
            mesh.weightBones.add(new String[]{"root", null, null, null});
            mesh.weightValues.add(new float[]{1f, 0f, 0f, 0f});
        }
        return mesh.vertices.isEmpty() || mesh.faces.isEmpty() ? null : mesh;
    }

    /**
     * 解析一行权重：只保留有限、非负、非零的项；同名骨骼合并；超过 4 项截断；
     * 最后归一化。全零/全非法回退 root=1，并写入 warnings。
     */
    private static void parseWeightRow(JsonArray a, int vertexIndex,
                                       String[] names, float[] vals, List<String> warnings) {
        int count = 0;
        float sum = 0f;
        for (int i = 0; i < a.size(); i++) {
            JsonElement e = a.get(i);
            if (!e.isJsonObject()) {
                warnings.add("顶点 " + vertexIndex + " 的权重项不是对象，已跳过");
                continue;
            }
            JsonObject w = e.getAsJsonObject();
            if (!w.has("bone") || !w.get("bone").isJsonPrimitive()) {
                warnings.add("顶点 " + vertexIndex + " 的权重缺少 bone，已跳过");
                continue;
            }
            String bone = w.get("bone").getAsString().trim();
            if (bone.isEmpty()) {
                warnings.add("顶点 " + vertexIndex + " 的权重 bone 为空，已跳过");
                continue;
            }
            if (!w.has("w") || !w.get("w").isJsonPrimitive()) {
                warnings.add("顶点 " + vertexIndex + " 的权重缺少 w，已跳过");
                continue;
            }
            float val;
            try {
                val = w.get("w").getAsFloat();
            } catch (RuntimeException ex) {
                warnings.add("顶点 " + vertexIndex + " 的权重不是数字，已跳过");
                continue;
            }
            if (!isFinite(val) || val < 0f) {
                warnings.add("顶点 " + vertexIndex + " 的权重非法(" + val + ")，已跳过");
                continue;
            }
            if (val == 0f) {
                continue;
            }
            int existing = -1;
            for (int j = 0; j < count; j++) {
                if (bone.equals(names[j])) {
                    existing = j;
                    break;
                }
            }
            if (existing >= 0) {
                vals[existing] += val;
                sum += val;
                continue;
            }
            if (count >= MAX_INFLUENCES) {
                warnings.add("顶点 " + vertexIndex + " 权重超过 " + MAX_INFLUENCES + " 个，已截断");
                continue;
            }
            names[count] = bone;
            vals[count] = val;
            count++;
            sum += val;
        }
        if (!isFinite(sum) || sum <= 0f) {
            names[0] = "root";
            vals[0] = 1f;
            warnings.add("顶点 " + vertexIndex + " 权重和非法，已回退 root=1");
            return;
        }
        if (Math.abs(sum - 1f) > 1.0e-4f) {
            warnings.add("顶点 " + vertexIndex + " 权重和为 " + sum + "，已归一化");
        }
        for (int i = 0; i < count; i++) {
            vals[i] /= sum;
        }
    }

    /** 保存 JSON；非法网格/权重直接抛异常，避免把坏数据写回文件。 */
    public JsonObject toJson() {
        if (vertices.isEmpty() || faces.isEmpty()) {
            throw new IllegalStateException("蒙皮网格为空（vertices/faces 不能为空）");
        }
        validateForSave();

        JsonObject out = new JsonObject();

        JsonArray vs = new JsonArray();
        for (Vertex v : vertices) {
            JsonArray a = new JsonArray();
            a.add(v.x);
            a.add(v.y);
            a.add(v.z);
            a.add(v.u);
            a.add(v.v);
            vs.add(a);
        }
        out.add("vertices", vs);

        if (sourceBones.size() == vertices.size()) {
            JsonArray sb = new JsonArray();
            for (String source : sourceBones) {
                sb.add(source == null ? "" : source);
            }
            out.add("source_bones", sb);
        }
        if (bindBones.size() == vertices.size()) {
            boolean anyBind = false;
            for (String b : bindBones) {
                if (b != null && !b.isBlank()) {
                    anyBind = true;
                    break;
                }
            }
            if (anyBind) {
                JsonArray bb = new JsonArray();
                for (String b : bindBones) {
                    bb.add(b == null ? "" : b);
                }
                out.add("bind_bones", bb);
            }
        }

        JsonArray fs = new JsonArray();
        for (int[] face : faces) {
            int count = face.length >= 4 && face[3] >= 0 ? 4 : 3;
            JsonArray a = new JsonArray();
            for (int i = 0; i < count; i++) {
                a.add(face[i]);
            }
            fs.add(a);
        }
        out.add("faces", fs);

        JsonArray ws = new JsonArray();
        for (int vi = 0; vi < vertices.size(); vi++) {
            String[] names = weightBones.get(vi);
            float[] vals = weightValues.get(vi);
            Map<String, Float> merged = new LinkedHashMap<>();
            float sum = 0f;
            for (int k = 0; k < MAX_INFLUENCES; k++) {
                String bone = names[k];
                float val = vals[k];
                if (!isFinite(val)) {
                    throw new IllegalStateException("顶点 " + vi + " 的权重不是有限数");
                }
                if (val < 0f) {
                    throw new IllegalStateException("顶点 " + vi + " 的权重为负数(" + val + ")");
                }
                if (val == 0f) {
                    continue;
                }
                if (bone == null || bone.isBlank()) {
                    throw new IllegalStateException("顶点 " + vi + " 的正权重没有骨骼名");
                }
                merged.merge(bone, val, Float::sum);
                sum += val;
            }
            if (!isFinite(sum) || sum <= 1.0e-6f) {
                throw new IllegalStateException("顶点 " + vi + " 的权重和为 0/非法，拒绝保存");
            }
            if (merged.size() > MAX_INFLUENCES) {
                throw new IllegalStateException("顶点 " + vi + " 的影响骨骼超过 " + MAX_INFLUENCES);
            }
            JsonArray row = new JsonArray();
            for (Map.Entry<String, Float> en : merged.entrySet()) {
                JsonObject w = new JsonObject();
                w.addProperty("bone", en.getKey());
                w.addProperty("w", en.getValue() / sum);
                row.add(w);
            }
            ws.add(row);
        }
        out.add("weights", ws);
        return out;
    }

    private void validateForSave() {
        if (weightBones.size() < vertices.size() || weightValues.size() < vertices.size()) {
            throw new IllegalStateException("weights 行数少于顶点数");
        }
        if (!sourceBones.isEmpty() && sourceBones.size() != vertices.size()) {
            throw new IllegalStateException("source_bones 数量与顶点数不一致");
        }
        if (!bindBones.isEmpty() && bindBones.size() != vertices.size()) {
            throw new IllegalStateException("bind_bones 数量与顶点数不一致");
        }
        for (int[] face : faces) {
            if (face == null || face.length < 3) {
                throw new IllegalStateException("存在非法面（少于 3 个顶点）");
            }
            int count = face.length >= 4 && face[3] >= 0 ? 4 : 3;
            for (int i = 0; i < count; i++) {
                if (face[i] < 0 || face[i] >= vertices.size()) {
                    throw new IllegalStateException("面引用了越界顶点 " + face[i]);
                }
            }
        }
        for (int vi = 0; vi < vertices.size(); vi++) {
            String[] names = weightBones.get(vi);
            float[] vals = weightValues.get(vi);
            if (names == null || vals == null || names.length < MAX_INFLUENCES || vals.length < MAX_INFLUENCES) {
                throw new IllegalStateException("顶点 " + vi + " 的权重行非法");
            }
        }
    }

    /**
     * 校验权重骨骼引用。解析时 strict=false 只记录 warning；保存时 strict=true 直接抛异常。
     * 使用真实骨骼名（model.findBone 也支持 alias，但权重写出仍保留传入名字）。
     */
    public void validateBones(ParsedModel model, boolean strict) {
        if (model == null) {
            return;
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String[] row : weightBones) {
            if (row == null) {
                continue;
            }
            for (String name : row) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                if (model.findBone(name) == null) {
                    missing.add(name);
                }
            }
        }
        if (!missing.isEmpty()) {
            String msg = "蒙皮权重引用了不存在的骨骼: " + String.join(", ", missing);
            if (strict) {
                throw new IllegalStateException(msg);
            }
            warnings.add(msg);
        }
    }

    private static float readFloat(JsonArray a, int index) {
        if (index < 0 || index >= a.size()) {
            return 0f;
        }
        JsonElement e = a.get(index);
        if (!e.isJsonPrimitive()) {
            return 0f;
        }
        try {
            return e.getAsFloat();
        } catch (RuntimeException ex) {
            return 0f;
        }
    }

    private static boolean isFinite(float v) {
        return Float.isFinite(v);
    }

    public int resolveBone(ParsedModel model, String name) {
        if (name == null) {
            return -1;
        }
        ParsedBone b = model.findBone(name);
        return b == null ? -1 : model.bones.indexOf(b);
    }
}
