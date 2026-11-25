package com.yourname.combatoverhaul.engine.core;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 运行时骨架：骨骼表 + 根列表 + local/global 矩阵。 */
public final class Skeleton {

    private final Map<String, SkeletonBone> bones = new LinkedHashMap<>();
    private final List<String> roots = new ArrayList<>();

    public SkeletonBone add(String name, String parent) {
        SkeletonBone bone = new SkeletonBone(name, parent);
        bones.put(bone.name, bone);
        if (parent == null || parent.isBlank() || !bones.containsKey(parent)) {
            roots.add(bone.name);
        }
        return bone;
    }

    public SkeletonBone bone(String name) {
        return name == null ? null : bones.get(name);
    }

    public Map<String, SkeletonBone> bones() {
        return bones;
    }

    public List<String> roots() {
        return roots;
    }

    public Matrix4f local(String name) {
        SkeletonBone b = bone(name);
        if (b == null) {
            return new Matrix4f();
        }
        return new Matrix4f()
                .translate(b.pivot)
                .rotate(b.rotation)
                .scale(b.scale)
                .translate(-b.pivot.x, -b.pivot.y, -b.pivot.z);
    }

    public Matrix4f global(String name) {
        SkeletonBone b = bone(name);
        if (b == null) {
            return new Matrix4f();
        }
        if (b.parent == null || b.parent.isBlank() || bone(b.parent) == null) {
            return local(name);
        }
        return global(b.parent).mul(local(name));
    }
}
