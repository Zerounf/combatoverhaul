package com.yourname.combatoverhaul.engine.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 最小状态机：一个当前 clip + 可选 crossfade 到目标 clip。
 * 不是完整 Spark 状态树，但先提供 Montage/动作层的运行时基础。
 */
public final class AnimationStateMachine {

    private final Map<String, AnimationClip> clips = new LinkedHashMap<>();
    private String current = "";
    private float time;
    private String fadeFrom = "";
    private float fadeElapsed;
    private float fadeDuration;

    public AnimationStateMachine add(String name, AnimationClip clip) {
        clips.put(name, clip);
        return this;
    }

    public AnimationClip currentClip() {
        return clips.get(current);
    }

    public String current() {
        return current;
    }

    public void play(String name, float fadeTicks) {
        if (!clips.containsKey(name)) {
            return;
        }
        if (name.equals(current)) {
            return;
        }
        fadeFrom = current;
        fadeElapsed = 0f;
        fadeDuration = Math.max(0f, fadeTicks);
        current = name;
        time = 0f;
    }

    public void update(float deltaTicks) {
        time += Math.max(0f, deltaTicks);
        if (fadeDuration > 0f) {
            fadeElapsed += Math.max(0f, deltaTicks);
        }
    }

    public Pose sample() {
        AnimationClip clip = currentClip();
        if (clip == null) {
            return new Pose();
        }
        Pose pose = clip.sample(time);
        AnimationClip from = clips.get(fadeFrom);
        if (from != null && fadeDuration > 0f && fadeElapsed < fadeDuration) {
            float t = fadeElapsed / fadeDuration;
            pose = from.sample(time).blend(pose, t);
        }
        return pose;
    }

    public float time() {
        return time;
    }
}
