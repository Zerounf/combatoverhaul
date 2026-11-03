package com.yourname.combatoverhaul.combat;

import com.yourname.combatoverhaul.client.model.ParsedBone;
import com.yourname.combatoverhaul.client.model.ParsedCube;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import com.yourname.combatoverhaul.store.ActionStore;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 判定框数学：按动作当前 tick 的骨骼动画姿态，计算世界坐标 AABB。
 *
 * <p>坐标系推导（与 LivingEntityRenderer.render 一致）：
 * <pre>world = entityPos + RotY(180 − yBodyRot) · (−mx/16, −my/16, mz/16) + (0, 1.501, 0)</pre>
 * 其中 (mx,my,mz) 为模型空间坐标（模型 +Y 朝下指向脚底）。骨骼链变换与
 * {@code ModelPart.translateAndRotate} 一致：translate(pivot/16) → rotationZYX(z,y,x) → scale。
 *
 * <p>边界：不处理幼体/缩放渲染与躺卧旋转（按 1:1 标准体型计算）。
 */
public final class HitboxMath {

    private HitboxMath() {
    }

    /** 把动作 tick 时刻姿态写入模型动画叠加字段（复用编辑器插值逻辑） */
    public static void sampleAnim(ParsedModel model, List<ActionStore.Action> actions, int actionIndex, float tick) {
        // 武器动作在实体模型缺失（GeckoLib 未导出 / 渲染器解析失败 / 新安装）时 entityModel 为 null；
        // 这里必须容忍，否则 ActionRunner 每 tick 都会 NPE 并中止动作。
        if (model == null) {
            return;
        }
        ActionStore.apply(actions, actionIndex, tick, model);
    }

    /**
     * 计算某判定框当前的世界 AABB。
     *
     * @param entityModel   实体模型（实体动作必填；武器动作用于挂接 anchorBone，可为 null）
     * @param weaponModel   武器模型（武器动作必填，否则传 null）
     * @param weaponAction  当前动作是否定义在武器模型上
     * @param hb            判定框定义
     * @param pitchAddRad   追踪俯仰偏移（弧度，加到标记 track_pitch 的骨骼绕 X 旋转上；不追踪传 0）
     */
    public static AABB computeBox(LivingEntity entity, ParsedModel entityModel, ParsedModel weaponModel,
                                  boolean weaponAction, ActionStore.HitboxDef hb, float pitchAddRad) {
        List<Vec3> corners = computeCorners(entity, entityModel, weaponModel, weaponAction, hb, pitchAddRad);
        if (corners.isEmpty()) {
            return null;
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Vec3 p : corners) {
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
            maxZ = Math.max(maxZ, p.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** 判定框 8 角的世界坐标（含相邻骨骼间扫掠所需的插值留待 Runner 做并集） */
    public static List<Vec3> computeCorners(LivingEntity entity, ParsedModel entityModel, ParsedModel weaponModel,
                                            boolean weaponAction, ActionStore.HitboxDef hb, float pitchAddRad) {
        List<Vec3> out = new ArrayList<>(8);
        Matrix4f m = new Matrix4f();
        boolean weaponKind = ActionStore.KIND_WEAPON.equals(hb.kind);

        // 自动几何：判定箱=武器模型立方体（含立方体自转）经骨骼链变换的世界包围盒，
        // 忽略手动 size/offset；模型缺失/无立方体时回退手动尺寸
        if (weaponAction && hb.autoGeom && weaponModel != null) {
            List<Vec3> auto = autoGeomCorners(entity, entityModel, weaponModel, hb, pitchAddRad);
            if (auto != null) {
                return auto;
            }
        }

        if (weaponAction) {
            // 武器动作：先求实体 anchorBone 链变换（武器根挂接点），再叠加武器骨骼链
            Matrix4f anchor = anchorTransform(entity, entityModel, hb.anchorBone, pitchAddRad);
            if (anchor == null) {
                anchor = fallbackAnchor(entity);
            }
            m.set(anchor);
            if (weaponModel != null) {
                if (hb.bone.isEmpty()) {
                    if (weaponKind) {
                        applyWeaponRootFrame(m, weaponModel);
                    }
                } else {
                    Matrix4f chain = boneChain(weaponModel, hb.bone, pitchAddRad);
                    if (chain != null) {
                        m.mul(chain);
                    }
                }
            }
        } else if (weaponKind) {
            // 实体动作的武器判定箱：挂到手持点（anchorBone 指定的骨骼；为空则取第一条手持配置的骨骼）。
            // 该骨骼有手持配置时叠加挂点偏移/原版手持修正/附加旋转（判定框贴合视觉）；
            // 无手持配置时退化为纯骨骼帧。
            String anchorName = hb.anchorBone;
            if ((anchorName == null || anchorName.isEmpty()) && entityModel != null
                    && !entityModel.heldItems.isEmpty()) {
                anchorName = entityModel.heldItems.get(0).bone;
            }
            Matrix4f chain = entityModel != null && anchorName != null && !anchorName.isEmpty()
                    ? boneChain(entityModel, anchorName, pitchAddRad) : null;
            m.set(chain != null ? chain : fallbackAnchor(entity));
            ParsedModel.HeldEntry held = entityModel != null ? entityModel.findHeld(anchorName) : null;
            if (held != null) {
                float[] off = entityModel.heldAttachOffset(held);
                m.translate(off[0] / 16f, off[1] / 16f, off[2] / 16f);
                // 原版手持修正（与渲染端 HeldItemRenderer 一致：XP(-90°)→YP(180°)）
                m.rotateX((float) Math.toRadians(-90.0));
                m.rotateY((float) Math.toRadians(180.0));
                if (held.rotDeg != null) {
                    m.rotateZYX((float) Math.toRadians(held.rotDeg[2]),
                            (float) Math.toRadians(held.rotDeg[1]),
                            (float) Math.toRadians(held.rotDeg[0]));
                }
            }
        } else {
            if (entityModel == null || hb.bone.isEmpty()) {
                // 无模型/无骨骼：挂在实体中心
                m.set(fallbackAnchor(entity));
            } else {
                Matrix4f chain = boneChain(entityModel, hb.bone, pitchAddRad);
                m.set(chain != null ? chain : fallbackAnchor(entity));
            }
        }

        // 尺寸/偏移全部手动配置（武器模型包围盒自动尺寸已随武器模型生成器一并移除）
        float[] size = hb.size;
        float[] offset = hb.offset;

        // 判定框局部 8 角（骨骼局部空间，模型单位 → /16 方块）
        float hx = Math.max(0, size[0]) / 32f;
        float hy = Math.max(0, size[1]) / 32f;
        float hz = Math.max(0, size[2]) / 32f;
        float cx = offset[0] / 16f;
        float cy = offset[1] / 16f;
        float cz = offset[2] / 16f;

        Matrix4f modelToWorld = entityTransform(entity);
        Vector4f v = new Vector4f();
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    v.set(cx + sx * hx, cy + sy * hy, cz + sz * hz, 1);
                    m.transform(v);
                    modelToWorld.transform(v);
                    out.add(new Vec3(v.x(), v.y(), v.z()));
                }
            }
        }
        return out;
    }

    /**
     * 实体变换矩阵：entityPos + RotY(180 − yBodyRot) · (−x, −y, z) + (0, 1.501, 0)。
     * 输入模型空间（/16 后的方块单位）坐标，输出世界坐标。
     */
    public static Matrix4f entityTransform(LivingEntity entity) {
        Matrix4f m = new Matrix4f();
        m.translate((float) entity.getX(), (float) entity.getY() + 1.501f, (float) entity.getZ());
        m.rotateY((float) Math.toRadians(180.0 - entity.yBodyRot));
        m.scale(-1, -1, 1);
        return m;
    }

    /** 从根骨骼到指定骨骼的链变换矩阵（模型单位已 /16，含动画叠加）；不追踪 */
    public static Matrix4f boneChain(ParsedModel model, String boneName) {
        return boneChain(model, boneName, 0f);
    }

    /**
     * 从根骨骼到指定骨骼的链变换矩阵（模型单位已 /16，含动画叠加）。
     * pitchAddRad：追踪偏移（弧度），叠加到链上标记 track_pitch 的骨骼绕 X 旋转——
     * 正值低头/向下、负值抬头/向上（与原版 ModelPart.xRot 同向）。
     */
    public static Matrix4f boneChain(ParsedModel model, String boneName, float pitchAddRad) {
        ParsedBone target = model.findBone(boneName);
        if (target == null) {
            return null;
        }
        List<ParsedBone> chain = new ArrayList<>();
        for (ParsedBone b = target; b != null; b = b.parent) {
            chain.add(b);
        }
        java.util.Collections.reverse(chain);
        Matrix4f m = new Matrix4f();
        for (ParsedBone b : chain) {
            m.translate((b.pivotX + b.animPosX) / 16f, (b.pivotY + b.animPosY) / 16f, (b.pivotZ + b.animPosZ) / 16f);
            m.rotateZYX(b.fz(), b.fy(), b.fx() + (b.trackPitch ? pitchAddRad : 0f));
            m.scale(b.scaleX * b.animScaleX, b.scaleY * b.animScaleY, b.scaleZ * b.animScaleZ);
        }
        return m;
    }

    /** 武器模型根骨骼帧（含根枢轴/旋转/缩放与动画叠加）；多根模型不施加（恒等） */
    private static void applyWeaponRootFrame(Matrix4f m, ParsedModel weaponModel) {
        if (weaponModel.roots.size() == 1) {
            Matrix4f c = boneChain(weaponModel, weaponModel.roots.get(0).name);
            if (c != null) {
                m.mul(c);
            }
        }
    }

    // ------------------------------------------------------------------ 自动几何

    /**
     * 自动几何判定箱：把武器模型的全部立方体（hb.bone 为空=整把武器；非空=该骨骼及其子树）
     * 的 8 角（含立方体自身旋转 rot_deg/rot_pivot，与渲染端语义一致）经骨骼链变换到世界坐标，
     * 返回所有角点（调用方取并集 AABB）。
     *
     * @return 角点列表；模型里没有任何立方体时返回 null（调用方回退手动尺寸）
     */
    private static List<Vec3> autoGeomCorners(LivingEntity entity, ParsedModel entityModel,
                                              ParsedModel weaponModel, ActionStore.HitboxDef hb,
                                              float pitchAddRad) {
        Matrix4f anchor = anchorTransform(entity, entityModel, hb.anchorBone, pitchAddRad);
        if (anchor == null) {
            anchor = fallbackAnchor(entity);
        }
        Matrix4f modelToWorld = entityTransform(entity);
        List<Vec3> out = new ArrayList<>();
        boolean[] anyCube = {false};
        String scope = hb.bone == null ? "" : hb.bone;
        for (ParsedBone root : weaponModel.roots) {
            walkAutoGeom(anchor, modelToWorld, root, scope, pitchAddRad,
                    new Matrix4f(), false, out, anyCube);
        }
        if (!anyCube[0]) {
            return null;
        }
        return out;
    }

    /**
     * 递归骨骼树：以 parent 矩阵为起点重放 translate→rotationZYX(fz,fy,fx+追踪)→scale
     * （与 {@link #boneChain} 逐骨骼步骤一致），骨骼进入判定范围（scope 命中或已在子树内）
     * 时发射其立方体 8 角。
     */
    private static void walkAutoGeom(Matrix4f anchor, Matrix4f modelToWorld,
                                     ParsedBone bone, String scopeBone, float pitchAddRad,
                                     Matrix4f parent, boolean parentInScope,
                                     List<Vec3> out, boolean[] anyCube) {
        Matrix4f m = new Matrix4f(parent);
        m.translate((bone.pivotX + bone.animPosX) / 16f,
                (bone.pivotY + bone.animPosY) / 16f,
                (bone.pivotZ + bone.animPosZ) / 16f);
        m.rotateZYX(bone.fz(), bone.fy(), bone.fx() + (bone.trackPitch ? pitchAddRad : 0f));
        m.scale(bone.scaleX * bone.animScaleX, bone.scaleY * bone.animScaleY, bone.scaleZ * bone.animScaleZ);

        // scope 为空=整把武器全部骨骼；非空=命中骨骼及其整个子树
        boolean inScope = parentInScope || scopeBone.isEmpty() || bone.name.equals(scopeBone);
        if (inScope && !bone.cubes.isEmpty()) {
            // 完整变换 = 实体锚点 × 骨骼链（链矩阵保持不含锚点，供子骨骼继续复用）
            Matrix4f full = new Matrix4f(anchor).mul(m);
            for (ParsedCube c : bone.cubes) {
                emitAutoCubeCorners(full, modelToWorld, c, out, anyCube);
            }
        }
        for (ParsedBone child : bone.children) {
            walkAutoGeom(anchor, modelToWorld, child, scopeBone, pitchAddRad,
                    m, inScope, out, anyCube);
        }
    }

    /** 单个立方体的 8 角：立方体自转（translate(pivot)→Rz·Ry·Rx→translate(-pivot)）后变换到世界 */
    private static void emitAutoCubeCorners(Matrix4f chain, Matrix4f modelToWorld,
                                            ParsedCube c, List<Vec3> out, boolean[] anyCube) {
        Matrix4f cm = new Matrix4f(chain);
        if (c.hasRot()) {
            boolean off = c.pivotX != 0 || c.pivotY != 0 || c.pivotZ != 0;
            if (off) {
                cm.translate(c.pivotX / 16f, c.pivotY / 16f, c.pivotZ / 16f);
            }
            cm.rotateZYX(c.rotZ, c.rotY, c.rotX);
            if (off) {
                cm.translate(-c.pivotX / 16f, -c.pivotY / 16f, -c.pivotZ / 16f);
            }
        }
        Vector4f v = new Vector4f();
        for (int sx = 0; sx <= 1; sx++) {
            for (int sy = 0; sy <= 1; sy++) {
                for (int sz = 0; sz <= 1; sz++) {
                    float x = (sx == 0 ? c.originX : c.originX + c.sizeX) / 16f;
                    float y = (sy == 0 ? c.originY : c.originY + c.sizeY) / 16f;
                    float z = (sz == 0 ? c.originZ : c.originZ + c.sizeZ) / 16f;
                    v.set(x, y, z, 1);
                    cm.transform(v);
                    modelToWorld.transform(v);
                    out.add(new Vec3(v.x(), v.y(), v.z()));
                }
            }
        }
        anyCube[0] = true;
    }

    /** 武器挂接骨骼（实体模型）变换；找不到返回 null */
    private static Matrix4f anchorTransform(LivingEntity entity, ParsedModel entityModel,
                                            String anchorBone, float pitchAddRad) {
        if (entityModel == null || anchorBone == null || anchorBone.isEmpty()) {
            return null;
        }
        if (entityModel.findBone(anchorBone) == null) {
            return null;
        }
        return boneChain(entityModel, anchorBone, pitchAddRad);
    }

    /**
     * 兜底挂接：模型空间恒等矩阵（实体原点）。
     *
     * <p>调用方随后还会乘 {@link #entityTransform} 做一次模型空间→世界空间变换；
     * 这里如果返回世界矩阵会把实体位置/旋转/缩放应用两遍，判定框会飞到地图外。
     */
    private static Matrix4f fallbackAnchor(LivingEntity entity) {
        return new Matrix4f();
    }
}
