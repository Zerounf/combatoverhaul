package com.yourname.combatoverhaul.client.model.core;

import com.google.gson.JsonObject;
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

    /** Bedrock/内部 JSON -> 编辑器 ParsedModel -> Spark core。 */
    public static Map<String, CoreBone> fromJson(JsonObject json) {
        return fromParsed(ParsedModel.parse(json));
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

    /** Core -> 编辑器 ParsedBone，用于导入后继续用现有编辑器/切分工具。 */
    public static java.util.List<ParsedBone> toParsed(Map<String, CoreBone> core) {
        java.util.List<ParsedBone> out = new java.util.ArrayList<>();
        if (core == null) {
            return out;
        }
        Map<String, ParsedBone> byName = new LinkedHashMap<>();
        for (CoreBone bone : core.values()) {
            ParsedBone p = new ParsedBone(bone.name);
            p.pivotX = bone.pivot.x;
            p.pivotY = bone.pivot.y;
            p.pivotZ = bone.pivot.z;
            org.joml.Vector3f e = bone.rotation.getEulerAnglesZYX(new org.joml.Vector3f());
            p.rotX = e.x;
            p.rotY = e.y;
            p.rotZ = e.z;
            for (CoreCube cube : bone.cubes) {
                p.cubes.add(toParsedCube(cube));
            }
            byName.put(p.name, p);
            out.add(p);
        }
        for (ParsedBone p : out) {
            CoreBone coreBone = core.get(p.name);
            if (coreBone == null || coreBone.parent == null || coreBone.parent.isBlank()) {
                continue;
            }
            ParsedBone parent = byName.get(coreBone.parent);
            if (parent != null) {
                p.parent = parent;
                parent.children.add(p);
            }
        }
        return out;
    }

    public static ParsedCube toParsedCube(CoreCube cube) {
        ParsedCube out = new ParsedCube(cube.origin.x, cube.origin.y, cube.origin.z,
                cube.size.x, cube.size.y, cube.size.z);
        out.inflate = cube.inflate;
        out.mirror = cube.mirror;
        out.pivotX = cube.pivot.x;
        out.pivotY = cube.pivot.y;
        out.pivotZ = cube.pivot.z;
        org.joml.Vector3f e = cube.rotation.getEulerAnglesZYX(new org.joml.Vector3f());
        out.rotX = e.x;
        out.rotY = e.y;
        out.rotZ = e.z;
        for (int f = 0; f < 6; f++) {
            UVUnion.FaceUV face = cube.uv == null ? null : cube.uv.face(f);
            if (face == null) {
                continue;
            }
            out.faces[f] = new float[][]{
                    {face.corner(0)[0], face.corner(0)[1]},
                    {face.corner(1)[0], face.corner(1)[1]},
                    {face.corner(2)[0], face.corner(2)[1]},
                    {face.corner(3)[0], face.corner(3)[1]}
            };
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
                float[][] corners = new float[4][2];
                for (int k = 0; k < 4; k++) {
                    corners[k][0] = face[k][0];
                    corners[k][1] = face[k][1];
                }
                uv.faces[f] = new UVUnion.FaceUV(corners);
            }
        }
        return uv;
    }
}
