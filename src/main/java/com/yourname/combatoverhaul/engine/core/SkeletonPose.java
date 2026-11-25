package com.yourname.combatoverhaul.engine.core;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Skeleton + animation offsets -> 局部/全局矩阵。
 *
 * <p>Pose 里的 position/rotation/scale 是相对骨骼 rest 的增量：
 * position 叠加到 pivot，rotation 乘到 rest rotation，scale 乘到 rest scale。
 */
public final class SkeletonPose {

    private final Skeleton skeleton;
    private final Pose pose;
    private final Map<String, Matrix4f> local = new LinkedHashMap<>();
    private final Map<String, Matrix4f> global = new LinkedHashMap<>();

    public SkeletonPose(Skeleton skeleton, Pose pose) {
        this.skeleton = skeleton;
        this.pose = pose == null ? new Pose() : pose;
        for (String name : skeleton.bones().keySet()) {
            buildLocal(name);
        }
        for (String name : skeleton.bones().keySet()) {
            buildGlobal(name);
        }
    }

    public Matrix4f local(String bone) {
        return local.getOrDefault(bone, new Matrix4f());
    }

    public Matrix4f global(String bone) {
        return global.getOrDefault(bone, new Matrix4f());
    }

    private void buildLocal(String name) {
        SkeletonBone b = skeleton.bone(name);
        if (b == null) {
            local.put(name, new Matrix4f());
            return;
        }
        Transform extra = pose.get(name);
        Vector3f offset = extra == null ? new Vector3f() : extra.position;
        Quaternionf rot = new Quaternionf(b.rotation);
        Vector3f scale = new Vector3f(b.scale);
        if (extra != null) {
            rot.mul(extra.rotation);
            scale.mul(extra.scale);
        }
        Vector3f pivot = new Vector3f(b.pivot).add(offset);
        local.put(name, new Matrix4f()
                .translate(pivot)
                .rotate(rot)
                .scale(scale)
                .translate(-b.pivot.x, -b.pivot.y, -b.pivot.z));
    }

    private void buildGlobal(String name) {
        SkeletonBone b = skeleton.bone(name);
        if (b == null) {
            global.put(name, new Matrix4f());
            return;
        }
        if (b.parent == null || b.parent.isBlank() || skeleton.bone(b.parent) == null) {
            global.put(name, new Matrix4f(local(name)));
        } else {
            global.put(name, new Matrix4f(global(b.parent)).mul(local(name)));
        }
    }
}
