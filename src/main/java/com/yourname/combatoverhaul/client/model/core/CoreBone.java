package com.yourname.combatoverhaul.client.model.core;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spark 风格 bone：pivot / rotation / cubes / locators。
 *
 * <p>只描述层级和几何，不限制数量，也不阻止 BoneSplitter 继续自由切分。
 */
public final class CoreBone {

    public String name = "";
    public String parent = "";
    public final Vector3f pivot = new Vector3f();
    public final Quaternionf rotation = new Quaternionf();
    public final List<CoreCube> cubes = new ArrayList<>();
    public final Map<String, Vector3f> locators = new LinkedHashMap<>();

    public Matrix4f localTransform() {
        return new Matrix4f()
                .translate(pivot)
                .rotate(rotation)
                .translate(-pivot.x, -pivot.y, -pivot.z);
    }

    public Matrix4f globalTransform(Map<String, CoreBone> bones) {
        Matrix4f local = localTransform();
        if (parent == null || parent.isBlank()) {
            return local;
        }
        CoreBone p = bones.get(parent);
        return p == null ? local : p.globalTransform(bones).mul(local);
    }
}
