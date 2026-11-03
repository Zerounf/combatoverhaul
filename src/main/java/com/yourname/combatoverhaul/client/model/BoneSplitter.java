package com.yourname.combatoverhaul.client.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一根 cube 骨骼沿指定轴、按给定比例切成 2~8 段。
 *
 * <p>与旧“插入空关节”不同：这里真的切立方体几何：
 * <ul>
 *   <li>选中骨骼保留名称/动作通道，拿第 1 段；</li>
 *   <li>后续段生成子骨骼链，枢轴落在各段起点；</li>
 *   <li>cube 原点重基到子骨骼枢轴，世界几何不变；</li>
 *   <li>逐面 UV 按切分比例裁剪；</li>
 *   <li>face_verts 烘焙进 origin/size 后清空，避免渲染端继续用旧顶点。</li>
 * </ul>
 *
 * <p>只支持轴对齐、无 cube 自转/镜像、无 rest_matrix 的 cube 骨骼；
 * 原生 player/Alex、GeckoLib 方块模型满足这些条件。
 */
public final class BoneSplitter {

    private static final float EPS = 1.0e-5f;
    public static final int MIN_SEGMENTS = 2;
    public static final int MAX_SEGMENTS = 8;

    private BoneSplitter() {
    }

    public static final class Result {
        public boolean ok;
        public String error;
        public int axis;
        public float splitPosition;
        public String childName;
        public int segments;
        public float[] ratios;
        public float[] boundaries;
        public final List<String> childNames = new ArrayList<>();
        /** 同步切开的外层皮肤子骨（sleeve/pants/jacket/hat）。 */
        public final List<String> outerChildNames = new ArrayList<>();
        public int upperCubes;
        public int lowerCubes;
    }

    /** 默认：最长轴、50% / 50%。 */
    public static Result splitAtMid(ParsedModel model, ParsedBone bone) {
        int axis = suggestAxis(bone);
        if (axis < 0) {
            Result r = new Result();
            r.error = "该骨骼没有可切分的 cube";
            return r;
        }
        return split(model, bone, axis, new float[]{0.5f, 0.5f});
    }

    /** 按用户指定的轴与比例切分；比例会自动归一化。 */
    public static Result split(ParsedModel model, ParsedBone bone, int axis, float[] ratios) {
        // 切跟随者时先切目标：目标切完会把跟随者按同轴同比例同步切，避免两边结构分叉。
        if (bone != null && bone.follow != null && !bone.follow.isBlank()) {
            ParsedBone target = model.findBone(bone.follow);
            if (target != null && target != bone) {
                return splitInternal(model, target, axis, ratios, true);
            }
        }
        return splitInternal(model, bone, axis, ratios, true);
    }

    private static Result splitInternal(ParsedModel model, ParsedBone bone, int axis,
                                        float[] ratios, boolean syncFollowers) {
        Result r = new Result();
        if (model == null || bone == null) {
            r.error = "没有选中骨骼";
            return r;
        }
        if (bone.cubes.isEmpty()) {
            r.error = "该骨骼没有 cube，无法分段";
            return r;
        }
        if (bone.restMatrix != null && bone.restMatrix.length >= 9) {
            r.error = "暂不支持切分 rest_matrix 骨骼";
            return r;
        }
        if (axis < 0 || axis > 2) {
            r.error = "切分轴必须是 X/Y/Z";
            return r;
        }
        if (ratios == null || ratios.length < MIN_SEGMENTS || ratios.length > MAX_SEGMENTS) {
            r.error = "段数必须在 " + MIN_SEGMENTS + "~" + MAX_SEGMENTS + " 之间";
            return r;
        }
        for (ParsedCube cube : bone.cubes) {
            if (cube.hasRot()) {
                r.error = "暂不支持切分 cube 自转的骨骼";
                return r;
            }
            if (cube.mirror) {
                r.error = "暂不支持切分 mirror cube 的骨骼";
                return r;
            }
        }

        float sum = 0f;
        for (float v : ratios) {
            if (!Float.isFinite(v) || v <= 0f) {
                r.error = "每段比例必须大于 0";
                return r;
            }
            sum += v;
        }
        if (sum <= EPS) {
            r.error = "比例总和必须大于 0";
            return r;
        }
        float[] normalized = new float[ratios.length];
        for (int i = 0; i < ratios.length; i++) {
            normalized[i] = ratios[i] / sum;
        }

        // 烘焙成轴对齐 box（face_verts 可能带 CubeDeformation 外扩）。
        List<ParsedCube> baked = new ArrayList<>();
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (ParsedCube cube : bone.cubes) {
            ParsedCube b = bake(cube);
            baked.add(b);
            min[0] = Math.min(min[0], b.originX);
            min[1] = Math.min(min[1], b.originY);
            min[2] = Math.min(min[2], b.originZ);
            max[0] = Math.max(max[0], b.originX + b.sizeX);
            max[1] = Math.max(max[1], b.originY + b.sizeY);
            max[2] = Math.max(max[2], b.originZ + b.sizeZ);
        }
        float start = min[axis];
        float span = max[axis] - min[axis];
        if (span <= EPS) {
            r.error = "骨骼在该轴上没有可切分跨度";
            return r;
        }

        int count = normalized.length;
        float[] segStart = new float[count];
        float[] segEnd = new float[count];
        float cumulative = 0f;
        for (int i = 0; i < count; i++) {
            segStart[i] = start + span * cumulative;
            cumulative += normalized[i];
            segEnd[i] = start + span * cumulative;
        }
        segEnd[count - 1] = max[axis];

        List<List<ParsedCube>> segmentCubes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            List<ParsedCube> list = new ArrayList<>();
            for (ParsedCube cube : baked) {
                ParsedCube clipped = clipCube(cube, axis, segStart[i], segEnd[i]);
                if (clipped != null) {
                    list.add(clipped);
                }
            }
            if (list.isEmpty()) {
                r.error = "第 " + (i + 1) + " 段切出来是空的，请调整比例";
                return r;
            }
            segmentCubes.add(list);
        }

        List<ParsedBone> outerChildren = new ArrayList<>();
        for (ParsedBone child : bone.children) {
            if (isHumanoidOuterLayer(model, child) && !child.cubes.isEmpty()) {
                outerChildren.add(child);
            }
        }

        bone.cubes.clear();
        bone.cubes.addAll(segmentCubes.get(0));

        List<ParsedBone> mainSegments = new ArrayList<>();
        mainSegments.add(bone);
        ParsedBone previous = bone;
        for (int i = 1; i < count; i++) {
            String childName = uniqueName(model, childBase(bone, i + 1));
            ParsedBone child = new ParsedBone(childName);
            child.parent = previous;
            child.hasPivot = true;
            float pivot = i == 1 ? segStart[i] : segStart[i] - segStart[i - 1];
            child.pivotX = axis == 0 ? pivot : 0f;
            child.pivotY = axis == 1 ? pivot : 0f;
            child.pivotZ = axis == 2 ? pivot : 0f;
            child.rotX = child.rotY = child.rotZ = 0f;
            child.scaleX = child.scaleY = child.scaleZ = 1f;
            child.visible = true;
            child.deform = true;
            child.hasDeform = true;
            child.attach = false;
            child.hasAttach = false;
            child.rigidJoint = true;
            child.softAxis = axis;
            for (ParsedCube cube : segmentCubes.get(i)) {
                shiftAxis(cube, axis, -segStart[i]);
                child.cubes.add(cube);
            }
            previous.children.add(child);
            previous = child;
            mainSegments.add(child);
            r.childNames.add(childName);
        }

        splitOuterLayers(model, axis, segStart, segEnd, outerChildren, mainSegments, r);
        if (syncFollowers) {
            syncFollowers(model, bone, axis, normalized, r);
        }
        model.flatten();
        r.ok = true;
        r.axis = axis;
        r.splitPosition = segStart.length > 1 ? segStart[1] : start;
        r.childName = r.childNames.isEmpty() ? null : r.childNames.get(0);
        r.segments = count;
        r.ratios = normalized;
        r.boundaries = segEnd;
        r.upperCubes = segmentCubes.get(0).size();
        r.lowerCubes = count > 1 ? segmentCubes.get(1).size() : 0;
        return r;
    }

    /**
     * 跟随子树同步分段：目标骨切开后，所有 follow=目标 的跟随者也按同轴同比例切，
     * 并把目标子骨的局部 rest 复制给跟随者子骨；随后 syncFollow 会同步动画。
     */
    private static float[] axisBounds(ParsedBone bone, int axis) {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (ParsedCube cube : bone.cubes) {
            ParsedCube b = bake(cube);
            if (axis == 0) {
                min = Math.min(min, b.originX);
                max = Math.max(max, b.originX + b.sizeX);
            } else if (axis == 1) {
                min = Math.min(min, b.originY);
                max = Math.max(max, b.originY + b.sizeY);
            } else {
                min = Math.min(min, b.originZ);
                max = Math.max(max, b.originZ + b.sizeZ);
            }
        }
        return min == Float.MAX_VALUE ? null : new float[]{min, max};
    }

    private static void syncFollowers(ParsedModel model, ParsedBone bone, int axis,
                                      float[] ratios, Result targetResult) {
        List<ParsedBone> followers = new ArrayList<>();
        for (ParsedBone b : model.bones) {
            if (b != bone && bone.name.equals(b.follow)) {
                followers.add(b);
            }
        }
        // 用目标骨的实际切面坐标，而不是把同一组比例套到外层皮肤上。
        // 外层袖/裤子通常比主骨更长或更短，同比例会切在错误的截面上。
        int count = targetResult.boundaries == null ? 0 : targetResult.boundaries.length;
        float[] cuts = new float[Math.max(0, count - 1)];
        if (count >= 2) {
            System.arraycopy(targetResult.boundaries, 0, cuts, 0, count - 1);
        }
        for (ParsedBone follower : followers) {
            float[] useRatios = ratios;
            float[] fb = axisBounds(follower, axis);
            if (cuts.length > 0 && fb != null) {
                float fMin = fb[0];
                float fMax = fb[1];
                float[] absolute = new float[cuts.length + 1];
                float prev = fMin;
                boolean valid = true;
                for (int i = 0; i < cuts.length; i++) {
                    float cut = Math.max(fMin + 1.0e-3f, Math.min(fMax - 1.0e-3f, cuts[i]));
                    absolute[i] = cut - prev;
                    if (absolute[i] <= 1.0e-4f) {
                        valid = false;
                        break;
                    }
                    prev = cut;
                }
                absolute[absolute.length - 1] = fMax - prev;
                if (valid && absolute[absolute.length - 1] > 1.0e-4f) {
                    useRatios = absolute;
                }
            }
            Result fr = splitInternal(model, follower, axis, useRatios, false);
            if (!fr.ok) {
                continue;
            }
            int n = Math.min(targetResult.childNames.size(), fr.childNames.size());
            for (int i = 0; i < n; i++) {
                ParsedBone source = model.findBone(targetResult.childNames.get(i));
                ParsedBone copy = model.findBone(fr.childNames.get(i));
                if (source != null && copy != null) {
                    copyBase(source, copy);
                    // 用显式映射代替“子骨索引”，避免目标子骨顺序变化后镜像到错误的段。
                    copy.follow = source.name;
                    copy.hasFollow = true;
                }
            }
        }
    }

    private static void copyBase(ParsedBone source, ParsedBone copy) {
        copy.pivotX = source.pivotX;
        copy.pivotY = source.pivotY;
        copy.pivotZ = source.pivotZ;
        copy.rotX = source.rotX;
        copy.rotY = source.rotY;
        copy.rotZ = source.rotZ;
        copy.scaleX = source.scaleX;
        copy.scaleY = source.scaleY;
        copy.scaleZ = source.scaleZ;
        copy.restMatrix = source.restMatrix == null ? null : source.restMatrix.clone();
        copy.hasPivot = source.hasPivot;
    }

    /**
     * 双层皮肤：主骨切开后，把同一块区域的外层骨（sleeve/pants/jacket/hat）也切开。
     * 外层第 2..N 段挂到对应的主骨第 2..N 段，避免外层仍跟着上半段。
     */
    private static void splitOuterLayers(ParsedModel model, int axis,
                                         float[] segStart, float[] segEnd,
                                         List<ParsedBone> outerChildren,
                                         List<ParsedBone> mainSegments,
                                         Result r) {
        if (outerChildren.isEmpty() || mainSegments.size() < 2) {
            return;
        }
        for (ParsedBone outer : outerChildren) {
            if (outer.restMatrix != null
                    || Math.abs(outer.rotX) > EPS || Math.abs(outer.rotY) > EPS
                    || Math.abs(outer.rotZ) > EPS
                    || Math.abs(outer.scaleX - 1f) > EPS
                    || Math.abs(outer.scaleY - 1f) > EPS
                    || Math.abs(outer.scaleZ - 1f) > EPS) {
                continue; // 保持保守：带自身变换的外层骨不自动切
            }
            List<ParsedCube> baked = new ArrayList<>();
            for (ParsedCube cube : outer.cubes) {
                baked.add(bake(cube));
            }
            float pOuter = axis == 0 ? outer.pivotX : axis == 1 ? outer.pivotY : outer.pivotZ;
            List<List<ParsedCube>> segments = new ArrayList<>();
            boolean any = false;
            for (int i = 0; i < segStart.length; i++) {
                List<ParsedCube> list = new ArrayList<>();
                for (ParsedCube cube : baked) {
                    ParsedCube clipped = clipCube(cube, axis,
                            segStart[i] - pOuter, segEnd[i] - pOuter);
                    if (clipped != null) {
                        list.add(clipped);
                    }
                }
                if (!list.isEmpty()) {
                    any = true;
                }
                segments.add(list);
            }
            if (!any) {
                continue;
            }
            outer.cubes.clear();
            outer.cubes.addAll(segments.get(0));
            for (int i = 1; i < segments.size() && i < mainSegments.size(); i++) {
                if (segments.get(i).isEmpty()) {
                    continue;
                }
                String name = uniqueName(model, childBase(outer, i + 1));
                ParsedBone child = new ParsedBone(name);
                child.parent = mainSegments.get(i);
                child.hasPivot = true;
                child.pivotX = axis == 0 ? 0f : outer.pivotX;
                child.pivotY = axis == 1 ? 0f : outer.pivotY;
                child.pivotZ = axis == 2 ? 0f : outer.pivotZ;
                child.rotX = child.rotY = child.rotZ = 0f;
                child.scaleX = child.scaleY = child.scaleZ = 1f;
                child.visible = true;
                child.deform = true;
                child.hasDeform = true;
                child.attach = false;
                child.hasAttach = false;
                child.rigidJoint = true;
                child.softAxis = axis;
                for (ParsedCube cube : segments.get(i)) {
                    shiftAxis(cube, axis, pOuter - segStart[i]);
                    child.cubes.add(cube);
                }
                mainSegments.get(i).children.add(child);
                r.outerChildNames.add(name);
            }
        }
    }

    private static boolean isHumanoidOuterLayer(ParsedModel model, ParsedBone bone) {
        String semantic = model.semanticName(bone.name);
        if (semantic == null) {
            return false;
        }
        String s = semantic.toLowerCase(java.util.Locale.ROOT);
        return s.equals("hat") || s.equals("jacket") || s.endsWith("_sleeve") || s.endsWith("_pants");
    }

    /** 推荐最长轴；长度相同优先 Y（竖直肢体/躯干）。 */
    public static int suggestAxis(ParsedBone bone) {
        if (bone == null || bone.cubes.isEmpty()) {
            return -1;
        }
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (ParsedCube cube : bone.cubes) {
            ParsedCube b = bake(cube);
            min[0] = Math.min(min[0], b.originX);
            min[1] = Math.min(min[1], b.originY);
            min[2] = Math.min(min[2], b.originZ);
            max[0] = Math.max(max[0], b.originX + b.sizeX);
            max[1] = Math.max(max[1], b.originY + b.sizeY);
            max[2] = Math.max(max[2], b.originZ + b.sizeZ);
        }
        int axis = 0;
        float best = max[0] - min[0];
        float y = max[1] - min[1];
        if (y >= best) {
            axis = 1;
            best = y;
        }
        float z = max[2] - min[2];
        if (z > best) {
            axis = 2;
        }
        return axis;
    }

    /** 把 face_verts 的外扩范围烘焙成 origin/size，并清空精确顶点。 */
    private static ParsedCube bake(ParsedCube source) {
        ParsedCube c = source.copy();
        float minX = c.originX, minY = c.originY, minZ = c.originZ;
        float maxX = c.originX + c.sizeX;
        float maxY = c.originY + c.sizeY;
        float maxZ = c.originZ + c.sizeZ;
        for (int f = 0; f < 6; f++) {
            if (c.faceVerts[f] == null) {
                continue;
            }
            for (int k = 0; k < 4; k++) {
                minX = Math.min(minX, c.faceVerts[f][k][0]);
                minY = Math.min(minY, c.faceVerts[f][k][1]);
                minZ = Math.min(minZ, c.faceVerts[f][k][2]);
                maxX = Math.max(maxX, c.faceVerts[f][k][0]);
                maxY = Math.max(maxY, c.faceVerts[f][k][1]);
                maxZ = Math.max(maxZ, c.faceVerts[f][k][2]);
            }
        }
        c.originX = minX;
        c.originY = minY;
        c.originZ = minZ;
        c.sizeX = maxX - minX;
        c.sizeY = maxY - minY;
        c.sizeZ = maxZ - minZ;
        for (int f = 0; f < 6; f++) {
            c.faceVerts[f] = null;
        }
        c.mirror = false;
        c.rotX = c.rotY = c.rotZ = 0f;
        c.pivotX = c.pivotY = c.pivotZ = 0f;
        return c;
    }

    private static ParsedCube clipCube(ParsedCube cube, int axis, float from, float to) {
        float origin = axisValue(cube, axis, false);
        float size = axisValue(cube, axis, true);
        float end = origin + size;
        float a = Math.max(origin, from);
        float b = Math.min(end, to);
        if (size <= EPS || b - a <= EPS) {
            return null;
        }
        ParsedCube c = cube.copy();
        setAxisOrigin(c, axis, a);
        setAxisSize(c, axis, b - a);
        sliceFaces(c, axis, (a - origin) / size, (b - origin) / size);
        return c;
    }

    private static float axisValue(ParsedCube c, int axis, boolean size) {
        if (axis == 0) {
            return size ? c.sizeX : c.originX;
        }
        if (axis == 1) {
            return size ? c.sizeY : c.originY;
        }
        return size ? c.sizeZ : c.originZ;
    }

    private static void setAxisOrigin(ParsedCube c, int axis, float value) {
        if (axis == 0) {
            c.originX = value;
        } else if (axis == 1) {
            c.originY = value;
        } else {
            c.originZ = value;
        }
    }

    private static void setAxisSize(ParsedCube c, int axis, float value) {
        if (axis == 0) {
            c.sizeX = value;
        } else if (axis == 1) {
            c.sizeY = value;
        } else {
            c.sizeZ = value;
        }
    }

    private static void shiftAxis(ParsedCube c, int axis, float delta) {
        setAxisOrigin(c, axis, axisValue(c, axis, false) + delta);
    }

    /**
     * 沿轴裁剪每个面的 UV。与 split_bone.py 一致：y 切 V，x/z 切 U。
     * 对垂直于切轴的面，UV 在该轴上没有跨度，裁剪后保持不变。
     */
    private static void sliceFaces(ParsedCube cube, int axis, float f0, float f1) {
        int uvIndex = axis == 1 ? 1 : 0;
        for (int f = 0; f < 6; f++) {
            float[][] quad = cube.faces[f];
            if (quad == null) {
                continue;
            }
            float lo = Float.MAX_VALUE;
            float hi = -Float.MAX_VALUE;
            for (int k = 0; k < 4; k++) {
                lo = Math.min(lo, quad[k][uvIndex]);
                hi = Math.max(hi, quad[k][uvIndex]);
            }
            float n0 = lo + (hi - lo) * f0;
            float n1 = lo + (hi - lo) * f1;
            float[][] sliced = new float[4][2];
            for (int k = 0; k < 4; k++) {
                float u = quad[k][0];
                float v = quad[k][1];
                float a = uvIndex == 1 ? v : u;
                float t = hi - lo <= 1.0e-9f ? 0f : (a - lo) / (hi - lo);
                float n = n0 + (n1 - n0) * t;
                sliced[k][0] = uvIndex == 1 ? u : n;
                sliced[k][1] = uvIndex == 1 ? n : v;
            }
            cube.faces[f] = sliced;
        }
    }

    private static String childBase(ParsedBone bone, int ordinal) {
        String sep = bone.name.endsWith("_") ? "" : "__";
        return bone.name + sep + ordinal;
    }

    private static String uniqueName(ParsedModel model, String base) {
        String name = base;
        int i = 2;
        while (model.findBone(name) != null) {
            name = base + "_" + i++;
        }
        return name;
    }
}
