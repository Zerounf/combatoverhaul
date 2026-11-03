package com.yourname.combatoverhaul.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yourname.combatoverhaul.client.model.ModelRigJoints;
import com.yourname.combatoverhaul.client.model.ParsedBone;
import com.yourname.combatoverhaul.client.model.ParsedCube;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import com.yourname.combatoverhaul.rig.RigJoint;
import com.yourname.combatoverhaul.rig.RigJointSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.util.FastColor;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * 编辑器视口内的 3D 模型预览渲染器。
 *
 * <ul>
 *   <li>模型面按 faces[] 逐面 UV 自定义镶嵌（不复用原版 ModelPart.Cube 盒式 UV 重建——
 *       盒式布局无法表达逐面 UV，武器「薄板+边缘拉伸侧面」必须直渲）；</li>
 *   <li>选中骨骼的线框描边与关节体中心线仍由本类手工绘制（{@link RenderType#lines}）；</li>
 *   <li>轨道相机：拖拽 yaw/pitch，滚轮缩放；围绕模型包围盒中心旋转；</li>
 *   <li>纹理解析优先级：JSON "texture" 字段 → 实体渲染器默认纹理（反射实体实例）→ 1×1 浅灰纯色兜底；</li>
 *   <li>RenderType.entityCutoutNoCull + 全亮度，GUI 共享 bufferSource，结束 endBatch。</li>
 * </ul>
 */
public final class ModelPreviewRenderer {

    private static final String FALLBACK_PATH = "combatoverhaul/preview_fallback";
    private static ResourceLocation fallbackTexture;

    /** 轨道相机状态（由编辑器界面操作） */
    public float yaw = 180.0f;
    public float pitch = 20.0f;
    public float roll = 0.0f;
    public float zoom = 40.0f;
    /** 屏幕空间平移（像素，中键拖拽） */
    public float panX = 0.0f;
    public float panY = 0.0f;
    /** 当前选中骨骼名：渲染时高亮该骨骼并绘制体中心线 + 枢轴关节十字；null 关闭 */
    public String selectedBone = null;
    private boolean mirrorOverlay = false;
    /** 是否绘制选中骨骼的线框描边（false 时仅保留体中心线与关节十字） */
    public boolean showBoneOutline = true;
    /** 模型空间整体偏移（模型单位/像素，锚点位移预览；0=无偏移） */
    public float modelOffX = 0, modelOffY = 0, modelOffZ = 0;
    /** 锚点目标位置标记（模型单位/像素，showAnchorMarker=true 时绘制橙色十字） */
    public float anchorMarkerX = 0, anchorMarkerY = 0, anchorMarkerZ = 0;
    public boolean showAnchorMarker = false;
    /** 显示整棵骨骼树：父原点 → 子原点；不参与蒙皮 */
    public boolean showSkeleton = false;
    /** 模型纹理透明度（1=不透明） */
    public float modelAlpha = 1.0f;
    /**
     * 是否用 skinnedMesh 渲染；false 时 cube 模型回到 BedrockRenderer。
     * 纯 mesh 模型没有 cube 可退，仍必须走蒙皮。
     */
    public boolean skinningEnabled = false;

    /**
     * 单条手持预览项：挂接骨骼 + 物品图标 + 挂点偏移（模型单位，骨骼局部空间，已解析含自动远端）
     * + 附加旋转（弧度，XYZ）。编辑器按模型 held_items 逐帧填充。
     */
    public static final class HeldVis {
        public String bone;
        public net.minecraft.world.item.ItemStack stack = net.minecraft.world.item.ItemStack.EMPTY;
        public float offX, offY, offZ;
        public float rotX, rotY, rotZ;
    }

    /** 手持预览列表（空 = 无手持渲染）；同一骨骼最多一条，不同骨骼可各持一件 */
    public final java.util.List<HeldVis> heldItems = new java.util.ArrayList<>();

    /** 最近一次纹理解析结果说明（用于视口左上角显示），未渲染过为 null */
    private String lastTextureInfo;

    public String resolvedTextureInfo() {
        return lastTextureInfo;
    }

    public ModelPreviewRenderer() {
    }

    /** 重置相机并按包围盒自适应缩放 */
    public void fitView(ParsedModel model, int vx0, int vy0, int vx1, int vy1) {
        this.yaw = 180.0f;
        this.pitch = 20.0f;
        this.roll = 0.0f;
        this.panX = 0.0f;
        this.panY = 0.0f;
        float[] b = model.bounds();
        float span = Math.max(Math.max(b[3] - b[0], b[4] - b[1]), b[5] - b[2]) / 16.0f;
        float minSpan = Math.min(vx1 - vx0, vy1 - vy0);
        this.zoom = span > 0.01f ? Math.max(2.0f, minSpan / span * 0.32f) : 40.0f;
    }

    /** 把模型空间点投影到视口页面坐标（与 render 的正交相机一致）。 */
    public float[] project(ParsedModel model, double mx, double my, double mz,
                           double camCx, double camCy) {
        if (model == null) {
            return new float[]{(float) camCx, (float) camCy};
        }
        float[] b = model.bounds();
        float x = (float) mx - (b[0] + b[3]) / 2.0f;
        float y = (float) my - (b[1] + b[4]) / 2.0f;
        float z = (float) mz - (b[2] + b[5]) / 2.0f;
        Quaternionf q = new Quaternionf()
                .rotationY((float) Math.toRadians(yaw))
                .rotateX((float) Math.toRadians(pitch))
                .rotateZ((float) Math.toRadians(roll));
        Vector3f v = new Vector3f(x, y, z);
        q.transform(v);
        return new float[]{
                (float) (camCx + panX + v.x * zoom / 16.0f),
                (float) (camCy + panY + v.y * zoom / 16.0f)
        };
    }

    /** 在视口矩形内渲染模型（含剪裁、相机、3D 绘制与批次刷新） */
    public void render(GuiGraphics g, ParsedModel model, int vx0, int vy0, int vx1, int vy1) {
        render(g, model, (vx0 + vx1) / 2.0, (vy0 + vy1) / 2.0, vx0, vy0, vx1, vy1);
    }

    /**
     * 完整版：相机中心（当前 pose 空间坐标，供页面缩放/平移下的编辑器使用）
     * 与剪裁矩形（物理屏幕坐标，enableScissor 不受 pose 变换影响）分离指定。
     */
    public void render(GuiGraphics g, ParsedModel model, double camCx, double camCy,
                       int sx0, int sy0, int sx1, int sy1) {
        Minecraft mc = Minecraft.getInstance();
        ResourceLocation texture = resolveTexture(mc, model);

        PoseStack pose = g.pose();
        pose.pushPose();
        g.enableScissor(sx0, sy0, sx1, sy1);

        // 屏幕中心 → 中键平移（屏幕像素） → 缩放 → 平移到包围盒中心 → 轨道旋转
        float[] b = model.bounds();
        // 相机深度：GUI 正交投影的深度预算是固定的，不能随手抬高。
        // 实测取值（Forge 1.20.1）：
        //   near = 1000；far = getGuiFarPlane() = 1000 + 10000 × (1 + 背景层数)；
        //   模型视图平移 z = 1000 − far。
        // 顶层界面（无背景层）时 far = 11000 → 顶点可见的 pose-z 约为 [−1000, 9000]。
        // 所以 camZ 必须待在 (radiusPx, 9000 − radiusPx) 之内：
        //   · 太小（< radiusPx）→ 模型后半截越近平面被裁（"转过某角度整块消失"）；
        //   · 太大（≥ 9000）  → 整个模型越近平面，视口里所有模型全部不可见
        //     （曾把下限硬抬到 10000，就是这个结果）。
        // 取包围球半径 ×zoom + 余量，并钳到 8000 留出两侧余量。
        float hx = (b[3] - b[0]) / 2.0f;
        float hy = (b[4] - b[1]) / 2.0f;
        float hz = (b[5] - b[2]) / 2.0f;
        float radiusPx = (float) Math.sqrt(hx * hx + hy * hy + hz * hz) / 16.0f * zoom * 1.35f;
        // GUI 正交投影的 near/far 固定；camZ 不应随模型半径缩到近平面里。
        // 固定放到深度预算中段，再用 disableDepthTest 避免任何角度被深度裁掉。
        float camZ = 6500.0f;
        com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
        pose.translate(camCx + panX, camCy + panY, camZ);
        pose.scale(zoom, zoom, zoom);
        // （相机中心与剪裁已由调用方给出，此处仅模型居中）
        if (b[3] > b[0] || b[4] > b[1] || b[5] > b[2]) {
            pose.translate(-(b[0] + b[3]) / 32.0f, -(b[1] + b[4]) / 32.0f, -(b[2] + b[5]) / 32.0f);
        }
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.ZP.rotationDegrees(roll));
        // 锚点位移：模型空间整体平移（在轨道旋转之后应用，随模型坐标系）
        if (modelOffX != 0 || modelOffY != 0 || modelOffZ != 0) {
            pose.translate(modelOffX / 16.0f, modelOffY / 16.0f, modelOffZ / 16.0f);
        }

        MultiBufferSource.BufferSource buffers = g.bufferSource();

        // ★ 关键：BufferSource 的共享 BufferBuilder 同一时刻只能承载一个 RenderType 的批次
        //（entityCutoutNoCull 与 lines 都不在固定缓冲映射内，均走共享 buffer）。
        // 必须"先申请面缓冲→发射全部面顶点→endBatch 冲洗"之后，才能再申请 lines 缓冲，
        // 否则 getBuffer(lines()) 会在面顶点发射前把共享缓冲切换为 DEBUG_LINES 模式，
        // 随后写入的 ENTITY_FORMAT 顶点格式错位 → 面全部不可见（这正是早期加骨骼高亮后模型消失的根因）。
        com.mojang.blaze3d.platform.Lighting.setupForEntityInInventory();
        boolean translucent = modelAlpha < 0.999f;
        RenderType modelType = translucent
                ? RenderType.entityTranslucent(texture)
                : RenderType.entityCutoutNoCull(texture);
        if (translucent) {
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            com.mojang.blaze3d.systems.RenderSystem.depthMask(false);
        }
        VertexConsumer vc = buffers.getBuffer(modelType);
        // 新渲染管线：ParsedModel → BedrockGeometry + 当前 anim 叠加 → 逐面 quad 直渲。
        // 不再经过 ModelPart.Cube 的盒式 UV 反推，逐面 uv_size/uv_rotation/inflate/mirror 可原样保留。
        // 预览不再做玩家专用 X 镜像：镜像必须同时作用于纹理、骨线、选中框和交互，
        // 局部只镜像一部分会让“线框/纹理左右反向”。统一使用模型自身坐标。
        model.syncFollow();
        com.yourname.combatoverhaul.client.bedrock.BedrockGeometry geometry =
                com.yourname.combatoverhaul.client.bedrock.BedrockGeometryAdapter.fromParsedModel(
                        model, false);
        com.yourname.combatoverhaul.client.bedrock.BedrockPose animPose =
                com.yourname.combatoverhaul.client.bedrock.BedrockPose.fromAnim(model);
        mirrorOverlay = false;
        boolean hasCubes = false;
        for (ParsedBone bone : model.bones) {
            if (!bone.cubes.isEmpty()) {
                hasCubes = true;
                break;
            }
        }
        boolean useSkinning = model.skinnedMesh != null && (skinningEnabled || !hasCubes);
        if (useSkinning) {
            com.yourname.combatoverhaul.client.skinned.SkinnedMeshRenderer.render(
                    vc, pose, model, model.skinnedMesh, animPose,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, modelAlpha);
        } else {
            com.yourname.combatoverhaul.client.bedrock.BedrockRenderer.render(
                    vc, pose, geometry, animPose, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    1f, 1f, 1f, modelAlpha);
        }
        buffers.endBatch();
        if (translucent) {
            com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        }

        // Blender 风格地面网格：1 格 = 1 个 Minecraft block（16 模型单位），放在脚底 y=24。
        {
            VertexConsumer gridVc = buffers.getBuffer(RenderType.lines());
            renderGroundGrid(pose, gridVc);
            buffers.endBatch();
        }

        // 手持物品：各骨骼的手持条目以物品图标渲染（第三人称右手），随骨骼动画
        if (!heldItems.isEmpty()) {
            pose.pushPose();
            renderHeldPass(pose, buffers, model);
            pose.popPose();
        }
        com.mojang.blaze3d.platform.Lighting.setupFor3DItems();

        // 面批次冲洗完毕后，再单独绘制选中骨骼的线框高亮 + 关节体中心线 + 锚点标记
        if (selectedBone != null || showAnchorMarker || showSkeleton) {
            VertexConsumer lineVc = buffers.getBuffer(RenderType.lines());
            if (showSkeleton) {
                // 关节/骨线由已有 bone 层级派生，只读；不插入骨骼、不改 pivot。
                RigJointSet rigJoints = ModelRigJoints.from(model);
                for (ParsedBone root : model.roots) {
                    renderSkeletonTree(pose, lineVc, root, rigJoints);
                }
            }
            if (selectedBone != null) {
                for (ParsedBone root : model.roots) {
                    renderBoneOverlay(pose, lineVc, root);
                }
            }
            if (showAnchorMarker) {
                // 锚点目标位置：模型空间橙色十字（不随骨骼层级，直接取当前 pose）
                PoseStack.Pose p = pose.last();
                float ax = anchorMarkerX;
                float ay = anchorMarkerY, az = anchorMarkerZ;
                float c = 5;
                line(lineVc, p.pose(), p.normal(), ax - c, ay, az, ax + c, ay, az, 1f, 0.6f, 0.1f);
                line(lineVc, p.pose(), p.normal(), ax, ay - c, az, ax, ay + c, az, 1f, 0.6f, 0.1f);
                line(lineVc, p.pose(), p.normal(), ax, ay, az - c, ax, ay, az + c, 1f, 0.6f, 0.1f);
            }
            buffers.endBatch();
        }

        pose.popPose();
        com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        g.disableScissor();
    }

    // ------------------------------------------------------------------ 模型面直渲（逐面 UV）

    /** 骨骼层级遍历（与 renderBoneOverlay 同一套变换：枢轴平移→ZYX 旋转→缩放），逐立方体发射纹理面 */
    private void renderBoneFaces(PoseStack pose, VertexConsumer vc, ParsedBone bone) {
        renderBoneFacesLit(pose, vc, bone, LightTexture.FULL_BRIGHT);
    }

    /**
     * 静态版骨骼面发射（光照可配）：编辑器预览（全亮）与游戏内手持渲染（实体光照）共用。
     */
    public static void renderBoneFacesLit(PoseStack pose, VertexConsumer vc, ParsedBone bone, int light) {
        if (!bone.visible) {
            return;
        }
        // 柔化：进入本骨骼之前的矩阵就是父级帧，用于关节处顶点插值
        Matrix4f parentMat = bone.softJoint > 0f ? new Matrix4f(pose.last().pose()) : null;
        pose.pushPose();
        pose.translate((bone.pivotX + bone.animPosX) / 16.0f,
                (bone.pivotY + bone.animPosY) / 16.0f,
                (bone.pivotZ + bone.animPosZ) / 16.0f);
        float rx = bone.fx();
        float ry = bone.fy();
        float rz = bone.fz();
        if (rx != 0 || ry != 0 || rz != 0) {
            pose.mulPose(new Quaternionf().rotationZYX(rz, ry, rx));
        }
        float sx = bone.scaleX * bone.animScaleX;
        float sy = bone.scaleY * bone.animScaleY;
        float sz = bone.scaleZ * bone.animScaleZ;
        if (sx != 1 || sy != 1 || sz != 1) {
            pose.scale(sx, sy, sz);
        }
        if (parentMat != null && !bone.cubes.isEmpty()) {
            // 静止帧 = 父矩阵 × 仅枢轴平移（不含本段旋转/缩放）
            Matrix4f rest = new Matrix4f(parentMat);
            rest.translate((bone.pivotX + bone.animPosX) / 16f,
                    (bone.pivotY + bone.animPosY) / 16f,
                    (bone.pivotZ + bone.animPosZ) / 16f);
            float band = softBand(bone);
            for (ParsedCube cube : bone.cubes) {
                if (cube.hasRot()) {
                    emitCubeFacesLit(pose, vc, cube, light);   // 自转立方体不参与柔化
                } else {
                    emitCubeFacesLitSoft(pose, vc, cube, light, rest, band, bone.softAxis);
                }
            }
        } else {
            for (ParsedCube cube : bone.cubes) {
                emitCubeFacesLit(pose, vc, cube, light);
            }
        }
        for (ParsedBone child : bone.children) {
            renderBoneFacesLit(pose, vc, child, light);
        }
        pose.popPose();
    }

    /**
     * 按 faces[] 逐面 UV 发射 6 面 quad；faceVerts 存在时优先用存储顶点（导出实体模型的精确还原）。
     * 立方体自身旋转（rotX/Y/Z + pivot）以 translate(pivot)→Rz·Ry·Rx→translate(-pivot)
     * 施加，与 GeckoLib 的 GeoCube 语义一致。
     */
    public static void emitCubeFacesLit(PoseStack pose, VertexConsumer vc, ParsedCube c, int light) {
        boolean rotated = c.hasRot();
        if (rotated) {
            pose.pushPose();
            boolean off = c.pivotX != 0 || c.pivotY != 0 || c.pivotZ != 0;
            if (off) {
                pose.translate(c.pivotX / 16f, c.pivotY / 16f, c.pivotZ / 16f);
            }
            pose.mulPose(new Quaternionf().rotationZYX(c.rotZ, c.rotY, c.rotX));
            if (off) {
                pose.translate(-c.pivotX / 16f, -c.pivotY / 16f, -c.pivotZ / 16f);
            }
        }
        Matrix4f mat = pose.last().pose();
        Matrix3f nmat = pose.last().normal();
        for (int f = 0; f < 6; f++) {
            if (!c.hasFace(f)) {
                continue;
            }
            float[] n = ParsedCube.FACE_NORMALS[f];
            for (int k = 0; k < 4; k++) {
                float x;
                float y;
                float z;
                if (c.hasFaceVerts(f)) {
                    x = c.vertComp(f, k, 0);
                    y = c.vertComp(f, k, 1);
                    z = c.vertComp(f, k, 2);
                } else {
                    x = c.cornerX(f, k);
                    y = c.cornerY(f, k);
                    z = c.cornerZ(f, k);
                    if (c.mirror) {
                        x = c.originX * 2 + c.sizeX - x;
                    }
                }
                float[] uv = c.uv(f, k);
                vc.vertex(mat, x / 16f, y / 16f, z / 16f)
                        .color(1.0f, 1.0f, 1.0f, 1.0f)
                        .uv(uv[0], uv[1])
                        .overlayCoords(OverlayTexture.NO_OVERLAY)
                        .uv2(light)
                        .normal(nmat, n[0], n[1], n[2])
                        .endVertex();
            }
        }
        if (rotated) {
            pose.popPose();
        }
    }

    /** 柔化带宽度（方块单位）：沿 softAxis 从关节起的过渡距离 */
    private static float softBand(ParsedBone bone) {
        float ext = 0f;
        for (ParsedCube c : bone.cubes) {
            float o = axisVal(c, bone.softAxis, false);
            float s = axisVal(c, bone.softAxis, true);
            ext = Math.max(ext, Math.max(Math.abs(o), Math.abs(o + s)));
        }
        // 至少 1.5 像素的过渡带：短段（前臂、小腿等）也不会退化成硬折
        return Math.max(1.5f, ext * bone.softJoint) / 16f;
    }

    private static float axisVal(ParsedCube c, int axis, boolean size) {
        if (axis == 0) {
            return size ? c.sizeX : c.originX;
        }
        if (axis == 2) {
            return size ? c.sizeZ : c.originZ;
        }
        return size ? c.sizeY : c.originY;
    }

    /**
     * 柔化版立方体发射：顶点在「父级静止帧」与「本段动画帧」之间按沿轴距离插值，
     * 关节处跟父级、远端跟本段，弯折时接缝呈平滑过渡而非硬折线。
     */
    private static void emitCubeFacesLitSoft(PoseStack pose, VertexConsumer vc, ParsedCube c, int light,
                                             Matrix4f restMat, float bandBlocks, int axis) {
        Matrix4f childMat = pose.last().pose();
        Matrix3f nmat = pose.last().normal();
        for (int f = 0; f < 6; f++) {
            if (!c.hasFace(f)) {
                continue;
            }
            float[] n = ParsedCube.FACE_NORMALS[f];
            for (int k = 0; k < 4; k++) {
                float x;
                float y;
                float z;
                if (c.hasFaceVerts(f)) {
                    x = c.vertComp(f, k, 0);
                    y = c.vertComp(f, k, 1);
                    z = c.vertComp(f, k, 2);
                } else {
                    x = c.cornerX(f, k);
                    y = c.cornerY(f, k);
                    z = c.cornerZ(f, k);
                    if (c.mirror) {
                        x = c.originX * 2 + c.sizeX - x;
                    }
                }
                float lx = x / 16f;
                float ly = y / 16f;
                float lz = z / 16f;
                Vector4f pc = new Vector4f(lx, ly, lz, 1f);
                childMat.transform(pc);
                Vector4f pr = new Vector4f(lx, ly, lz, 1f);
                restMat.transform(pr);
                float d = axis == 0 ? Math.abs(lx) : axis == 2 ? Math.abs(lz) : Math.abs(ly);
                float w = bandBlocks <= 1e-6f ? 1f : Math.min(1f, d / bandBlocks);
                w = w * w * (3f - 2f * w);      // smoothstep
                float px = pr.x + (pc.x - pr.x) * w;
                float py = pr.y + (pc.y - pr.y) * w;
                float pz = pr.z + (pc.z - pr.z) * w;
                float[] uv = c.uv(f, k);
                vc.vertex(px, py, pz)
                        .color(1.0f, 1.0f, 1.0f, 1.0f)
                        .uv(uv[0], uv[1])
                        .overlayCoords(OverlayTexture.NO_OVERLAY)
                        .uv2(light)
                        .normal(nmat, n[0], n[1], n[2])
                        .endVertex();
            }
        }
    }

    // ------------------------------------------------------------------ 手持物品渲染

    /**
     * 手持通道：遍历实体骨骼，命中某骨骼的手持条目时在其帧上施加挂点偏移/旋转后
     * 渲染物品图标（第三人称右手）。调用前实体面批次已 endBatch。
     */
    private void renderHeldPass(PoseStack pose, MultiBufferSource.BufferSource buffers, ParsedModel model) {
        for (ParsedBone root : model.roots) {
            heldWalk(pose, buffers, root);
        }
        buffers.endBatch();
    }

    /** 骨骼层级遍历（与 renderBoneFaces 同套变换），命中挂接骨骼时渲染全部手持条目 */
    private void heldWalk(PoseStack pose, MultiBufferSource.BufferSource buffers, ParsedBone bone) {
        if (!bone.visible) {
            return;
        }
        pose.pushPose();
        pose.translate((bone.pivotX + bone.animPosX) / 16.0f,
                (bone.pivotY + bone.animPosY) / 16.0f,
                (bone.pivotZ + bone.animPosZ) / 16.0f);
        float rx = bone.fx();
        float ry = bone.fy();
        float rz = bone.fz();
        if (rx != 0 || ry != 0 || rz != 0) {
            pose.mulPose(new Quaternionf().rotationZYX(rz, ry, rx));
        }
        float sx = bone.scaleX * bone.animScaleX;
        float sy = bone.scaleY * bone.animScaleY;
        float sz = bone.scaleZ * bone.animScaleZ;
        if (sx != 1 || sy != 1 || sz != 1) {
            pose.scale(sx, sy, sz);
        }
        for (HeldVis hv : heldItems) {
            if (hv.stack.isEmpty() || !bone.name.equals(hv.bone)) {
                continue;
            }
            pose.pushPose();
            pose.translate(hv.offX / 16.0f, hv.offY / 16.0f, hv.offZ / 16.0f);
            // 手持朝向修正（与游戏内 HeldItemRenderer 一致：XP(-90°)→YP(180°)）
            pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-90.0f));
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0f));
            if (hv.rotX != 0 || hv.rotY != 0 || hv.rotZ != 0) {
                pose.mulPose(new Quaternionf().rotationZYX(hv.rotZ, hv.rotY, hv.rotX));
            }
            Minecraft mc = Minecraft.getInstance();
            // 关闭背面剔除：物品经 XP/YP + display 变换后可能正面朝内，禁用剔除保证所有面可见
            com.mojang.blaze3d.systems.RenderSystem.disableCull();
            mc.getItemRenderer().renderStatic(hv.stack,
                    net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    pose, buffers, mc.level, 0);
            buffers.endBatch();
            com.mojang.blaze3d.systems.RenderSystem.enableCull();
            pose.popPose();
        }
        for (ParsedBone child : bone.children) {
            heldWalk(pose, buffers, child);
        }
        pose.popPose();
    }

    // ------------------------------------------------------------------ 骨骼层级（仅选中高亮：线框 + 关节）

    /**
     * 通用骨骼线：每个子骨 origin 与父骨 origin 连线，不读取人形骨名。
     * 是否画线由 {@link RigJointSet} 的派生拓扑决定（root/挂点/非变形/零长度不画）。
     */
    private void renderSkeletonTree(PoseStack pose, VertexConsumer vc, ParsedBone bone,
                                    RigJointSet joints) {
        if (!bone.visible) {
            return;
        }
        pose.pushPose();
        float px = mirrorOverlay ? -(bone.pivotX + bone.animPosX) : (bone.pivotX + bone.animPosX);
        pose.translate(px / 16.0f,
                (bone.pivotY + bone.animPosY) / 16.0f,
                (bone.pivotZ + bone.animPosZ) / 16.0f);
        if (bone.fx() != 0 || bone.fy() != 0 || bone.fz() != 0) {
            pose.mulPose(new Quaternionf().rotationZYX(bone.fz(), bone.fy(), bone.fx()));
        }
        float sx = bone.scaleX * bone.animScaleX;
        float sy = bone.scaleY * bone.animScaleY;
        float sz = bone.scaleZ * bone.animScaleZ;
        if (sx != 1 || sy != 1 || sz != 1) {
            pose.scale(sx, sy, sz);
        }
        Matrix4f mat = pose.last().pose();
        Matrix3f nmat = pose.last().normal();

        RigJoint joint = joints == null ? null : joints.joint(bone.name);
        float cr = 0.3f, cg = 0.9f, cb = 0.35f;
        if (joint != null && joint.attach()) {
            cr = 1.0f;
            cg = 0.55f;
            cb = 0.15f;
        } else if (joint != null && !joint.deform()) {
            cr = 0.55f;
            cg = 0.55f;
            cb = 0.55f;
        }
        // 关节十字
        float c = 0.8f;
        line(vc, mat, nmat, -c / 16, 0, 0, c / 16, 0, 0, cr, cg, cb);
        line(vc, mat, nmat, 0, -c / 16, 0, 0, c / 16, 0, cr, cg, cb);
        line(vc, mat, nmat, 0, 0, -c / 16, 0, 0, c / 16, cr, cg, cb);

        for (ParsedBone child : bone.children) {
            if (joints == null || joints.hasLine(bone.name, child.name)) {
                RigJoint childJoint = joints == null ? null : joints.joint(child.name);
                float lx = (childJoint == null ? child.pivotX : childJoint.localX()) + child.animPosX;
                float ly = (childJoint == null ? child.pivotY : childJoint.localY()) + child.animPosY;
                float lz = (childJoint == null ? child.pivotZ : childJoint.localZ()) + child.animPosZ;
                line(vc, mat, nmat, 0, 0, 0, lx, ly, lz, 0.4f, 0.9f, 0.45f);
            }
            renderSkeletonTree(pose, vc, child, joints);
        }
        pose.popPose();
    }

    /** 仅绘制选中骨骼的线框描边与关节体中心线；模型面由 {@link #renderBoneFaces} 负责。 */
    private void renderBoneOverlay(PoseStack pose, VertexConsumer lineVc, ParsedBone bone) {
        if (!bone.visible) {
            return;
        }
        pose.pushPose();
        float px = mirrorOverlay ? -(bone.pivotX + bone.animPosX) : (bone.pivotX + bone.animPosX);
        pose.translate(px / 16.0f,
                (bone.pivotY + bone.animPosY) / 16.0f,
                (bone.pivotZ + bone.animPosZ) / 16.0f);
        float rx = bone.fx();
        float ry = bone.fy();
        float rz = bone.fz();
        if (rx != 0 || ry != 0 || rz != 0) {
            pose.mulPose(new Quaternionf().rotationZYX(rz, ry, rx));
        }
        float sx = bone.scaleX * bone.animScaleX;
        float sy = bone.scaleY * bone.animScaleY;
        float sz = bone.scaleZ * bone.animScaleZ;
        if (sx != 1 || sy != 1 || sz != 1) {
            pose.scale(sx, sy, sz);
        }
        PoseStack.Pose p = pose.last();
        Matrix4f mat = p.pose();
        Matrix3f nmat = p.normal();
        boolean selected = selectedBone != null && selectedBone.equals(bone.name);
        if (selected) {
            if (showBoneOutline) {
                for (ParsedCube cube : bone.cubes) {
                    emitCubeOutline(mat, nmat, lineVc, cube, mirrorOverlay);
                }
            }
            emitJointGizmo(mat, nmat, lineVc, bone, mirrorOverlay);
        }
        for (ParsedBone child : bone.children) {
            renderBoneOverlay(pose, lineVc, child);
        }
        pose.popPose();
    }

    /** 选中骨骼高亮：每个立方体的 12 条边线框（青色），不遮挡纹理 */
    private void emitCubeOutline(Matrix4f mat, Matrix3f nmat, VertexConsumer vc, ParsedCube c, boolean mirror) {
        float x0 = c.originX, y0 = c.originY, z0 = c.originZ;
        float x1 = x0 + c.sizeX, y1 = y0 + c.sizeY, z1 = z0 + c.sizeZ;
        if (mirror) {
            float nx0 = -x1;
            x1 = -x0;
            x0 = nx0;
        }
        // 8 个角点
        float[][] v = {
                {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1},
                {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
        int[][] edges = {
                {0, 1}, {1, 2}, {2, 3}, {3, 0},
                {4, 5}, {5, 6}, {6, 7}, {7, 4},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] e : edges) {
            line(vc, mat, nmat, v[e[0]][0], v[e[0]][1], v[e[0]][2],
                    v[e[1]][0], v[e[1]][1], v[e[1]][2], 0.3f, 0.9f, 1.0f);
        }
    }

    /** 选中骨骼辅助线：体中心线（沿最长轴，黄色）+ 枢轴关节十字（XYZ 红/绿/蓝） */
    private void emitJointGizmo(Matrix4f mat, Matrix3f nmat, VertexConsumer vc, ParsedBone bone, boolean mirror) {
        float[] cb = bone.cubeBounds();
        if (cb != null) {
            float x0 = mirror ? -cb[3] : cb[0];
            float x1 = mirror ? -cb[0] : cb[3];
            float cx = (x0 + x1) / 2;
            float cy = (cb[1] + cb[4]) / 2;
            float cz = (cb[2] + cb[5]) / 2;
            float ex = x1 - x0;
            float ey = cb[4] - cb[1];
            float ez = cb[5] - cb[2];
            float m = 6; // 两端外延（模型单位），保证线段露出体表面
            if (ex >= ey && ex >= ez) {
                line(vc, mat, nmat, x0 - m, cy, cz, x1 + m, cy, cz, 1f, 0.95f, 0.2f);
            } else if (ey >= ez) {
                line(vc, mat, nmat, cx, cb[1] - m, cz, cx, cb[4] + m, cz, 1f, 0.95f, 0.2f);
            } else {
                line(vc, mat, nmat, cx, cy, cb[2] - m, cx, cy, cb[5] + m, 1f, 0.95f, 0.2f);
            }
        }
        // 枢轴十字 = 关节点指示
        float c = 4;
        line(vc, mat, nmat, -c, 0, 0, c, 0, 0, 1f, 0.25f, 0.25f);  // X 红
        line(vc, mat, nmat, 0, -c, 0, 0, c, 0, 0.35f, 1f, 0.35f);  // Y 绿
        line(vc, mat, nmat, 0, 0, -c, 0, 0, c, 0.35f, 0.55f, 1f);  // Z 蓝
    }

    private void renderGroundGrid(PoseStack pose, VertexConsumer vc) {
        PoseStack.Pose p = pose.last();
        Matrix4f mat = p.pose();
        Matrix3f nmat = p.normal();
        float y = 24.01f;
        int half = 64; // 世界平面 ±64 blocks；相机附近看起来就是无限网格
        for (int i = -half; i <= half; i++) {
            if (i == 0) {
                continue;
            }
            float c = i * 16.0f;
            float g = (i % 8 == 0) ? 0.56f : 0.32f;
            line(vc, mat, nmat, -half * 16.0f, y, c, half * 16.0f, y, c,
                    g, g, g);
            line(vc, mat, nmat, c, y, -half * 16.0f, c, y, half * 16.0f,
                    g, g, g);
        }
        // Blender 风格主轴线：X 红，Z 蓝。
        line(vc, mat, nmat, -half * 16.0f, y, 0, half * 16.0f, y, 0, 0.9f, 0.25f, 0.25f);
        line(vc, mat, nmat, 0, y, -half * 16.0f, 0, y, half * 16.0f, 0.25f, 0.4f, 0.95f);
        // 原点 Y 轴（绿色），便于看高度。
        line(vc, mat, nmat, 0, y, 0, 0, 0.0f, 0, 0.3f, 0.9f, 0.35f);
    }

    private void line(VertexConsumer vc, Matrix4f mat, Matrix3f nmat,
                      float x1, float y1, float z1, float x2, float y2, float z2,
                      float r, float g, float b) {
        vc.vertex(mat, x1 / 16f, y1 / 16f, z1 / 16f).color(r, g, b, 1f).normal(nmat, 0, 1, 0).endVertex();
        vc.vertex(mat, x2 / 16f, y2 / 16f, z2 / 16f).color(r, g, b, 1f).normal(nmat, 0, 1, 0).endVertex();
    }

    // ------------------------------------------------------------------ 纹理解析

    /** 纹理优先级：玩家当前皮肤 → JSON texture 字段 → 实体渲染器默认纹理 → 1×1 浅灰纯色兜底 */
    private ResourceLocation resolveTexture(Minecraft mc, ParsedModel model) {
        // 玩家模型必须用“当前玩家皮肤”，不能用导出时写死的 Steve/Alex 路径；
        // 皮肤 atlas 里未覆盖的区域通常是透明的，交给 entityCutoutNoCull 做 alpha 裁剪。
        if ((isPlayerModel(model) || "combatoverhaul:humanoid".equals(model.entityId)
                || "combatoverhaul:humanoid_steve".equals(model.entityId)
                || "combatoverhaul:skinned_player".equals(model.entityId)
                || "combatoverhaul:skinned_player_er".equals(model.entityId))
                && mc.player != null) {
            ResourceLocation skin = mc.player.getSkinTextureLocation();
            if (skin != null) {
                lastTextureInfo = "player skin: " + skin;
                return skin;
            }
        }
        if (model.resolvedTexture != null) {
            return model.resolvedTexture;
        }
        ResourceLocation rl = null;
        if (model.textureSpec != null && !model.textureSpec.isEmpty()) {
            try {
                rl = new ResourceLocation(model.textureSpec);
                if (mc.getResourceManager().getResource(rl).isPresent()) {
                    lastTextureInfo = "tex: " + model.textureSpec;
                } else {
                    // 路径无效 → 回退，避免绑定"缺失纹理"紫黑块
                    lastTextureInfo = "tex missing: " + model.textureSpec;
                    rl = null;
                }
            } catch (RuntimeException e) {
                lastTextureInfo = "tex invalid: " + model.textureSpec + " (" + e.getClass().getSimpleName() + ")";
                rl = null;
            }
        }
        if (rl == null && model.entityId != null && !model.entityId.isEmpty()) {
            String[] diag = new String[1];
            rl = textureFromEntityRenderer(mc, model.entityId, diag);
            if (rl != null) {
                lastTextureInfo = "renderer: " + rl + (diag[0] != null ? " (" + diag[0] + ")" : "");
            } else {
                lastTextureInfo = "no entity texture: " + model.entityId
                        + (diag[0] != null ? " [" + diag[0] + "]" : "");
            }
        }
        if (rl == null) {
            rl = plainFallback(mc);
            lastTextureInfo = (lastTextureInfo == null ? "no texture" : lastTextureInfo) + " -> plain";
        }
        model.resolvedTexture = rl;
        return rl;
    }

    /** 玩家模型判定：minecraft:player 以及兼容其它命名空间的 player。 */
    private static boolean isPlayerModel(ParsedModel model) {
        if (model == null || model.entityId == null) {
            return false;
        }
        String id = model.entityId;
        return "minecraft:player".equals(id) || id.endsWith(":player") || id.endsWith(":player_slim");
    }

    /**
     * 游戏内等非编辑器场景的纹理解析（与编辑器同一优先级：JSON texture → 实体渲染器 → 灰色兜底，
     * 不写编辑器诊断信息，结果缓存在 model.resolvedTexture）。
     */
    public static ResourceLocation textureFor(Minecraft mc, ParsedModel model) {
        if ((isPlayerModel(model) || "combatoverhaul:skinned_player".equals(model.entityId)
                || "combatoverhaul:skinned_player_er".equals(model.entityId))
                && mc.player != null) {
            ResourceLocation skin = mc.player.getSkinTextureLocation();
            if (skin != null) {
                return skin;
            }
        }
        if (model.resolvedTexture != null) {
            return model.resolvedTexture;
        }
        ResourceLocation rl = null;
        if (model.textureSpec != null && !model.textureSpec.isEmpty()) {
            try {
                ResourceLocation t = new ResourceLocation(model.textureSpec);
                if (mc.getResourceManager().getResource(t).isPresent()) {
                    rl = t;
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (rl == null && model.entityId != null && !model.entityId.isEmpty()) {
            rl = textureFromEntityRenderer(mc, model.entityId, new String[1]);
        }
        if (rl == null) {
            rl = plainFallback(mc);
        }
        model.resolvedTexture = rl;
        return rl;
    }

    /**
     * 通过实体渲染器取与游戏内完全一致的默认纹理。
     * 两条路径：① 创建临时实体实例调 {@code getTextureLocation}（与游戏运行时同一方法）；
     * ② 实例创建/调用失败时，反射渲染器类声明的 static final ResourceLocation 常量
     * （原版渲染器如 WitchRenderer.WITCH_LOCATION 均为此形态，排除状态变体纹理）。
     *
     * @param diag 输出失败/路径诊断说明（供视口显示）
     */
    private static ResourceLocation textureFromEntityRenderer(Minecraft mc, String entityId, String[] diag) {
        EntityType<?> type;
        try {
            type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(entityId));
        } catch (RuntimeException e) {
            diag[0] = "bad id " + e.getClass().getSimpleName();
            return null;
        }
        if (type == null) {
            diag[0] = "type not registered";
            return null;
        }
        var dispatcher = mc.getEntityRenderDispatcher();
        // renderers 为 dispatcher 的公共注册表：先确认渲染器确实注册
        Object registered = null;
        try {
            registered = dispatcher.renderers.get(type);
        } catch (RuntimeException e) {
            diag[0] = "renderer map fail " + e.getClass().getSimpleName();
        }
        // 路径①：临时实体实例（需要 ClientLevel）
        if (mc.level != null) {
            try {
                // 玩家无法用 create 构造（工厂返回 null）：直接用本地玩家实例，贴图即本人皮肤
                Entity entity = type == EntityType.PLAYER && mc.player != null
                        ? mc.player
                        : type.create(mc.level);
                if (entity != null) {
                    var renderer = dispatcher.getRenderer(entity);
                    if (renderer != null) {
                        ResourceLocation rl = renderer.getTextureLocation(entity);
                        if (rl != null && mc.getResourceManager().getResource(rl).isPresent()) {
                            diag[0] = "instance";
                            return rl;
                        }
                        if (rl != null) {
                            diag[0] = "instance rl missing file: " + rl;
                        }
                    } else {
                        diag[0] = "getRenderer null";
                    }
                } else {
                    diag[0] = "create returned null";
                }
            } catch (RuntimeException e) {
                diag[0] = "instance fail: " + e.getClass().getSimpleName()
                        + (e.getMessage() != null ? " " + e.getMessage() : "");
            }
        } else {
            diag[0] = "level null";
        }
        // 路径②：反射渲染器类的静态纹理常量（不依赖实体实例，拿到的就是游戏内使用的同一张图）
        Object rendererObj = registered;
        if (rendererObj == null) {
            try {
                if (mc.level != null) {
                    Entity probe = type.create(mc.level);
                    if (probe != null) {
                        rendererObj = dispatcher.getRenderer(probe);
                    }
                }
            } catch (RuntimeException ignored) {
                // 继续用注册表里的类型（下面按 Class 反射静态字段，不需要实例）
            }
        }
        Class<?> cls = rendererObj != null ? rendererObj.getClass() : null;
        ResourceLocation reflected = cls == null ? null : reflectDefaultTexture(cls);
        if (reflected != null) {
            diag[0] = (diag[0] != null ? diag[0] + "; " : "") + "static-field ok";
            return reflected;
        }
        return null;
    }

    /** 状态变体纹理关键词（angry/tamed/rare 等不是默认纹理） */
    private static final String[] NON_DEFAULT_TEX_KEYWORDS = {
            "angry", "tamed", "rare", "other", "hurt", "sleep", "invisible", "sweat", "cold"};

    /**
     * 反射渲染器类（含父类层级）声明的 static final ResourceLocation：
     * 优先取字段名不含状态变体关键词、且资源存在的第一个；与游戏内默认贴图一致。
     */
    private static ResourceLocation reflectDefaultTexture(Class<?> rendererClass) {
        ResourceLocation fallbackCandidate = null;
        Class<?> cls = rendererClass;
        while (cls != null && cls != Object.class) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        || !ResourceLocation.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (!(v instanceof ResourceLocation rl)) {
                        continue;
                    }
                    boolean present;
                    try {
                        present = Minecraft.getInstance().getResourceManager().getResource(rl).isPresent();
                    } catch (RuntimeException e) {
                        present = false;
                    }
                    if (!present) {
                        continue;
                    }
                    String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                    boolean variant = false;
                    for (String kw : NON_DEFAULT_TEX_KEYWORDS) {
                        if (n.contains(kw)) {
                            variant = true;
                            break;
                        }
                    }
                    if (!variant) {
                        return rl;
                    }
                    if (fallbackCandidate == null) {
                        fallbackCandidate = rl;
                    }
                } catch (Exception ignored) {
                    // 单个字段不可访问不影响其它字段
                }
            }
            cls = cls.getSuperclass();
        }
        return fallbackCandidate;
    }

    /**
     * 无纹理时的灰白棋盘兜底（懒加载注册一次）：几何形状与 UV 铺展仍然可见，
     * 棋格让不同面/不同体素之间有区分，避免纯灰一片看不出结构。
     */
    private static synchronized ResourceLocation plainFallback(Minecraft mc) {
        if (fallbackTexture != null) {
            return fallbackTexture;
        }
        final int size = 16;
        final int cell = 4;
        com.mojang.blaze3d.platform.NativeImage img =
                new com.mojang.blaze3d.platform.NativeImage(size, size, false);
        // ABGR 打包：FastColor.ABGR32.color(r, g, b, a)
        int light = FastColor.ABGR32.color(236, 236, 240, 255);
        int dark = FastColor.ABGR32.color(150, 150, 158, 255);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean odd = ((x / cell) + (y / cell)) % 2 == 1;
                img.setPixelRGBA(x, y, odd ? dark : light);
            }
        }
        DynamicTexture texture = new DynamicTexture(img);
        // register(String, DynamicTexture) 返回实际分配的资源路径
        fallbackTexture = mc.getTextureManager().register(FALLBACK_PATH, texture);
        return fallbackTexture;
    }
}
