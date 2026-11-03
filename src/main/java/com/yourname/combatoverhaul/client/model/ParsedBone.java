package com.yourname.combatoverhaul.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析后的骨骼节点：枢轴/旋转/缩放基础姿态 + 立方体 + 子骨骼，
 * 另含动画叠加状态（由动作播放/预览写入，渲染时与基础姿态合成）。
 *
 * <p>变换语义与 1.20.1 {@code ModelPart.translateAndRotate} 完全一致：
 * translate(pivot/16) → rotationZYX(z, y, x) → scale。
 */
public final class ParsedBone {

    public String name = "bone";
    /** 基础枢轴（模型单位） */
    public float pivotX, pivotY, pivotZ;
    /** 基础旋转（弧度，JSON 中为度） */
    public float rotX, rotY, rotZ;
    /** 基础缩放 */
    public float scaleX = 1, scaleY = 1, scaleZ = 1;
    /** 可选 rest 矩阵（9 个，列主序）；ER 标准骨架使用它代替欧拉 rest。 */
    public float[] restMatrix;
    public boolean visible = true;
    /**
     * 是否参与变形/蒙皮。显式 {@code deform} 优先；{@link #hasDeform}=false 时
     * 由关节与自动蒙皮规则决定（根部默认不参与，其余默认参与）。
     */
    public boolean deform = true;
    /** JSON 是否显式提供 deform；用于无损写回 */
    public boolean hasDeform = false;
    /** 是否为武器/饰品挂点：不参与蒙皮、不生成骨段线 */
    public boolean attach = false;
    /** JSON 是否显式提供 attach；用于无损写回 */
    public boolean hasAttach = false;
    /**
     * JSON 是否显式提供 pivot。缺失 pivot 的骨骼不能被当作“枢轴在原点”，
     * 自动关节应回退到包围盒接触面中心。程序创建/编辑后的骨骼默认 true。
     */
    public boolean hasPivot = true;
    /** 关节柔化带宽度（占本段长度比例，0=刚性；切分生成的子骨骼使用） */
    public float softJoint = 0f;
    /** 分段工具生成的关节：蒙皮不在该切点做第二骨混合，避免上下段被拉成模糊过渡带。 */
    public boolean rigidJoint = false;
    /** 柔化轴向：0=x 1=y 2=z */
    public int softAxis = 1;
    /** 追踪标签：播放带追踪标签的动作时，按视线俯仰角叠加抬头/低头偏移（向上看=上抬） */
    public boolean trackPitch = false;
    /** 跟随目标骨骼名：本骨及相对路径子树完全镜像目标（含基础姿态与动画） */
    public String follow;
    /** JSON 是否显式提供 follow；用于无损写回 */
    public boolean hasFollow = false;

    public final List<ParsedCube> cubes = new ArrayList<>();
    public final List<ParsedBone> children = new ArrayList<>();
    public ParsedBone parent;

    /**
     * 原始骨骼 JSON。保存时以它为底再覆盖已知字段，保留 deform/attach 之外的
     * 未知字段（未来扩展、外部工具标记），避免“打开再保存”洗掉数据。
     */
    private JsonObject originalJson;

    // 动画叠加状态（每帧渲染前由 ActionStore.apply 重置并写入）
    public float animRotX, animRotY, animRotZ;   // 弧度
    public float animPosX, animPosY, animPosZ;   // 模型单位（枢轴偏移）
    public float animScaleX = 1, animScaleY = 1, animScaleZ = 1;

    public ParsedBone() {
    }

    public ParsedBone(String name) {
        this.name = name;
    }

    public void resetAnim() {
        animRotX = animRotY = animRotZ = 0;
        animPosX = animPosY = animPosZ = 0;
        animScaleX = animScaleY = animScaleZ = 1;
    }

    /** 合成后的旋转（弧度） */
    public float fx() {
        return rotX + animRotX;
    }

    public float fy() {
        return rotY + animRotY;
    }

    public float fz() {
        return rotZ + animRotZ;
    }

    /** 自身 + 全部后代按前序遍历收集 */
    public void collectAll(List<ParsedBone> out) {
        out.add(this);
        for (ParsedBone c : children) {
            c.collectAll(out);
        }
    }

    public int depth() {
        int d = 0;
        for (ParsedBone p = parent; p != null; p = p.parent) {
            d++;
        }
        return d;
    }

    /** 自身立方体在本骨骼局部空间的包围盒 {minX,minY,minZ,maxX,maxY,maxZ}；无立方体返回 null */
    public float[] cubeBounds() {
        if (cubes.isEmpty()) {
            return null;
        }
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (ParsedCube c : cubes) {
            b[0] = Math.min(b[0], c.originX);
            b[1] = Math.min(b[1], c.originY);
            b[2] = Math.min(b[2], c.originZ);
            b[3] = Math.max(b[3], c.originX + c.sizeX);
            b[4] = Math.max(b[4], c.originY + c.sizeY);
            b[5] = Math.max(b[5], c.originZ + c.sizeZ);
        }
        return b;
    }

    /** 体中心：立方体包围盒中心（局部空间）；无立方体返回枢轴原点 */
    public float[] cubeCenter() {
        float[] b = cubeBounds();
        if (b == null) {
            return new float[]{0, 0, 0};
        }
        return new float[]{(b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2};
    }

    // ------------------------------------------------------------------ JSON

    public JsonObject toJson() {
        // 以原始骨骼 JSON 为底：deform/attach 之外的未知字段也必须保留。
        JsonObject o = originalJson != null ? originalJson.deepCopy() : new JsonObject();
        o.addProperty("name", name);
        if (hasPivot || pivotX != 0 || pivotY != 0 || pivotZ != 0) {
            o.add("pivot", ParsedCube.arr(pivotX, pivotY, pivotZ));
        } else {
            // 输入本来就没有 pivot：不要伪造一个 0，让自动关节仍能回退到接触面中心。
            o.remove("pivot");
        }
        o.add("rotation_deg", ParsedCube.arr(
                (float) Math.toDegrees(rotX), (float) Math.toDegrees(rotY), (float) Math.toDegrees(rotZ)));
        o.add("scale", ParsedCube.arr(scaleX, scaleY, scaleZ));
        if (restMatrix != null && restMatrix.length >= 9) {
            com.google.gson.JsonArray rm = new com.google.gson.JsonArray();
            for (int i = 0; i < 9; i++) {
                rm.add(restMatrix[i]);
            }
            o.add("rest_matrix", rm);
        } else {
            o.remove("rest_matrix");
        }
        o.addProperty("visible", visible);
        if (hasDeform) {
            o.addProperty("deform", deform);
        } else {
            o.remove("deform");
        }
        if (hasAttach) {
            o.addProperty("attach", attach);
        } else {
            o.remove("attach");
        }
        if (softJoint > 0f) {
            o.addProperty("soft_joint", softJoint);
            o.addProperty("soft_axis", softAxis == 0 ? "x" : softAxis == 2 ? "z" : "y");
        } else {
            o.remove("soft_joint");
            o.remove("soft_axis");
        }
        if (rigidJoint) {
            o.addProperty("rigid_joint", true);
        } else {
            o.remove("rigid_joint");
        }
        if (trackPitch) {
            o.addProperty("track_pitch", true);
        } else {
            o.remove("track_pitch");
        }
        if (follow != null && !follow.isBlank()) {
            o.addProperty("follow", follow);
        } else {
            o.remove("follow");
        }
        // 立方体/子树是结构化编辑结果，必须整体重写，不能保留旧快照。
        o.remove("cubes");
        if (!cubes.isEmpty()) {
            JsonArray cs = new JsonArray();
            for (ParsedCube c : cubes) {
                cs.add(c.toJson());
            }
            o.add("cubes", cs);
        }
        o.remove("children");
        if (!children.isEmpty()) {
            JsonObject ch = new JsonObject();
            for (ParsedBone c : children) {
                ch.add(c.name, c.toJson());
            }
            o.add("children", ch);
        }
        return o;
    }

    public static ParsedBone fromJson(String keyName, JsonObject o, ParsedBone parent) {
        ParsedBone b = new ParsedBone(o.has("name") && o.get("name").isJsonPrimitive()
                ? o.get("name").getAsString() : keyName);
        b.name = keyName != null ? keyName : b.name;
        b.parent = parent;
        b.originalJson = o.deepCopy();
        b.hasPivot = o.has("pivot") && o.get("pivot").isJsonArray()
                && o.getAsJsonArray("pivot").size() >= 3;
        float[] pivot = ParsedCube.floats(o, "pivot");
        b.pivotX = pivot[0];
        b.pivotY = pivot[1];
        b.pivotZ = pivot[2];
        float[] rot = ParsedCube.floats(o, "rotation_deg");
        b.rotX = (float) Math.toRadians(rot[0]);
        b.rotY = (float) Math.toRadians(rot[1]);
        b.rotZ = (float) Math.toRadians(rot[2]);
        float[] scale = ParsedCube.floats(o, "scale");
        // 缺失 scale 用 1；显式 0 是合法的隐藏/塌缩值，不能改写
        boolean hasScale = o.has("scale") && o.get("scale").isJsonArray();
        b.scaleX = hasScale ? scale[0] : 1f;
        b.scaleY = hasScale ? scale[1] : 1f;
        b.scaleZ = hasScale ? scale[2] : 1f;
        if (o.has("rest_matrix") && o.get("rest_matrix").isJsonArray()) {
            JsonArray rm = o.getAsJsonArray("rest_matrix");
            if (rm.size() >= 9) {
                try {
                    b.restMatrix = new float[9];
                    for (int i = 0; i < 9; i++) {
                        b.restMatrix[i] = rm.get(i).getAsFloat();
                    }
                } catch (RuntimeException e) {
                    // 非法 rest_matrix 不能把整份模型读挂；保留原始 JSON，交由校验报错。
                    b.restMatrix = null;
                }
            }
        }
        if (o.has("visible") && o.get("visible").isJsonPrimitive()) {
            b.visible = o.get("visible").getAsBoolean();
        }
        if (o.has("deform") && o.get("deform").isJsonPrimitive()) {
            b.deform = o.get("deform").getAsBoolean();
            b.hasDeform = true;
        }
        if (o.has("attach") && o.get("attach").isJsonPrimitive()) {
            b.attach = o.get("attach").getAsBoolean();
            b.hasAttach = true;
        }
        if (o.has("soft_joint") && o.get("soft_joint").isJsonPrimitive()) {
            b.softJoint = Math.max(0f, Math.min(1f, o.get("soft_joint").getAsFloat()));
        }
        if (o.has("soft_axis") && o.get("soft_axis").isJsonPrimitive()) {
            String ax = o.get("soft_axis").getAsString().toLowerCase();
            b.softAxis = ax.equals("x") ? 0 : ax.equals("z") ? 2 : 1;
        }
        if (o.has("rigid_joint") && o.get("rigid_joint").isJsonPrimitive()) {
            b.rigidJoint = o.get("rigid_joint").getAsBoolean();
        }
        if (o.has("track_pitch") && o.get("track_pitch").isJsonPrimitive()) {
            b.trackPitch = o.get("track_pitch").getAsBoolean();
        }
        if (o.has("follow") && o.get("follow").isJsonPrimitive()) {
            b.follow = o.get("follow").getAsString();
            b.hasFollow = true;
        }
        if (o.has("cubes") && o.get("cubes").isJsonArray()) {
            for (var e : o.getAsJsonArray("cubes")) {
                if (e.isJsonObject()) {
                    b.cubes.add(ParsedCube.fromJson(e.getAsJsonObject()));
                }
            }
        }
        if (o.has("children") && o.get("children").isJsonObject()) {
            for (var e : o.getAsJsonObject("children").entrySet()) {
                if (e.getValue().isJsonObject()) {
                    b.children.add(ParsedBone.fromJson(e.getKey(), e.getValue().getAsJsonObject(), b));
                }
            }
        }
        return b;
    }
}
