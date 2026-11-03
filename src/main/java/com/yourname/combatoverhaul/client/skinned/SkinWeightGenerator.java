package com.yourname.combatoverhaul.client.skinned;

import com.yourname.combatoverhaul.client.model.ModelRigJoints;
import com.yourname.combatoverhaul.client.model.ParsedBone;
import com.yourname.combatoverhaul.client.model.ParsedCube;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import com.yourname.combatoverhaul.rig.RigJoint;
import com.yourname.combatoverhaul.rig.RigJointSet;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用自动蒙皮：把连续网格顶点绑定到已有骨骼派生的骨段。
 *
 * <p>算法（与模型类型无关，不读取人形骨名）：
 * <ol>
 *   <li>cube 骨骼用世界 AABB 作为影响体（方块模型必须按体积，不能用身体中心线）；</li>
 *   <li>无 cube 的网格骨骼用骨段：父关节 → 本关节；</li>
 *   <li>每个顶点找最近影响体；</li>
 *   <li>只允许最近影响体与其<strong>父/子相邻</strong>影响体混合，避免左右腿/跨肢体串权重；</li>
 *   <li>在关节带内按距离差平滑混合，最多 2 个影响；</li>
 *   <li>权重归一化；不参与 deform/attach 的骨骼不产生权重。</li>
 * </ol>
 *
 * <p>原生 player/Alex 的扫描树是扁平根：{@link ModelRigJoints} 会用标准人形 alias
 * 把 head/手臂/腿在<strong>派生 skin 拓扑</strong>里挂到 body；不修改模型骨骼树。
 *
 * <p>这是编辑器生成器：只改内存中的 {@link SkinnedMesh} 权重，不写文件；
 * 调用方需要再执行模型保存，保存路径会校验并写回 JSON。
 */
public final class SkinWeightGenerator {

    /** 关节带最小宽度（模型单位/像素） */
    private static final float MIN_BAND = 0.75f;
    /** 关节带最大宽度 */
    private static final float MAX_BAND = 2.5f;
    /** 骨段越长，关节带略宽；仍受 MAX_BAND 限制 */
    private static final float BAND_PER_LENGTH = 0.10f;
    /** 第二根影响骨只能出现在共享关节附近，防止两个 AABB 大面积重叠时全身粘住。 */
    private static final float SECOND_JOINT_RADIUS = 4.0f;
    /** 分段接缝只允许很窄的一圈柔化，避免整段被拉成模糊过渡带。 */
    private static final float SPLIT_BAND = 0.35f;

    private SkinWeightGenerator() {
    }

    public static final class Result {
        public boolean ok;
        public String error;
        public int vertices;
        public int segments;
        public int oneInfluence;
        public int twoInfluence;
        public int noSegment;
        public float maxWeightSumError;
        /** 本次是否从 cube 几何生成了网格 */
        public boolean meshBuilt;
        public int meshVertices;
        public int meshQuads;
        public final List<String> warnings = new ArrayList<>();
    }

    public static Result generate(ParsedModel model) {
        Result r = new Result();
        if (model == null) {
            r.error = "模型为空";
            return r;
        }
        boolean cubeModel = model.bones.stream().anyMatch(b -> !b.cubes.isEmpty());
        boolean needMesh = model.skinnedMesh == null
                || (cubeModel && model.skinnedMesh.sourceBones.size() != model.skinnedMesh.vertices.size());
        if (needMesh) {
            // 原生 player/Alex/GeckoLib cube 模型没有 mesh，或旧 mesh 没有 source_bones：
            // 先通用细分生成连续网格，附带“顶点来自哪个 cube 骨骼”。
            SkinnedMesh oldMesh = model.skinnedMesh;
            model.skinnedMesh = null;
            CubeMeshBuilder.Result built = CubeMeshBuilder.build(model);
            if (!built.ok) {
                model.skinnedMesh = oldMesh;
                r.error = built.error;
                return r;
            }
            r.meshBuilt = true;
            r.meshVertices = built.vertices;
            r.meshQuads = built.quads;
        }
        Result weights = generate(model.skinnedMesh, ModelRigJoints.from(model), model);
        if (!weights.ok && r.meshBuilt) {
            // 生成网格成功但权重失败时不要留下半成品，否则预览会读到空权重。
            model.skinnedMesh = null;
        }
        weights.meshBuilt = r.meshBuilt;
        weights.meshVertices = r.meshVertices;
        weights.meshQuads = r.meshQuads;
        return weights;
    }

    public static Result generate(SkinnedMesh mesh, RigJointSet joints) {
        return generate(mesh, joints, null);
    }

    /**
     * 把任意已建好的网格（例如盔甲模型）绑定到另一套骨架。
     * source_bones / bind_bones 会按目标骨架的 alias 解析，因此盔甲模型可以有自己的骨名。
     */
    public static Result bindToSkeleton(SkinnedMesh mesh, ParsedModel skeleton) {
        if (skeleton == null) {
            Result r = new Result();
            r.error = "目标骨架为空";
            return r;
        }
        return generate(mesh, ModelRigJoints.from(skeleton), skeleton);
    }

    private static Result generate(SkinnedMesh mesh, RigJointSet joints, ParsedModel model) {
        Result r = new Result();
        if (mesh == null || mesh.vertices.isEmpty()) {
            r.error = "网格没有顶点";
            return r;
        }
        if (joints == null) {
            r.error = "没有关节数据";
            return r;
        }

        List<Segment> segments = buildShapes(model, joints);
        r.segments = segments.size();
        r.vertices = mesh.vertices.size();
        if (segments.isEmpty()) {
            r.error = "没有可参与蒙皮的骨段（检查 deform/attach 与骨骼层级）";
            return r;
        }
        Map<String, Integer> shapeByName = new HashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            shapeByName.put(segments.get(i).name, i);
        }

        // 解析/构造的网格可能权重行不足；先补齐，之后逐顶点覆盖。
        while (mesh.weightBones.size() < mesh.vertices.size()) {
            mesh.weightBones.add(new String[SkinnedMesh.MAX_INFLUENCES]);
            mesh.weightValues.add(new float[SkinnedMesh.MAX_INFLUENCES]);
        }
        boolean hasSource = mesh.sourceBones.size() == mesh.vertices.size();

        for (int vi = 0; vi < mesh.vertices.size(); vi++) {
            SkinnedMesh.Vertex v = mesh.vertices.get(vi);
            int best = -1;
            float bestD2 = Float.MAX_VALUE;
            // 手动绑定优先：用户在“绑定”工具里刷到的顶点必须跟随选中骨骼。
            String manual = mesh.bindBones.size() == mesh.vertices.size()
                    ? mesh.bindBones.get(vi) : "";
            if (manual != null && !manual.isBlank()) {
                int idx = resolveSourceBest(manual, model, joints, shapeByName, segments, v);
                if (idx >= 0) {
                    float d2 = segments.get(idx).distanceSq(v.x, v.y, v.z);
                    if (Float.isFinite(d2)) {
                        best = idx;
                        bestD2 = d2;
                    }
                }
            }
            // cube 生成网格带 source_bones：顶点必须优先跟随自己的源 cube 骨骼，
            // 否则手臂/身体 AABB 重叠时会被 body 抢走，形成拉伸粘连。
            if (best < 0 && hasSource) {
                int source = resolveSourceBest(mesh.sourceBones.get(vi), model, joints,
                        shapeByName, segments, v);
                if (source >= 0) {
                    float d2 = segments.get(source).distanceSq(v.x, v.y, v.z);
                    if (Float.isFinite(d2)) {
                        best = source;
                        bestD2 = d2;
                    }
                }
            }
            if (best < 0) {
                for (int si = 0; si < segments.size(); si++) {
                    float d2 = segments.get(si).distanceSq(v.x, v.y, v.z);
                    if (Float.isFinite(d2) && d2 < bestD2) {
                        bestD2 = d2;
                        best = si;
                    }
                }
            }
            if (best < 0) {
                r.noSegment++;
                r.warnings.add("顶点 " + vi + " 没有有效骨段，回退 root=1");
                mesh.weightBones.set(vi, new String[]{"root", null, null, null});
                mesh.weightValues.set(vi, new float[]{1f, 0f, 0f, 0f});
                continue;
            }

            int second = -1;
            float secondD2 = Float.MAX_VALUE;
            Segment bestSeg = segments.get(best);
            for (int si = 0; si < segments.size(); si++) {
                if (si == best) {
                    continue;
                }
                Segment other = segments.get(si);
                if (!adjacent(bestSeg, other)) {
                    continue;
                }
                String jointName = sharedJointName(bestSeg, other);
                ParsedBone jointBone = model == null || jointName == null
                        ? null : model.findBone(jointName);
                boolean splitJoint = jointBone != null && (jointBone.rigidJoint
                        || isSplitGeneratedName(jointBone.name, jointBone.parent));
                // 只在共享关节附近允许第二影响；普通解剖关节保留宽一点，
                // 分段接缝只柔化最靠切面的一圈。
                // 分段接缝是垂直于肢体的切面，不能用“到关节点的球半径”判断：
                // 接缝一圈顶点离关节中心有 2~3px。这里直接交给下方很窄的 band
                // 按两段 AABB 距离差筛选，只保留切面附近一圈。
                float jointD2 = sharedJointDistanceSq(bestSeg, other, v, joints);
                if (!splitJoint
                        && (!Float.isFinite(jointD2)
                        || jointD2 > SECOND_JOINT_RADIUS * SECOND_JOINT_RADIUS)) {
                    continue;
                }
                float d2 = other.distanceSq(v.x, v.y, v.z);
                if (Float.isFinite(d2) && d2 < secondD2) {
                    secondD2 = d2;
                    second = si;
                }
            }
            String[] names = new String[SkinnedMesh.MAX_INFLUENCES];
            float[] vals = new float[SkinnedMesh.MAX_INFLUENCES];
            if (second >= 0) {
                Segment secondSeg = segments.get(second);
                float d1 = (float) Math.sqrt(bestD2);
                float d2 = (float) Math.sqrt(secondD2);
                float band = splitJointBand(bestSeg, secondSeg, model);
                float diff = Math.max(0f, d2 - d1);
                if (diff < band) {
                    float t = 1f - diff / band;
                    t = t * t * (3f - 2f * t); // smoothstep
                    float w2 = 0.5f * t;
                    float w1 = 1f - w2;
                    names[0] = bestSeg.name;
                    vals[0] = w1;
                    names[1] = secondSeg.name;
                    vals[1] = w2;
                    r.twoInfluence++;
                } else {
                    names[0] = bestSeg.name;
                    vals[0] = 1f;
                    r.oneInfluence++;
                }
            } else {
                names[0] = bestSeg.name;
                vals[0] = 1f;
                r.oneInfluence++;
            }

            float sum = 0f;
            for (int k = 0; k < vals.length; k++) {
                sum += vals[k];
            }
            if (!Float.isFinite(sum) || sum <= 1.0e-6f) {
                names = new String[]{"root", null, null, null};
                vals = new float[]{1f, 0f, 0f, 0f};
                r.warnings.add("顶点 " + vi + " 权重和非法，回退 root=1");
            } else {
                for (int k = 0; k < vals.length; k++) {
                    vals[k] /= sum;
                }
                r.maxWeightSumError = Math.max(r.maxWeightSumError, Math.abs(sum - 1f));
            }
            mesh.weightBones.set(vi, names);
            mesh.weightValues.set(vi, vals);
        }

        applySplitSeamWeights(mesh, model, joints);
        r.oneInfluence = 0;
        r.twoInfluence = 0;
        r.noSegment = 0;
        for (int vi = 0; vi < mesh.weightValues.size(); vi++) {
            int count = 0;
            for (float w : mesh.weightValues.get(vi)) {
                if (w > 1.0e-6f) {
                    count++;
                }
            }
            if (count <= 1) {
                r.oneInfluence++;
            } else {
                r.twoInfluence++;
            }
        }
        r.ok = true;
        return r;
    }

    /**
     * 分段接缝单独柔化：只在切面平面附近把上下两段做 50/50，不改变整段权重。
     * 这样旋转时只有接缝一圈过渡，不会把整条手臂拉成模糊带。
     */
    private static void applySplitSeamWeights(SkinnedMesh mesh, ParsedModel model,
                                              RigJointSet joints) {
        if (model == null || joints == null) {
            return;
        }
        for (ParsedBone child : model.bones) {
            if (!(child.rigidJoint || isSplitGeneratedName(child.name, child.parent))
                    || child.parent == null) {
                continue;
            }
            RigJoint joint = joints.joint(child.name);
            RigJoint parentJoint = joints.joint(child.parent.name);
            if (joint == null || parentJoint == null) {
                continue;
            }
            int axis = dominantAxis(parentJoint, joint);
            float jointCoord = axis == 0 ? joint.x() : axis == 1 ? joint.y() : joint.z();
            for (int vi = 0; vi < mesh.vertices.size(); vi++) {
                if (mesh.bindBones.size() == mesh.vertices.size()
                        && !mesh.bindBones.get(vi).isBlank()) {
                    continue; // 手动绑定优先
                }
                SkinnedMesh.Vertex v = mesh.vertices.get(vi);
                float coord = axis == 0 ? v.x : axis == 1 ? v.y : v.z;
                if (Math.abs(coord - jointCoord) > SPLIT_BAND) {
                    continue;
                }
                String[] names = mesh.weightBones.get(vi);
                float[] vals = mesh.weightValues.get(vi);
                if (!hasWeight(names, child.name) && !hasWeight(names, child.parent.name)) {
                    continue;
                }
                names[0] = child.name;
                vals[0] = 0.5f;
                names[1] = child.parent.name;
                vals[1] = 0.5f;
                for (int k = 2; k < names.length; k++) {
                    names[k] = null;
                    vals[k] = 0f;
                }
            }
        }
    }

    private static boolean hasWeight(String[] names, String bone) {
        for (String n : names) {
            if (bone.equals(n)) {
                return true;
            }
        }
        return false;
    }

    private static int dominantAxis(RigJoint a, RigJoint b) {
        float dx = Math.abs(b.x() - a.x());
        float dy = Math.abs(b.y() - a.y());
        float dz = Math.abs(b.z() - a.z());
        if (dx >= dy && dx >= dz) {
            return 0;
        }
        return dy >= dz ? 1 : 2;
    }

    /**
     * 从 source cube 骨骼沿父链找到第一个有影响体的骨骼。
     * 例如外层 sleeve 没有影响体，就解析到父级 arm。
     */
    private static int resolveSourceShape(String source, RigJointSet joints,
                                          Map<String, Integer> shapeByName) {
        if (source == null || source.isBlank()) {
            return -1;
        }
        String cur = source;
        int guard = 0;
        while (cur != null && guard++ < 64) {
            Integer idx = shapeByName.get(cur);
            if (idx != null) {
                return idx;
            }
            RigJoint j = joints.joint(cur);
            cur = j == null ? null : j.parent();
        }
        return -1;
    }

    /**
     * 解析 source 骨骼对应的最佳影响体。
     *
     * <p>普通骨骼直接用自己的影响体；外层皮肤（sleeve/pants/jacket/hat）没有影响体，
     * 不能永远绑父骨——否则主骨分段后下半段外层仍跟上半段。这里在“父主骨 +
     * 该主骨的分段子链”中按顶点位置取最近段，让双层皮肤跟着对应段一起弯。
     */
    private static int resolveSourceBest(String source, ParsedModel model,
                                         RigJointSet joints,
                                         Map<String, Integer> shapeByName,
                                         List<Segment> segments, SkinnedMesh.Vertex v) {
        if (source == null || source.isBlank()) {
            return -1;
        }
        String lookup = source;
        boolean followed = false;
        ParsedBone sourceBone = model == null ? null : model.findBone(source);
        if (sourceBone != null && sourceBone.follow != null && !sourceBone.follow.isBlank()) {
            lookup = sourceBone.follow;
            followed = true;
        }
        boolean sourceHadShape = shapeByName.containsKey(source);
        if (!followed || sourceHadShape) {
            Integer exact = shapeByName.get(lookup);
            if (exact != null) {
                return exact;
            }
        }
        int anchor = resolveSourceShape(lookup, joints, shapeByName);
        if (anchor < 0) {
            return -1;
        }
        Segment anchorSeg = segments.get(anchor);
        int best = anchor;
        float bestD2 = anchorSeg.distanceSq(v.x, v.y, v.z);
        for (int i = 0; i < segments.size(); i++) {
            if (i == anchor) {
                continue;
            }
            String name = segments.get(i).name;
            if (!isSplitDescendant(name, anchorSeg.name)) {
                continue;
            }
            float d2 = segments.get(i).distanceSq(v.x, v.y, v.z);
            if (Float.isFinite(d2) && d2 < bestD2) {
                bestD2 = d2;
                best = i;
            }
        }
        return best;
    }

    /** 判断 name 是否是 anchor 由 BoneSplitter 生成的分段子骨（如 arm__2 / f_102811_2）。 */
    private static boolean isSplitDescendant(String name, String anchor) {
        if (anchor == null || name == null || name.length() <= anchor.length()) {
            return false;
        }
        if (anchor.endsWith("_")) {
            return name.startsWith(anchor) && Character.isDigit(name.charAt(anchor.length()));
        }
        return name.startsWith(anchor + "__");
    }

    /** 两个相邻骨段共享的子关节名。 */
    private static String sharedJointName(Segment a, Segment b) {
        if (a.name.equals(b.parent)) {
            return a.name;
        }
        if (b.name.equals(a.parent)) {
            return b.name;
        }
        return null;
    }

    /** 两个相邻影响体共享的关节到顶点的距离平方。 */
    private static float sharedJointDistanceSq(Segment a, Segment b,
                                               SkinnedMesh.Vertex v, RigJointSet joints) {
        String jointName = sharedJointName(a, b);
        if (jointName == null) {
            return Float.POSITIVE_INFINITY;
        }
        RigJoint j = joints.joint(jointName);
        if (j == null) {
            return Float.POSITIVE_INFINITY;
        }
        float dx = v.x - j.x();
        float dy = v.y - j.y();
        float dz = v.z - j.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 影响体：cube 骨骼用世界 AABB（方块模型靠体积判定，避免“身体中心线”把肩/髋判错）；
     * 无 cube 的网格骨骼用父关节 → 本关节骨段。
     */
    private static List<Segment> buildShapes(ParsedModel model, RigJointSet joints) {
        if (model == null) {
            return buildSegments(joints);
        }
        List<Segment> out = new ArrayList<>();
        Map<ParsedBone, Matrix4f> matrices = CubeMeshBuilder.boneMatrices(model);
        for (ParsedBone b : model.bones) {
            RigJoint j = joints.joint(b.name);
            if (j == null || !j.deform() || j.attach() || !b.visible) {
                continue;
            }
            // 原版双层皮肤的 hat/jacket/sleeve/pants 与主 cube 重叠；如果参与影响体，
            // 每个顶点都会变成“主骨/外层骨”两个冗余影响，真正的 body/arm 肩部混合
            // 永远拿不到第二槽。外层几何仍由网格覆盖，绑定到其父主骨即可。
            if (isHumanoidOuterLayer(model.semanticName(b.name))) {
                continue;
            }
            if (b.cubes.isEmpty()) {
                RigJoint p = j.parent() == null ? null : joints.joint(j.parent());
                if (p == null || p.attach()) {
                    continue;
                }
                out.add(Segment.segment(j.name(), j.parent(),
                        p.x(), p.y(), p.z(), j.x(), j.y(), j.z()));
                continue;
            }
            Matrix4f boneMat = matrices.get(b);
            if (boneMat == null) {
                continue;
            }
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            boolean any = false;
            for (ParsedCube cube : b.cubes) {
                Matrix4f cubeMat = CubeMeshBuilder.cubeMatrix(boneMat, cube);
                for (int f = 0; f < 6; f++) {
                    if (!cube.hasFace(f)) {
                        continue;
                    }
                    for (int k = 0; k < 4; k++) {
                        float[] p = CubeMeshBuilder.facePoint(cube, f, k);
                        Vector3f w = cubeMat.transformPosition(new Vector3f(p[0], p[1], p[2]));
                        minX = Math.min(minX, w.x);
                        minY = Math.min(minY, w.y);
                        minZ = Math.min(minZ, w.z);
                        maxX = Math.max(maxX, w.x);
                        maxY = Math.max(maxY, w.y);
                        maxZ = Math.max(maxZ, w.z);
                        any = true;
                    }
                }
            }
            if (any) {
                out.add(Segment.box(j.name(), j.parent(), minX, minY, minZ, maxX, maxY, maxZ));
            }
        }
        return out;
    }

    /** 无模型上下文时的旧路径：只使用骨段线。 */
    private static List<Segment> buildSegments(RigJointSet joints) {
        List<Segment> out = new ArrayList<>();
        for (RigJoint j : joints.joints()) {
            if (!j.deform() || j.attach()) {
                continue;
            }
            String parentName = j.parent();
            if (parentName == null) {
                continue;
            }
            RigJoint p = joints.joint(parentName);
            if (p == null || p.attach()) {
                continue;
            }
            out.add(Segment.segment(j.name(), parentName,
                    p.x(), p.y(), p.z(), j.x(), j.y(), j.z()));
        }
        return out;
    }

    /** 原版人形第二层皮肤的外层骨；几何仍存在，但权重归父主骨。 */
    private static boolean isHumanoidOuterLayer(String semantic) {
        if (semantic == null) {
            return false;
        }
        return switch (semantic.toLowerCase(java.util.Locale.ROOT)) {
            case "hat", "jacket", "left_sleeve", "right_sleeve", "left_pants", "right_pants" -> true;
            default -> false;
        };
    }

    /** 兼容旧模型：没有 rigid_joint 字段时，按 arm__2 / f_102811_2 命名识别分段骨。 */
    private static boolean isSplitGeneratedName(String name, ParsedBone parent) {
        if (name == null || parent == null || parent.name == null) {
            return false;
        }
        String p = parent.name;
        if (p.endsWith("_")) {
            return name.length() > p.length() && name.startsWith(p)
                    && Character.isDigit(name.charAt(p.length()));
        }
        return name.startsWith(p + "__");
    }

    /** 只允许父/子骨段混合；兄弟骨段不混合，避免左右腿在骨盆处互串。 */
    private static boolean adjacent(Segment a, Segment b) {
        return a.name.equals(b.parent) || b.name.equals(a.parent);
    }

    private static float splitJointBand(Segment a, Segment b, ParsedModel model) {
        String jointName = sharedJointName(a, b);
        ParsedBone jointBone = model == null || jointName == null ? null : model.findBone(jointName);
        if (jointBone != null && (jointBone.rigidJoint
                || isSplitGeneratedName(jointBone.name, jointBone.parent))) {
            return SPLIT_BAND;
        }
        return band(a, b);
    }

    private static float band(Segment a, Segment b) {
        float minLen = Math.min(a.length, b.length);
        return Math.min(MAX_BAND, MIN_BAND + BAND_PER_LENGTH * minLen);
    }

    private static final class Segment {
        final String name;
        final String parent;
        final boolean box;
        final float minX, minY, minZ, maxX, maxY, maxZ;
        final float ax, ay, az;
        final float length;
        final float abx, aby, abz;
        final float abLenSq;

        private Segment(String name, String parent, boolean box,
                        float minX, float minY, float minZ,
                        float maxX, float maxY, float maxZ,
                        float ax, float ay, float az,
                        float bx, float by, float bz) {
            this.name = name;
            this.parent = parent;
            this.box = box;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.ax = ax;
            this.ay = ay;
            this.az = az;
            this.abx = bx - ax;
            this.aby = by - ay;
            this.abz = bz - az;
            this.abLenSq = abx * abx + aby * aby + abz * abz;
            if (box) {
                float dimX = maxX - minX;
                float dimY = maxY - minY;
                float dimZ = maxZ - minZ;
                float minDim = Math.min(dimX, Math.min(dimY, dimZ));
                this.length = Math.max(0.1f, minDim > 1.0e-4f ? minDim : Math.max(dimX, Math.max(dimY, dimZ)));
            } else {
                this.length = (float) Math.sqrt(abLenSq);
            }
        }

        static Segment segment(String name, String parent,
                               float ax, float ay, float az, float bx, float by, float bz) {
            return new Segment(name, parent, false,
                    0f, 0f, 0f, 0f, 0f, 0f,
                    ax, ay, az, bx, by, bz);
        }

        static Segment box(String name, String parent,
                           float minX, float minY, float minZ,
                           float maxX, float maxY, float maxZ) {
            return new Segment(name, parent, true,
                    minX, minY, minZ, maxX, maxY, maxZ,
                    0f, 0f, 0f, 0f, 0f, 0f);
        }

        float distanceSq(float x, float y, float z) {
            if (box) {
                float dx = x < minX ? minX - x : x > maxX ? x - maxX : 0f;
                float dy = y < minY ? minY - y : y > maxY ? y - maxY : 0f;
                float dz = z < minZ ? minZ - z : z > maxZ ? z - maxZ : 0f;
                return dx * dx + dy * dy + dz * dz;
            }
            float apx = x - ax;
            float apy = y - ay;
            float apz = z - az;
            float t = 0f;
            if (abLenSq > 1.0e-12f) {
                t = (apx * abx + apy * aby + apz * abz) / abLenSq;
                t = Math.max(0f, Math.min(1f, t));
            }
            float px = ax + abx * t;
            float py = ay + aby * t;
            float pz = az + abz * t;
            float dx = x - px;
            float dy = y - py;
            float dz = z - pz;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
