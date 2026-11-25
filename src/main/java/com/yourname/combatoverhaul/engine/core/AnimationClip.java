package com.yourname.combatoverhaul.engine.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** 一段动画：长度、循环、每根骨骼的轨道。 */
public final class AnimationClip {

    public final String name;
    public float lengthTicks = 1f;
    public boolean loop = true;
    public final Map<String, BoneAnimation> bones = new LinkedHashMap<>();

    public AnimationClip(String name) {
        this.name = name == null ? "" : name;
    }

    public BoneAnimation bone(String name) {
        return bones.computeIfAbsent(name == null ? "" : name, BoneAnimation::new);
    }

    public float normalize(float tick) {
        if (lengthTicks <= 0f) {
            return 0f;
        }
        float t = tick;
        if (loop) {
            t = t % lengthTicks;
            if (t < 0f) {
                t += lengthTicks;
            }
        } else {
            t = Math.max(0f, Math.min(lengthTicks, t));
        }
        return t;
    }

    public Pose sample(float tick) {
        float t = normalize(tick);
        Pose pose = new Pose();
        for (BoneAnimation anim : bones.values()) {
            if (!anim.isEmpty()) {
                pose.getOrCreate(anim.bone).set(anim.sample(t, null));
            }
        }
        return pose;
    }

    /** 用基础姿态补全没有轨道变化的骨骼，再采样。 */
    public Pose sample(float tick, Map<String, Transform> basePose) {
        Pose pose = new Pose();
        if (basePose != null) {
            for (Map.Entry<String, Transform> e : basePose.entrySet()) {
                pose.getOrCreate(e.getKey()).set(e.getValue());
            }
        }
        float t = normalize(tick);
        for (BoneAnimation anim : bones.values()) {
            if (!anim.isEmpty()) {
                pose.getOrCreate(anim.bone).set(anim.sample(t, pose.get(anim.bone)));
            }
        }
        return pose;
    }
}
