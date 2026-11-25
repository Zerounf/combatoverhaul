package com.yourname.combatoverhaul.client.model.core;

import com.yourname.combatoverhaul.client.model.ParsedBone;
import com.yourname.combatoverhaul.client.model.ParsedCube;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import org.joml.Quaternionf;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 编辑器 ParsedModel -> Spark 风格 core。
 *
 * <p>只做数据适配，不改 ParsedBone 树，也不限制 BoneSplitter 的自由切分。
 */
public final class CoreModelAdapter {

    private CoreModelAdapter() {
    }

    public static Map<String, CoreBone> fromParsed(ParsedModel model) {
        Map<String, CoreBone> out = new LinkedHashMap<>();
        if (model == null) {
            return out;
        }
        for (ParsedBone bone : model.bones) {
            out.put(bone.name, fromBone(bone));
        }
        return out;
    }

    public static CoreBone fromBone(ParsedBone bone) {
        CoreBone out = new CoreBone();
        out.name = bone.name;
        out.parent = bone.parent == null ? "" : bone.parent.name;
        out.pivot.set(bone.pivotX, bone.pivotY, bone.pivotZ);
        out.rotation.set(new Quaternionf().rotationZYX(bone.rotZ, bone.rotY, bone.rotX));
        for (ParsedCube cube : bone.cubes) {
            out.cubes.add(fromCube(cube));
        }
        return out;
    }

    public static CoreCube fromCube(ParsedCube cube) {
        CoreCube out = new CoreCube();
        out.origin.set(cube.originX, cube.originY, cube.originZ);
        out.pivot.set(cube.pivotX, cube.pivotY, cube.pivotZ);
        out.size.set(cube.sizeX, cube.sizeY, cube.sizeZ);
        out.inflate = cube.inflate;
        out.mirror = cube.mirror;
        out.rotation.set(new Quaternionf().rotationZYX(cube.rotZ, cube.rotY, cube.rotX));
        out.uv = fromFaces(cube);
        return out;
    }

    private static UVUnion fromFaces(ParsedCube cube) {
        UVUnion uv = UVUnion.faces();
        for (int f = 0; f < 6; f++) {
            float[][] face = cube.faces[f];
            if (face == null || face.length != 4) {
                continue;
            }
            float minU = Float.MAX_VALUE;
            float minV = Float.MAX_VALUE;
            float maxU = -Float.MAX_VALUE;
            float maxV = -Float.MAX_VALUE;
            for (float[] p : face) {
                if (p == null || p.length < 2) {
                    continue;
                }
                minU = Math.min(minU, p[0]);
                minV = Math.min(minV, p[1]);
                maxU = Math.max(maxU, p[0]);
                maxV = Math.max(maxV, p[1]);
            }
            if (minU != Float.MAX_VALUE) {
                uv.face(f, minU, minV, maxU - minU, maxV - minV);
            }
        }
        return uv;
    }
}
