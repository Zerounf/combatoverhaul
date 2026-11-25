package com.yourname.combatoverhaul.client.model.core;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Spark 风格 cube：origin / pivot / rotation / size / inflate / mirror / UV。
 * 不依赖编辑器 ParsedCube，也不限制骨骼切分。
 */
public final class CoreCube {

    public final Vector3f origin = new Vector3f();
    public final Vector3f pivot = new Vector3f();
    public final Quaternionf rotation = new Quaternionf();
    public final Vector3f size = new Vector3f();
    public float inflate;
    public boolean mirror;
    public UVUnion uv = UVUnion.box(0, 0);

    public Matrix4f localTransform() {
        return new Matrix4f()
                .translate(origin)
                .translate(pivot)
                .rotate(rotation)
                .translate(-pivot.x, -pivot.y, -pivot.z);
    }

    public Vector3f min() {
        return new Vector3f(origin).sub(inflate, inflate, inflate);
    }

    public Vector3f max() {
        return new Vector3f(origin).add(size).add(inflate, inflate, inflate);
    }
}
