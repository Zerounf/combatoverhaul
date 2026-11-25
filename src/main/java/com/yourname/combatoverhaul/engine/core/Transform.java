package com.yourname.combatoverhaul.engine.core;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** 单骨骼局部变换：位置 / 四元数旋转 / 缩放。 */
public final class Transform {

    public final Vector3f position = new Vector3f();
    public final Quaternionf rotation = new Quaternionf();
    public final Vector3f scale = new Vector3f(1, 1, 1);

    public Transform() {
    }

    public Transform(Vector3f position, Quaternionf rotation, Vector3f scale) {
        this.position.set(position);
        this.rotation.set(rotation);
        this.scale.set(scale);
    }

    public Transform copy() {
        return new Transform(position, rotation, scale);
    }

    public void set(Transform other) {
        position.set(other.position);
        rotation.set(other.rotation);
        scale.set(other.scale);
    }

    public Transform lerp(Transform other, float t) {
        Transform out = new Transform();
        out.position.set(position).lerp(other.position, t);
        out.rotation.set(rotation).slerp(other.rotation, t);
        out.scale.set(scale).lerp(other.scale, t);
        return out;
    }
}
