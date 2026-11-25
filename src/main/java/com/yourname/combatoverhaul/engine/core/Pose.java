package com.yourname.combatoverhaul.engine.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** 动画采样结果：骨骼名 -> 局部 Transform。 */
public final class Pose {

    private final Map<String, Transform> bones = new LinkedHashMap<>();

    public Transform getOrCreate(String bone) {
        return bones.computeIfAbsent(bone == null ? "" : bone, k -> new Transform());
    }

    public Transform get(String bone) {
        return bones.get(bone);
    }

    public Map<String, Transform> bones() {
        return bones;
    }

    public Pose blend(Pose other, float t) {
        Pose out = new Pose();
        for (Map.Entry<String, Transform> e : bones.entrySet()) {
            Transform b = other.get(e.getKey());
            out.bones.put(e.getKey(), b == null ? e.getValue().copy() : e.getValue().lerp(b, t));
        }
        for (Map.Entry<String, Transform> e : other.bones.entrySet()) {
            out.bones.putIfAbsent(e.getKey(), e.getValue().copy());
        }
        return out;
    }
}
