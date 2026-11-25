package com.yourname.combatoverhaul.engine.core;

/** 单骨骼动画轨道集合。 */
public final class BoneAnimation {

    public final String bone;
    public Vec3Track position = new Vec3Track();
    public QuatTrack rotation = new QuatTrack();
    public Vec3Track scale = new Vec3Track();

    public BoneAnimation(String bone) {
        this.bone = bone == null ? "" : bone;
    }

    public boolean isEmpty() {
        return position.isEmpty() && rotation.isEmpty() && scale.isEmpty();
    }

    public Transform sample(float time, Transform base) {
        Transform out = base == null ? new Transform() : base.copy();
        if (!position.isEmpty()) {
            out.position.set(position.sample(time));
        }
        if (!rotation.isEmpty()) {
            out.rotation.set(rotation.sample(time));
        }
        if (!scale.isEmpty()) {
            out.scale.set(scale.sample(time));
        }
        return out;
    }
}
