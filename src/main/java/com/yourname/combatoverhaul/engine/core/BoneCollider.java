package com.yourname.combatoverhaul.engine.core;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** 骨骼局部 OBB：中心、半尺寸、旋转；先用于命中体，不接刚体物理。 */
public final class BoneCollider {

    public String bone = "";
    public final Vector3f center = new Vector3f();
    public final Vector3f halfExtents = new Vector3f(0.5f, 0.5f, 0.5f);
    public final Quaternionf rotation = new Quaternionf();

    public BoneCollider(String bone) {
        this.bone = bone == null ? "" : bone;
    }

    public BoneCollider set(Vector3f center, Vector3f halfExtents, Quaternionf rotation) {
        this.center.set(center);
        this.halfExtents.set(halfExtents);
        this.rotation.set(rotation);
        return this;
    }

    public boolean contains(Vector3f point) {
        Vector3f local = new Vector3f(point).sub(center);
        new Quaternionf(rotation).conjugate().transform(local);
        return Math.abs(local.x) <= halfExtents.x
                && Math.abs(local.y) <= halfExtents.y
                && Math.abs(local.z) <= halfExtents.z;
    }
}
