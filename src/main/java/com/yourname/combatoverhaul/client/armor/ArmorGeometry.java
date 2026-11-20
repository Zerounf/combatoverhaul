package com.yourname.combatoverhaul.client.armor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 与渲染器无关的盔甲几何中间表示。
 *
 * <p>原版 HumanoidArmorModel、Forge HumanoidModel、Geo JSON 等适配器最终都产出
 * 这个结构；动画运行时只依赖它，不依赖 ModelPart/GeckoLib 类。
 */
public final class ArmorGeometry {

    public static final int FORMAT_VERSION = 1;

    public String id = "";
    public String slot = "";
    public boolean inner;
    public String texture = "";
    public final List<Bone> bones = new ArrayList<>();
    public final List<Vertex> vertices = new ArrayList<>();
    public final List<Face> faces = new ArrayList<>();

    public static final class Bone {
        public String name;
        public String parent;
        public float pivotX;
        public float pivotY;
        public float pivotZ;

        public Bone() {
        }

        public Bone(String name, String parent, float x, float y, float z) {
            this.name = name;
            this.parent = parent;
            this.pivotX = x;
            this.pivotY = y;
            this.pivotZ = z;
        }
    }

    public static final class Vertex {
        public float x, y, z, u, v;
        public String bone;

        public Vertex() {
        }

        public Vertex(float x, float y, float z, float u, float v, String bone) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.u = u;
            this.v = v;
            this.bone = bone;
        }
    }

    public static final class Face {
        public int[] indices;

        public Face() {
        }

        public Face(int... indices) {
            this.indices = indices;
        }
    }

    public Bone bone(String name) {
        for (Bone b : bones) {
            if (b.name.equals(name)) {
                return b;
            }
        }
        return null;
    }

    /** 返回错误列表；为空表示结构可安全交给渲染/缓存。 */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (id == null || id.isBlank()) {
            errors.add("id 为空");
        }
        if (slot == null || slot.isBlank()) {
            errors.add("slot 为空");
        }
        if (bones.isEmpty()) {
            errors.add("bones 为空");
        }
        Set<String> names = new HashSet<>();
        for (Bone b : bones) {
            if (b.name == null || b.name.isBlank()) {
                errors.add("bone 名为空");
            } else if (!names.add(b.name)) {
                errors.add("重复 bone: " + b.name);
            }
        }
        for (Bone b : bones) {
            if (b.parent != null && !b.parent.isBlank() && !names.contains(b.parent)) {
                errors.add("bone " + b.name + " 的 parent 不存在: " + b.parent);
            }
        }
        for (Vertex v : vertices) {
            if (!Float.isFinite(v.x) || !Float.isFinite(v.y) || !Float.isFinite(v.z)
                    || !Float.isFinite(v.u) || !Float.isFinite(v.v)) {
                errors.add("顶点包含 NaN/Inf");
                break;
            }
            if (v.bone != null && !v.bone.isBlank() && !names.contains(v.bone)) {
                errors.add("顶点引用了不存在的 bone: " + v.bone);
                break;
            }
        }
        for (Face f : faces) {
            if (f.indices == null || f.indices.length < 3 || f.indices.length > 4) {
                errors.add("face 必须是 3/4 个索引");
                break;
            }
            for (int i : f.indices) {
                if (i < 0 || i >= vertices.size()) {
                    errors.add("face 索引越界: " + i);
                    break;
                }
            }
        }
        return errors;
    }

    /** 顶点 AABB：minX,minY,minZ,maxX,maxY,maxZ；无顶点返回 null。 */
    public float[] bounds() {
        if (vertices.isEmpty()) {
            return null;
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
}
