package com.yourname.combatoverhaul.engine.core;

import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 四元数关键帧轨道，始终用 slerp。 */
public final class QuatTrack {

    public static final class Key {
        public final float time;
        public final Quaternionf value;

        public Key(float time, Quaternionf value) {
            this.time = time;
            this.value = new Quaternionf(value).normalize();
        }
    }

    private final List<Key> keys = new ArrayList<>();

    public QuatTrack add(float time, Quaternionf value) {
        keys.add(new Key(time, value));
        keys.sort(Comparator.comparingDouble(k -> k.time));
        return this;
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    public Quaternionf sample(float time) {
        if (keys.isEmpty()) {
            return new Quaternionf();
        }
        if (keys.size() == 1 || time <= keys.get(0).time) {
            return new Quaternionf(keys.get(0).value);
        }
        Key last = keys.get(keys.size() - 1);
        if (time >= last.time) {
            return new Quaternionf(last.value);
        }
        int hi = 1;
        while (hi < keys.size() && keys.get(hi).time < time) {
            hi++;
        }
        Key a = keys.get(hi - 1);
        Key b = keys.get(hi);
        float span = b.time - a.time;
        float t = span <= 1.0e-6f ? 0f : (time - a.time) / span;
        return new Quaternionf(a.value).slerp(b.value, t);
    }
}
