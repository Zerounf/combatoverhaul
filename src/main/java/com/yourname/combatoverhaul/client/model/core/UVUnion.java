package com.yourname.combatoverhaul.client.model.core;

/**
 * Bedrock/Spark 风格 UV：要么一个 box UV 原点，要么六面逐面 UV。
 *
 * <p>面顺序与 {@code ParsedCube.FACE_NAMES} 一致：
 * down, up, west, east, north, south。
 */
public final class UVUnion {

    public enum Kind {
        BOX,
        FACES
    }

    public Kind kind = Kind.BOX;
    public float boxU;
    public float boxV;
    public final FaceUV[] faces = new FaceUV[6];

    public static UVUnion box(float u, float v) {
        UVUnion uv = new UVUnion();
        uv.kind = Kind.BOX;
        uv.boxU = u;
        uv.boxV = v;
        return uv;
    }

    public static UVUnion faces() {
        UVUnion uv = new UVUnion();
        uv.kind = Kind.FACES;
        return uv;
    }

    public UVUnion face(int index, float u, float v, float w, float h) {
        if (index >= 0 && index < faces.length) {
            faces[index] = new FaceUV(u, v, w, h);
        }
        return this;
    }

    public FaceUV face(int index) {
        return index >= 0 && index < faces.length ? faces[index] : null;
    }

    public static final class FaceUV {
        public final float u;
        public final float v;
        public final float w;
        public final float h;

        public FaceUV(float u, float v, float w, float h) {
            this.u = u;
            this.v = v;
            this.w = w;
            this.h = h;
        }
    }
}
