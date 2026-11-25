package com.yourname.combatoverhaul.engine.core;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** 把骨骼局部 OBB 变换到模型空间。 */
public final class ColliderRuntime {

    private ColliderRuntime() {
    }

    public static BoneCollider world(BoneCollider local, SkeletonPose pose, String bone) {
        BoneCollider out = new BoneCollider(local.bone);
        Matrix4f m = pose.global(bone);
        out.center.set(local.center);
        m.transformPosition(out.center);
        out.halfExtents.set(local.halfExtents);
        Vector3f scale = m.getScale(new Vector3f());
        out.halfExtents.mul(Math.abs(scale.x), Math.abs(scale.y), Math.abs(scale.z));
        Quaternionf rot = m.getUnnormalizedRotation(new Quaternionf());
        out.rotation.set(rot).mul(local.rotation);
        return out;
    }
}
