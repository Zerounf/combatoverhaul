package com.yourname.combatoverhaul.engine.core;

/** 播放一次的动作层；可循环，可设置权重。 */
public final class Montage {

    public final String name;
    public final AnimationClip clip;
    public float time;
    public float weight = 1f;
    public boolean loop;
    public boolean finished;

    public Montage(String name, AnimationClip clip, boolean loop) {
        this.name = name == null ? "" : name;
        this.clip = clip;
        this.loop = loop;
    }

    public void update(float deltaTicks) {
        if (finished || clip == null) {
            return;
        }
        time += Math.max(0f, deltaTicks);
        if (loop) {
            time = clip.normalize(time);
        } else if (time >= clip.lengthTicks) {
            time = clip.lengthTicks;
            finished = true;
        }
    }

    public Pose sample() {
        if (clip == null || finished) {
            return new Pose();
        }
        Pose pose = clip.sample(time);
        if (weight >= 1f) {
            return pose;
        }
        Pose scaled = new Pose();
        for (var e : pose.bones().entrySet()) {
            Transform t = e.getValue().copy();
            t.position.mul(weight);
            t.rotation.slerp(new org.joml.Quaternionf(), 1f - weight);
            t.scale.set(1f + (t.scale.x - 1f) * weight,
                    1f + (t.scale.y - 1f) * weight,
                    1f + (t.scale.z - 1f) * weight);
            scaled.bones().put(e.getKey(), t);
        }
        return scaled;
    }
}
