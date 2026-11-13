package com.yourname.combatoverhaul.client.screen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.combatoverhaul.client.model.BoneSplitter;
import com.yourname.combatoverhaul.client.model.ModelRigJoints;
import com.yourname.combatoverhaul.client.model.ParsedBone;
import com.yourname.combatoverhaul.client.model.ParsedCube;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import com.yourname.combatoverhaul.rig.RigJointSet;
import com.yourname.combatoverhaul.client.render.ModelPreviewRenderer;
import com.yourname.combatoverhaul.client.screen.ui.EditorButton;
import com.yourname.combatoverhaul.client.screen.ui.EditorDraw;
import com.yourname.combatoverhaul.client.skinned.SkinWeightGenerator;
import com.yourname.combatoverhaul.client.screen.ui.EditorTheme;
import com.yourname.combatoverhaul.store.ActionStore;
import com.yourname.combatoverhaul.store.ModelStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * 3D 模型编辑器：左侧骨骼树（选中/添加/删除骨骼，新骨骼克隆选中骨骼的立方体以复用关节贴图），
 * 右侧 3D 视口（拖拽旋转、滚轮缩放、动作姿态实时预览），底部时间轴 + 关键帧 + 数值编辑。
 *
 * <p>动作（关键帧）与模型几何分别保存为 &lt;entity&gt;.actions.json 与 &lt;entity&gt;.json。
 * 写入目标可在「动作关键帧」与「骨骼基础姿态」间切换。
 */
public class ModelEditorScreen extends Screen {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int ROW_H = 12;

    private final Screen parent;
    private final String modid;
    private final String path;
    private final boolean weapon; // 武器模型：存取 weapons 目录
    /** 实际读取/保存的模型文件路径；玩家 slim 皮肤会切到 player_slim，动作仍挂在 path 上。 */
    private String modelPath;
    private static boolean playerHierarchyMigrated;

    private ParsedModel model;
    private final ModelPreviewRenderer preview = new ModelPreviewRenderer();

    private final List<ActionStore.Action> actions = new java.util.ArrayList<>();
    private int actionIndex = -1;
    private boolean playing = false;
    private float currentTick = 0;
    private int selectedKeyframe = -1;

    private final List<ParsedBone> flatBones = new java.util.ArrayList<>();
    /** 骨骼树行（ParsedBone 或 ParsedModel.HeldEntry 手持节点，手持节点排在其骨骼行之后） */
    private final List<Object> treeRows = new java.util.ArrayList<>();
    private int selectedRow = -1;
    /** 选中骨骼下的立方体索引（-1 = 未选，编辑骨骼本身；>=0 = 编辑该立方体） */
    private int selectedCube = -1;
    private int boneScroll = 0;

    /** 0-8：骨骼 pos/rot/scale；9-11：当前关键帧锚点目标位置（无锚点时留空） */
    private final EditBox[] boxes = new EditBox[12];
    private boolean showTransform = true;
    /** 父级骨骼名输入框（挂接目标；空 = 脱离为根） */
    private EditBox parentBox;
    private boolean poseMode = false; // false=关键帧 true=基础姿态
    private String status = "";
    /** 模型文件存在但 JSON 解析失败：禁止保存，避免用空骨架覆盖坏文件（先走 JSON 编辑器修复） */
    private boolean modelParseFailed = false;

    // 布局（页面空间坐标）
    private int bx0, bx1, by0, by1;      // 骨骼面板
    private int vx0, vy0, vx1, vy1;      // 3D 视口
    private int ix0, ix1, iy0, iy1;      // 右侧 Inspector
    private int tlX0, tlX1, tlY0, tlY1;  // 时间轴

    // 页面变换：虚拟画布不小于窗口，小窗口下可缩放/上下滚动/左右平移
    private int pageW, pageH;
    private int viewW, viewH;
    private float pageScale = 1.0f;
    private int pageOffX = 0, pageOffY = 0;

    private Button btnPlay, btnLoop, btnMode, btnTool, btnAnchorSet, btnAnchorClear;
    private Button btnTrack, btnBoneTrack;
    private EditBox trackMaxBox;
    private Button btnCubePrev, btnCubeSel, btnCubeNext;
    private Button btnSkeleton, btnAlpha;
    private boolean dragging = false;

    // 视口工具：0=骨骼（左键旋转选中骨骼） 1=锚点（左键拖动当前关键帧锚点位置） 2=枢轴（左键移动枢轴，几何保持原位）；右键=旋转视角 中键=平移
    private int toolMode = 0;
    /** 绑定工具：左键刷选当前骨骼，Shift+左键擦除 */
    private boolean bindingDrag = false;
    private static final int BIND_BRUSH_RADIUS = 16;
    private int gizmoAxis = 0; // 1=X红 2=Y绿 3=Z蓝
    /** 上一帧投影后的轴环点（页面坐标）；命中测试按线段距离，不再用椭圆近似。 */
    private int[][] gizmoRingX = new int[0][];
    private int[][] gizmoRingY = new int[0][];
    private int[][] gizmoRingZ = new int[0][];
    /** 屏幕角增量 → 模型旋转角增量的符号 */
    private final double[] gizmoRingSign = {1, 1, 1};
    private double gizmoLastAngle;
    private int gizmoCx, gizmoCy;
    private boolean boneDragging = false;
    private boolean anchorDragging = false;
    private boolean pivotDragging = false;
    private double dragStartX, dragStartY;
    /** 拖动开始时的旋转初值（度，XYZ），拖动 = 初值 + 鼠标增量 */
    private final float[] dragStartRot = new float[3];
    /** 锚点拖动开始时的锚点目标位置（模型单位） */
    private final float[] dragStartAnchor = new float[3];
    /** 枢轴拖动开始时的枢轴初值（父级空间，模型单位） */
    private final float[] dragStartPivot = new float[3];
    /** 枢轴拖动开始时的立方体原点快照（拖动期间按位移补偿，保持几何世界位置） */
    private java.util.List<float[]> dragStartCubeOrigins;
    /** 枢轴拖动开始时的 faceVerts 快照（渲染端优先使用存储顶点，需同步补偿） */
    private java.util.List<float[][][]> dragStartCubeVerts;
    /** 中轴线参数（拖动开始时计算，父级空间）：线上一点 Q 与单位方向 U */
    private final float[] axisQ = new float[3];
    private final float[] axisU = new float[3];
    private boolean axisValid = false;
    /** 骨骼自身旋转×缩放矩阵（局部→父级 3x3）及其逆 */
    private final org.joml.Matrix3f dragBoneRS = new org.joml.Matrix3f();
    private final org.joml.Matrix3f dragBoneRSInv = new org.joml.Matrix3f();
    /** 父级链旋转×缩放矩阵的逆（模型空间鼠标位移 → 父级空间） */
    private final org.joml.Matrix3f dragParentInv = new org.joml.Matrix3f();
    /** 关键帧模式下拖动写入的目标帧/通道（姿态模式为 null，直接写 bone.rotX/Y/Z） */
    private ActionStore.Keyframe dragKeyframe;
    private ActionStore.BoneChannel dragChannel;
    /** 本次拖动是否新建了关键帧；空点击（无位移）时回滚，避免点一下视口就多一帧 */
    private boolean dragKeyframeCreated;
    private boolean boneDragMoved;
    /** 拖动开始时选中的手持节点（非 null 时骨骼工具=旋转物品，枢轴工具=移动挂点） */
    private ParsedModel.HeldEntry dragHeld;

    public ModelEditorScreen(Screen parent, String modid, String path, boolean weapon) {
        super(Component.translatable("gui.combatoverhaul.editor3d_title", modid, path));
        this.parent = parent;
        this.modid = modid;
        this.path = path;
        this.weapon = weapon;
    }

    // ------------------------------------------------------------------ 初始化

    @Override
    protected void init() {
        // 设计画布等比缩放到窗口；保留旧编辑器的完整功能区域，同时给右侧 Inspector 留位。
        pageW = 1080;
        pageH = 760;
        viewW = EditorTheme.visibleWidth(this.minecraft);
        viewH = EditorTheme.visibleHeight(this.minecraft);
        fitPage();

        int top = 70;
        int bottom = pageH - 138;
        bx0 = 12;
        bx1 = 206;
        by0 = top;
        by1 = bottom;

        vx0 = bx1 + 10;
        vy0 = top;
        vx1 = Math.max(vx0 + 260, pageW - 300);
        vy1 = bottom;

        ix0 = pageW - 292;
        ix1 = pageW - 12;
        iy0 = top;
        iy1 = bottom;

        tlX0 = 12;
        tlX1 = pageW - 300;
        tlY0 = pageH - 132;
        tlY1 = pageH - 36;

        if (model == null) {
            loadAll();
        }
        preview.fitView(model, vx0, vy0, vx1, vy1);

        buildTopBar();
        buildInspectorButtons();
        buildBoneButtons();
        buildTransport();
        buildBoxes();
        clampBoneScroll();
        syncBoxesFromState();
    }

    /** 当前玩家皮肤模型是否为 slim/Alex：直接看渲染器右臂立方体宽度（3px vs 4px）。 */
    private boolean currentPlayerSlim() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        try {
            var renderer = mc.getEntityRenderDispatcher().getRenderer(mc.player);
            if (renderer instanceof net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?> ler
                    && ler.getModel() instanceof net.minecraft.client.model.HumanoidModel<?> hm
                    && hm.rightArm != null) {
                for (java.lang.reflect.Field f : net.minecraft.client.model.geom.ModelPart.class.getDeclaredFields()) {
                    if (!java.util.List.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object value = f.get(hm.rightArm);
                    if (value instanceof java.util.List<?> list && !list.isEmpty()
                            && list.get(0) instanceof net.minecraft.client.model.geom.ModelPart.Cube cube) {
                        return cube.maxX - cube.minX < 3.5f;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return false;
    }

    private void loadAll() {
        modelPath = path;
        if (!weapon && "minecraft".equals(modid) && "player".equals(path) && currentPlayerSlim()) {
            modelPath = "player_slim";
        }
        // 14 段 rig 的 Steve/Alex 两套几何：按当前皮肤自动选，动作相同。
        if (!weapon && "combatoverhaul".equals(modid) && "humanoid".equals(path) && !currentPlayerSlim()) {
            modelPath = "humanoid_steve";
        }
        com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.info(
                "[CombatOverhaul][Skin] player model path={} slimDetected={}", modelPath,
                "player_slim".equals(modelPath));
        String content = weapon ? ModelStore.readWeapon(modid, modelPath)
                : ModelStore.read(modid, modelPath);
        // 旧 player.json 的外层 sleeve/pants 还是独立根：自动重扫一次，挂回主骨骼。
        if (!weapon && "minecraft".equals(modid) && "player".equals(path)
                && (content == null || !content.contains("player_helpers_removed"))
                && !playerHierarchyMigrated) {
            playerHierarchyMigrated = true;
            for (var scan : com.yourname.combatoverhaul.client.model.EntityModelScanner.scanAll()) {
                if ("player".equals(scan.path) || "player_slim".equals(scan.path)) {
                    com.yourname.combatoverhaul.client.model.EntityModelScanner.exportOverwrite(scan);
                }
            }
            content = ModelStore.read(modid, modelPath);
        }
        modelParseFailed = false;
        if (content != null) {
            try {
                JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                model = ParsedModel.parse(json);
            } catch (RuntimeException e) {
                model = ParsedModel.emptyDefault(modid + ":" + path);
                modelParseFailed = true;
                status = Component.translatable("gui.combatoverhaul.model_broken").getString();
            }
        } else {
            model = ParsedModel.emptyDefault(modid + ":" + path);
        }
        actions.clear();
        actions.addAll(ActionStore.load(modid, path, weapon));
        actionIndex = actions.isEmpty() ? -1 : 0;
        preview.fitView(model, vx0, vy0, vx1, vy1);
        refreshBoneSelection();
        boolean hasCubes = model.bones.stream().anyMatch(b -> !b.cubes.isEmpty());
        // 蒙皮是可选项：cube 模型默认显示方块；纯 mesh 模型没有方块可退，必须用蒙皮。
        preview.skinningEnabled = model.skinnedMesh != null && !hasCubes;
        refreshSkinningLabel();
        if (model.skinnedMesh != null && hasCubes
                && model.skinnedMesh.sourceBones.size() != model.skinnedMesh.vertices.size()) {
            status = "旧蒙皮网格缺少 source_bones：请点“自动蒙皮”重新生成，否则手臂/身体可能粘连";
        }
    }

    private void buildTopBar() {
        int y = 30;
        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> cycleAction(-1))
                .bounds(34, y, 16, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> cycleAction(1))
                .bounds(52, y, 16, 16).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.new_action"), b -> newAction())
                .bounds(140, y, 60, 16).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.del_action"), b -> deleteAction())
                .bounds(204, y, 60, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal("-"), b -> changeLength(-1))
                .bounds(288, y, 16, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal("+"), b -> changeLength(1))
                .bounds(330, y, 16, 16).build());
        btnLoop = this.addRenderableWidget(Button.builder(Component.literal(""), b -> toggleLoop())
                .bounds(350, y, 48, 16).build());
        refreshLoopLabel();
        // 动作追踪开关 + 最大上下偏移（°）：带追踪标签的动作播放/判定时按视线俯仰叠加偏移
        btnTrack = this.addRenderableWidget(Button.builder(Component.literal(""), b -> toggleTrack())
                .bounds(350, y + 18, 48, 16).build());
        trackMaxBox = new EditBox(this.font, 402, y + 19, 34, 14, Component.literal(""));
        trackMaxBox.setMaxLength(5);
        trackMaxBox.setHint(Component.translatable("gui.combatoverhaul.track_max_hint"));
        trackMaxBox.setResponder(v -> {
            ActionStore.Action a = curAction();
            if (a != null) {
                try {
                    String s = v.trim();
                    if (!s.isEmpty()) {
                        float f = Float.parseFloat(s);
                        a.maxPitchOffset = Math.max(0f, Math.min(90f, f));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        });
        this.addRenderableWidget(trackMaxBox);
        refreshTrackLabel();
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.save_model"), b -> saveModel())
                .bounds(pageW - 250, y, 78, 16).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.save_actions"), b -> saveActions())
                .bounds(pageW - 168, y, 78, 16).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.back"), b -> onClose())
                .bounds(pageW - 86, y, 76, 16).build());
        // 页面级开关：放大/缩小/适应/参数抽屉
        this.addRenderableWidget(new EditorButton(660, y, 32, 16, Component.literal("UI-"),
                b -> zoomPage(1 / 1.2f)));
        this.addRenderableWidget(new EditorButton(696, y, 32, 16, Component.literal("UI+"),
                b -> zoomPage(1.2f)));
        this.addRenderableWidget(new EditorButton(732, y, 40, 16, Component.literal("适配"),
                b -> fitPage()));
        this.addRenderableWidget(new EditorButton(776, y, 48, 16, Component.literal("参数"),
                b -> toggleTransform()));
        btnSkeleton = new EditorButton(520, y, 60, 16, Component.literal("骨骼"),
                b -> toggleSkeleton());
        this.addRenderableWidget(btnSkeleton);
        btnAlpha = new EditorButton(584, y, 68, 16, Component.literal("透明 100%"),
                b -> toggleModelAlpha());
        this.addRenderableWidget(btnAlpha);
        this.addRenderableWidget(new EditorButton(446, y, 70, 16, Component.literal("手持物品"),
                b -> openHeldQuick()));
    }

    /** 浏览器/编辑器快捷入口：切到手持页，没有条目时直接建一条。 */
    private void openHeldQuick() {
        setInspectorTab(3);
        if (model.heldItems.isEmpty()) {
            addInspectorHeld();
        }
        status = "手持：选择骨骼后编辑物品与挂点";
    }

    private void toggleSkeleton() {
        preview.showSkeleton = !preview.showSkeleton;
        btnSkeleton.setMessage(Component.literal(preview.showSkeleton ? "骨骼 开" : "骨骼"));
        if (preview.showSkeleton) {
            RigJointSet joints = ModelRigJoints.from(model);
            status = "显示骨骼线：" + joints.joints().size() + " 关节 / "
                    + joints.lines().size() + " 段（root/挂点不画长线）";
        } else {
            status = "隐藏骨骼线";
        }
    }

    private void toggleModelAlpha() {
        preview.modelAlpha = preview.modelAlpha < 0.99f ? 1.0f : 0.35f;
        btnAlpha.setMessage(Component.literal("透明 " + (int) (preview.modelAlpha * 100) + "%"));
        status = preview.modelAlpha < 0.99f ? "纹理半透明，骨骼线可透视" : "纹理不透明";
    }

    /** 实机回归测试入口：选中第一个带 hitbox 的动作并打开判定框线框工具。 */
    public void openHitboxToolForTest() {
        for (int i = 0; i < actions.size(); i++) {
            if (!actions.get(i).hitboxes.isEmpty()) {
                actionIndex = i;
                inspectorHitbox = 0;
                break;
            }
        }
        setInspectorTab(1);
        hitboxEdit = true;
        currentTick = 0;
        status = "测试：判定框线框已打开";
        syncBoxesFromState();
    }

    /**
     * 自动蒙皮：按已有骨骼派生的骨段生成权重，只改内存中的 SkinnedMesh。
     * 不自动写文件；用户确认预览后点“保存模型”，保存路径会校验并持久化。
     */
    private void autoSkin() {
        if (model == null || modelParseFailed) {
            status = "模型未正确加载，无法自动蒙皮";
            return;
        }
        SkinWeightGenerator.Result r = SkinWeightGenerator.generate(model);
        if (!r.ok) {
            status = "自动蒙皮失败：" + r.error;
            return;
        }
        StringBuilder sb = new StringBuilder("自动蒙皮完成");
        if (r.meshBuilt) {
            sb.append("（cube 生成网格 ").append(r.meshVertices).append(" 顶点 / ")
                    .append(r.meshQuads).append(" 面）");
        }
        sb.append("：")
                .append(r.vertices).append(" 顶点 / ")
                .append(r.segments).append(" 骨段 / ")
                .append(r.twoInfluence).append(" 双骨 / ")
                .append(r.oneInfluence).append(" 单骨");
        if (r.noSegment > 0) {
            sb.append(" / ").append(r.noSegment).append(" 回退");
        }
        if (!r.warnings.isEmpty()) {
            sb.append(" / 警告 ").append(r.warnings.size());
        }
        sb.append("；预览确认后点保存模型");
        status = sb.toString();
        preview.skinningEnabled = true;
        refreshSkinningLabel();
    }

    /**
     * 绑定工具依赖连续网格和权重；cube 模型先自动生成网格/权重，之后刷选只改 bind_bones。
     */
    private boolean ensureMeshForBinding() {
        if (model == null || modelParseFailed) {
            status = "模型未正确加载，无法绑定";
            return false;
        }
        boolean hasCubes = model.bones.stream().anyMatch(b -> !b.cubes.isEmpty());
        boolean rowsReady = model.skinnedMesh != null
                && model.skinnedMesh.weightBones.size() >= model.skinnedMesh.vertices.size();
        boolean sourceReady = !hasCubes
                || model.skinnedMesh != null
                && model.skinnedMesh.sourceBones.size() == model.skinnedMesh.vertices.size();
        if (rowsReady && sourceReady) {
            return true;
        }
        SkinWeightGenerator.Result r = SkinWeightGenerator.generate(model);
        if (!r.ok) {
            status = "绑定需要网格/权重，生成失败：" + r.error;
            return false;
        }
        return true;
    }

    private static boolean hasInfluenceShape(ParsedBone bone) {
        for (ParsedBone b = bone; b != null; b = b.parent) {
            if (!b.cubes.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 视口刷选：把投影距离笔刷半径内的顶点写入当前骨骼的 bind_bones。
     * 只有当前骨骼这一层是手动的；相邻关节权重仍由自动蒙皮计算。
     */
    private void applyBindBrush(double mx, double my, boolean erase) {
        ParsedBone bone = selectedBone();
        if (bone == null) {
            status = "先选中一根骨骼，再刷选绑定";
            return;
        }
        if (!hasInfluenceShape(bone)) {
            status = "该骨骼及父链没有 cube，无法作为绑定目标";
            return;
        }
        if (!ensureMeshForBinding()) {
            return;
        }
        var mesh = model.skinnedMesh;
        while (mesh.bindBones.size() < mesh.vertices.size()) {
            mesh.bindBones.add("");
        }
        double cx = (vx0 + vx1) / 2.0;
        double cy = (vy0 + vy1) / 2.0;
        int changed = 0;
        int total = 0;
        double r2 = BIND_BRUSH_RADIUS * (double) BIND_BRUSH_RADIUS;
        for (int vi = 0; vi < mesh.vertices.size(); vi++) {
            var v = mesh.vertices.get(vi);
            float[] sp = preview.project(model, v.x, v.y, v.z, cx, cy);
            double dx = sp[0] - mx;
            double dy = sp[1] - my;
            if (dx * dx + dy * dy <= r2) {
                String neu = erase ? "" : bone.name;
                if (!java.util.Objects.equals(mesh.bindBones.get(vi), neu)) {
                    mesh.bindBones.set(vi, neu);
                    changed++;
                }
            }
            if (!mesh.bindBones.get(vi).isBlank()) {
                total++;
            }
        }
        if (changed == 0) {
            return;
        }
        SkinWeightGenerator.Result r = SkinWeightGenerator.generate(model);
        if (!r.ok) {
            status = "绑定后重算权重失败：" + r.error;
            return;
        }
        status = (erase ? "擦除绑定 " : "绑定 ") + bone.name
                + "：本次 " + changed + " 顶点，当前 " + total + " 个手动绑定";
    }

    private void drawBindingOverlay(GuiGraphics g, int mouseX, int mouseY) {
        if (toolMode != 3) {
            return;
        }
        g.enableScissor(sx(vx0 + 1), sy(vy0 + 1), sx(vx1 - 1), sy(vy1 - 1));
        var mesh = model == null ? null : model.skinnedMesh;
        if (mesh != null && mesh.bindBones.size() == mesh.vertices.size()) {
            double cx = (vx0 + vx1) / 2.0;
            double cy = (vy0 + vy1) / 2.0;
            for (int vi = 0; vi < mesh.vertices.size(); vi++) {
                if (mesh.bindBones.get(vi).isBlank()) {
                    continue;
                }
                var v = mesh.vertices.get(vi);
                float[] sp = preview.project(model, v.x, v.y, v.z, cx, cy);
                if (sp[0] >= vx0 && sp[0] <= vx1 && sp[1] >= vy0 && sp[1] <= vy1) {
                    g.fill((int) sp[0] - 1, (int) sp[1] - 1,
                            (int) sp[0] + 2, (int) sp[1] + 2, 0xCC55FFFF);
                }
            }
        }
        int color = hasShiftDown() ? 0xCCFF5555 : 0xCC55FFFF;
        for (int i = 0; i < 40; i++) {
            double a = Math.PI * 2 * i / 40.0;
            int x = mouseX + (int) (Math.cos(a) * BIND_BRUSH_RADIUS);
            int y = mouseY + (int) (Math.sin(a) * BIND_BRUSH_RADIUS);
            g.fill(x - 1, y - 1, x + 1, y + 1, color);
        }
        g.disableScissor();
    }

    private int inspectorTab = 0;
    private EditBox cooldownBox;
    private EditBox iframeStartBox;
    private EditBox iframeEndBox;
    private EditBox hbStartBox;
    private EditBox hbEndBox;
    private EditBox hbDamageBox;
    private EditBox hbMaxBox;
    private int inspectorHitbox = 0;
    private boolean hitboxEdit;
    private boolean hitboxDrag;
    private int hitboxFaceAxis = -1;
    private int hitboxFaceSign = 1;
    private double hitboxLastX;
    private double hitboxLastY;
    private boolean inspectorUpdating = false;
    private EditBox layerClipBox;
    private EditBox layerZoneBox;
    private EditBox layerWeightBox;
    private EditBox layerStartBox;
    private EditBox layerEnterBox;
    private EditBox layerExitBox;
    private int inspectorSegment = 0;
    private int dragSegment = -1;
    private double dragSegmentMouseX;
    private float dragSegmentStart;
    private EditBox heldItemBox;
    private EditBox heldBoneBox;
    private int inspectorHeld = 0;
    private EditBox comboNameBox;
    private EditBox comboActionBox;
    private EditBox comboParentBox;
    private EditBox comboInputBox;
    private EditBox comboWinStartBox;
    private EditBox comboWinEndBox;
    private int inspectorChain = 0;
    private int inspectorNode = 0;
    private EditBox bindWeaponBox;
    private EditBox bindEntityBox;

    private void buildInspectorButtons() {
        int x = ix0 + 6;
        int w = (ix1 - ix0 - 12) / 6;
        String[] labels = {"动作", "战斗", "连段", "手持", "层", "绑定"};
        for (int i = 0; i < labels.length; i++) {
            final int tab = i;
            this.addRenderableWidget(new EditorButton(x + i * w, iy0 + 20, w - 1, 16,
                    Component.literal(labels[i]), b -> {
                        inspectorTab = tab;
                        if (tab == 5) {
                            openBinding();
                        } else {
                            setInspectorTab(tab);
                        }
                    }, i == inspectorTab));
        }
        buildInspectorFields();
        this.addRenderableWidget(new EditorButton(ix0 + 6, iy1 - 44, ix1 - ix0 - 12, 18,
                Component.translatable("gui.combatoverhaul.save_model"), b -> saveModel()));
        this.addRenderableWidget(new EditorButton(ix0 + 6, iy1 - 22, ix1 - ix0 - 12, 18,
                Component.translatable("gui.combatoverhaul.save_actions"), b -> saveActions()));
        this.addRenderableWidget(new EditorButton(ix0 + 6, iy1 - 68, ix1 - ix0 - 12, 18,
                Component.literal("完整面板"), b -> openInspectorFull(), true));
    }

    private void buildInspectorFields() {
        int x = ix0 + 8;
        int y = iy0 + 112;
        int w = (ix1 - ix0 - 20) / 2;
        cooldownBox = box(x, y, w);
        iframeStartBox = box(x, y + 30, w);
        iframeEndBox = box(x + w + 4, y + 30, w);
        hbStartBox = box(x, y + 76, w);
        hbEndBox = box(x + w + 4, y + 76, w);
        hbDamageBox = box(x, y + 106, w);
        hbMaxBox = box(x + w + 4, y + 106, w);
        this.addRenderableWidget(new EditorButton(x, y + 52, w, 16, Component.literal("判定框 <"),
                b -> cycleInspectorHitbox(-1)));
        this.addRenderableWidget(new EditorButton(x + w + 4, y + 52, w, 16, Component.literal("判定框 >"),
                b -> cycleInspectorHitbox(1)));
        this.addRenderableWidget(new EditorButton(x, y + 126, w, 16, Component.literal("+ 判定"),
                b -> addInspectorHitbox()));
        this.addRenderableWidget(new EditorButton(x + w + 4, y + 126, w, 16, Component.literal("- 判定"),
                b -> removeInspectorHitbox()));
        this.addRenderableWidget(new EditorButton(x, y + 148, w * 2 + 4, 16,
                Component.literal("判定框拖动"), b -> {
                    hitboxEdit = !hitboxEdit;
                    status = hitboxEdit ? "判定框拖动：开；在视口左键拖判定框中心" : "判定框拖动：关";
                }));

        int ly = iy0 + 236;
        layerClipBox = box(x, ly, w * 2 + 4);
        layerZoneBox = box(x, ly + 26, w);
        layerWeightBox = box(x + w + 4, ly + 26, w);
        layerStartBox = box(x, ly + 52, w);
        layerEnterBox = box(x + w + 4, ly + 52, w);
        layerExitBox = box(x, ly + 78, w);
        this.addRenderableWidget(new EditorButton(x, ly + 100, w, 16, Component.literal("层 <"),
                b -> cycleInspectorSegment(-1)));
        this.addRenderableWidget(new EditorButton(x + w + 4, ly + 100, w, 16, Component.literal("层 >"),
                b -> cycleInspectorSegment(1)));
        this.addRenderableWidget(new EditorButton(x, ly + 120, w, 16, Component.literal("+ 层"),
                b -> addInspectorSegment()));
        this.addRenderableWidget(new EditorButton(x + w + 4, ly + 120, w, 16, Component.literal("- 层"),
                b -> removeInspectorSegment()));
        layerClipBox.setResponder(v -> withInspectorSegment(s -> s.clip = v.trim()));
        layerZoneBox.setResponder(v -> withInspectorSegment(s -> s.zone = v.trim()));
        layerWeightBox.setResponder(v -> withInspectorSegment(s -> s.weight = parse(v, s.weight, 0, 4)));
        layerStartBox.setResponder(v -> withInspectorSegment(s -> s.startTick = parse(v, s.startTick, 0, 10000)));
        layerEnterBox.setResponder(v -> withInspectorSegment(s -> {
            if (s.blend == null) s.blend = new ActionStore.Blend();
            s.blend.enterTicks = parse(v, s.blend.enterTicks, 0, 1000);
        }));
        layerExitBox.setResponder(v -> withInspectorSegment(s -> {
            if (s.blend == null) s.blend = new ActionStore.Blend();
            s.blend.exitTicks = parse(v, s.blend.exitTicks, 0, 1000);
        }));

        int hy = iy0 + 300;
        heldItemBox = box(x, hy, w * 2 + 4);
        heldBoneBox = box(x, hy + 26, w * 2 + 4);
        this.addRenderableWidget(new EditorButton(x, hy + 46, w, 16, Component.literal("手持 <"),
                b -> cycleInspectorHeld(-1)));
        this.addRenderableWidget(new EditorButton(x + w + 4, hy + 46, w, 16, Component.literal("手持 >"),
                b -> cycleInspectorHeld(1)));
        this.addRenderableWidget(new EditorButton(x, hy + 66, w, 16, Component.literal("+ 手持"),
                b -> addInspectorHeld()));
        this.addRenderableWidget(new EditorButton(x + w + 4, hy + 66, w, 16, Component.literal("- 手持"),
                b -> removeInspectorHeld()));
        heldItemBox.setResponder(v -> withInspectorHeld(h -> h.item = v.trim()));
        heldBoneBox.setResponder(v -> withInspectorHeld(h -> h.bone = v.trim()));

        int cy = iy0 + 270;
        comboNameBox = box(x, cy, w * 2 + 4);
        comboActionBox = box(x, cy + 26, w * 2 + 4);
        comboParentBox = box(x, cy + 52, w);
        comboInputBox = box(x + w + 4, cy + 52, w);
        comboWinStartBox = box(x, cy + 78, w);
        comboWinEndBox = box(x + w + 4, cy + 78, w);
        this.addRenderableWidget(new EditorButton(x, cy + 100, w, 16, Component.literal("链 <"),
                b -> cycleInspectorChain(-1)));
        this.addRenderableWidget(new EditorButton(x + w + 4, cy + 100, w, 16, Component.literal("链 >"),
                b -> cycleInspectorChain(1)));
        this.addRenderableWidget(new EditorButton(x, cy + 120, w, 16, Component.literal("+ 链"),
                b -> addInspectorChain()));
        this.addRenderableWidget(new EditorButton(x + w + 4, cy + 120, w, 16, Component.literal("- 链"),
                b -> removeInspectorChain()));
        this.addRenderableWidget(new EditorButton(x, cy + 140, w, 16, Component.literal("+ 节点"),
                b -> addInspectorNode()));
        this.addRenderableWidget(new EditorButton(x + w + 4, cy + 140, w, 16, Component.literal("- 节点"),
                b -> removeInspectorNode()));
        this.addRenderableWidget(new EditorButton(x, cy + 160, w * 2 + 4, 16,
                Component.literal("保存连段"), b -> saveCombo()));
        comboNameBox.setResponder(v -> withCombo(c -> c.name = v.trim()));
        comboActionBox.setResponder(v -> withComboNode(n -> n.action = v.trim()));
        comboParentBox.setResponder(v -> withComboNode(n -> n.parent = (int) parse(v, n.parent, -1, 10000)));
        comboInputBox.setResponder(v -> withComboNode(n -> n.input = v.trim()));
        comboWinStartBox.setResponder(v -> withComboNode(n -> n.windowStart = parse(v, n.windowStart, 0, 10000)));
        comboWinEndBox.setResponder(v -> withComboNode(n -> n.windowEnd = parse(v, n.windowEnd, 0, 10000)));

        int by = iy0 + 220;
        bindWeaponBox = box(x, by, w * 2 + 4);
        bindEntityBox = box(x, by + 26, w * 2 + 4);
        this.addRenderableWidget(new EditorButton(x, by + 46, w, 16, Component.literal("+ 武器"),
                b -> editBinding(true, true)));
        this.addRenderableWidget(new EditorButton(x + w + 4, by + 46, w, 16, Component.literal("- 武器"),
                b -> editBinding(true, false)));
        this.addRenderableWidget(new EditorButton(x, by + 66, w, 16, Component.literal("+ 实体"),
                b -> editBinding(false, true)));
        this.addRenderableWidget(new EditorButton(x + w + 4, by + 66, w, 16, Component.literal("- 实体"),
                b -> editBinding(false, false)));

        // Action Tab 的常用动作操作（放到动作摘要下方，避免和文字重叠）
        int ay = iy0 + 140;
        this.addRenderableWidget(new EditorButton(x, ay, w, 16, Component.literal("新建动作"),
                b -> newAction()));
        this.addRenderableWidget(new EditorButton(x + w + 4, ay, w, 16, Component.literal("删除动作"),
                b -> deleteAction()));
        this.addRenderableWidget(new EditorButton(x, ay + 20, w, 16, Component.literal("长度 -"),
                b -> changeLength(-1)));
        this.addRenderableWidget(new EditorButton(x + w + 4, ay + 20, w, 16, Component.literal("长度 +"),
                b -> changeLength(1)));
        this.addRenderableWidget(new EditorButton(x, ay + 40, w, 16, Component.literal("循环"),
                b -> toggleLoop()));
        this.addRenderableWidget(new EditorButton(x + w + 4, ay + 40, w, 16, Component.literal("追踪"),
                b -> toggleTrack()));
        btnBoneTrack = this.addRenderableWidget(new EditorButton(x, ay + 60, w * 2 + 4, 16,
                Component.literal(""), b -> toggleBoneTrack()));
        refreshBoneTrackLabel();
        this.addRenderableWidget(new EditorButton(x, ay + 80, w * 2 + 4, 16,
                Component.literal("自动蒙皮"), b -> autoSkin()));
        cooldownBox.setResponder(v -> withInspector(a -> {
            if (!inspectorUpdating) a.cooldownTicks = (int) parse(v, a.cooldownTicks, 0, 10000);
        }));
        iframeStartBox.setResponder(v -> withInspectorIframes(f ->
                f.startTick = parse(v, f.startTick, 0, 10000)));
        iframeEndBox.setResponder(v -> withInspectorIframes(f ->
                f.endTick = parse(v, f.endTick, 0, 10000)));
        hbStartBox.setResponder(v -> withInspectorHitbox(h -> h.startTick = parse(v, h.startTick, 0, 10000)));
        hbEndBox.setResponder(v -> withInspectorHitbox(h -> h.endTick = parse(v, h.endTick, 0, 10000)));
        hbDamageBox.setResponder(v -> withInspectorHitbox(h -> h.damageScale = parse(v, h.damageScale, 0, 100)));
        hbMaxBox.setResponder(v -> withInspectorHitbox(h -> h.maxTargets =
                (int) parse(v, h.maxTargets, 0, 1000)));
        setInspectorTab(0);
    }

    private EditBox box(int x, int y, int w) {
        EditBox b = new EditBox(this.font, x, y, w, 14, Component.literal(""));
        b.setMaxLength(12);
        this.addRenderableWidget(b);
        return b;
    }

    private static float parse(String text, float def, float min, float max) {
        try {
            return Math.max(min, Math.min(max, Float.parseFloat(text.trim())));
        } catch (RuntimeException e) {
            return def;
        }
    }

    private void withInspector(java.util.function.Consumer<ActionStore.Action> edit) {
        if (inspectorUpdating) return;
        ActionStore.Action a = curAction();
        if (a != null) edit.accept(a);
    }

    private void withInspectorHitbox(java.util.function.Consumer<ActionStore.HitboxDef> edit) {
        if (inspectorUpdating) return;
        ActionStore.Action a = curAction();
        if (a == null || a.hitboxes.isEmpty()) return;
        inspectorHitbox = Math.max(0, Math.min(inspectorHitbox, a.hitboxes.size() - 1));
        edit.accept(a.hitboxes.get(inspectorHitbox));
    }

    private void withInspectorIframes(java.util.function.Consumer<ActionStore.IframesDef> edit) {
        if (inspectorUpdating) return;
        ActionStore.Action a = curAction();
        if (a == null) return;
        if (a.iframes == null) a.iframes = new ActionStore.IframesDef();
        edit.accept(a.iframes);
    }

    private void cycleInspectorHitbox(int dir) {
        ActionStore.Action a = curAction();
        if (a == null || a.hitboxes.isEmpty()) return;
        inspectorHitbox = ((inspectorHitbox + dir) % a.hitboxes.size() + a.hitboxes.size()) % a.hitboxes.size();
        syncInspectorFields();
    }

    private void addInspectorHitbox() {
        ActionStore.Action a = curAction();
        if (a == null) return;
        ActionStore.HitboxDef h = new ActionStore.HitboxDef();
        h.name = "hitbox_" + a.hitboxes.size();
        h.startTick = 2;
        h.endTick = Math.max(3, a.lengthTicks - 2);
        a.hitboxes.add(h);
        inspectorHitbox = a.hitboxes.size() - 1;
        syncInspectorFields();
    }

    private void removeInspectorHitbox() {
        ActionStore.Action a = curAction();
        if (a == null || a.hitboxes.isEmpty()) return;
        a.hitboxes.remove(inspectorHitbox);
        inspectorHitbox = Math.max(0, Math.min(inspectorHitbox, a.hitboxes.size() - 1));
        syncInspectorFields();
    }

    private void withInspectorSegment(java.util.function.Consumer<ActionStore.Segment> edit) {
        if (inspectorUpdating) return;
        ActionStore.Action a = curAction();
        if (a == null || a.segments.isEmpty()) return;
        inspectorSegment = Math.max(0, Math.min(inspectorSegment, a.segments.size() - 1));
        edit.accept(a.segments.get(inspectorSegment));
    }

    private void cycleInspectorSegment(int dir) {
        ActionStore.Action a = curAction();
        if (a == null || a.segments.isEmpty()) return;
        inspectorSegment = ((inspectorSegment + dir) % a.segments.size() + a.segments.size()) % a.segments.size();
        syncInspectorFields();
    }

    private void addInspectorSegment() {
        ActionStore.Action a = curAction();
        if (a == null) return;
        ActionStore.Segment s = new ActionStore.Segment();
        for (ActionStore.Action other : actions) {
            if (other != a && !other.internal) {
                s.clip = other.name;
                break;
            }
        }
        a.segments.add(s);
        inspectorSegment = a.segments.size() - 1;
        syncInspectorFields();
    }

    private void removeInspectorSegment() {
        ActionStore.Action a = curAction();
        if (a == null || a.segments.isEmpty()) return;
        a.segments.remove(inspectorSegment);
        inspectorSegment = Math.max(0, Math.min(inspectorSegment, a.segments.size() - 1));
        syncInspectorFields();
    }

    private void withInspectorHeld(java.util.function.Consumer<ParsedModel.HeldEntry> edit) {
        if (inspectorUpdating) return;
        if (model.heldItems.isEmpty()) return;
        inspectorHeld = Math.max(0, Math.min(inspectorHeld, model.heldItems.size() - 1));
        edit.accept(model.heldItems.get(inspectorHeld));
    }

    private void cycleInspectorHeld(int dir) {
        if (model.heldItems.isEmpty()) return;
        inspectorHeld = ((inspectorHeld + dir) % model.heldItems.size() + model.heldItems.size()) % model.heldItems.size();
        syncInspectorFields();
    }

    private void addInspectorHeld() {
        ParsedModel.HeldEntry he = new ParsedModel.HeldEntry();
        he.item = "minecraft:iron_sword";
        ParsedBone b = selectedBone();
        he.bone = b != null ? b.name : (flatBones.isEmpty() ? "root" : flatBones.get(0).name);
        model.heldItems.add(he);
        inspectorHeld = model.heldItems.size() - 1;
        syncInspectorFields();
    }

    private void removeInspectorHeld() {
        if (model.heldItems.isEmpty()) return;
        model.heldItems.remove(inspectorHeld);
        inspectorHeld = Math.max(0, Math.min(inspectorHeld, model.heldItems.size() - 1));
        syncInspectorFields();
    }

    private com.yourname.combatoverhaul.combat.ComboStore.File comboFile() {
        return com.yourname.combatoverhaul.combat.ComboStore.get(
                com.yourname.combatoverhaul.combat.ActionCache.keyFor(modid, path, weapon));
    }

    private com.yourname.combatoverhaul.combat.ComboStore.Chain comboChain() {
        var f = comboFile();
        if (f.chains.isEmpty()) return null;
        inspectorChain = Math.max(0, Math.min(inspectorChain, f.chains.size() - 1));
        return f.chains.get(inspectorChain);
    }

    private com.yourname.combatoverhaul.combat.ComboStore.Node comboNode() {
        var c = comboChain();
        if (c == null || c.nodes.isEmpty()) return null;
        inspectorNode = Math.max(0, Math.min(inspectorNode, c.nodes.size() - 1));
        return c.nodes.get(inspectorNode);
    }

    private void withCombo(java.util.function.Consumer<com.yourname.combatoverhaul.combat.ComboStore.Chain> edit) {
        if (inspectorUpdating) return;
        var c = comboChain();
        if (c != null) edit.accept(c);
    }

    private void withComboNode(java.util.function.Consumer<com.yourname.combatoverhaul.combat.ComboStore.Node> edit) {
        if (inspectorUpdating) return;
        var n = comboNode();
        if (n != null) edit.accept(n);
    }

    private void cycleInspectorChain(int dir) {
        var f = comboFile();
        if (f.chains.isEmpty()) return;
        inspectorChain = ((inspectorChain + dir) % f.chains.size() + f.chains.size()) % f.chains.size();
        inspectorNode = 0;
        syncInspectorFields();
    }

    private void addInspectorChain() {
        var f = comboFile();
        var c = new com.yourname.combatoverhaul.combat.ComboStore.Chain();
        c.name = "chain_" + f.chains.size();
        f.chains.add(c);
        inspectorChain = f.chains.size() - 1;
        inspectorNode = 0;
        syncInspectorFields();
    }

    private void removeInspectorChain() {
        var f = comboFile();
        if (f.chains.isEmpty()) return;
        f.chains.remove(inspectorChain);
        inspectorChain = Math.max(0, Math.min(inspectorChain, f.chains.size() - 1));
        inspectorNode = 0;
        syncInspectorFields();
    }

    private void addInspectorNode() {
        var c = comboChain();
        if (c == null) return;
        var n = new com.yourname.combatoverhaul.combat.ComboStore.Node();
        n.action = actions.isEmpty() ? "" : actions.get(0).name;
        n.parent = c.nodes.isEmpty() ? -1 : 0;
        n.input = "attack";
        n.windowStart = 0;
        n.windowEnd = Math.max(1, actionLength());
        c.nodes.add(n);
        inspectorNode = c.nodes.size() - 1;
        syncInspectorFields();
    }

    private void removeInspectorNode() {
        var c = comboChain();
        if (c == null || c.nodes.isEmpty()) return;
        c.nodes.remove(inspectorNode);
        inspectorNode = Math.max(0, Math.min(inspectorNode, c.nodes.size() - 1));
        syncInspectorFields();
    }

    private void saveCombo() {
        boolean ok = com.yourname.combatoverhaul.combat.ComboStore.write(modid, path, weapon, comboFile());
        if (ok) {
            com.yourname.combatoverhaul.combat.ComboStore.invalidate(modid, path, weapon);
        }
        status = Component.translatable(ok
                ? "gui.combatoverhaul.saved_actions"
                : "gui.combatoverhaul.write_failed").getString();
    }

    private void editBinding(boolean weaponList, boolean add) {
        ActionStore.Action a = curAction();
        if (a == null) return;
        String id = (weaponList ? bindWeaponBox.getValue() : bindEntityBox.getValue()).trim();
        if (id.isEmpty()) return;
        List<String> list = weaponList ? a.weapons : a.entities;
        if (add) {
            if (!list.contains(id)) list.add(id);
        } else {
            list.remove(id);
        }
        syncInspectorFields();
    }

    private void setInspectorTab(int tab) {
        inspectorTab = tab;
        boolean combat = tab == 1;
        boolean layers = tab == 4;
        boolean held = tab == 3;
        boolean combo = tab == 2;
        boolean bind = tab == 5;
        boolean actionTab = tab == 0;        EditBox[] combatBoxes = {cooldownBox, iframeStartBox, iframeEndBox, hbStartBox, hbEndBox, hbDamageBox, hbMaxBox};
        for (EditBox b : combatBoxes) if (b != null) b.visible = combat;
        EditBox[] layerBoxes = {layerClipBox, layerZoneBox, layerWeightBox, layerStartBox, layerEnterBox, layerExitBox};
        for (EditBox b : layerBoxes) if (b != null) b.visible = layers;
        EditBox[] heldBoxes = {heldItemBox, heldBoneBox};
        for (EditBox b : heldBoxes) if (b != null) b.visible = held;
        EditBox[] comboBoxes = {comboNameBox, comboActionBox, comboParentBox, comboInputBox,
                comboWinStartBox, comboWinEndBox};
        for (EditBox b : comboBoxes) if (b != null) b.visible = combo;
        bindWeaponBox.visible = bind;
        bindEntityBox.visible = bind;
        // hitbox / layer / held / combo 操作按钮由 Screen 统一渲染，按 tab 过滤
        for (var child : this.children()) {
            if (child instanceof EditorButton eb) {
                String s = eb.getMessage().getString();
                if (s.contains("判定")) {
                    eb.visible = combat;
                } else if (s.startsWith("层 <") || s.startsWith("层 >")
                        || s.startsWith("+ 层") || s.startsWith("- 层")) {
                    eb.visible = layers;
                } else if (s.startsWith("手持 <") || s.startsWith("手持 >")
                        || s.startsWith("+ 手持") || s.startsWith("- 手持")) {
                    eb.visible = held;
                } else if (s.contains("链") || s.contains("节点") || s.contains("保存连段")) {
                    eb.visible = combo;
                } else if (s.startsWith("+ 武器") || s.startsWith("- 武器")
                        || s.startsWith("+ 实体") || s.startsWith("- 实体")) {
                    eb.visible = bind;
                } else if (s.equals("新建动作") || s.equals("删除动作")
                        || s.equals("长度 -") || s.equals("长度 +")
                        || s.equals("循环") || s.equals("追踪")
                        || s.equals("自动蒙皮")
                        || s.startsWith("骨骼追踪")) {
                    eb.visible = actionTab;
                } else if (s.contains("完整面板")) {
                    eb.visible = true;
                }
            }
        }
        syncInspectorFields();
    }

    private void syncInspectorFields() {
        if (cooldownBox == null) return;
        inspectorUpdating = true;
        ActionStore.Action a = curAction();
        cooldownBox.setValue(a == null ? "0" : String.valueOf(a.cooldownTicks));
        iframeStartBox.setValue(a == null || a.iframes == null ? "0" : String.valueOf(a.iframes.startTick));
        iframeEndBox.setValue(a == null || a.iframes == null ? "0" : String.valueOf(a.iframes.endTick));
        ActionStore.HitboxDef h = null;
        if (a != null && !a.hitboxes.isEmpty()) {
            inspectorHitbox = Math.max(0, Math.min(inspectorHitbox, a.hitboxes.size() - 1));
            h = a.hitboxes.get(inspectorHitbox);
        }
        hbStartBox.setValue(h == null ? "0" : String.valueOf(h.startTick));
        hbEndBox.setValue(h == null ? "0" : String.valueOf(h.endTick));
        hbDamageBox.setValue(h == null ? "1" : String.valueOf(h.damageScale));
        hbMaxBox.setValue(h == null ? "0" : String.valueOf(h.maxTargets));
        ActionStore.Segment s = null;
        if (a != null && !a.segments.isEmpty()) {
            inspectorSegment = Math.max(0, Math.min(inspectorSegment, a.segments.size() - 1));
            s = a.segments.get(inspectorSegment);
        }
        layerClipBox.setValue(s == null ? "" : s.clip);
        layerZoneBox.setValue(s == null || s.zone == null ? "" : s.zone);
        layerWeightBox.setValue(s == null ? "1" : String.valueOf(s.weight));
        layerStartBox.setValue(s == null ? "0" : String.valueOf(s.startTick));
        layerEnterBox.setValue(s == null || s.blend == null ? "0" : String.valueOf(s.blend.enterTicks));
        layerExitBox.setValue(s == null || s.blend == null ? "0" : String.valueOf(s.blend.exitTicks));
        ParsedModel.HeldEntry he = null;
        if (!model.heldItems.isEmpty()) {
            inspectorHeld = Math.max(0, Math.min(inspectorHeld, model.heldItems.size() - 1));
            he = model.heldItems.get(inspectorHeld);
        }
        heldItemBox.setValue(he == null ? "" : he.item);
        heldBoneBox.setValue(he == null ? "" : he.bone);
        var cf = comboFile();
        var cc = cf.chains.isEmpty() ? null : cf.chains.get(Math.max(0, Math.min(inspectorChain, cf.chains.size() - 1)));
        var cn = cc == null || cc.nodes.isEmpty() ? null
                : cc.nodes.get(Math.max(0, Math.min(inspectorNode, cc.nodes.size() - 1)));
        comboNameBox.setValue(cc == null ? "" : cc.name);
        comboActionBox.setValue(cn == null ? "" : cn.action);
        comboParentBox.setValue(cn == null ? "-1" : String.valueOf(cn.parent));
        comboInputBox.setValue(cn == null ? "attack" : cn.input);
        comboWinStartBox.setValue(cn == null ? "0" : String.valueOf(cn.windowStart));
        comboWinEndBox.setValue(cn == null ? "0" : String.valueOf(cn.windowEnd));
        inspectorUpdating = false;
    }

    private void openInspectorFull() {
        switch (inspectorTab) {
            case 1 -> openCombatPanel();
            case 2 -> openComboPanel();
            case 3 -> openHeldItem();
            case 4 -> openCompositePanel();
            case 5 -> openBinding();
            default -> {
            }
        }
    }

    private void openBinding() {
        if (!saveModel() || !saveActions()) {
            return;
        }
        this.minecraft.setScreen(new ActionBindingScreen(this, modid, path, weapon, actionIndex));
    }

    private void buildBoneButtons() {
        int y = pageH - 114;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.add_bone"), b -> addBone())
                .bounds(10, y, 52, 16).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.del_bone"), b -> deleteBone())
                .bounds(66, y, 52, 16).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.add_joint"), b -> addJoint())
                .bounds(122, y, 52, 16).build());
        // 线框描边开关（默认开）
        this.addRenderableWidget(Button.builder(outlineLabel(), b -> {
            outlineEnabled = !outlineEnabled;
            preview.showBoneOutline = outlineEnabled;
            b.setMessage(outlineLabel());
        }).bounds(10, y + 18, 90, 16).build());
        // 视口工具切换：视角 / 骨骼 / 锚点（循环）
        btnTool = this.addRenderableWidget(Button.builder(toolLabel(), b -> cycleTool())
                .bounds(104, y + 18, 72, 16).build());
        btnSkinning = this.addRenderableWidget(Button.builder(skinningLabel(), b -> toggleSkinning())
                .bounds(180, y + 18, 70, 16).build());
        // 父级挂接行：输入目标骨骼名（空=脱离为根），保持世界位置
        parentBox = new EditBox(this.font, 10, y + 36, 84, 14, Component.literal(""));
        parentBox.setMaxLength(64);
        parentBox.setHint(Component.translatable("gui.combatoverhaul.parent_hint"));
        this.addRenderableWidget(parentBox);
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.attach"), b -> attachParent())
                .bounds(98, y + 36, 46, 14).build());
        // 跟随：让当前骨骼及相对路径子树完全镜像目标骨骼（含后续同步分段）
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.follow"),
                        b -> applyFollow())
                .bounds(148, y + 36, 40, 14).build());
        // 枢轴中轴锁定：开启后枢轴工具拖动只能沿骨骼中轴线滑动（几何保持原位）
        btnAxisLock = this.addRenderableWidget(Button.builder(axisLockLabel(), b -> {
            axisLock = !axisLock;
            b.setMessage(axisLockLabel());
        }).bounds(10, y + 54, 90, 16).build());
        // 复位：选中骨骼旋转归零（关键帧模式同时位移归零、缩放归一）；手持节点恢复自动挂点与零旋转
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.reset"), b -> resetSelected())
                .bounds(104, y + 54, 62, 16).build());
        // 骨骼追踪标签：移到 Inspector/Action 页，避免和底部 transport 重叠
    }

    private Button btnSkinning;

    private boolean modelHasCubes() {
        return model != null && model.bones.stream().anyMatch(b -> !b.cubes.isEmpty());
    }

    private Component skinningLabel() {
        if (model == null || model.skinnedMesh == null) {
            return Component.literal("蒙皮：无");
        }
        if (!modelHasCubes()) {
            return Component.literal("蒙皮：必需");
        }
        return Component.literal(preview.skinningEnabled ? "蒙皮：开" : "蒙皮：关");
    }

    private void refreshSkinningLabel() {
        if (btnSkinning != null) {
            btnSkinning.setMessage(skinningLabel());
        }
    }

    private void toggleSkinning() {
        if (model == null || model.skinnedMesh == null) {
            status = "没有蒙皮数据：先点“自动蒙皮”生成";
            return;
        }
        if (!modelHasCubes()) {
            status = "该模型没有 cube，只能使用蒙皮渲染";
            return;
        }
        preview.skinningEnabled = !preview.skinningEnabled;
        refreshSkinningLabel();
        status = preview.skinningEnabled ? "蒙皮预览：开" : "蒙皮预览：关（显示方块模型）";
    }

    private Button btnAxisLock;
    private boolean axisLock = false;

    private Component axisLockLabel() {
        return Component.translatable(axisLock
                ? "gui.combatoverhaul.axis_lock_on" : "gui.combatoverhaul.axis_lock_off");
    }

    private boolean outlineEnabled = true;

    private Component outlineLabel() {
        return Component.translatable(outlineEnabled
                ? "gui.combatoverhaul.outline_on" : "gui.combatoverhaul.outline_off");
    }

    /** 工具循环：骨骼 → 锚点 → 枢轴 → 绑定（R 键同效）；视角旋转固定在视口右键拖拽 */
    private void cycleTool() {
        toolMode = (toolMode + 1) % 4;
        boneDragging = false;
        anchorDragging = false;
        pivotDragging = false;
        bindingDrag = false;
        refreshToolLabel();
        status = toolLabel().getString();
        if (toolMode == 3 && ensureMeshForBinding()) {
            status = "绑定模式：左键刷选当前骨骼，Shift+左键擦除";
        }
    }

    private void refreshToolLabel() {
        if (btnTool != null) {
            btnTool.setMessage(toolLabel());
        }
    }

    private Component toolLabel() {
        return Component.translatable(switch (toolMode) {
            case 1 -> "gui.combatoverhaul.tool_anchor";
            case 2 -> "gui.combatoverhaul.tool_pivot";
            case 3 -> "gui.combatoverhaul.tool_bind";
            default -> "gui.combatoverhaul.tool_bone";
        });
    }

    // ------------------------------------------------------------------ 手持物品

    /**
     * 打开手持物品选择器（配置当前选中骨骼自己的手持条目）。
     * 不同骨骼的手持互不影响：A 骨骼持 A 物品后，选中 B 骨骼再配 B 物品，两件同时存在。
     * 武器模型编辑器无手持概念。
     */
    private void openHeldItem() {
        if (weapon) {
            status = Component.translatable("gui.combatoverhaul.held_no_weapon").getString();
            return;
        }
        ParsedBone bone = selectedBone();
        if (bone == null) {
            status = Component.translatable("gui.combatoverhaul.need_bone").getString();
            return;
        }
        final String boneName = bone.name;
        ParsedModel.HeldEntry cur = model.findHeld(boneName);
        this.minecraft.setScreen(new HeldItemScreen(this, boneName, cur != null ? cur.item : null, itemId -> {
            if (itemId == null) {
                model.clearHeld(boneName);
                status = Component.translatable("gui.combatoverhaul.held_cleared").getString();
            } else {
                model.setHeld(boneName, itemId); // 新配置挂点自动（包围盒最长轴远端，手掌端）
                status = Component.translatable("gui.combatoverhaul.held_set", itemId, boneName).getString();
            }
            refreshBoneSelection();
            // 选中刚配置/清除的骨骼行，便于继续调整
            for (int i = 0; i < treeRows.size(); i++) {
                Object row = treeRows.get(i);
                if (row instanceof ParsedModel.HeldEntry he && he.bone.equals(boneName)) {
                    selectedRow = i;
                    break;
                }
                if (row instanceof ParsedBone pb && pb.name.equals(boneName)) {
                    selectedRow = i;
                }
            }
            syncBoxesFromState();
        }));
    }

    /** 手持预览物品图标缓存（物品 id → ItemStack），随模型 held_items 逐帧取用 */
    private final java.util.Map<String, net.minecraft.world.item.ItemStack> heldStackCache = new java.util.HashMap<>();

    private net.minecraft.world.item.ItemStack heldStackFor(String itemId) {
        return heldStackCache.computeIfAbsent(itemId, id -> {
            try {
                var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                        new net.minecraft.resources.ResourceLocation(id));
                return item != null ? new net.minecraft.world.item.ItemStack(item)
                        : net.minecraft.world.item.ItemStack.EMPTY;
            } catch (RuntimeException e) {
                return net.minecraft.world.item.ItemStack.EMPTY;
            }
        });
    }

    /** 打开战斗编辑面板（判定框/倍率/无敌帧）；先保存模型+动作，避免切界面时丢掉未保存的几何编辑 */
    private void openCombatPanel() {
        if (!saveModel() || !saveActions()) {
            return;
        }
        this.minecraft.setScreen(new CombatPanelScreen(this.parent, modid, path, weapon, actionIndex));
    }

    /** 打开组合动作编辑（segments/zone/weight/blend）；改动直接写回内存 actions。 */
    private void openCompositePanel() {
        if (curAction() == null) {
            status = Component.translatable("gui.combatoverhaul.need_action").getString();
            return;
        }
        if (!saveModel()) {
            return;
        }
        this.minecraft.setScreen(new CompositeActionScreen(this, modid, path, weapon, actions, actionIndex));
    }

    /** 打开连段编辑（树状动作链接 + 取消窗口）；先保存模型+动作，返回时重建编辑器重载 */
    private void openComboPanel() {
        if (!saveModel() || !saveActions()) {
            return;
        }
        this.minecraft.setScreen(new ComboPanelScreen(this.parent, modid, path, weapon));
    }

    private void buildTransport() {
        int y = pageH - 92;
        int x = vx0;
        btnPlay = this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.play"), b -> togglePlay())
                .bounds(x, y, 48, 16).build());
        x += 52;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.add_kf"), b -> addKeyframe())
                .bounds(x, y, 60, 16).build());
        x += 64;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.del_kf"), b -> deleteKeyframe())
                .bounds(x, y, 60, 16).build());
        x += 64;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.read"), b -> readValues())
                .bounds(x, y, 40, 16).build());
        x += 44;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.write"), b -> writeValues())
                .bounds(x, y, 40, 16).build());
        x += 44;
        btnMode = this.addRenderableWidget(Button.builder(Component.literal(""), b -> toggleMode())
                .bounds(x, y, 110, 16).build());
        refreshModeLabel();
        x += 114;
        // 锚点：把选中骨骼设为当前关键帧的不动点（初始位置=该骨骼当前世界位置，零偏移）
        btnAnchorSet = this.addRenderableWidget(Button.builder(
                Component.translatable("gui.combatoverhaul.anchor_set"), b -> setAnchor())
                .bounds(x, y, 58, 16).build());
        x += 62;
        btnAnchorClear = this.addRenderableWidget(Button.builder(
                Component.translatable("gui.combatoverhaul.anchor_clear"), b -> clearAnchor())
                .bounds(x, y, 58, 16).build());
        x += 62;
        // 立方体选择：< / 当前（骨骼 或 立方体 k/n，点击回到骨骼）/ >
        btnCubePrev = this.addRenderableWidget(Button.builder(Component.literal("<"), b -> cycleCube(-1))
                .bounds(x, y, 16, 16).build());
        x += 20;
        btnCubeSel = this.addRenderableWidget(Button.builder(
                Component.translatable("gui.combatoverhaul.cube_sel_bone"), b -> selectBoneMode())
                .bounds(x, y, 60, 16).build());
        x += 64;
        btnCubeNext = this.addRenderableWidget(Button.builder(Component.literal(">"), b -> cycleCube(1))
                .bounds(x, y, 16, 16).build());
    }

    private void buildBoxes() {
        int y = pageH - 65;
        for (int group = 0; group < 4; group++) {
            int gx = vx0 + 4 + group * 140;
            for (int j = 0; j < 3; j++) {
                EditBox box = new EditBox(this.font, gx + 26 + j * 36, y, 34, 14, Component.literal(""));
                box.setMaxLength(16);
                boxes[group * 3 + j] = box;
                this.addRenderableWidget(box);
            }
        }
    }

    // ------------------------------------------------------------------ 状态刷新

    private void refreshBoneSelection() {
        model.flatten();
        flatBones.clear();
        flatBones.addAll(model.bones);
        treeRows.clear();
        for (ParsedBone b : flatBones) {
            treeRows.add(b);
            ParsedModel.HeldEntry he = model.findHeld(b.name);
            if (he != null) {
                treeRows.add(he);
            }
        }
        if (selectedRow >= treeRows.size()) {
            selectedRow = treeRows.size() - 1;
        }
        if (selectedRow < 0 && !treeRows.isEmpty()) {
            selectedRow = 0;
        }
        clampSelectedCube();
        clampBoneScroll();
        refreshBoneTrackLabel();
    }

    private void clampBoneScroll() {
        int contentH = treeRows.size() * ROW_H;
        int panelH = Math.max(0, by1 - by0 - 4);
        boneScroll = Math.max(0, Math.min(boneScroll, Math.max(0, contentH - panelH)));
    }

    /** 当前选中行（骨骼或手持节点）；无选中返回 null */
    private Object selectedTreeRow() {
        if (selectedRow < 0 || selectedRow >= treeRows.size()) {
            return null;
        }
        return treeRows.get(selectedRow);
    }

    /** 当前选中的骨骼；选中手持节点时返回其挂接骨骼 */
    private ParsedBone selectedBone() {
        Object row = selectedTreeRow();
        if (row instanceof ParsedBone b) {
            return b;
        }
        if (row instanceof ParsedModel.HeldEntry he) {
            return model.findBone(he.bone);
        }
        return null;
    }

    /** 当前选中的手持节点；选中骨骼时返回 null */
    private ParsedModel.HeldEntry selectedHeld() {
        return selectedTreeRow() instanceof ParsedModel.HeldEntry he ? he : null;
    }

    /** 当前选中骨骼下、由 selectedCube 指定的立方体；未选或越界返回 null */
    private ParsedCube selectedCube() {
        if (selectedCube < 0) {
            return null;
        }
        ParsedBone b = selectedBone();
        if (b == null || selectedCube >= b.cubes.size()) {
            return null;
        }
        return b.cubes.get(selectedCube);
    }

    /** 选中项变化后修正立方体索引：越界或未选中骨骼时回退到骨骼模式 */
    private void clampSelectedCube() {
        if (selectedCube < 0) {
            return;
        }
        ParsedBone b = selectedBone();
        if (b == null || b.cubes.isEmpty()) {
            selectedCube = -1;
        } else if (selectedCube >= b.cubes.size()) {
            selectedCube = b.cubes.size() - 1;
        }
    }

    private ActionStore.Action curAction() {
        if (actionIndex < 0 || actionIndex >= actions.size()) {
            return null;
        }
        return actions.get(actionIndex);
    }

    private float actionLength() {
        ActionStore.Action a = curAction();
        return a != null ? Math.max(1, a.lengthTicks) : 1;
    }

    private void refreshLoopLabel() {
        ActionStore.Action a = curAction();
        btnLoop.setMessage(Component.translatable(a != null && a.loop
                ? "gui.combatoverhaul.loop_on" : "gui.combatoverhaul.loop_off"));
        refreshTrackLabel();
    }

    private void refreshTrackLabel() {
        if (btnTrack == null) {
            return;
        }
        ActionStore.Action a = curAction();
        btnTrack.setMessage(Component.translatable(a != null && a.trackPitch
                ? "gui.combatoverhaul.track_on" : "gui.combatoverhaul.track_off"));
        trackMaxBox.setValue(a != null && a.trackPitch ? fmt(a.maxPitchOffset) : "");
        trackMaxBox.setEditable(a != null && a.trackPitch);
    }

    private void toggleTrack() {
        ActionStore.Action a = curAction();
        if (a == null) {
            status = Component.translatable("gui.combatoverhaul.need_action").getString();
            return;
        }
        a.trackPitch = !a.trackPitch;
        refreshTrackLabel();
        status = btnTrack.getMessage().getString();
    }

    private void refreshBoneTrackLabel() {
        if (btnBoneTrack == null) {
            return;
        }
        ParsedBone b = selectedBone();
        btnBoneTrack.setMessage(Component.translatable(b != null && b.trackPitch
                ? "gui.combatoverhaul.bone_track_on" : "gui.combatoverhaul.bone_track_off"));
    }

    private void toggleBoneTrack() {
        ParsedBone b = selectedBone();
        if (b == null) {
            status = Component.translatable("gui.combatoverhaul.need_bone").getString();
            return;
        }
        b.trackPitch = !b.trackPitch;
        refreshBoneTrackLabel();
        status = btnBoneTrack.getMessage().getString();
    }

    private void refreshModeLabel() {
        btnMode.setMessage(Component.translatable(poseMode
                ? "gui.combatoverhaul.mode_pose" : "gui.combatoverhaul.mode_kf"));
    }

    private void refreshPlayLabel() {
        btnPlay.setMessage(Component.translatable(playing
                ? "gui.combatoverhaul.stop" : "gui.combatoverhaul.play"));
    }

    /** 把当前状态（姿态或关键帧通道）填入 9 个数值框；选中手持节点时填挂点偏移/附加旋转 */
    private void syncBoxesFromState() {
        clampSelectedCube();
        refreshCubeLabel();
        ParsedModel.HeldEntry held = selectedHeld();
        if (held != null) {
            // 手持节点：平移=挂点偏移（空=自动取手掌端），旋转=附加旋转°；缩放/锚点不适用
            for (int j = 0; j < 3; j++) {
                boxes[j].setValue(held.offset != null ? fmt(held.offset[j]) : "");
                boxes[3 + j].setValue(fmt(held.rotDeg != null ? held.rotDeg[j] : 0));
                boxes[6 + j].setValue("");
                boxes[9 + j].setValue("");
            }
            if (parentBox != null) {
                ParsedBone hb = model.findBone(held.bone);
                parentBox.setValue(hb != null && hb.parent != null ? hb.parent.name : "");
            }
            return;
        }
        ParsedCube cube = selectedCube();
        if (cube != null) {
            // 立方体模式：平移=位置(origin)，旋转=自身旋转°，缩放=尺寸，末组=旋转枢轴
            float[] org = {cube.originX, cube.originY, cube.originZ};
            float[] cr = {(float) Math.toDegrees(cube.rotX),
                    (float) Math.toDegrees(cube.rotY), (float) Math.toDegrees(cube.rotZ)};
            float[] sz = {cube.sizeX, cube.sizeY, cube.sizeZ};
            float[] pv = {cube.pivotX, cube.pivotY, cube.pivotZ};
            for (int j = 0; j < 3; j++) {
                boxes[j].setValue(fmt(org[j]));
                boxes[3 + j].setValue(fmt(cr[j]));
                boxes[6 + j].setValue(fmt(sz[j]));
                boxes[9 + j].setValue(fmt(pv[j]));
            }
            if (parentBox != null) {
                ParsedBone cb = selectedBone();
                parentBox.setValue(cb != null && cb.parent != null ? cb.parent.name : "");
            }
            return;
        }
        ParsedBone bone = selectedBone();
        float[][] v = new float[3][3];
        if (bone != null) {
            if (poseMode) {
                v[0] = new float[]{bone.pivotX, bone.pivotY, bone.pivotZ};
                v[1] = new float[]{(float) Math.toDegrees(bone.rotX),
                        (float) Math.toDegrees(bone.rotY), (float) Math.toDegrees(bone.rotZ)};
                v[2] = new float[]{bone.scaleX, bone.scaleY, bone.scaleZ};
            } else {
                ActionStore.Action a = curAction();
                ActionStore.Keyframe kf = a != null ? a.at(currentTick) : null;
                ActionStore.BoneChannel ch = kf != null ? kf.bones.get(bone.name) : null;
                if (ch != null) {
                    v[0] = ch.pos;
                    v[1] = ch.rotDeg;
                    v[2] = ch.scale;
                }
            }
        }
        for (int group = 0; group < 3; group++) {
            for (int j = 0; j < 3; j++) {
                boxes[group * 3 + j].setValue(fmt(v[group][j]));
            }
        }
        // 第 4 组：当前关键帧锚点目标位置（无锚点留空）
        ActionStore.Keyframe kf = poseMode ? null : currentKf();
        float[] ap = kf != null && kf.anchorBone != null ? kf.anchorPos : null;
        for (int j = 0; j < 3; j++) {
            boxes[9 + j].setValue(ap != null ? fmt(ap[j]) : "");
        }
        // 父级/跟随输入框随选中骨骼更新：有 follow 时显示目标，方便再次确认/清除
        if (parentBox != null) {
            if (bone != null && bone.follow != null && !bone.follow.isBlank()) {
                parentBox.setValue(bone.follow);
            } else {
                parentBox.setValue(bone != null && bone.parent != null ? bone.parent.name : "");
            }
        }
    }

    private static String fmt(float f) {
        return String.format(java.util.Locale.ROOT, "%.2f", f);
    }

    private static float fmtF(float f) {
        return Math.round(f * 100f) / 100f;
    }

    // ------------------------------------------------------------------ 动作操作

    private void cycleAction(int dir) {
        if (actions.isEmpty()) {
            return;
        }
        actionIndex = ((actionIndex + dir) % actions.size() + actions.size()) % actions.size();
        currentTick = 0;
        selectedKeyframe = -1;
        playing = false;
        refreshPlayLabel();
        refreshLoopLabel();
        syncBoxesFromState();
    }

    private void newAction() {
        ActionStore.Action a = new ActionStore.Action();
        int n = actions.size();
        while (findActionName("action_" + n)) {
            n++;
        }
        a.name = "action_" + n;
        a.lengthTicks = 10;
        actions.add(a);
        actionIndex = actions.size() - 1;
        currentTick = 0;
        selectedKeyframe = -1;
        playing = false;
        refreshPlayLabel();
        refreshLoopLabel();
        syncBoxesFromState();
    }

    private boolean findActionName(String name) {
        for (ActionStore.Action a : actions) {
            if (a.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private void deleteAction() {
        if (curAction() == null) {
            status = Component.translatable("gui.combatoverhaul.need_action").getString();
            return;
        }
        actions.remove(actionIndex);
        actionIndex = actions.isEmpty() ? -1 : Math.min(actionIndex, actions.size() - 1);
        currentTick = 0;
        selectedKeyframe = -1;
        playing = false;
        refreshPlayLabel();
        refreshLoopLabel();
        syncBoxesFromState();
    }

    private void changeLength(int delta) {
        ActionStore.Action a = curAction();
        if (a == null) {
            status = Component.translatable("gui.combatoverhaul.need_action").getString();
            return;
        }
        a.lengthTicks = Math.max(1, Math.min(1200, a.lengthTicks + delta));
        currentTick = Math.min(currentTick, a.lengthTicks);
        refreshLoopLabel();
    }

    private void toggleLoop() {
        ActionStore.Action a = curAction();
        if (a == null) {
            status = Component.translatable("gui.combatoverhaul.need_action").getString();
            return;
        }
        a.loop = !a.loop;
        refreshLoopLabel();
    }

    private void togglePlay() {
        if (curAction() == null) {
            status = Component.translatable("gui.combatoverhaul.need_action").getString();
            return;
        }
        playing = !playing;
        if (playing && currentTick >= actionLength()) {
            currentTick = 0;
        }
        refreshPlayLabel();
    }

    private void toggleMode() {
        poseMode = !poseMode;
        refreshModeLabel();
        syncBoxesFromState();
    }

    // ------------------------------------------------------------------ 关键帧操作

    private void addKeyframe() {
        ActionStore.Action a = curAction();
        if (a == null) {
            status = Component.translatable("gui.combatoverhaul.need_action").getString();
            return;
        }
        ActionStore.Keyframe kf = a.atOrCreate(fmtF(currentTick));
        a.sort();
        selectedKeyframe = a.keyframes.indexOf(kf);
        status = Component.translatable("gui.combatoverhaul.kf_added", fmt(kf.time)).getString();
    }

    private void deleteKeyframe() {
        ActionStore.Action a = curAction();
        ParsedBone bone = selectedBone();
        if (a == null || bone == null) {
            status = Component.translatable("gui.combatoverhaul.need_bone").getString();
            return;
        }
        ActionStore.Keyframe kf = a.at(fmtF(currentTick));
        if (kf == null) {
            status = Component.translatable("gui.combatoverhaul.no_kf_here").getString();
            return;
        }
        kf.bones.remove(bone.name);
        if (kf.isEmpty()) {
            a.keyframes.remove(kf);
        }
        selectedKeyframe = -1;
        status = Component.translatable("gui.combatoverhaul.kf_removed").getString();
        syncBoxesFromState();
    }

    /** 轴环拖动：修改选中骨骼当前关键帧的 rotDeg，并立即刷新预览。 */
    private void rotateSelectedBone(int axis, float deltaDeg) {
        ParsedBone bone = selectedBone();
        if (bone == null) {
            status = "先选中一根骨骼";
            return;
        }
        if (poseMode) {
            if (axis == 0) bone.rotX += Math.toRadians(deltaDeg);
            if (axis == 1) bone.rotY += Math.toRadians(deltaDeg);
            if (axis == 2) bone.rotZ += Math.toRadians(deltaDeg);
            syncBoxesFromState();
            return;
        }
        ActionStore.Action a = curAction();
        if (a == null) {
            status = "当前模型没有动作";
            return;
        }
        float t = fmtF(Math.max(0, Math.min(currentTick, a.lengthTicks)));
        ActionStore.Keyframe kf = a.atOrCreate(t);
        ActionStore.BoneChannel ch = kf.bones.get(bone.name);
        if (ch == null) {
            ch = new ActionStore.BoneChannel();
            ch.pos = new float[3];
            ch.rotDeg = new float[3];
            ch.scale = new float[]{1, 1, 1};
        }
        ch.rotDeg[axis] += deltaDeg;
        kf.bones.put(bone.name, ch);
        a.sort();
        selectedKeyframe = a.keyframes.indexOf(kf);
        ActionStore.apply(actions, actionIndex, currentTick, model);
        syncBoxesFromState();
        status = "旋转 " + bone.name + " " + (axis == 0 ? "X" : axis == 1 ? "Y" : "Z")
                + " = " + fmt(ch.rotDeg[axis]) + "°";
    }

    // ------------------------------------------------------------------ 锚点（不动点）

    /** 设锚点：当前关键帧记录选中骨骼为不动点，初始目标位置=该骨骼当前世界位置（零位移） */
    private void setAnchor() {
        ActionStore.Action a = curAction();
        ParsedBone bone = selectedBone();
        if (a == null) {
            status = Component.translatable("gui.combatoverhaul.need_action").getString();
            return;
        }
        if (bone == null) {
            status = Component.translatable("gui.combatoverhaul.need_bone").getString();
            return;
        }
        // 先采样当前姿态，保证世界位置与视口一致
        ActionStore.apply(actions, actionIndex, currentTick, model);
        float[] world = model.boneWorldPos(bone.name);
        if (world == null) {
            return;
        }
        ActionStore.Keyframe kf = a.atOrCreate(fmtF(Math.max(0, Math.min(currentTick, a.lengthTicks))));
        kf.anchorBone = bone.name;
        kf.anchorPos = world;
        a.sort();
        selectedKeyframe = a.keyframes.indexOf(kf);
        syncBoxesFromState();
        status = Component.translatable("gui.combatoverhaul.anchor_set_done", bone.name).getString();
    }

    /** 清除当前时刻关键帧的锚点 */
    private void clearAnchor() {
        ActionStore.Action a = curAction();
        if (a == null) {
            status = Component.translatable("gui.combatoverhaul.need_action").getString();
            return;
        }
        ActionStore.Keyframe kf = a.at(fmtF(currentTick));
        if (kf == null || kf.anchorBone == null) {
            status = Component.translatable("gui.combatoverhaul.anchor_none").getString();
            return;
        }
        kf.anchorBone = null;
        kf.anchorPos = null;
        if (kf.isEmpty()) {
            a.keyframes.remove(kf);
        }
        selectedKeyframe = -1;
        syncBoxesFromState();
        status = Component.translatable("gui.combatoverhaul.anchor_cleared").getString();
    }

    /** 当前时刻关键帧（无则 null） */
    private ActionStore.Keyframe currentKf() {
        ActionStore.Action a = curAction();
        return a != null ? a.at(fmtF(currentTick)) : null;
    }

    /** 读取：姿态模式读骨骼基础姿态，关键帧模式读当前时间关键帧通道 */
    private void readValues() {
        syncBoxesFromState();
        status = Component.translatable("gui.combatoverhaul.read_done").getString();
    }

    /** 写入：姿态模式写骨骼基础姿态，关键帧模式写当前时间关键帧通道（无则创建）；手持节点写挂点偏移/旋转 */
    private void writeValues() {
        ParsedModel.HeldEntry held = selectedHeld();
        if (held != null) {
            writeHeldValues(held);
            return;
        }
        ParsedCube cube = selectedCube();
        if (cube != null) {
            writeCubeValues(cube);
            return;
        }
        ParsedBone bone = selectedBone();
        if (bone == null) {
            status = Component.translatable("gui.combatoverhaul.need_bone").getString();
            return;
        }
        float[][] v = new float[3][3];
        for (int group = 0; group < 3; group++) {
            for (int j = 0; j < 3; j++) {
                try {
                    v[group][j] = Float.parseFloat(boxes[group * 3 + j].getValue().trim());
                } catch (NumberFormatException e) {
                    status = Component.translatable("gui.combatoverhaul.box_invalid").getString();
                    return;
                }
            }
        }
        if (poseMode) {
            bone.pivotX = v[0][0];
            bone.pivotY = v[0][1];
            bone.pivotZ = v[0][2];
            bone.rotX = (float) Math.toRadians(v[1][0]);
            bone.rotY = (float) Math.toRadians(v[1][1]);
            bone.rotZ = (float) Math.toRadians(v[1][2]);
            bone.scaleX = v[2][0] != 0 ? v[2][0] : 1;
            bone.scaleY = v[2][1] != 0 ? v[2][1] : 1;
            bone.scaleZ = v[2][2] != 0 ? v[2][2] : 1;
            status = Component.translatable("gui.combatoverhaul.write_pose_done").getString();
            return;
        }
        ActionStore.Action a = curAction();
        if (a == null) {
            status = Component.translatable("gui.combatoverhaul.need_action").getString();
            return;
        }
        float t = fmtF(Math.max(0, Math.min(currentTick, a.lengthTicks)));
        ActionStore.Keyframe kf = a.atOrCreate(t);
        ActionStore.BoneChannel ch = new ActionStore.BoneChannel();
        ch.pos = v[0].clone();
        ch.rotDeg = v[1].clone();
        ch.scale = new float[]{
                v[2][0] != 0 ? v[2][0] : 1,
                v[2][1] != 0 ? v[2][1] : 1,
                v[2][2] != 0 ? v[2][2] : 1};
        kf.bones.put(bone.name, ch);
        // 锚点目标位置（仅当该帧已设锚点时写入，避免无锚点时误读空框）
        if (kf.anchorBone != null) {
            float[] ap = new float[3];
            try {
                for (int j = 0; j < 3; j++) {
                    ap[j] = Float.parseFloat(boxes[9 + j].getValue().trim());
                }
                kf.anchorPos = ap;
            } catch (NumberFormatException e) {
                status = Component.translatable("gui.combatoverhaul.box_invalid").getString();
                return;
            }
        }
        a.sort();
        selectedKeyframe = a.keyframes.indexOf(kf);
        status = Component.translatable("gui.combatoverhaul.kf_added", bone.name + " @" + fmt(t)).getString();
    }

    /**
     * 写入手持节点：平移组=挂点偏移（三格全空=恢复自动手掌端；单项空按 0 计），
     * 旋转组=附加旋转°（全 0 时存 null）。随「保存模型」持久化。
     */
    private void writeHeldValues(ParsedModel.HeldEntry held) {
        String[] offText = new String[3];
        boolean offAllEmpty = true;
        for (int j = 0; j < 3; j++) {
            offText[j] = boxes[j].getValue().trim();
            offAllEmpty &= offText[j].isEmpty();
        }
        if (offAllEmpty) {
            held.offset = null;
        } else {
            float[] off = new float[3];
            for (int j = 0; j < 3; j++) {
                try {
                    off[j] = offText[j].isEmpty() ? 0 : Float.parseFloat(offText[j]);
                } catch (NumberFormatException e) {
                    status = Component.translatable("gui.combatoverhaul.box_invalid").getString();
                    return;
                }
            }
            held.offset = off;
        }
        float[] rot = new float[3];
        boolean rotAllZero = true;
        for (int j = 0; j < 3; j++) {
            String s = boxes[3 + j].getValue().trim();
            try {
                rot[j] = s.isEmpty() ? 0 : Float.parseFloat(s);
            } catch (NumberFormatException e) {
                status = Component.translatable("gui.combatoverhaul.box_invalid").getString();
                return;
            }
            rotAllZero &= rot[j] == 0;
        }
        held.rotDeg = rotAllZero ? null : rot;
        status = Component.translatable("gui.combatoverhaul.held_adjust_done", held.item).getString();
    }

    /**
     * 写入立方体（立方体模式）：平移组=位置(origin)，旋转组=自身旋转°，缩放组=尺寸（0 按 1 计），
     * 末组=旋转枢轴。已存储面顶点（导出实体模型会写 face_verts）随 origin/size 同步重映射，
     * 保证渲染端优先使用 face_verts 时同样生效。
     */
    private void writeCubeValues(ParsedCube cube) {
        float[][] v = new float[4][3];
        for (int g = 0; g < 4; g++) {
            for (int j = 0; j < 3; j++) {
                String s = boxes[g * 3 + j].getValue().trim();
                try {
                    v[g][j] = s.isEmpty() ? 0f : Float.parseFloat(s);
                } catch (NumberFormatException e) {
                    status = Component.translatable("gui.combatoverhaul.box_invalid").getString();
                    return;
                }
            }
        }
        float[] oldOrigin = {cube.originX, cube.originY, cube.originZ};
        float[] oldSize = {cube.sizeX, cube.sizeY, cube.sizeZ};
        cube.originX = v[0][0];
        cube.originY = v[0][1];
        cube.originZ = v[0][2];
        cube.rotX = (float) Math.toRadians(v[1][0]);
        cube.rotY = (float) Math.toRadians(v[1][1]);
        cube.rotZ = (float) Math.toRadians(v[1][2]);
        cube.sizeX = v[2][0] != 0 ? v[2][0] : 1;
        cube.sizeY = v[2][1] != 0 ? v[2][1] : 1;
        cube.sizeZ = v[2][2] != 0 ? v[2][2] : 1;
        cube.pivotX = v[3][0];
        cube.pivotY = v[3][1];
        cube.pivotZ = v[3][2];
        cube.remapFaceVerts(oldOrigin, oldSize);
        syncBoxesFromState();
        status = Component.translatable("gui.combatoverhaul.cube_write_done").getString();
    }

    /** 循环切换立方体选择：-1（骨骼）↔ 0..n-1 */
    private void cycleCube(int dir) {
        ParsedBone b = selectedBone();
        int n = b != null ? b.cubes.size() : 0;
        if (n == 0) {
            status = Component.translatable("gui.combatoverhaul.no_cubes").getString();
            return;
        }
        int cur = selectedCube < 0 ? -1 : Math.min(selectedCube, n - 1);
        int next = cur + dir;
        if (next < -1) {
            next = n - 1;
        }
        if (next >= n) {
            next = -1;
        }
        selectedCube = next;
        syncBoxesFromState();
    }

    /** 回到骨骼编辑模式 */
    private void selectBoneMode() {
        selectedCube = -1;
        syncBoxesFromState();
    }

    /** 刷新"骨骼/立方体"选择按钮的文字 */
    private void refreshCubeLabel() {
        if (btnCubeSel == null) {
            return;
        }
        ParsedBone b = selectedBone();
        int n = b != null ? b.cubes.size() : 0;
        if (selectedCube < 0 || n == 0) {
            btnCubeSel.setMessage(Component.translatable("gui.combatoverhaul.cube_sel_bone"));
        } else {
            btnCubeSel.setMessage(Component.translatable("gui.combatoverhaul.cube_sel_cube",
                    Math.min(selectedCube + 1, n), n));
        }
    }

    /**
     * 复位：选中骨骼/手持节点恢复默认姿态。
     * <ul>
     *   <li>手持节点：挂点偏移置空（恢复自动手掌端）、附加旋转置空；</li>
     *   <li>骨骼·姿态模式：旋转 XYZ 归零（不改动枢轴，避免几何移位）；</li>
     *   <li>骨骼·关键帧模式：当前时刻关键帧通道的旋转归零、位移归零、缩放归一。</li>
     * </ul>
     */
    private void resetSelected() {
        ParsedModel.HeldEntry held = selectedHeld();
        if (held != null) {
            held.offset = null;
            held.rotDeg = null;
            syncBoxesFromState();
            status = Component.translatable("gui.combatoverhaul.held_reset_done").getString();
            return;
        }
        ParsedBone bone = selectedBone();
        ParsedCube cube = selectedCube();
        if (cube != null) {
            // 立方体模式：自身旋转归零（不动位置/尺寸/枢轴）
            cube.rotX = 0;
            cube.rotY = 0;
            cube.rotZ = 0;
            syncBoxesFromState();
            status = Component.translatable("gui.combatoverhaul.cube_reset_done").getString();
            return;
        }
        if (bone == null) {
            status = Component.translatable("gui.combatoverhaul.need_bone").getString();
            return;
        }
        if (poseMode) {
            bone.rotX = 0;
            bone.rotY = 0;
            bone.rotZ = 0;
        } else {
            ActionStore.Action a = curAction();
            if (a == null) {
                status = Component.translatable("gui.combatoverhaul.need_action").getString();
                return;
            }
            float t = fmtF(Math.max(0, Math.min(currentTick, a.lengthTicks)));
            ActionStore.Keyframe kf = a.at(t);
            if (kf == null) {
                status = Component.translatable("gui.combatoverhaul.no_kf_here").getString();
                return;
            }
            ActionStore.BoneChannel ch = kf.bones.get(bone.name);
            if (ch == null) {
                status = Component.translatable("gui.combatoverhaul.no_kf_here").getString();
                return;
            }
            ch.rotDeg = new float[]{0, 0, 0};
            ch.pos = new float[]{0, 0, 0};
            ch.scale = new float[]{1, 1, 1};
        }
        syncBoxesFromState();
        status = Component.translatable("gui.combatoverhaul.bone_reset_done").getString();
    }

    // ------------------------------------------------------------------ 骨骼操作

    private void addBone() {
        ParsedBone src = selectedBone();
        ParsedBone nb = new ParsedBone(model.nextBoneName());
        if (src != null) {
            nb.parent = src;
            nb.pivotX = src.pivotX;
            nb.pivotY = src.pivotY;
            nb.pivotZ = src.pivotZ;
            for (ParsedCube c : src.cubes) {
                nb.cubes.add(c.copy()); // 克隆立方体：UV 与关节处一致 → 旋转时呈现关节拉伸
            }
            src.children.add(nb);
        } else {
            model.roots.add(nb);
        }
        refreshBoneSelection();
        selectedRow = treeRows.indexOf(nb);
        syncBoxesFromState();
        status = Component.translatable("gui.combatoverhaul.bone_added", nb.name).getString();
    }

    /**
     * 打开分段关节设置页：轴、段数、每段百分比都由用户决定。
     */
    private void addJoint() {
        ParsedBone src = selectedBone();
        if (src == null) {
            status = Component.translatable("gui.combatoverhaul.need_bone").getString();
            return;
        }
        int axis = BoneSplitter.suggestAxis(src);
        if (axis < 0) {
            status = "该骨骼没有 cube，无法分段";
            return;
        }
        this.minecraft.setScreen(new JointSplitScreen(this, src.name, axis,
                new float[]{0.5f, 0.5f}));
    }

    /**
     * 由 {@link JointSplitScreen} 确认后调用：切 cube 链、重建网格并自动蒙皮。
     * @return null=成功；非 null=错误信息（设置页保持打开）
     */
    String applyBoneSplit(String boneName, int axis, float[] ratios) {
        ParsedBone src = model == null ? null : model.findBone(boneName);
        if (src == null) {
            return "找不到骨骼：" + boneName;
        }
        if (src.cubes.isEmpty()) {
            return "该骨骼没有 cube，无法分段";
        }
        java.util.List<ParsedCube> backupCubes = new java.util.ArrayList<>();
        for (ParsedCube c : src.cubes) {
            backupCubes.add(c.copy());
        }
        java.util.List<ParsedBone> backupChildren = new java.util.ArrayList<>(src.children);
        java.util.Map<ParsedBone, java.util.List<ParsedCube>> backupChildCubes =
                new java.util.LinkedHashMap<>();
        for (ParsedBone child : src.children) {
            java.util.List<ParsedCube> cubes = new java.util.ArrayList<>();
            for (ParsedCube c : child.cubes) {
                cubes.add(c.copy());
            }
            backupChildCubes.put(child, cubes);
        }
        var backupMesh = model.skinnedMesh;

        BoneSplitter.Result split = BoneSplitter.split(model, src, axis, ratios);
        if (!split.ok) {
            return split.error;
        }
        ParsedBone firstChild = split.childNames.isEmpty() ? null : model.findBone(split.childNames.get(0));
        if (firstChild == null) {
            src.cubes.clear();
            src.cubes.addAll(backupCubes);
            for (var e : backupChildCubes.entrySet()) {
                e.getKey().cubes.clear();
                e.getKey().cubes.addAll(e.getValue());
            }
            src.children.clear();
            src.children.addAll(backupChildren);
            model.flatten();
            return "分段后找不到新骨骼";
        }

        // 切完 cube 后必须重建网格，否则旧 mesh 不会包含新关节。
        model.skinnedMesh = null;
        SkinWeightGenerator.Result skin = SkinWeightGenerator.generate(model);
        if (!skin.ok) {
            src.cubes.clear();
            src.cubes.addAll(backupCubes);
            for (var e : backupChildCubes.entrySet()) {
                e.getKey().cubes.clear();
                e.getKey().cubes.addAll(e.getValue());
            }
            src.children.clear();
            src.children.addAll(backupChildren);
            model.flatten();
            model.skinnedMesh = backupMesh;
            return "分段后自动蒙皮失败：" + skin.error;
        }

        refreshBoneSelection();
        selectedRow = treeRows.indexOf(firstChild);
        syncBoxesFromState();
        preview.skinningEnabled = true;
        refreshSkinningLabel();
        String axisName = axis == 0 ? "X" : axis == 1 ? "Y" : "Z";
        StringBuilder pct = new StringBuilder();
        for (int i = 0; i < split.ratios.length; i++) {
            if (i > 0) {
                pct.append('/');
            }
            pct.append(String.format(java.util.Locale.ROOT, "%.0f", split.ratios[i] * 100f));
        }
        status = "已按 " + axisName + " 轴切 " + split.segments + " 段 (" + pct + "%)："
                + src.name + " → " + String.join(", ", split.childNames)
                + "；自动蒙皮 " + skin.vertices + " 顶点"
                + (skin.meshBuilt ? "（重新生成网格）" : "");
        return null;
    }

    /** 删除：选中骨骼删骨骼；选中手持节点则卸下该骨骼的手持物品 */
    private void deleteBone() {
        ParsedModel.HeldEntry held = selectedHeld();
        if (held != null) {
            model.clearHeld(held.bone);
            refreshBoneSelection();
            syncBoxesFromState();
            status = Component.translatable("gui.combatoverhaul.held_removed", held.item, held.bone).getString();
            return;
        }
        ParsedBone bone = selectedBone();
        if (bone == null) {
            status = Component.translatable("gui.combatoverhaul.need_bone").getString();
            return;
        }
        if (!bone.children.isEmpty()) {
            status = Component.translatable("gui.combatoverhaul.bone_has_children").getString();
            return;
        }
        if (bone.parent != null) {
            bone.parent.children.remove(bone);
        } else {
            model.roots.remove(bone);
        }
        refreshBoneSelection();
        syncBoxesFromState();
        status = Component.translatable("gui.combatoverhaul.bone_removed", bone.name).getString();
    }

    /**
     * 挂接/脱离：把选中骨骼挂到输入框指定的骨骼下（输入框为空 = 脱离为根）。
     * 用基础姿态矩阵链换算新枢轴，骨骼及其子树的世界位置保持不变；
     * 保存后游戏内层级同步会让上级的旋转带动该骨骼。
     */
    private void attachParent() {
        ParsedBone bone = selectedBone();
        if (bone == null) {
            status = Component.translatable("gui.combatoverhaul.need_bone").getString();
            return;
        }
        String name = parentBox.getValue().trim();
        ParsedBone np = name.isEmpty() ? null : model.findBone(name);
        if (!name.isEmpty() && np == null) {
            status = Component.translatable("gui.combatoverhaul.attach_no_bone", name).getString();
            return;
        }
        // 不能挂到自身或其后代（会成环）
        for (ParsedBone p = np; p != null; p = p.parent) {
            if (p == bone) {
                status = Component.translatable("gui.combatoverhaul.attach_cycle").getString();
                return;
            }
        }
        // 当前枢轴的世界位置（基础姿态矩阵链，变换原点即枢轴点）
        org.joml.Matrix4f chain = model.boneBaseMatrix(bone.name);
        org.joml.Vector3f worldPivot = chain.transformPosition(new org.joml.Vector3f());
        // 脱离旧父级
        if (bone.parent != null) {
            bone.parent.children.remove(bone);
        } else {
            model.roots.remove(bone);
        }
        bone.parent = np;
        // 手动改父级视为取消跟随，否则下一次 syncFollow 会把姿态覆盖回去。
        bone.follow = null;
        bone.hasFollow = false;
        if (np != null) {
            // 新父级链的逆变换 → 新父级相对枢轴
            org.joml.Matrix4f inv = new org.joml.Matrix4f(model.boneBaseMatrix(np.name)).invert();
            org.joml.Vector3f local = inv.transformPosition(new org.joml.Vector3f(worldPivot));
            bone.pivotX = local.x;
            bone.pivotY = local.y;
            bone.pivotZ = local.z;
            np.children.add(bone);
            status = Component.translatable("gui.combatoverhaul.attach_done", bone.name, np.name).getString();
        } else {
            bone.pivotX = worldPivot.x;
            bone.pivotY = worldPivot.y;
            bone.pivotZ = worldPivot.z;
            model.roots.add(bone);
            status = Component.translatable("gui.combatoverhaul.attach_detached", bone.name).getString();
        }
        refreshBoneSelection();
        selectedRow = treeRows.indexOf(bone);
        syncBoxesFromState();
    }

    /**
     * 骨骼跟随：当前骨骼及相对路径子树完全镜像输入框中的目标骨骼。
     * 跟随者与目标挂到同一父级，并复制目标的基础姿态；渲染前 syncFollow 同步动画。
     */
    private void applyFollow() {
        ParsedBone bone = selectedBone();
        if (bone == null) {
            status = Component.translatable("gui.combatoverhaul.need_bone").getString();
            return;
        }
        String name = parentBox.getValue().trim();
        if (name.isEmpty()) {
            bone.follow = null;
            bone.hasFollow = false;
            status = "已清除跟随：" + bone.name;
            syncBoxesFromState();
            return;
        }
        ParsedBone target = model.findBone(name);
        if (target == null) {
            status = "跟随目标不存在：" + name;
            return;
        }
        if (target == bone) {
            status = "不能跟随自身";
            return;
        }
        for (ParsedBone p = target; p != null; p = p.parent) {
            if (p == bone) {
                status = "不能跟随自己的后代（会成环）";
                return;
            }
        }
        // 跟随者必须和目标在同一父级下，复制同一套基础局部姿态，世界矩阵才会完全一致。
        if (bone.parent != null) {
            bone.parent.children.remove(bone);
        } else {
            model.roots.remove(bone);
        }
        bone.parent = target.parent;
        if (target.parent != null) {
            target.parent.children.add(bone);
        } else {
            model.roots.add(bone);
        }
        bone.pivotX = target.pivotX;
        bone.pivotY = target.pivotY;
        bone.pivotZ = target.pivotZ;
        bone.rotX = target.rotX;
        bone.rotY = target.rotY;
        bone.rotZ = target.rotZ;
        bone.scaleX = target.scaleX;
        bone.scaleY = target.scaleY;
        bone.scaleZ = target.scaleZ;
        bone.restMatrix = target.restMatrix == null ? null : target.restMatrix.clone();
        bone.hasPivot = target.hasPivot;
        bone.follow = target.name;
        bone.hasFollow = true;
        model.flatten();
        model.syncFollow();
        refreshBoneSelection();
        selectedRow = treeRows.indexOf(bone);
        syncBoxesFromState();
        status = "跟随 " + target.name + "：根骨与相对路径子树同步；目标分段时跟随者同步分段";
    }

    // ------------------------------------------------------------------ 保存

    private boolean saveModel() {
        if (modelParseFailed) {
            status = Component.translatable("gui.combatoverhaul.model_broken").getString()
                    + " " + Component.translatable("gui.combatoverhaul.save_blocked").getString();
            return false;
        }
        JsonObject json;
        try {
            json = model.toJson();
        } catch (RuntimeException e) {
            // 网格/权重非法时拒绝写入，避免用坏数据覆盖磁盘上的有效模型。
            com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.error(
                    "[CombatOverhaul] 模型校验失败，拒绝保存 {}:{}", modid, modelPath, e);
            status = Component.translatable("gui.combatoverhaul.write_failed").getString()
                    + ": " + e.getMessage();
            return false;
        }
        boolean ok = weapon ? ModelStore.writeWeapon(modid, modelPath, GSON.toJson(json))
                : ModelStore.write(modid, modelPath, GSON.toJson(json));
        if (ok) {
            // toJson() 基于打开编辑器时的 originalJson，里面的 actions 是旧快照。
            // 必须紧接着用内存里的实时 actions 覆盖，否则“先保存动作、再保存模型”会把动作回滚。
            if (!ActionStore.save(modid, path, weapon, actions)) {
                status = Component.translatable("gui.combatoverhaul.saved_model").getString()
                        + " / " + Component.translatable("gui.combatoverhaul.write_failed").getString();
                return false;
            }
            // 双端运行时缓存失效：判定框/游戏内手持渲染读取最新模型
            com.yourname.combatoverhaul.combat.ActionCache.invalidate(modid, path, weapon);
        }
        status = Component.translatable(ok ? "gui.combatoverhaul.saved_model" : "gui.combatoverhaul.write_failed").getString();
        return ok;
    }

    private boolean saveActions() {
        if (modelParseFailed) {
            status = Component.translatable("gui.combatoverhaul.model_broken").getString()
                    + " " + Component.translatable("gui.combatoverhaul.save_blocked").getString();
            return false;
        }
        boolean ok = ActionStore.save(modid, path, weapon, actions);
        if (ok) {
            com.yourname.combatoverhaul.combat.ActionCache.invalidate(modid, path, weapon);
        }
        status = Component.translatable(ok ? "gui.combatoverhaul.saved_actions" : "gui.combatoverhaul.write_failed").getString();
        return ok;
    }

    // ------------------------------------------------------------------ tick / 渲染

    @Override
    public void tick() {
        super.tick();
        if (playing) {
            currentTick += 1;
            if (currentTick >= actionLength()) {
                ActionStore.Action a = curAction();
                if (a != null && a.loop) {
                    currentTick = 0;
                } else {
                    currentTick = actionLength();
                    playing = false;
                    refreshPlayLabel();
                }
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        EditorTheme.appBackground(g, this.width, this.height);

        // 页面变换保留；鼠标从 Screen.width 坐标换算到实际可见逻辑坐标。
        double msx = viewW / (double) Math.max(1, this.width);
        double msy = viewH / (double) Math.max(1, this.height);
        double tmx = tx(mouseX * msx), tmy = ty(mouseY * msy);
        g.pose().pushPose();
        g.pose().translate(pageOffX, pageOffY, 0);
        g.pose().scale(pageScale, pageScale, 1.0f);

        g.fill(0, 0, pageW, 64, EditorTheme.BG_HEADER);
        g.fill(0, 63, pageW, 64, EditorTheme.BORDER);
        g.drawString(this.font, modid + ":" + path, 12, 7, EditorTheme.TEXT, false);
        g.drawString(this.font, "模型编辑器", 12 + this.font.width(modid + ":" + path) + 12,
                7, EditorTheme.TEXT_DIM, false);

        drawTopBarLabels(g);
        drawBonePanel(g, (int) tmx, (int) tmy);
        drawViewport(g, (int) tmx, (int) tmy);
        drawInspector(g);
        drawTimeline(g);
        drawBoxLabels(g);

        super.render(g, (int) tmx, (int) tmy, partialTick);
        g.pose().popPose();

        String right = "t=" + String.format(java.util.Locale.ROOT, "%.1f", currentTick)
                + " / " + fmt(actionLength());
        EditorTheme.statusBar(g, this.font, pageW, pageH, status, right);
    }

    private void drawTopBarLabels(GuiGraphics g) {
        g.drawString(this.font, Component.translatable("gui.combatoverhaul.action_label").getString(), 12, 35, EditorTheme.TEXT_MUTED, false);
        ActionStore.Action a = curAction();
        String name = a != null ? a.name : Component.translatable("gui.combatoverhaul.action_none").getString();
        g.drawString(this.font, clip(name, 60), 74, 35, a != null ? EditorTheme.TEXT : EditorTheme.TEXT_DIM, false);
        g.drawString(this.font, Component.translatable("gui.combatoverhaul.length_label", a != null ? fmt(a.lengthTicks) : "-").getString(),
                268, 35, EditorTheme.TEXT_MUTED, false);
    }

    private void drawInspector(GuiGraphics g) {
        EditorTheme.panel(g, ix0, iy0, ix1 - ix0, iy1 - iy0);
        EditorTheme.section(g, this.font, "检查器 INSPECTOR", ix0, iy0, ix1 - ix0);
        ActionStore.Action a = curAction();
        ParsedBone bone = selectedBone();
        int x = ix0 + 8;
        int y = iy0 + 48;
        int w = ix1 - ix0 - 16;
        switch (inspectorTab) {
            case 1 -> {
                int hits = a == null ? 0 : a.hitboxes.size();
                g.drawString(this.font, "判定框 " + hits + " 个", x, y, EditorTheme.TEXT, false);
                g.drawString(this.font, "冷却", x, iy0 + 100, EditorTheme.TEXT_MUTED, false);
                g.drawString(this.font, "无敌帧 起 / 止", x, iy0 + 130, EditorTheme.TEXT_MUTED, false);
                g.drawString(this.font, "判定框 " + (hits == 0 ? "-" : (inspectorHitbox + 1) + "/" + hits),
                        x, iy0 + 154, EditorTheme.TEXT_MUTED, false);
                g.drawString(this.font, "判定框 起 / 止", x, iy0 + 176, EditorTheme.TEXT_MUTED, false);
                g.drawString(this.font, "伤害倍率 / 最大目标", x, iy0 + 206, EditorTheme.TEXT_MUTED, false);
            }
            case 2 -> {
                g.drawString(this.font, "连段", x, y, EditorTheme.TEXT, false);
                g.drawString(this.font, "动作链接 / 取消窗口", x, y + 16, EditorTheme.TEXT_MUTED, false);
                try {
                    var cf = comboFile();
                    int yy = y + 36;
                    for (int i = 0; i < cf.chains.size(); i++) {
                        var chain = cf.chains.get(i);
                        g.drawString(this.font, clip((i == inspectorChain ? "> " : "  ")
                                + chain.name + "  (" + chain.nodes.size() + " 节点)", w),
                                x, yy, i == inspectorChain ? EditorTheme.TEXT : EditorTheme.TEXT_MUTED, false);
                        yy += 14;
                        if (yy > iy0 + 240) break;
                    }
                    if (cf.chains.isEmpty()) {
                        g.drawString(this.font, "没有连段链，点 + 链 新建", x, yy,
                                EditorTheme.TEXT_DIM, false);
                    }
                    var cn = comboNode();
                    if (cn != null) {
                        g.drawString(this.font, "节点 " + (inspectorNode + 1) + "/" + comboChain().nodes.size(),
                                x, iy0 + 250, EditorTheme.TEXT_MUTED, false);
                    }
                } catch (RuntimeException ignored) {
                }
                int cy = iy0 + 270;
                g.drawString(this.font, "连段名", x, cy - 12, EditorTheme.TEXT_MUTED, false);
                g.drawString(this.font, "节点动作", x, cy + 14, EditorTheme.TEXT_MUTED, false);
                g.drawString(this.font, "父节点 / 输入", x, cy + 40, EditorTheme.TEXT_MUTED, false);
                g.drawString(this.font, "窗口 起 / 止", x, cy + 66, EditorTheme.TEXT_MUTED, false);
            }
            case 3 -> {
                g.drawString(this.font, "手持", x, y, EditorTheme.TEXT, false);
                int yy = y + 18;
                for (int i = 0; i < model.heldItems.size(); i++) {
                    ParsedModel.HeldEntry he = model.heldItems.get(i);
                    String line = (i == inspectorHeld ? "> " : "  ") + he.item + " @ " + he.bone;
                    g.drawString(this.font, clip(line, w), x, yy,
                            i == inspectorHeld ? EditorTheme.TEXT : EditorTheme.TEXT_MUTED, false);
                    yy += 14;
                    if (yy > iy0 + 280) break;
                }
                if (model.heldItems.isEmpty()) {
                    g.drawString(this.font, "无手持条目，点 + 手持 新建", x, yy, EditorTheme.TEXT_DIM, false);
                }
                int hy = iy0 + 300;
                g.drawString(this.font, "物品 id", x, hy - 12, EditorTheme.TEXT_MUTED, false);
                g.drawString(this.font, "骨骼", x, hy + 14, EditorTheme.TEXT_MUTED, false);
            }
            case 4 -> {
                g.drawString(this.font, "组合层", x, y, EditorTheme.TEXT, false);
                int yy = y + 18;
                if (a != null) {
                    for (ActionStore.Segment s : a.segments) {
                        g.drawString(this.font, clip((s.clip.isEmpty() ? "(空)" : s.clip)
                                + "  " + (s.zone == null || s.zone.isEmpty() ? "(片段)" : s.zone)
                                + "  w=" + s.weight, w), x, yy, EditorTheme.TEXT_MUTED, false);
                        yy += 14;
                        if (yy > iy0 + 210) break;
                    }
                    if (a.segments.isEmpty()) {
                        g.drawString(this.font, "没有片段，点 + 层 新建", x, yy,
                                EditorTheme.TEXT_DIM, false);
                    }
                }
                int ly = iy0 + 236;
                g.drawString(this.font, "片段 / 区域", x, ly - 12, EditorTheme.TEXT_MUTED, false);
                g.drawString(this.font, "区域 / 权重", x, ly + 14, EditorTheme.TEXT_MUTED, false);
                g.drawString(this.font, "开始 / 进入", x, ly + 40, EditorTheme.TEXT_MUTED, false);
                g.drawString(this.font, "退出", x, ly + 66, EditorTheme.TEXT_MUTED, false);
            }
            case 5 -> {
                g.drawString(this.font, "绑定", x, y, EditorTheme.TEXT, false);
                g.drawString(this.font, "输入 id 后点 + 武器 / + 实体", x, y + 16, EditorTheme.TEXT_MUTED, false);
                int yy = y + 36;
                if (a != null) {
                    g.drawString(this.font, "武器 " + a.weapons.size(), x, yy, EditorTheme.TEXT_MUTED, false);
                    yy += 14;
                    for (String w2 : a.weapons) {
                        g.drawString(this.font, clip("  " + w2, w), x, yy, EditorTheme.TEXT_DIM, false);
                        yy += 12;
                        if (yy > iy0 + 112) break;
                    }
                    g.drawString(this.font, "实体 " + a.entities.size(), x, yy + 4, EditorTheme.TEXT_MUTED, false);
                    yy += 18;
                    for (String e2 : a.entities) {
                        g.drawString(this.font, clip("  " + e2, w), x, yy, EditorTheme.TEXT_DIM, false);
                        yy += 12;
                        if (yy > iy0 + 190) break;
                    }
                }
                g.drawString(this.font, "武器 id", x, iy0 + 208, EditorTheme.TEXT_MUTED, false);
                g.drawString(this.font, "实体 id", x, iy0 + 234, EditorTheme.TEXT_MUTED, false);
            }
            default -> {
                g.drawString(this.font, "动作", x, y, EditorTheme.TEXT_MUTED, false);
                g.drawString(this.font, clip(a == null ? "-" : a.name, w), x, y + 12,
                        a == null ? EditorTheme.TEXT_DIM : EditorTheme.TEXT, false);
                g.drawString(this.font, "长度 " + (a == null ? "-" : fmt(a.lengthTicks))
                        + "  循环=" + (a != null && a.loop), x, y + 32, EditorTheme.TEXT_MUTED, false);
                g.drawString(this.font, "骨骼", x, y + 52, EditorTheme.TEXT_MUTED, false);
                g.drawString(this.font, clip(bone == null ? "-" : bone.name, w), x, y + 64,
                        bone == null ? EditorTheme.TEXT_DIM : EditorTheme.TEXT, false);
            }
        }
    }

    private void drawBonePanel(GuiGraphics g, int mouseX, int mouseY) {
        EditorTheme.panel(g, bx0, by0, bx1 - bx0, by1 - by0);
        EditorTheme.section(g, this.font,
                Component.translatable("gui.combatoverhaul.bone_tree", flatBones.size()).getString(),
                bx0, by0, bx1 - bx0);
        g.enableScissor(sx(bx0), sy(by0), sx(bx1), sy(by1));
        for (int i = 0; i < treeRows.size(); i++) {
            int y = by0 + 24 + i * ROW_H - boneScroll;
            if (y > by1 - ROW_H) {
                break;
            }
            if (y < by0 + 19) {
                continue;
            }
            Object row = treeRows.get(i);
            boolean sel = i == selectedRow;
            if (sel) {
                g.fill(bx0 + 1, y, bx1 - 1, y + ROW_H, EditorTheme.BG_ROW_SELECTED);
                g.fill(bx0 + 1, y, bx0 + 3, y + ROW_H, EditorTheme.ACCENT);
            }
            String text;
            int depth;
            int color;
            if (row instanceof ParsedModel.HeldEntry he) {
                // 手持节点：缩进 = 挂接骨骼深度 + 1，显示物品路径（» 前缀由语言文件提供）
                ParsedBone host = model.findBone(he.bone);
                depth = (host != null ? host.depth() : 0) + 1;
                String itemPath = he.item.contains(":")
                        ? he.item.substring(he.item.indexOf(':') + 1) : he.item;
                text = Component.translatable("gui.combatoverhaul.held_node", itemPath).getString();
                color = 0xFFCC66;
            } else {
                ParsedBone b = (ParsedBone) row;
                depth = b.depth();
                StringBuilder sb = new StringBuilder();
                sb.append(b.name);
                if (!b.cubes.isEmpty()) {
                    sb.append(" (").append(b.cubes.size()).append(')');
                }
                text = sb.toString();
                color = EditorTheme.TEXT_MUTED;
            }
            StringBuilder line = new StringBuilder();
            for (int d = 0; d < depth; d++) {
                line.append("  ");
            }
            line.append(text);
            g.drawString(this.font, clip(line.toString(), bx1 - bx0 - 10 - depth * 6), bx0 + 8, y + 3,
                    sel ? EditorTheme.TEXT : color, false);
        }
        g.disableScissor();
    }

    /**
     * Blockbench 风格三轴旋转环：不是屏幕空间装饰椭圆，而是把模型空间中的
     * X/Y/Z 三个圆投影到当前相机；只有选中骨骼时显示，点击/拖动才旋转该骨骼。
     */
    private void drawGizmo(GuiGraphics g) {
        gizmoRingX = new int[0][];
        gizmoRingY = new int[0][];
        gizmoRingZ = new int[0][];
        if (toolMode == 3) {
            return;
        }
        ParsedBone sel = selectedBone();
        if (sel == null) {
            return;
        }
        float[] wp = model.boneWorldPos(sel.name);
        if (wp == null) {
            return;
        }
        float[] sp = preview.project(model, wp[0], wp[1], wp[2],
                (vx0 + vx1) / 2.0, (vy0 + vy1) / 2.0);
        int cx = (int) sp[0];
        int cy = (int) sp[1];
        int r = 48;
        float[] cb = sel.cubeBounds();
        if (cb != null) {
            float span = Math.max(cb[3] - cb[0], Math.max(cb[4] - cb[1], cb[5] - cb[2]));
            r = (int) Math.max(24, Math.min(90, span * preview.zoom / 16.0 * 0.9));
        }
        gizmoCx = cx;
        gizmoCy = cy;

        // 屏幕半径换算回模型单位；project() 使用 zoom/16 的缩放。
        float radiusModel = Math.max(0.4f, r * 16f / Math.max(1.0e-4f, preview.zoom));
        gizmoRingX = buildGizmoRing(wp, radiusModel, 0);
        gizmoRingY = buildGizmoRing(wp, radiusModel, 1);
        gizmoRingZ = buildGizmoRing(wp, radiusModel, 2);
        gizmoRingSign[0] = ringSign(gizmoRingX, cx, cy);
        gizmoRingSign[1] = ringSign(gizmoRingY, cx, cy);
        gizmoRingSign[2] = ringSign(gizmoRingZ, cx, cy);

        drawRing(g, gizmoRingX, 0x99FF5555);
        drawRing(g, gizmoRingY, 0x9955FF77);
        drawRing(g, gizmoRingZ, 0x995577FF);
        drawRingLabel(g, "X", gizmoRingX, 0xFFFF7777);
        drawRingLabel(g, "Y", gizmoRingY, 0xFF77FF99);
        drawRingLabel(g, "Z", gizmoRingZ, 0xFF7799FF);
    }

    private int[][] buildGizmoRing(float[] pivot, float radius, int axis) {
        int steps = 64;
        int[][] out = new int[steps + 1][];
        for (int i = 0; i <= steps; i++) {
            double a = Math.PI * 2 * i / steps;
            double c = Math.cos(a);
            double s = Math.sin(a);
            float x = pivot[0];
            float y = pivot[1];
            float z = pivot[2];
            if (axis == 0) {          // X 轴：YZ 平面
                y += (float) (c * radius);
                z += (float) (s * radius);
            } else if (axis == 1) {   // Y 轴：XZ 平面
                x += (float) (c * radius);
                z += (float) (s * radius);
            } else {                  // Z 轴：XY 平面
                x += (float) (c * radius);
                y += (float) (s * radius);
            }
            float[] p = preview.project(model, x, y, z,
                    (vx0 + vx1) / 2.0, (vy0 + vy1) / 2.0);
            out[i] = new int[]{(int) p[0], (int) p[1]};
        }
        return out;
    }

    private void drawRing(GuiGraphics g, int[][] ring, int color) {
        for (int[] p : ring) {
            g.fill(p[0] - 1, p[1] - 1, p[0] + 1, p[1] + 1, color);
        }
    }

    private void drawRingLabel(GuiGraphics g, String text, int[][] ring, int color) {
        if (ring.length == 0) {
            return;
        }
        int[] p = ring[0];
        g.drawString(this.font, text, p[0] + 4, p[1] - 6, color, false);
    }

    /**
     * 屏幕角增量与模型角增量的符号：取环上 0° 和 90° 两个投影点，
     * 看它们在屏幕上的 atan2 角是增大还是减小。
     */
    private double ringSign(int[][] ring, int cx, int cy) {
        if (ring.length < 5) {
            return 1;
        }
        int[] a = ring[0];
        int[] b = ring[ring.length / 4];
        double a0 = Math.atan2(a[1] - cy, a[0] - cx);
        double a1 = Math.atan2(b[1] - cy, b[0] - cx);
        double d = normalizeAngle(a1 - a0);
        return d >= 0 ? 1 : -1;
    }

    private static double normalizeAngle(double angle) {
        while (angle > Math.PI) {
            angle -= Math.PI * 2;
        }
        while (angle < -Math.PI) {
            angle += Math.PI * 2;
        }
        return angle;
    }

    /** 按投影后的线段距离命中，而不是按椭圆方程近似。 */
    private int gizmoAxisAt(double mx, double my) {
        int best = 0;
        double bestD = 49.0; // 7px²
        double d = nearestRingDistanceSq(gizmoRingX, mx, my);
        if (d < bestD) {
            bestD = d;
            best = 1;
        }
        d = nearestRingDistanceSq(gizmoRingY, mx, my);
        if (d < bestD) {
            bestD = d;
            best = 2;
        }
        d = nearestRingDistanceSq(gizmoRingZ, mx, my);
        if (d < bestD) {
            bestD = d;
            best = 3;
        }
        return best;
    }

    private static double nearestRingDistanceSq(int[][] ring, double mx, double my) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < ring.length; i++) {
            double d = pointSegmentDistanceSq(mx, my,
                    ring[i][0], ring[i][1], ring[i + 1][0], ring[i + 1][1]);
            if (d < best) {
                best = d;
            }
        }
        return best;
    }

    private static double pointSegmentDistanceSq(double px, double py,
                                                 double ax, double ay,
                                                 double bx, double by) {
        double abx = bx - ax;
        double aby = by - ay;
        double apx = px - ax;
        double apy = py - ay;
        double len = abx * abx + aby * aby;
        double t = len <= 1.0e-9 ? 0 : (apx * abx + apy * aby) / len;
        t = Math.max(0, Math.min(1, t));
        double dx = px - (ax + abx * t);
        double dy = py - (ay + aby * t);
        return dx * dx + dy * dy;
    }

    private ActionStore.HitboxDef currentHitbox() {
        ActionStore.Action a = curAction();
        if (a == null || a.hitboxes.isEmpty()) {
            return null;
        }
        inspectorHitbox = Math.max(0, Math.min(inspectorHitbox, a.hitboxes.size() - 1));
        return a.hitboxes.get(inspectorHitbox);
    }

    private Matrix4f hitboxBoneMatrix(ActionStore.HitboxDef h) {
        ParsedBone bone = model.findBone(h.bone);
        if (bone == null && !model.roots.isEmpty()) {
            bone = model.roots.get(0);
        }
        if (bone == null) {
            return new Matrix4f();
        }
        return com.yourname.combatoverhaul.client.skinned.SkinnedMeshRenderer.boneMatrix(
                model, bone.name, com.yourname.combatoverhaul.client.bedrock.BedrockPose.fromAnim(model));
    }

    private float[] projectHitboxCorner(ActionStore.HitboxDef h, Matrix4f bm,
                                        int ix, int iy, int iz) {
        float sx = h.size[0] * 0.5f;
        float sy = h.size[1] * 0.5f;
        float sz = h.size[2] * 0.5f;
        Vector3f p = new Vector3f(
                h.offset[0] + (ix == 0 ? -sx : sx),
                h.offset[1] + (iy == 0 ? -sy : sy),
                h.offset[2] + (iz == 0 ? -sz : sz));
        bm.transformPosition(p);
        return preview.project(model, p.x, p.y, p.z,
                (vx0 + vx1) / 2.0, (vy0 + vy1) / 2.0);
    }

    private float[] hitboxFaceScreen(ActionStore.HitboxDef h, Matrix4f bm, int axis, int sign) {
        float x = h.offset[0];
        float y = h.offset[1];
        float z = h.offset[2];
        float half = sign * h.size[axis] * 0.5f;
        if (axis == 0) {
            x += half;
        } else if (axis == 1) {
            y += half;
        } else {
            z += half;
        }
        Vector3f p = new Vector3f(x, y, z);
        bm.transformPosition(p);
        return preview.project(model, p.x, p.y, p.z,
                (vx0 + vx1) / 2.0, (vy0 + vy1) / 2.0);
    }

    private int hitboxFaceAt(double mx, double my) {
        ActionStore.HitboxDef h = currentHitbox();
        if (h == null) {
            return -1;
        }
        Matrix4f bm = hitboxBoneMatrix(h);
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                float[] p = hitboxFaceScreen(h, bm, axis, sign);
                if (Math.hypot(mx - p[0], my - p[1]) <= 9) {
                    hitboxFaceAxis = axis;
                    hitboxFaceSign = sign;
                    return axis;
                }
            }
        }
        return -1;
    }

    private void drawHitboxOverlay(GuiGraphics g) {
        if (!hitboxEdit) {
            return;
        }
        ActionStore.HitboxDef h = currentHitbox();
        if (h == null) {
            g.drawString(this.font, "没有判定框：右侧 +判定", vx0 + 4, vy0 + 16, 0xFFFFAA55, false);
            return;
        }
        Matrix4f bm = hitboxBoneMatrix(h);
        float[][] c = new float[8][];
        for (int i = 0; i < 8; i++) {
            c[i] = projectHitboxCorner(h, bm, i & 1, (i >> 1) & 1, (i >> 2) & 1);
        }
        int color = 0xFFFF9933;
        int[][] edges = {
                {0, 1}, {0, 2}, {0, 4}, {1, 3}, {1, 5}, {2, 3},
                {2, 6}, {3, 7}, {4, 5}, {4, 6}, {5, 7}, {6, 7}
        };
        for (int[] e : edges) {
            EditorDraw.line(g, (int) c[e[0]][0], (int) c[e[0]][1],
                    (int) c[e[1]][0], (int) c[e[1]][1], color);
        }
        float[] mid = projectHitboxCorner(h, bm, 1, 1, 1);
        g.fill((int) mid[0] - 2, (int) mid[1] - 2, (int) mid[0] + 3, (int) mid[1] + 3, 0xFFFFFF55);
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                float[] fp = hitboxFaceScreen(h, bm, axis, sign);
                int col = axis == 0 ? 0xFFFF6666 : axis == 1 ? 0xFF66FF66 : 0xFF6688FF;
                g.fill((int) fp[0] - 4, (int) fp[1] - 4, (int) fp[0] + 5, (int) fp[1] + 5, col);
                g.fill((int) fp[0] - 2, (int) fp[1] - 2, (int) fp[0] + 3, (int) fp[1] + 3, 0xFF111111);
            }
        }
        g.drawString(this.font, h.name + "  size=" + fmt(h.size[0]) + "," + fmt(h.size[1]) + ","
                + fmt(h.size[2]), vx0 + 4, vy0 + 16, 0xFFFFB366, false);
        g.drawString(this.font, "拖动=移动 XY；Ctrl+拖动=移动 Z；Shift=缩放 XY；Shift+Ctrl=缩放 Z",
                vx0 + 4, vy0 + 28, 0xAAFFB366, false);
    }

    private float[] hitboxCenterScreen() {
        ActionStore.HitboxDef h = currentHitbox();
        if (h == null) {
            return null;
        }
        Matrix4f bm = hitboxBoneMatrix(h);
        Vector3f p = new Vector3f(h.offset[0], h.offset[1], h.offset[2]);
        bm.transformPosition(p);
        return preview.project(model, p.x, p.y, p.z,
                (vx0 + vx1) / 2.0, (vy0 + vy1) / 2.0);
    }

    private void drawViewport(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(vx0, vy0, vx1, vy1, 0xFF0B0E13);
        g.renderOutline(vx0, vy0, vx1 - vx0, vy1 - vy0, EditorTheme.BORDER);

        ActionStore.apply(actions, actionIndex, currentTick, model);
        ParsedBone sel = selectedBone();
        preview.selectedBone = sel != null ? sel.name : null;
        // 手持预览同步（实体编辑器：遍历全部手持条目，物品直接调用游戏内渲染）
        preview.heldItems.clear();
        if (!weapon) {
            for (ParsedModel.HeldEntry he : model.heldItems) {
                net.minecraft.world.item.ItemStack stack = heldStackFor(he.item);
                if (stack.isEmpty()) {
                    continue;
                }
                ModelPreviewRenderer.HeldVis hv = new ModelPreviewRenderer.HeldVis();
                hv.bone = he.bone;
                hv.stack = stack;
                float[] off = model.heldAttachOffset(he);
                hv.offX = off[0];
                hv.offY = off[1];
                hv.offZ = off[2];
                if (he.rotDeg != null) {
                    hv.rotX = (float) Math.toRadians(he.rotDeg[0]);
                    hv.rotY = (float) Math.toRadians(he.rotDeg[1]);
                    hv.rotZ = (float) Math.toRadians(he.rotDeg[2]);
                }
                preview.heldItems.add(hv);
            }
        }
        // 锚点位移预览：整体平移模型使锚骨骼落到锚点目标位置 + 橙色十字标记目标
        float[] ai = poseMode ? null : ActionStore.anchorInfo(actions, actionIndex, currentTick, model);
        if (ai != null) {
            preview.modelOffX = ai[0];
            preview.modelOffY = ai[1];
            preview.modelOffZ = ai[2];
            preview.anchorMarkerX = ai[3];
            preview.anchorMarkerY = ai[4];
            preview.anchorMarkerZ = ai[5];
            preview.showAnchorMarker = true;
        } else {
            preview.modelOffX = preview.modelOffY = preview.modelOffZ = 0;
            preview.showAnchorMarker = false;
        }
        // 相机中心用页面坐标（随 pose 变换），剪裁用物理屏幕坐标（enableScissor 不受 pose 影响）
        preview.render(g, model, (vx0 + vx1) / 2.0, (vy0 + vy1) / 2.0,
                sx(vx0 + 1), sy(vy0 + 1), sx(vx1 - 1), sy(vy1 - 1));
        drawHitboxOverlay(g);
        // 万向节只画在视口内，避免环压到骨骼树/Inspector。
        g.enableScissor(sx(vx0 + 1), sy(vy0 + 1), sx(vx1 - 1), sy(vy1 - 1));
        drawGizmo(g);
        g.disableScissor();
        if (toolMode == 3) {
            drawBindingOverlay(g, mouseX, mouseY);
        }

        String hint = Component.translatable(switch (toolMode) {
            case 1 -> "gui.combatoverhaul.hint_viewport_anchor";
            case 2 -> "gui.combatoverhaul.hint_viewport_pivot";
            case 3 -> "gui.combatoverhaul.hint_viewport_bind";
            default -> "gui.combatoverhaul.hint_viewport_bone";
        }).getString();
        g.drawString(this.font, hint, vx0 + 4, vy1 - 11, 0x77FFFFFF);
        if (preview.resolvedTextureInfo() != null) {
            String tex = preview.resolvedTextureInfo();
            g.drawString(this.font, clip(tex, vx1 - vx0 - 8), vx0 + 4, vy0 + 4, 0x77FFFFFF);
        }
    }

    private void drawTimeline(GuiGraphics g) {
        EditorTheme.panel(g, tlX0, tlY0, tlX1 - tlX0, tlY1 - tlY0);
        float len = actionLength();
        float midY = (tlY0 + tlY1) / 2.0f;

        g.fill(tlX0 + 2, (int) midY - 1, tlX1 - 2, (int) midY, EditorTheme.BORDER);
        int step = len <= 64 ? 1 : len <= 160 ? 5 : 10;
        for (int t = 0; t <= len; t += step) {
            int x = xForTime(t, len);
            boolean major = t % (step * 5) == 0;
            g.fill(x, (int) midY - (major ? 4 : 2), x + 1, (int) midY + (major ? 4 : 2), 0xFF888888);
        }
        ActionStore.Action a = curAction();
        if (a != null) {
            for (int i = 0; i < a.keyframes.size(); i++) {
                int x = xForTime(a.keyframes.get(i).time, len);
                int color = i == selectedKeyframe ? 0xFFFFE066 : 0xFF66CCFF;
                g.fill(x - 2, tlY0 + 3, x + 3, tlY0 + 8, color);
                g.fill(x, (int) midY - 5, x + 1, (int) midY, 0x9966CCFF);
                // 带锚点的关键帧：下方橙色小点
                if (a.keyframes.get(i).anchorBone != null) {
                    g.fill(x - 1, tlY1 - 4, x + 2, tlY1 - 1, 0xFFFF9926);
                }
            }
        }
        int px = xForTime(currentTick, len);
        g.fill(px, tlY0 + 2, px + 1, tlY1 - 2, 0xFFFF5050);

        // 多轨：底部 segment lane（组合动作）
        int segTop = tlY1 - 30;
        g.fill(tlX0 + 2, segTop - 1, tlX1 - 2, segTop, EditorTheme.BORDER);
        g.drawString(this.font, "层", tlX0 + 4, segTop - 11, EditorTheme.TEXT_DIM, false);
        if (a != null && !a.segments.isEmpty()) {
            int yy = segTop + 3;
            for (int i = 0; i < a.segments.size() && yy < tlY1 - 6; i++) {
                ActionStore.Segment seg = a.segments.get(i);
                ActionStore.Action clip = null;
                for (ActionStore.Action candidate : actions) {
                    if (candidate.name.equals(seg.clip)) {
                        clip = candidate;
                        break;
                    }
                }
                float segLen = clip == null ? Math.max(1f, len - seg.startTick) : Math.max(1f, clip.lengthTicks);
                int x0 = xForTime(seg.startTick, len);
                int x1 = xForTime(seg.startTick + segLen, len);
                int color = switch (seg.zone == null ? "" : seg.zone) {
                    case "upper" -> 0xFF4EA1FF;
                    case "lower" -> 0xFF6FD08C;
                    case "full" -> 0xFFE2B86B;
                    default -> 0xFF9B7BFF;
                };
                g.fill(x0, yy, Math.max(x0 + 2, x1), yy + 7, color);
                String label = (seg.clip.isEmpty() ? "(empty)" : seg.clip)
                        + "  " + (seg.zone == null || seg.zone.isEmpty() ? "(clip)" : seg.zone);
                g.drawString(this.font, clip(label, Math.max(20, x1 - x0 - 2)), x0 + 2, yy - 1,
                        0xFF0B0E13, false);
                yy += 9;
            }
        } else {
            g.drawString(this.font, "单动作（无 segments）", tlX0 + 4, segTop + 4, EditorTheme.TEXT_DIM, false);
        }

        g.drawString(this.font, String.format(java.util.Locale.ROOT, "t=%.1f / %.1f", currentTick, len),
                tlX0 + 4, tlY1 - 10, EditorTheme.TEXT_MUTED, false);
    }

    private int xForTime(float t, float len) {
        int usable = tlX1 - tlX0 - 8;
        return tlX0 + 4 + (int) (Math.max(0, Math.min(t, len)) / len * usable);
    }

    private float timeForX(double x, float len) {
        int usable = Math.max(1, tlX1 - tlX0 - 8);
        return (float) ((x - tlX0 - 4) / usable * len);
    }

    private void toggleTransform() {
        showTransform = !showTransform;
        for (EditBox b : boxes) {
            if (b != null) {
                b.visible = showTransform;
            }
        }
    }

    private void drawBoxLabels(GuiGraphics g) {
        if (!showTransform) {
            return;
        }
        String[] labels;
        if (selectedCube() != null) {
            // 立方体模式：位置 / 自身旋转° / 尺寸 / 旋转枢轴
            labels = new String[]{
                    Component.translatable("gui.combatoverhaul.cube_pos_label").getString(),
                    Component.translatable("gui.combatoverhaul.rot_label").getString(),
                    Component.translatable("gui.combatoverhaul.cube_size_label").getString(),
                    Component.translatable("gui.combatoverhaul.cube_pivot_label").getString()};
        } else {
            labels = new String[]{
                    Component.translatable("gui.combatoverhaul.pos_label").getString(),
                    Component.translatable("gui.combatoverhaul.rot_label").getString(),
                    Component.translatable("gui.combatoverhaul.scale_label").getString(),
                    Component.translatable("gui.combatoverhaul.anchor_label").getString()};
        }
        int y = pageH - 76;
        for (int group = 0; group < 4; group++) {
            g.drawString(this.font, labels[group], vx0 + 4 + group * 140, y + 4, EditorTheme.TEXT_MUTED, false);
        }
    }

    // ------------------------------------------------------------------ 页面变换

    /** 屏幕坐标 → 页面坐标 */
    private double tx(double mx) {
        return (mx - pageOffX) / pageScale;
    }

    private double ty(double my) {
        return (my - pageOffY) / pageScale;
    }

    /** 页面坐标 → 物理屏幕坐标（用于 scissor 等不受 pose 影响的操作） */
    private int sx(double px) {
        return (int) Math.round(pageOffX + px * pageScale);
    }

    private int sy(double py) {
        return (int) Math.round(pageOffY + py * pageScale);
    }

    private double inX(double mouseX) {
        return mouseX * viewW / (double) Math.max(1, this.width);
    }

    private double inY(double mouseY) {
        return mouseY * viewH / (double) Math.max(1, this.height);
    }

    private void clampPage() {
        float pw = pageW * pageScale;
        float ph = pageH * pageScale;
        pageOffX = pw <= viewW ? (int) ((viewW - pw) / 2)
                : Math.max(viewW - (int) pw, Math.min(0, pageOffX));
        pageOffY = ph <= viewH ? (int) ((viewH - ph) / 2)
                : Math.max(viewH - (int) ph, Math.min(0, pageOffY));
    }

    /** 适应窗口：取能完整显示整页的最小缩放（上限 1.0 避免放大），并居中 */
    private void fitPage() {
        float s = Math.min((float) viewW / pageW, (float) viewH / pageH);
        pageScale = Math.max(0.35f, Math.min(1.0f, s));
        pageOffX = Math.round((viewW - pageW * pageScale) / 2.0f);
        pageOffY = Math.round((viewH - pageH * pageScale) / 2.0f);
        if (model != null) {
            preview.fitView(model, vx0, vy0, vx1, vy1);
        }
    }

    /** 以屏幕中心为锚点缩放页面 */
    private void zoomPage(float factor) {
        float old = pageScale;
        float ns = Math.max(0.35f, Math.min(2.0f, old * factor));
        if (ns == old) {
            return;
        }
        float cx = viewW / 2.0f;
        float cy = viewH / 2.0f;
        pageOffX = Math.round(cx - (cx - pageOffX) * ns / old);
        pageOffY = Math.round(cy - (cy - pageOffY) * ns / old);
        pageScale = ns;
        clampPage();
    }

    private void panPageX(int dir) {
        pageOffX += dir * (int) (pageW * pageScale * 0.5f);
        clampPage();
    }

    private void panPageY(int dir) {
        pageOffY += dir * (int) (pageH * pageScale * 0.5f);
        clampPage();
    }

    // ------------------------------------------------------------------ 页面控制条（屏幕固定，右侧竖排）

    private static final int CHROME_BTN_W = 18;
    private static final int CHROME_BTN_H = 16;
    private static final int CHROME_GAP = 4;
    private static final int CHROME_RIGHT_MARGIN = 6;
    /** 竖排按钮列顶部 y（避开顶部栏按钮 y=24~40） */
    private static final int CHROME_TOP = 44;

    /** 第 i 个按钮的屏幕 x。索引 0=▲ 1=◀ 2=▶ 3=▼ 4=－ 5=＋ */
    private int chromeBtnX(int i) {
        int right = this.width - CHROME_RIGHT_MARGIN;
        if (i == 1) {
            return right - 2 * CHROME_BTN_W - CHROME_GAP; // ◀ 靠左
        }
        return right - CHROME_BTN_W; // ▲ ▶ ▼ － ＋ 右对齐列
    }

    /** 第 i 个按钮的屏幕 y（▲、▼、－、＋ 各占一行，◀▶ 同一行） */
    private int chromeBtnY(int i) {
        int row = switch (i) {
            case 0 -> 0;      // ▲
            case 1, 2 -> 1;   // ◀ ▶
            case 3 -> 2;      // ▼
            case 4 -> 3;      // －
            default -> 4;     // ＋
        };
        return CHROME_TOP + row * (CHROME_BTN_H + CHROME_GAP);
    }

    private void drawChrome(GuiGraphics g, int mouseX, int mouseY) {
        String[] labels = {"▲", "◀", "▶", "▼", "－", "＋"};
        for (int i = 0; i < labels.length; i++) {
            int x = chromeBtnX(i);
            int y = chromeBtnY(i);
            boolean hover = mouseX >= x && mouseX < x + CHROME_BTN_W
                    && mouseY >= y && mouseY < y + CHROME_BTN_H;
            g.fill(x, y, x + CHROME_BTN_W, y + CHROME_BTN_H, hover ? 0xCC555577 : 0x88000000);
            g.renderOutline(x, y, CHROME_BTN_W, CHROME_BTN_H, 0xFF555555);
            g.drawCenteredString(this.font, labels[i], x + CHROME_BTN_W / 2, y + 4, 0xFFFFFF);
        }
        // 缩放百分比：放在按钮列下方居中，不遮挡顶部栏与按钮
        int colCenterX = this.width - CHROME_RIGHT_MARGIN - CHROME_BTN_W - CHROME_GAP / 2;
        int zoomY = CHROME_TOP + 5 * (CHROME_BTN_H + CHROME_GAP) + 2;
        String zoom = Component.translatable("gui.combatoverhaul.page_zoom", (int) (pageScale * 100)).getString();
        g.drawCenteredString(this.font, zoom, colCenterX, zoomY, 0xC8C8C8);
    }

    /** 页面控制条点击；命中返回 true */
    private boolean chromeClicked(double mouseX, double mouseY) {
        for (int i = 0; i < 6; i++) {
            int x = chromeBtnX(i);
            int y = chromeBtnY(i);
            if (mouseX >= x && mouseX < x + CHROME_BTN_W
                    && mouseY >= y && mouseY < y + CHROME_BTN_H) {
                switch (i) {
                    case 0 -> panPageY(1);   // ▲
                    case 1 -> panPageX(1);   // ◀ 页面右移（视角向左）
                    case 2 -> panPageX(-1);  // ▶ 页面左移（视角向右）
                    case 3 -> panPageY(-1);  // ▼
                    case 4 -> zoomPage(1 / 1.2f); // －
                    case 5 -> zoomPage(1.2f);     // ＋
                    default -> { }
                }
                return true;
            }
        }
        return false;
    }

    private String clip(String s, int maxWidth) {
        while (!s.isEmpty() && this.font.width(s) > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // ------------------------------------------------------------------ 鼠标

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double mx = tx(inX(mouseX)), my = ty(inY(mouseY));
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        // 骨骼树行：左键选择（骨骼/手持节点），右键复制（骨骼行复制骨骼名，手持行复制物品ID）
        if (mx >= bx0 && mx <= bx1 && my >= by0 && my <= by1) {
            int idx = ((int) my - by0 - 24 + boneScroll) / ROW_H;
            if (idx >= 0 && idx < treeRows.size()) {
                if (button == 1) {
                    Object row = treeRows.get(idx);
                    String copied = row instanceof ParsedModel.HeldEntry he
                            ? he.item : ((ParsedBone) row).name;
                    this.minecraft.keyboardHandler.setClipboard(copied);
                    status = Component.translatable("gui.combatoverhaul.bone_copied", copied).getString();
                    return true;
                }
                selectedRow = idx;
                syncBoxesFromState();
                return true;
            }
        }
        // 时间轴
        if (mx >= tlX0 && mx <= tlX1 && my >= tlY0 && my <= tlY1) {
            float len = actionLength();
            // Segment lane：点击色条选中对应 Inspector/Layers segment
            int segTop = tlY1 - 30;
            if (my >= segTop) {
                ActionStore.Action act = curAction();
                if (act != null && !act.segments.isEmpty()) {
                    for (int i = 0; i < act.segments.size(); i++) {
                        ActionStore.Segment seg = act.segments.get(i);
                        ActionStore.Action clip = null;
                        for (ActionStore.Action candidate : actions) {
                            if (candidate.name.equals(seg.clip)) {
                                clip = candidate;
                                break;
                            }
                        }
                        float segLen = clip == null ? Math.max(1f, len - seg.startTick)
                                : Math.max(1f, clip.lengthTicks);
                        int x0 = xForTime(seg.startTick, len);
                        int x1 = xForTime(seg.startTick + segLen, len);
                        if (mx >= x0 && mx <= Math.max(x0 + 4, x1)) {
                            inspectorSegment = i;
                            setInspectorTab(4);
                            currentTick = Math.max(0, Math.min(len, seg.startTick));
                            dragSegment = i;
                            dragSegmentMouseX = mx;
                            dragSegmentStart = seg.startTick;
                            playing = false;
                            refreshPlayLabel();
                            return true;
                        }
                    }
                }
            }
            playing = false;
            refreshPlayLabel();
            currentTick = Math.max(0, Math.min(len, timeForX(mx, len)));
            dragSegment = -1;
            ActionStore.Action a = curAction();
            if (a != null) {
                selectedKeyframe = -1;
                for (int i = 0; i < a.keyframes.size(); i++) {
                    if (Math.abs(xForTime(a.keyframes.get(i).time, len) - mx) <= 3) {
                        selectedKeyframe = i;
                        currentTick = a.keyframes.get(i).time;
                        break;
                    }
                }
            }
            syncBoxesFromState();
            return true;
        }
        // 判定框拖动：左键在视口拖判定框中心（页坐标）
        if (button == 0 && hitboxEdit
                && mx >= vx0 && mx <= vx1 && my >= vy0 && my <= vy1) {
            if (hitboxFaceAt(mx, my) >= 0) {
                hitboxDrag = true;
                hitboxLastX = mx;
                hitboxLastY = my;
                return true;
            }
            float[] center = hitboxCenterScreen();
            if (center != null && Math.hypot(mx - center[0], my - center[1]) <= 32) {
                hitboxFaceAxis = -1;
                hitboxDrag = true;
                hitboxLastX = mx;
                hitboxLastY = my;
                return true;
            }
        }
        // 绑定工具：左键刷选当前骨骼，Shift+左键擦除；优先于轴环
        if (button == 0 && toolMode == 3
                && mx >= vx0 && mx <= vx1 && my >= vy0 && my <= vy1) {
            bindingDrag = true;
            applyBindBrush(mx, my, hasShiftDown());
            return true;
        }
        // 视口拖拽：先命中三向轴环，否则按工具模式
        if (button == 0 && mx >= vx0 && mx <= vx1 && my >= vy0 && my <= vy1) {
            int axis = gizmoAxisAt(mx, my);
            if (axis != 0) {
                gizmoAxis = axis;
                gizmoLastAngle = Math.atan2(my - gizmoCy, mx - gizmoCx);
                return true;
            }
        }
        // 视口拖拽：左键按工具模式（骨骼=旋转选中骨骼 / 锚点=拖动锚点 / 枢轴=拖动枢轴）；
        // 右键=旋转视角；中键=平移
        if (mx >= vx0 && mx <= vx1 && my >= vy0 && my <= vy1
                && (button == 0 || button == 1 || button == 2)) {
            if (button == 1) {
                dragging = true; // 右键轨道相机
            } else if (button == 0 && toolMode == 0 && beginBoneDrag(mx, my)) {
                boneDragging = true;
            } else if (button == 0 && toolMode == 1 && beginAnchorDrag(mx, my)) {
                anchorDragging = true;
            } else if (button == 0 && toolMode == 2 && beginPivotDrag(mx, my)) {
                pivotDragging = true;
            } else if (button == 2) {
                dragging = true; // 中键平移
            }
            return true;
        }
        return false;
    }

    /**
     * 开始骨骼旋转拖动：记录初值并决定写入目标。
     * 姿态模式直接写 bone.rotX/Y/Z；关键帧模式在当前时刻取/建关键帧通道
     * （新通道捕获当前插值姿态，避免把位移/缩放打回单位值）。
     */
    private boolean beginBoneDrag(double mx, double my) {
        ParsedModel.HeldEntry held = selectedHeld();
        if (held != null) {
            // 手持节点：骨骼工具拖拽 = 旋转手持物品（与关键帧无关，直接写 held.rotDeg）
            dragHeld = held;
            dragStartX = mx;
            dragStartY = my;
            dragKeyframe = null;
            dragChannel = null;
            dragStartRot[0] = held.rotDeg != null ? held.rotDeg[0] : 0;
            dragStartRot[1] = held.rotDeg != null ? held.rotDeg[1] : 0;
            dragStartRot[2] = held.rotDeg != null ? held.rotDeg[2] : 0;
            return true;
        }
        dragHeld = null;
        ParsedBone bone = selectedBone();
        if (bone == null) {
            status = Component.translatable("gui.combatoverhaul.need_bone").getString();
            return false;
        }
        dragStartX = mx;
        dragStartY = my;
        dragKeyframe = null;
        dragChannel = null;
        boneDragMoved = false;
        if (poseMode) {
            dragStartRot[0] = (float) Math.toDegrees(bone.rotX);
            dragStartRot[1] = (float) Math.toDegrees(bone.rotY);
            dragStartRot[2] = (float) Math.toDegrees(bone.rotZ);
        } else {
            ActionStore.Action a = curAction();
            if (a == null) {
                status = Component.translatable("gui.combatoverhaul.need_action").getString();
                return false;
            }
            // 拖动期间停止播放，避免 currentTick 漂移导致写入别的帧
            playing = false;
            refreshPlayLabel();
            float t = fmtF(Math.max(0.0f, Math.min(currentTick, a.lengthTicks)));
            ActionStore.Keyframe existing = a.at(t);
            dragKeyframeCreated = existing == null;
            ActionStore.Keyframe kf = existing != null ? existing : a.atOrCreate(t);
            ActionStore.BoneChannel ch = kf.bones.get(bone.name);
            if (ch == null) {
                ch = new ActionStore.BoneChannel();
                ch.pos = new float[] {bone.animPosX, bone.animPosY, bone.animPosZ};
                ch.rotDeg = new float[] {
                        (float) Math.toDegrees(bone.animRotX),
                        (float) Math.toDegrees(bone.animRotY),
                        (float) Math.toDegrees(bone.animRotZ)};
                ch.scale = new float[] {bone.animScaleX, bone.animScaleY, bone.animScaleZ};
                kf.bones.put(bone.name, ch);
            }
            dragStartRot[0] = ch.rotDeg[0];
            dragStartRot[1] = ch.rotDeg[1];
            dragStartRot[2] = ch.rotDeg[2];
            dragKeyframe = kf;
            dragChannel = ch;
        }
        return true;
    }

    /** 拖动中：上下=绕 X，左右=绕 Y；按住 Shift=绕 Z 滚转（均为页面像素 × 灵敏度） */
    private void applyBoneDrag(double mx, double my) {
        if (dragHeld != null) {
            // 手持节点：写 held.rotDeg（初值 + 鼠标增量）。
            // 注意：手持物品垂直拖动方向与骨骼相反（dy 取负），与武器在手中的实际朝向一致。
            float dx = (float) (mx - dragStartX);
            float dy = (float) (my - dragStartY);
            float sens = 0.5f;
            float nx = dragStartRot[0] - dy * sens;
            float ny = dragStartRot[1] + dx * sens;
            float nz = dragStartRot[2];
            if (hasShiftDown()) {
                nx = dragStartRot[0];
                ny = dragStartRot[1];
                nz = dragStartRot[2] + dx * sens;
            }
            if (dragHeld.rotDeg == null) {
                dragHeld.rotDeg = new float[3];
            }
            dragHeld.rotDeg[0] = nx;
            dragHeld.rotDeg[1] = ny;
            dragHeld.rotDeg[2] = nz;
            return;
        }
        ParsedBone bone = selectedBone();
        if (bone == null) {
            return;
        }
        float dx = (float) (mx - dragStartX);
        float dy = (float) (my - dragStartY);
        if (Math.abs(dx) > 0.5f || Math.abs(dy) > 0.5f) {
            boneDragMoved = true;
        }
        float sens = 0.5f;
        float nx = dragStartRot[0] + dy * sens;
        float ny = dragStartRot[1] + dx * sens;
        float nz = dragStartRot[2];
        if (hasShiftDown()) {
            nx = dragStartRot[0];
            ny = dragStartRot[1];
            nz = dragStartRot[2] + dx * sens;
        }
        if (poseMode || dragChannel == null) {
            bone.rotX = (float) Math.toRadians(nx);
            bone.rotY = (float) Math.toRadians(ny);
            bone.rotZ = (float) Math.toRadians(nz);
        } else {
            dragChannel.rotDeg[0] = nx;
            dragChannel.rotDeg[1] = ny;
            dragChannel.rotDeg[2] = nz;
        }
    }

    /**
     * 开始锚点拖动：当前关键帧必须已有锚点（先用「设锚点」指定骨骼）。
     * 记录锚点目标位置初值并暂停播放，避免写入漂移。
     */
    private boolean beginAnchorDrag(double mx, double my) {
        if (poseMode) {
            status = Component.translatable("gui.combatoverhaul.anchor_drag_none").getString();
            return false;
        }
        ActionStore.Keyframe kf = currentKf();
        if (kf == null || kf.anchorPos == null) {
            status = Component.translatable("gui.combatoverhaul.anchor_drag_none").getString();
            return false;
        }
        playing = false;
        refreshPlayLabel();
        dragStartX = mx;
        dragStartY = my;
        dragStartAnchor[0] = kf.anchorPos[0];
        dragStartAnchor[1] = kf.anchorPos[1];
        dragStartAnchor[2] = kf.anchorPos[2];
        dragKeyframe = kf;
        return true;
    }

    /**
     * 拖动中：把页面像素增量按相机 yaw/pitch 逆变换映射回模型空间，
     * 换算系数 16/zoom（页面像素 → 模型像素）。左右/上下=视平面移动，Shift=沿视线深度。
     */
    private void applyAnchorDrag(double mx, double my) {
        if (dragKeyframe == null || dragKeyframe.anchorPos == null) {
            return;
        }
        float dx = (float) (mx - dragStartX);
        float dy = (float) (my - dragStartY);
        float k = 16.0f / preview.zoom;
        // 视口旋转 R = Ry(yaw)·Rx(pitch)（与渲染管线一致），屏幕向量 → 模型空间用其逆
        org.joml.Quaternionf inv = new org.joml.Quaternionf()
                .rotateX((float) Math.toRadians(-preview.pitch))
                .rotateY((float) Math.toRadians(-preview.yaw));
        org.joml.Vector3f delta;
        if (hasShiftDown()) {
            // Shift：沿视线轴（入屏方向=视口 +z）移动，下拖=远离相机
            delta = new org.joml.Vector3f(0, 0, dy).rotate(inv).mul(k);
        } else {
            delta = new org.joml.Vector3f(dx, dy, 0).rotate(inv).mul(k);
        }
        dragKeyframe.anchorPos[0] = dragStartAnchor[0] + delta.x;
        dragKeyframe.anchorPos[1] = dragStartAnchor[1] + delta.y;
        dragKeyframe.anchorPos[2] = dragStartAnchor[2] + delta.z;
    }

    /**
     * 开始枢轴拖动：移动选中骨骼的旋转中心，立方体按 (R·S)⁻¹·(P初−P新) 补偿保持几何原位。
     * 记录枢轴/立方体（含 faceVerts）快照、骨骼自身 RS 及其逆、父级链 3x3 逆（鼠标位移 → 父级空间）；
     * 轴锁开启时预计算中轴线（包围盒最长轴，父级空间：线上点 Q + 单位方向 U）。
     */
    private boolean beginPivotDrag(double mx, double my) {
        ParsedModel.HeldEntry held = selectedHeld();
        if (held != null) {
            // 手持节点：枢轴工具拖拽 = 移动手持挂点 held.offset（骨骼局部空间，模型单位）
            dragHeld = held;
            playing = false;
            refreshPlayLabel();
            dragStartX = mx;
            dragStartY = my;
            float[] off = model.heldAttachOffset(held); // 解析值（配置优先，否则包围盒端面自动值）
            dragStartPivot[0] = off[0];
            dragStartPivot[1] = off[1];
            dragStartPivot[2] = off[2];
            // 骨骼全链 3x3 逆（模型空间位移 → 骨骼局部空间；取 4x4 左上 3x3，平移不影响位移向量）
            org.joml.Matrix4f bm = model.boneBaseMatrix(held.bone);
            if (bm != null) {
                dragParentInv.set(bm).invert();
            } else {
                dragParentInv.identity();
            }
            return true;
        }
        dragHeld = null;
        ParsedBone bone = selectedBone();
        if (bone == null) {
            status = Component.translatable("gui.combatoverhaul.need_bone").getString();
            return false;
        }
        playing = false;
        refreshPlayLabel();
        dragStartX = mx;
        dragStartY = my;
        dragStartPivot[0] = bone.pivotX;
        dragStartPivot[1] = bone.pivotY;
        dragStartPivot[2] = bone.pivotZ;
        dragStartCubeOrigins = new java.util.ArrayList<>();
        dragStartCubeVerts = new java.util.ArrayList<>();
        for (ParsedCube c : bone.cubes) {
            dragStartCubeOrigins.add(new float[]{c.originX, c.originY, c.originZ});
            float[][][] sv = new float[6][][];
            for (int f = 0; f < 6; f++) {
                if (c.faceVerts[f] != null) {
                    sv[f] = new float[4][3];
                    for (int k = 0; k < 4; k++) {
                        sv[f][k][0] = c.faceVerts[f][k][0];
                        sv[f][k][1] = c.faceVerts[f][k][1];
                        sv[f][k][2] = c.faceVerts[f][k][2];
                    }
                }
            }
            dragStartCubeVerts.add(sv);
        }
        // 骨骼自身：局部 → 父级 = R(z,y,x)·S
        dragBoneRS.rotationZYX(bone.rotZ, bone.rotY, bone.rotX)
                .scale(bone.scaleX, bone.scaleY, bone.scaleZ);
        dragBoneRSInv.set(dragBoneRS).invert();
        // 父级链 3x3 逆（模型空间位移 → 父级空间位移）
        if (bone.parent != null) {
            org.joml.Matrix4f pm = model.boneBaseMatrix(bone.parent.name);
            dragParentInv.set(pm).invert();
        } else {
            dragParentInv.identity();
        }
        // 中轴线（父级空间）：Q = P初 + RS·C（C=包围盒中心），U = normalize(RS·D)（D=最长轴方向）
        axisValid = false;
        float[] cb = bone.cubeBounds();
        if (cb != null) {
            float ex = cb[3] - cb[0], ey = cb[4] - cb[1], ez = cb[5] - cb[2];
            org.joml.Vector3f cc = new org.joml.Vector3f(
                    (cb[0] + cb[3]) / 2, (cb[1] + cb[4]) / 2, (cb[2] + cb[5]) / 2);
            org.joml.Vector3f dd = (ex >= ey && ex >= ez) ? new org.joml.Vector3f(1, 0, 0)
                    : (ey >= ez ? new org.joml.Vector3f(0, 1, 0) : new org.joml.Vector3f(0, 0, 1));
            cc.mul(dragBoneRS).add(dragStartPivot[0], dragStartPivot[1], dragStartPivot[2]);
            dd.mul(dragBoneRS);
            if (dd.lengthSquared() > 1.0e-8f) {
                dd.normalize();
                axisQ[0] = cc.x;
                axisQ[1] = cc.y;
                axisQ[2] = cc.z;
                axisU[0] = dd.x;
                axisU[1] = dd.y;
                axisU[2] = dd.z;
                axisValid = true;
            }
        }
        if (axisLock && !axisValid) {
            status = Component.translatable("gui.combatoverhaul.pivot_no_cubes").getString();
        }
        return true;
    }

    /**
     * 枢轴拖动中：鼠标位移 → 模型空间（同锚点拖动映射）→ 父级空间；
     * 轴锁开启且骨骼有立方体时投影到中轴线。写回枢轴并按快照补偿立方体原点/顶点，几何保持原位。
     */
    private void applyPivotDrag(double mx, double my) {
        if (dragHeld != null) {
            // 手持挂点拖动：鼠标位移 → 模型空间（同锚点拖动映射）→ 骨骼局部空间；
            // 写 held.offset（挂点无立方体补偿，轴锁不适用）
            float dx = (float) (mx - dragStartX);
            float dy = (float) (my - dragStartY);
            float k = 16.0f / preview.zoom;
            org.joml.Quaternionf inv = new org.joml.Quaternionf()
                    .rotateX((float) Math.toRadians(-preview.pitch))
                    .rotateY((float) Math.toRadians(-preview.yaw));
            org.joml.Vector3f delta;
            if (hasShiftDown()) {
                delta = new org.joml.Vector3f(0, 0, dy).rotate(inv).mul(k);
            } else {
                delta = new org.joml.Vector3f(dx, dy, 0).rotate(inv).mul(k);
            }
            delta.mul(dragParentInv);
            dragHeld.offset = new float[] {
                    dragStartPivot[0] + delta.x,
                    dragStartPivot[1] + delta.y,
                    dragStartPivot[2] + delta.z };
            return;
        }
        ParsedBone bone = selectedBone();
        if (bone == null || dragStartCubeOrigins == null) {
            return;
        }
        float dx = (float) (mx - dragStartX);
        float dy = (float) (my - dragStartY);
        float k = 16.0f / preview.zoom;
        org.joml.Quaternionf inv = new org.joml.Quaternionf()
                .rotateX((float) Math.toRadians(-preview.pitch))
                .rotateY((float) Math.toRadians(-preview.yaw));
        org.joml.Vector3f delta;
        if (hasShiftDown()) {
            delta = new org.joml.Vector3f(0, 0, dy).rotate(inv).mul(k);
        } else {
            delta = new org.joml.Vector3f(dx, dy, 0).rotate(inv).mul(k);
        }
        // 模型空间 → 父级空间
        delta.mul(dragParentInv);
        float tx = dragStartPivot[0] + delta.x;
        float ty = dragStartPivot[1] + delta.y;
        float tz = dragStartPivot[2] + delta.z;
        float nx = tx, ny = ty, nz = tz;
        if (axisLock && axisValid) {
            // 投影到中轴线：Q + U·dot(T−Q, U)
            float dot = (tx - axisQ[0]) * axisU[0] + (ty - axisQ[1]) * axisU[1] + (tz - axisQ[2]) * axisU[2];
            nx = axisQ[0] + axisU[0] * dot;
            ny = axisQ[1] + axisU[1] * dot;
            nz = axisQ[2] + axisU[2] * dot;
        }
        bone.pivotX = nx;
        bone.pivotY = ny;
        bone.pivotZ = nz;
        // 立方体补偿（含 faceVerts 存储顶点：渲染端优先使用，必须同步平移）
        org.joml.Vector3f comp = new org.joml.Vector3f(
                dragStartPivot[0] - nx, dragStartPivot[1] - ny, dragStartPivot[2] - nz).mul(dragBoneRSInv);
        for (int i = 0; i < bone.cubes.size() && i < dragStartCubeOrigins.size(); i++) {
            ParsedCube c = bone.cubes.get(i);
            float[] o = dragStartCubeOrigins.get(i);
            c.originX = o[0] + comp.x;
            c.originY = o[1] + comp.y;
            c.originZ = o[2] + comp.z;
            float[][][] sv = dragStartCubeVerts.get(i);
            for (int f = 0; f < 6; f++) {
                if (sv[f] != null && c.faceVerts[f] != null) {
                    for (int kk = 0; kk < 4; kk++) {
                        c.faceVerts[f][kk][0] = sv[f][kk][0] + comp.x;
                        c.faceVerts[f][kk][1] = sv[f][kk][1] + comp.y;
                        c.faceVerts[f][kk][2] = sv[f][kk][2] + comp.z;
                    }
                }
            }
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (hitboxDrag && button == 0) {
            ActionStore.HitboxDef h = currentHitbox();
            if (h != null) {
                double mx = tx(inX(mouseX));
                double my = ty(inY(mouseY));
                float k = 16.0f / Math.max(1.0f, preview.zoom);
                if (hitboxFaceAxis >= 0) {
                    Matrix4f bm = hitboxBoneMatrix(h);
                    float[] center = hitboxCenterScreen();
                    float[] face = hitboxFaceScreen(h, bm, hitboxFaceAxis, hitboxFaceSign);
                    if (center != null && face != null) {
                        double dx = mx - hitboxLastX;
                        double dy = my - hitboxLastY;
                        double dirX = face[0] - center[0];
                        double dirY = face[1] - center[1];
                        double len = Math.hypot(dirX, dirY);
                        float delta = 0f;
                        if (len > 1.0e-4) {
                            delta = (float) ((dx * dirX + dy * dirY) / len) * k;
                        }
                        if (Math.abs(delta) > 1.0e-4) {
                            h.size[hitboxFaceAxis] = Math.max(1f, Math.min(256f,
                                    h.size[hitboxFaceAxis] + delta));
                            h.offset[hitboxFaceAxis] += hitboxFaceSign * delta * 0.5f;
                        }
                    }
                    hitboxLastX = mx;
                    hitboxLastY = my;
                    syncInspectorFields();
                    return true;
                }
                boolean ctrl = hasControlDown();
                if (hasShiftDown()) {
                    h.size[0] = Math.max(1f, Math.min(256f,
                            h.size[0] + (float) (mx - hitboxLastX) * k * 2f));
                    h.size[1] = Math.max(1f, Math.min(256f,
                            h.size[1] - (float) (my - hitboxLastY) * k * 2f));
                    if (ctrl) {
                        h.size[2] = Math.max(1f, Math.min(256f,
                                h.size[2] + (float) (mx - hitboxLastX) * k * 2f));
                    }
                } else if (ctrl) {
                    h.offset[2] += (float) (mx - hitboxLastX) * k;
                } else {
                    h.offset[0] += (float) (mx - hitboxLastX) * k;
                    h.offset[1] -= (float) (my - hitboxLastY) * k;
                }
                hitboxLastX = mx;
                hitboxLastY = my;
                syncInspectorFields();
            }
            return true;
        }
        if (bindingDrag && button == 0) {
            double mx = tx(inX(mouseX));
            double my = ty(inY(mouseY));
            applyBindBrush(mx, my, hasShiftDown());
            return true;
        }
        if (gizmoAxis != 0 && button == 0) {
            double mx = tx(inX(mouseX));
            double my = ty(inY(mouseY));
            double angle = Math.atan2(my - gizmoCy, mx - gizmoCx);
            double delta = normalizeAngle(angle - gizmoLastAngle);
            gizmoLastAngle = angle;
            float deltaDeg = (float) Math.toDegrees(delta)
                    * (float) gizmoRingSign[gizmoAxis - 1];
            rotateSelectedBone(gizmoAxis - 1, deltaDeg);
            return true;
        }
        if (dragSegment >= 0 && button == 0) {
            ActionStore.Action a = curAction();
            if (a != null && dragSegment < a.segments.size()) {
                double mx = tx(inX(mouseX));
                float len = actionLength();
                float usable = Math.max(1f, tlX1 - tlX0 - 8);
                float delta = (float) ((mx - dragSegmentMouseX) / usable * len);
                ActionStore.Segment seg = a.segments.get(dragSegment);
                seg.startTick = Math.max(0f, Math.min(len, dragSegmentStart + delta));
                syncInspectorFields();
                return true;
            }
        }
        if (boneDragging && button == 0) {
            applyBoneDrag(tx(inX(mouseX)), ty(inY(mouseY)));
            return true;
        }
        if (anchorDragging && button == 0) {
            applyAnchorDrag(tx(inX(mouseX)), ty(inY(mouseY)));
            return true;
        }
        if (pivotDragging && button == 0) {
            applyPivotDrag(tx(inX(mouseX)), ty(inY(mouseY)));
            return true;
        }
        if (dragging && button == 2) {
            // 中键：只沿屏幕平面平移相机，绝不旋转。
            preview.panX += (float) dragX;
            preview.panY += (float) dragY;
            return true;
        }
        if (dragging && button == 1) {
            // 右键：轨道旋转。
            preview.yaw += (float) dragX * 0.6f;
            preview.pitch = Math.max(-89.0f, Math.min(89.0f, preview.pitch + (float) dragY * 0.6f));
            return true;
        }
        return super.mouseDragged(tx(inX(mouseX)), ty(inY(mouseY)), button, dragX / pageScale, dragY / pageScale);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (hitboxDrag && button == 0) {
            hitboxDrag = false;
            return true;
        }
        if (bindingDrag && button == 0) {
            bindingDrag = false;
            return true;
        }
        if (gizmoAxis != 0 && button == 0) {
            gizmoAxis = 0;
            return true;
        }
        if (dragSegment >= 0 && button == 0) {
            dragSegment = -1;
            return true;
        }
        if (boneDragging && button == 0) {
            boneDragging = false;
            if (dragHeld != null) {
                // 手持物品旋转结束
                dragHeld = null;
                dragKeyframe = null;
                dragChannel = null;
                syncBoxesFromState();
                status = Component.translatable("gui.combatoverhaul.held_rot_done").getString();
            } else {
                boolean emptyClick = false;
                if (!poseMode && dragKeyframe != null) {
                    ActionStore.Action a = curAction();
                    if (!boneDragMoved && dragKeyframeCreated && dragChannel != null) {
                        // 空点击（没有实际拖动）：回滚 beginBoneDrag 里新建的关键帧/通道
                        dragKeyframe.bones.values().removeIf(v -> v == dragChannel);
                        if (a != null && dragKeyframe.isEmpty()) {
                            a.keyframes.remove(dragKeyframe);
                        }
                        selectedKeyframe = -1;
                        emptyClick = true;
                    } else if (a != null) {
                        a.sort();
                        selectedKeyframe = a.keyframes.indexOf(dragKeyframe);
                    }
                }
                dragKeyframe = null;
                dragChannel = null;
                dragKeyframeCreated = false;
                boneDragMoved = false;
                syncBoxesFromState();
                if (!emptyClick) {
                    status = Component.translatable(poseMode
                            ? "gui.combatoverhaul.bone_rot_pose" : "gui.combatoverhaul.bone_rot_kf").getString();
                }
            }
        }
        if (anchorDragging && button == 0) {
            anchorDragging = false;
            dragKeyframe = null;
            syncBoxesFromState();
            status = Component.translatable("gui.combatoverhaul.anchor_drag_done").getString();
        }
        if (pivotDragging && button == 0) {
            pivotDragging = false;
            if (dragHeld != null) {
                // 手持挂点移动结束
                dragHeld = null;
                syncBoxesFromState();
                status = Component.translatable("gui.combatoverhaul.held_off_done").getString();
            } else {
                dragStartCubeOrigins = null;
                dragStartCubeVerts = null;
                syncBoxesFromState();
                status = Component.translatable("gui.combatoverhaul.pivot_drag_done").getString();
            }
        }
        dragging = false;
        return super.mouseReleased(tx(inX(mouseX)), ty(inY(mouseY)), button);
    }

    /** R 键循环切换视口工具（视角/骨骼/锚点）；输入框聚焦时不拦截 */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_R
                && !(getFocused() instanceof EditBox)) {
            cycleTool();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        // Ctrl+滚轮 = 页面缩放（任意位置）
        if (hasControlDown()) {
            zoomPage((float) Math.pow(1.15, delta));
            return true;
        }
        double mx = tx(inX(mouseX)), my = ty(inY(mouseY));
        if (mx >= vx0 && mx <= vx1 && my >= vy0 && my <= vy1) {
            preview.zoom = Math.max(2.0f, Math.min(600.0f, preview.zoom * (float) Math.pow(1.15, delta)));
            return true;
        }
        if (mx >= bx0 && mx <= bx1 && my >= by0 && my <= by1) {
            boneScroll -= (int) delta * ROW_H * 3;
            clampBoneScroll();
            return true;
        }
        // 其余区域：页面上下滚动
        pageOffY -= (int) (delta * 24);
        clampPage();
        return true;
    }

    // ------------------------------------------------------------------ 其它

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
