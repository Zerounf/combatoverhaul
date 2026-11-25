package com.yourname.combatoverhaul.engine.core;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** 运行时骨骼：父名 + 局部 pivot/rotation/scale。 */
public final class SkeletonBone {

    public final String name;
    public final String parent;
    public final Vector3f pivot = new Vector3f();
    public final Quaternionf rotation = new Quaternionf();
    public final Vector3f scale = new Vector3f(1, 1, 1);

    public SkeletonBone(String name, String parent) {
        this.name = name == null ? "" : name;
        this.parent = parent == null ? "" : parent;
    }
}
