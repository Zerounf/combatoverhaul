package com.yourname.combatoverhaul.engine.core;

import java.util.ArrayList;
import java.util.List;

/** Skeleton + 状态机 + Montage 运行时；每帧只产出 Pose，不碰 Minecraft 渲染。 */
public final class AnimationRuntime {

    private final Skeleton skeleton;
    private final AnimationStateMachine states = new AnimationStateMachine();
    private final List<Montage> montages = new ArrayList<>();

    public AnimationRuntime(Skeleton skeleton) {
        this.skeleton = skeleton;
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    public AnimationStateMachine states() {
        return states;
    }

    public Montage playMontage(String name, AnimationClip clip, boolean loop) {
        Montage montage = new Montage(name, clip, loop);
        montages.add(montage);
        return montage;
    }

    public void update(float deltaTicks) {
        states.update(deltaTicks);
        for (Montage montage : montages) {
            montage.update(deltaTicks);
        }
        montages.removeIf(m -> m.finished && m.weight <= 0f);
    }

    public Pose sample() {
        Pose pose = states.sample();
        for (Montage montage : montages) {
            Pose layer = montage.sample();
            if (layer.bones().isEmpty()) {
                continue;
            }
            pose = pose.blend(layer, montage.weight);
        }
        return pose;
    }

    public SkeletonPose sampleMatrices() {
        return new SkeletonPose(skeleton, sample());
    }
}
