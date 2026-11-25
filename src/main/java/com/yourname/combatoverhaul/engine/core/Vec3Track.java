package com.yourname.combatoverhaul.engine.core;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Vector3 关键帧轨道。 */
public final class Vec3Track {

    public static final class Key {
        public final float time;
        public final Vector3f value;

        public Key(float time, Vector3f value) {
            this.time = time;
            this.value = new Vector3f(value);
        }
    }

    private final List<Key> keys = new ArrayList<>();
    private Interpolation interpolation = Interpolation.LINEAR;

    public Vec3Track add(float time, Vector3f value) {
        keys.add(new Key(time, value));
        keys.sort(Comparator.comparingDouble(k -> k.time));
        return this;
    }

    public Vec3Track interpolation(Interpolation interpolation) {
        this.interpolation = interpolation;
        return this;
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    public Vector3f sample(float time) {
        if (keys.isEmpty()) {
            return new Vector3f();
        }
        if (keys.size() == 1 || time <= keys.get(0).time) {
            return new Vector3f(keys.get(0).value);
        }
        Key last = keys.get(keys.size() - 1);
        if (time >= last.time) {
            return new Vector3f(last.value);
        }
        int hi = 1;
        while (hi < keys.size() && keys.get(hi).time < time) {
            hi++;
        }
        int lo = hi - 1;
        Key a = keys.get(lo);
        Key b = keys.get(hi);
        float span = b.time - a.time;
        float t = span <= 1.0e-6f ? 0f : (time - a.time) / span;
        if (interpolation == Interpolation.CATMULL_ROM && keys.size() >= 4) {
            Vector3f p0 = keys.get(Math.max(0, lo - 1)).value;
            Vector3f p1 = a.value;
            Vector3f p2 = b.value;
            Vector3f p3 = keys.get(Math.min(keys.size() - 1, hi + 1)).value;
            return catmull(p0, p1, p2, p3, t);
        }
        return new Vector3f(a.value).lerp(b.value, t);
    }

    private static Vector3f catmull(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        Vector3f out = new Vector3f();
        for (int i = 0; i < 3; i++) {
            float v = 0.5f * ((2f * p1.get(i))
                    + (-p0.get(i) + p2.get(i)) * t
                    + (2f * p0.get(i) - 5f * p1.get(i) + 4f * p2.get(i) - p3.get(i)) * t2
                    + (-p0.get(i) + 3f * p1.get(i) - 3f * p2.get(i) + p3.get(i)) * t3);
            out.setComponent(i, v);
        }
        return out;
    }
}
