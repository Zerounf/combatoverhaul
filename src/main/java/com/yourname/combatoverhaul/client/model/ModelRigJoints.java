package com.yourname.combatoverhaul.client.model;

import com.yourname.combatoverhaul.rig.RigJoint;
import com.yourname.combatoverhaul.rig.RigJointSet;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 {@link ParsedModel} 的已有 bone 层级派生关节与骨段线。
 *
 * <p>规则（与 HANDOFF 一致）：
 * <ul>
 *   <li>关节 = 骨骼基础姿态下的 pivot 世界位置；</li>
 *   <li>骨线 = 父关节 → 子关节；</li>
 *   <li>root 默认非变形；显式 {@code deform} 优先；</li>
 *   <li>{@code attach=true} 是挂点，不参与蒙皮、不画骨段；</li>
 *   <li>零长度连接跳过；</li>
 *   <li>只有源骨骼缺少 pivot 时才回退到立方体包围盒最近面中心；</li>
 *   <li>不引入任何人形专用中心线；</li>
 *   <li>原版 player/Alex 的扁平根（head/arms/legs 与 body 同级）只在本派生拓扑里
 *       用标准 alias 挂到 body，不修改 ParsedBone 树。</li>
 * </ul>
 *
 * <p>这是只读派生：不会插入关节骨骼、不会改 pivot、不会改动作通道。
 */
public final class ModelRigJoints {

    private static final float ZERO_LINE_EPSILON_SQ = 1.0e-8f;

    private ModelRigJoints() {
    }

    public static RigJointSet from(ParsedModel model) {
        if (model == null || model.bones.isEmpty()) {
            return RigJointSet.empty();
        }
        List<JointData> data = new ArrayList<>(model.bones.size());
        for (ParsedBone root : model.roots) {
            build(root, new Matrix4f(), data);
        }

        // 原版 player/Alex 的扫描树常常是“body/head/arms/legs 全是根”（没有父子关系）。
        // 这不是自定义模型问题，而是 native 层级缺失。用标准人形 alias 做一层
        // 只读的 skin 拓扑补全：head/手臂/腿挂到 body；不修改 ParsedBone 树。
        ParsedBone bodyBone = model.findBone("body");
        String bodyName = bodyBone == null ? null : bodyBone.name;

        Map<String, RigJoint> byName = new LinkedHashMap<>();
        for (JointData d : data) {
            ParsedBone b = d.bone;
            boolean deform = b.hasDeform ? b.deform : (b.parent != null || !b.cubes.isEmpty());
            boolean attach = b.hasAttach && b.attach;
            String parentName = b.parent == null ? null : b.parent.name;
            if (parentName == null && bodyName != null && !bodyName.equals(b.name)
                    && !b.cubes.isEmpty() && isFlatHumanoidLimb(model.semanticName(b.name))) {
                parentName = bodyName;
            }
            byName.put(b.name, new RigJoint(b.name, parentName,
                    d.x, d.y, d.z,
                    d.localX, d.localY, d.localZ,
                    deform, attach, b.hasPivot));
        }

        List<RigJointSet.BoneLine> lines = new ArrayList<>();
        for (JointData d : data) {
            RigJoint self = byName.get(d.bone.name);
            if (self == null || self.parent() == null) {
                continue;
            }
            RigJoint parent = byName.get(self.parent());
            if (self == null || parent == null) {
                continue;
            }
            // 挂点/非变形骨骼不参与骨段线；root 因此不会产生 root→Pelvis 的长线。
            if (!self.deform() || !parent.deform() || self.attach() || parent.attach()) {
                continue;
            }
            float dx = self.x() - parent.x();
            float dy = self.y() - parent.y();
            float dz = self.z() - parent.z();
            if (dx * dx + dy * dy + dz * dz <= ZERO_LINE_EPSILON_SQ) {
                continue;
            }
            lines.add(new RigJointSet.BoneLine(parent.name(), self.name()));
        }
        return new RigJointSet(new ArrayList<>(byName.values()), lines);
    }

    private static void build(ParsedBone bone, Matrix4f parent, List<JointData> out) {
        float[] pivot = bone.hasPivot
                ? new float[]{bone.pivotX, bone.pivotY, bone.pivotZ}
                : fallbackPivot(bone);

        Matrix4f local = new Matrix4f(parent)
                .translate(pivot[0], pivot[1], pivot[2]);
        if (bone.restMatrix != null && bone.restMatrix.length >= 9) {
            local.mul(restMatrix4f(bone.restMatrix));
        } else {
            local.rotateZYX(bone.rotZ, bone.rotY, bone.rotX);
        }
        local.scale(bone.scaleX, bone.scaleY, bone.scaleZ);

        Vector3f p = local.transformPosition(new Vector3f());
        out.add(new JointData(bone, pivot[0], pivot[1], pivot[2], p.x, p.y, p.z));
        for (ParsedBone child : bone.children) {
            build(child, local, out);
        }
    }

    /** 标准人形中应挂在 body 下的扁平根（原版 PlayerModel 扫描结果常见）。 */
    private static boolean isFlatHumanoidLimb(String semantic) {
        if (semantic == null) {
            return false;
        }
        return switch (semantic.toLowerCase(java.util.Locale.ROOT)) {
            case "head", "right_arm", "left_arm", "right_leg", "left_leg" -> true;
            default -> false;
        };
    }

    /**
     * 缺少 pivot 时的回退：取立方体包围盒上离原点最近的面中心。
     * 轴跨过 0 时取 0；整段在正/负侧时取最近的面。无立方体返回原点。
     */
    private static float[] fallbackPivot(ParsedBone bone) {
        float[] b = bone.cubeBounds();
        if (b == null) {
            return new float[]{0f, 0f, 0f};
        }
        return new float[]{
                nearestFace(b[0], b[3]),
                nearestFace(b[1], b[4]),
                nearestFace(b[2], b[5])
        };
    }

    private static float nearestFace(float min, float max) {
        if (min > 0f) {
            return min;
        }
        if (max < 0f) {
            return max;
        }
        return 0f;
    }

    private static Matrix4f restMatrix4f(float[] m) {
        return new Matrix4f().set3x3(new Matrix3f(
                m[0], m[3], m[6],
                m[1], m[4], m[7],
                m[2], m[5], m[8]));
    }

    /** 中间结果：关节 + 局部回退 pivot + 基础姿态世界坐标 */
    private static final class JointData {
        final ParsedBone bone;
        final float localX, localY, localZ;
        final float x, y, z;

        JointData(ParsedBone bone, float localX, float localY, float localZ,
                  float x, float y, float z) {
            this.bone = bone;
            this.localX = localX;
            this.localY = localY;
            this.localZ = localZ;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
