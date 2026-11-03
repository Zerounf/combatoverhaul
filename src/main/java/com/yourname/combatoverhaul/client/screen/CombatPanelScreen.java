package com.yourname.combatoverhaul.client.screen;

import com.yourname.combatoverhaul.client.model.ParsedBone;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import com.yourname.combatoverhaul.combat.ActionCache;
import com.yourname.combatoverhaul.store.ActionStore;
import com.yourname.combatoverhaul.store.ModelStore;
import com.yourname.damageoverhaul.config.ConfigLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 战斗编辑面板：为当前动作编辑判定框（骨骼/尺寸/偏移/生效时间/倍率/上限/击退）、
 * 无敌帧窗口与动作冷却。从 ModelEditorScreen 的「战斗编辑」按钮进入，
 * 返回时重建编辑器以重新加载动作文件。
 *
 * <p>整页内容（判定框列表 + 全部字段 + 倍率表）在顶栏下方纵向滚动（鼠标滚轮），
 * 自适应列宽，避免小窗口下文字重叠。
 */
public class CombatPanelScreen extends Screen {
    private static final int ROW_H = 12;
    private static final int CONTENT_TOP = 46;
    private static final int BOTTOM_BAR = 24;

    private final Screen parent;
    private final String modid;
    private final String path;
    private final boolean weapon;

    private final List<ActionStore.Action> actions = new ArrayList<>();
    private int actionIndex = -1;
    private final int initialActionIndex;
    /** 只加载一次：actions 为空时也不能在每次 rebuild/resize/滚轮时重复加载 */
    private boolean loaded = false;

    private final List<ParsedBone> flatBones = new ArrayList<>();
    private int hitboxIndex = -1;
    private String status = "";

    // 滚动内容
    private int scroll = 0;
    private int contentH = 0;
    private int hitboxListY = 0; // 判定框列表第一行的内容坐标（未减 scroll）
    private final List<AbstractWidget> contentWidgets = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();

    private EditBox boneBox, anchorBox;
    private EditBox[] sizeBoxes = new EditBox[3];
    private EditBox[] offsetBoxes = new EditBox[3];
    private EditBox startBox, endBox, maxTargetsBox, knockbackBox, damageScaleBox, cooldownBox;
    private EditBox iframeStartBox, iframeEndBox;
    private Button btnFull, btnCustom;
    private Button btnKind;
    private Button btnAuto;
    private EditBox[] multBoxes = new EditBox[0];
    private List<String> damageTypes = new ArrayList<>();

    private record Label(String text, int x, int y) {
    }

    public CombatPanelScreen(Screen parent, String modid, String path, boolean weapon) {
        this(parent, modid, path, weapon, -1);
    }

    /** @param initialActionIndex 进入时要选中的动作下标（-1=第一个） */
    public CombatPanelScreen(Screen parent, String modid, String path, boolean weapon, int initialActionIndex) {
        super(Component.translatable("gui.combatoverhaul.combat_title", modid, path));
        this.parent = parent;
        this.modid = modid;
        this.path = path;
        this.weapon = weapon;
        this.initialActionIndex = initialActionIndex;
    }

    private ActionStore.Action action() {
        return actionIndex >= 0 && actionIndex < actions.size() ? actions.get(actionIndex) : null;
    }

    private ActionStore.HitboxDef hitbox() {
        ActionStore.Action a = action();
        if (a == null || hitboxIndex < 0 || hitboxIndex >= a.hitboxes.size()) {
            return null;
        }
        return a.hitboxes.get(hitboxIndex);
    }

    @Override
    protected void init() {
        if (!loaded) {
            loaded = true;
            actions.addAll(ActionStore.load(modid, path, weapon));
            if (actions.isEmpty()) {
                actionIndex = -1;
            } else {
                actionIndex = initialActionIndex >= 0
                        ? Math.min(initialActionIndex, actions.size() - 1) : 0;
            }
            flatBones.clear();
            String content = weapon ? ModelStore.readWeapon(modid, path) : ModelStore.read(modid, path);
            if (content != null) {
                try {
                    ParsedModel m = ParsedModel.parse(
                            com.google.gson.JsonParser.parseString(content).getAsJsonObject());
                    m.flatten();
                    flatBones.addAll(m.bones);
                } catch (RuntimeException ignored) {
                }
            }
            try {
                damageTypes = ConfigLoader.getDamageTypes();
            } catch (Throwable ignored) {
                damageTypes = List.of("blunt", "slash", "pierce", "fire");
            }
        }

        // 顶栏（固定不滚动）
        int y = 24;
        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> cycleAction(-1))
                .bounds(34, y, 16, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> cycleAction(1))
                .bounds(52, y, 16, 16).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.binding"), b -> openBinding())
                .bounds(72, y, 44, 16).build());
        if (weapon) {
            this.addRenderableWidget(Button.builder(
                            Component.translatable("gui.combatoverhaul.grant_weapons"), b -> openGrant())
                    .bounds(118, y, 50, 16).build());
        }
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.save_combat"), b -> save())
                .bounds(this.width - 168, y, 78, 16).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.back"), b -> back())
                .bounds(this.width - 86, y, 76, 16).build());

        buildContent();
    }

    // ------------------------------------------------------------------ 内容布局（滚动区）

    private int contentBottom() {
        return this.height - BOTTOM_BAR;
    }

    private int maxScroll() {
        return Math.max(0, contentH - (contentBottom() - CONTENT_TOP));
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    private void addContent(AbstractWidget w) {
        this.addWidget(w);
        contentWidgets.add(w);
    }

    private void label(String key, int x, int cy) {
        labels.add(new Label(Component.translatable("gui.combatoverhaul." + key).getString(), x, cy));
    }

    /** 带标签的输入框：标签在内容上 y，框体在 cy+10（已按 scroll 换算屏幕坐标） */
    private EditBox cfield(String labelKey, int x, int cy, int w) {
        label(labelKey, x, cy);
        EditBox box = new EditBox(this.font, x, cy + 10 - scroll, w, 14,
                Component.translatable("gui.combatoverhaul." + labelKey));
        box.setMaxLength(64);
        addContent(box);
        return box;
    }

    private void buildContent() {
        contentWidgets.clear();
        labels.clear();
        clampScroll();

        ActionStore.Action a = action();
        ActionStore.HitboxDef h = hitbox();
        int colW = Math.min(170, Math.max(110, (this.width - 60) / 3));
        int x0 = 10;
        int x1 = x0 + colW + 14;
        int x2 = x1 + colW + 14;
        // 内容坐标从视口顶部开始：render() 的 scissor/visible 判定以 CONTENT_TOP 为上界，
        // 若从 0 开始，第一排“新增/删除判定框”按钮会被永远判为不可见、无法点击。
        int cy = CONTENT_TOP;

        // 判定框 增/删
        addContent(Button.builder(Component.translatable("gui.combatoverhaul.add_hitbox"), b -> addHitbox())
                .bounds(x0, cy - scroll, 110, 16).build());
        addContent(Button.builder(Component.translatable("gui.combatoverhaul.del_hitbox"), b -> delHitbox())
                .bounds(x0 + 116, cy - scroll, 90, 16).build());
        cy += 24;

        // 判定框列表（纯绘制 + 点击选择）
        label("hitbox_list", x0, cy);
        cy += 12;
        hitboxListY = cy;
        int hbRows = a == null ? 0 : a.hitboxes.size();
        cy += Math.max(1, hbRows) * ROW_H + 10;

        // 骨骼 / 挂接骨骼
        boneBox = cfield("bone", x0, cy, colW - 34);
        addContent(Button.builder(Component.literal("<"), b -> cycleBone(-1))
                .bounds(x0 + colW - 32, cy + 10 - scroll, 15, 14).build());
        addContent(Button.builder(Component.literal(">"), b -> cycleBone(1))
                .bounds(x0 + colW - 16, cy + 10 - scroll, 15, 14).build());
        anchorBox = cfield(weapon ? "anchor_bone" : "anchor_bone_unused", x1, cy, colW);
        if (h != null) {
            boneBox.setValue(h.bone);
            anchorBox.setValue(h.anchorBone);
        }
        cy += 32;

        // 类型：武器类判定框锚在手持武器上（尺寸/偏移全部手动配置）
        label("hitbox_type", x0, cy);
        btnKind = Button.builder(Component.empty(), b -> toggleKind())
                .bounds(x0, cy + 10 - scroll, colW - 34, 14).build();
        addContent(btnKind);
        refreshKindLabels();
        // 自动几何：判定箱=武器模型立方体包围盒（忽略尺寸/偏移；仅武器动作生效）
        btnAuto = Button.builder(Component.empty(), b -> toggleAuto())
                .bounds(x1, cy + 10 - scroll, colW, 14).build();
        addContent(btnAuto);
        refreshAutoLabels();
        cy += 32;

        // 尺寸 / 偏移
        String[] sizeKeys = {"size_x", "size_y", "size_z"};
        String[] offKeys = {"off_x", "off_y", "off_z"};
        int[] xs = {x0, x1, x2};
        for (int i = 0; i < 3; i++) {
            sizeBoxes[i] = cfield(sizeKeys[i], xs[i], cy, colW);
            if (h != null) {
                sizeBoxes[i].setValue(fmt(h.size[i]));
            }
        }
        cy += 32;
        for (int i = 0; i < 3; i++) {
            offsetBoxes[i] = cfield(offKeys[i], xs[i], cy, colW);
            if (h != null) {
                offsetBoxes[i].setValue(fmt(h.offset[i]));
            }
        }
        cy += 32;

        // 生效时间 / 上限
        startBox = cfield("start_tick", x0, cy, colW);
        endBox = cfield("end_tick", x1, cy, colW);
        maxTargetsBox = cfield("max_targets", x2, cy, colW);
        if (h != null) {
            startBox.setValue(fmt(h.startTick));
            endBox.setValue(fmt(h.endTick));
            maxTargetsBox.setValue(String.valueOf(h.maxTargets));
        }
        cy += 32;

        // 击退 / 总伤缩放 / 动作冷却
        knockbackBox = cfield("extra_knockback", x0, cy, colW);
        damageScaleBox = cfield("damage_scale", x1, cy, colW);
        cooldownBox = cfield("action_cooldown", x2, cy, colW);
        if (h != null) {
            knockbackBox.setValue(fmt(h.extraKnockback));
            damageScaleBox.setValue(fmt(h.damageScale));
        }
        if (a != null) {
            cooldownBox.setValue(String.valueOf(a.cooldownTicks));
        }
        cy += 32;

        // 无敌帧
        iframeStartBox = cfield("iframes_start", x0, cy, colW);
        iframeEndBox = cfield("iframes_end", x1, cy, colW);
        label("iframes_switches", x2, cy);
        btnFull = Button.builder(Component.translatable("gui.combatoverhaul.iframes_full"), b -> toggleFull())
                .bounds(x2, cy + 10 - scroll, colW / 2 - 2, 14).build();
        btnCustom = Button.builder(Component.translatable("gui.combatoverhaul.iframes_custom"), b -> toggleCustom())
                .bounds(x2 + colW / 2 + 2, cy + 10 - scroll, colW / 2 - 2, 14).build();
        addContent(btnFull);
        addContent(btnCustom);
        if (a != null && a.iframes != null) {
            iframeStartBox.setValue(fmt(a.iframes.startTick));
            iframeEndBox.setValue(fmt(a.iframes.endTick));
        }
        refreshIframeLabels();
        cy += 36;

        // 伤害类型倍率（两列排布，全部列出，随页面滚动）
        label("multipliers_hint", x0, cy);
        cy += 12;
        multBoxes = new EditBox[damageTypes.size()];
        for (int i = 0; i < damageTypes.size(); i++) {
            String type = damageTypes.get(i);
            int col = i % 2;
            int row = i / 2;
            int bx = col == 0 ? x0 : x1;
            int by = cy + row * 18 - scroll;
            final int idx = i;
            addContent(Button.builder(Component.literal(type), b -> { })
                    .bounds(bx, by, colW - 70, 14).build());
            EditBox box = new EditBox(this.font, bx + colW - 66, by, 66, 14, Component.literal(type));
            box.setMaxLength(16);
            if (h != null && h.multipliers.containsKey(type)) {
                box.setValue(fmt(h.multipliers.get(type)));
            }
            multBoxes[idx] = box;
            addContent(box);
        }
        cy += ((damageTypes.size() + 1) / 2) * 18 + 16;

        contentH = cy;
        clampScroll();
    }

    // ------------------------------------------------------------------ 状态/操作

    private Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static String fmt(float v) {
        float r = Math.round(v * 100f) / 100f;
        return r == (long) r ? String.valueOf((long) r) : String.valueOf(r);
    }

    private void cycleAction(int d) {
        collect();
        if (actions.isEmpty()) {
            return;
        }
        actionIndex = Math.floorMod(actionIndex + d, actions.size());
        hitboxIndex = -1;
        rebuildWidgets();
    }

    private void cycleBone(int d) {
        if (flatBones.isEmpty() || boneBox == null) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (ParsedBone b : flatBones) {
            names.add(b.name);
        }
        int cur = names.indexOf(boneBox.getValue());
        int next = cur < 0 ? 0 : Math.floorMod(cur + d, names.size());
        boneBox.setValue(names.get(next));
    }

    private void addHitbox() {
        ActionStore.Action a = action();
        if (a == null) {
            status = Component.translatable("gui.combatoverhaul.no_action").getString();
            return;
        }
        collect();
        ActionStore.HitboxDef h = new ActionStore.HitboxDef();
        h.name = "hitbox_" + (a.hitboxes.size() + 1);
        h.endTick = Math.max(1, Math.min(a.lengthTicks, h.startTick + 2));
        a.hitboxes.add(h);
        hitboxIndex = a.hitboxes.size() - 1;
        rebuildWidgets();
    }

    private void delHitbox() {
        ActionStore.Action a = action();
        if (a == null || hitboxIndex < 0) {
            return;
        }
        a.hitboxes.remove(hitboxIndex);
        hitboxIndex = -1;
        rebuildWidgets();
    }

    private void toggleFull() {
        ActionStore.Action a = action();
        if (a == null) {
            return;
        }
        if (a.iframes == null) {
            a.iframes = new ActionStore.IframesDef();
        }
        a.iframes.fullImmunity = !a.iframes.fullImmunity;
        refreshIframeLabels();
    }

    private void toggleCustom() {
        ActionStore.Action a = action();
        if (a == null) {
            return;
        }
        if (a.iframes == null) {
            a.iframes = new ActionStore.IframesDef();
        }
        a.iframes.blockCustomHitboxes = !a.iframes.blockCustomHitboxes;
        refreshIframeLabels();
    }

    private void refreshIframeLabels() {
        ActionStore.Action a = action();
        boolean full = a != null && a.iframes != null && a.iframes.fullImmunity;
        boolean custom = a != null && a.iframes != null && a.iframes.blockCustomHitboxes;
        if (btnFull != null) {
            btnFull.setMessage(Component.translatable(full
                    ? "gui.combatoverhaul.iframes_full_on" : "gui.combatoverhaul.iframes_full"));
        }
        if (btnCustom != null) {
            btnCustom.setMessage(Component.translatable(custom
                    ? "gui.combatoverhaul.iframes_custom_on" : "gui.combatoverhaul.iframes_custom"));
        }
    }

    // ------------------------------------------------------------------ 判定框类型/包围盒

    private void toggleKind() {
        ActionStore.HitboxDef h = hitbox();
        if (h == null) {
            return;
        }
        h.kind = ActionStore.KIND_WEAPON.equals(h.kind) ? ActionStore.KIND_MELEE : ActionStore.KIND_WEAPON;
        refreshKindLabels();
    }

    private void refreshKindLabels() {
        ActionStore.HitboxDef h = hitbox();
        boolean isWeapon = h != null && ActionStore.KIND_WEAPON.equals(h.kind);
        if (btnKind != null) {
            btnKind.setMessage(Component.translatable(isWeapon
                    ? "gui.combatoverhaul.hitbox_kind_weapon" : "gui.combatoverhaul.hitbox_kind_melee"));
            btnKind.active = h != null;
        }
    }

    // ------------------------------------------------------------------ 自动几何

    private void toggleAuto() {
        ActionStore.HitboxDef h = hitbox();
        if (h == null) {
            return;
        }
        h.autoGeom = !h.autoGeom;
        refreshAutoLabels();
    }

    private void refreshAutoLabels() {
        ActionStore.HitboxDef h = hitbox();
        boolean on = h != null && h.autoGeom;
        if (btnAuto != null) {
            btnAuto.setMessage(Component.translatable(on
                    ? "gui.combatoverhaul.auto_geom_on" : "gui.combatoverhaul.auto_geom"));
            // 仅武器动作生效（实体动作的判定箱挂实体骨骼，无武器几何可依）
            btnAuto.active = weapon && h != null;
        }
    }

    /** 把界面输入收集回数据模型 */
    private void collect() {
        ActionStore.Action a = action();
        ActionStore.HitboxDef h = hitbox();
        if (h != null) {
            if (boneBox != null) {
                h.bone = boneBox.getValue().trim();
            }
            if (anchorBox != null) {
                h.anchorBone = anchorBox.getValue().trim();
            }
            float[] size = parseVec(sizeBoxes, h.size);
            float[] off = parseVec(offsetBoxes, h.offset);
            if (size != null) h.size = size;
            if (off != null) h.offset = off;
            h.startTick = clampMin(parseF(startBox, h.startTick), 0);
            h.endTick = clampMin(parseF(endBox, h.endTick), 0);
            h.maxTargets = clampMin(parseI(maxTargetsBox, h.maxTargets), 0);
            h.extraKnockback = clampMin(parseF(knockbackBox, h.extraKnockback), 0);
            h.damageScale = Math.max(0f, parseF(damageScaleBox, h.damageScale));
            for (int i = 0; i < multBoxes.length && i < damageTypes.size(); i++) {
                if (multBoxes[i] == null) {
                    continue;
                }
                String type = damageTypes.get(i);
                String v = multBoxes[i].getValue().trim();
                if (v.isEmpty()) {
                    h.multipliers.remove(type);
                } else {
                    try {
                        h.multipliers.put(type, Float.parseFloat(v));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        if (a != null) {
            a.cooldownTicks = clampMin(parseI(cooldownBox, a.cooldownTicks), 0);
            if (a.iframes != null) {
                a.iframes.startTick = clampMin(parseF(iframeStartBox, a.iframes.startTick), 0);
                a.iframes.endTick = clampMin(parseF(iframeEndBox, a.iframes.endTick), 0);
            }
        }
    }

    private static float[] parseVec(EditBox[] boxes, float[] def) {
        if (boxes[0] == null || boxes[1] == null || boxes[2] == null) {
            return null;
        }
        return new float[]{
                parseF(boxes[0], def[0]), parseF(boxes[1], def[1]), parseF(boxes[2], def[2])};
    }

    private static float parseF(EditBox box, float def) {
        if (box == null) return def;
        try {
            return Float.parseFloat(box.getValue().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int parseI(EditBox box, int def) {
        if (box == null) return def;
        try {
            return Integer.parseInt(box.getValue().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static float clampMin(float v, float min) {
        return Math.max(min, v);
    }

    private static int clampMin(int v, int min) {
        return Math.max(min, v);
    }

    private boolean save() {
        collect();
        if (ActionStore.save(modid, path, weapon, actions)) {
            ActionCache.invalidate(modid, path, weapon);
            status = Component.translatable("gui.combatoverhaul.save_ok").getString();
            return true;
        }
        status = Component.translatable("gui.combatoverhaul.save_fail").getString();
        return false;
    }

    /** 打开动作绑定编辑（先保存当前战斗编辑，返回后保持当前动作下标） */
    private void openBinding() {
        if (action() == null) {
            status = Component.translatable("gui.combatoverhaul.no_action").getString();
            return;
        }
        collect();
        if (!save()) {
            return;
        }
        mc().setScreen(new ActionBindingScreen(parent, modid, path, weapon, actionIndex));
    }

    /** 打开武器授权编辑（仅武器模型）：勾选授权武器 + 无模型自动生成（先保存当前编辑） */
    private void openGrant() {
        if (action() == null) {
            status = Component.translatable("gui.combatoverhaul.no_action").getString();
            return;
        }
        collect();
        if (!save()) {
            return;
        }
        mc().setScreen(new WeaponGrantScreen(parent, modid, path, actionIndex));
    }

    private void back() {
        collect();
        if (!save()) {
            return;
        }
        mc().setScreen(new ModelEditorScreen(parent, modid, path, weapon));
    }

    @Override
    public void onClose() {
        back();
    }

    // ------------------------------------------------------------------ 鼠标

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int next = Math.max(0, Math.min(maxScroll(), scroll - (int) (delta * 14)));
        if (next != scroll) {
            collect();
            scroll = next;
            rebuildWidgets();
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 判定框列表：点击选中（坐标按 scroll 换算，仅在内容区内）
        ActionStore.Action a = action();
        if (a != null && mouseY >= CONTENT_TOP && mouseY <= contentBottom()
                && mouseX >= 10 && mouseX <= 340) {
            int idx = (int) ((mouseY - (hitboxListY - scroll)) / ROW_H);
            if (idx >= 0 && idx < a.hitboxes.size()) {
                collect();
                hitboxIndex = idx;
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

        ActionStore.Action a = action();
        g.drawString(this.font, a == null ? Component.translatable("gui.combatoverhaul.no_action")
                : Component.translatable("gui.combatoverhaul.combat_action", a.name),
                weapon ? 174 : 124, 28, 0xA0A0FF);

        int regionBottom = contentBottom();
        g.enableScissor(0, CONTENT_TOP, this.width, regionBottom);

        // 字段标签
        for (Label l : labels) {
            int y = l.y() - scroll;
            if (y > CONTENT_TOP - 12 && y < regionBottom) {
                g.drawString(this.font, l.text(), l.x(), y, 0x909090);
            }
        }

        // 判定框列表
        if (a != null) {
            for (int i = 0; i < a.hitboxes.size(); i++) {
                int y = hitboxListY - scroll + i * ROW_H;
                if (y < CONTENT_TOP - ROW_H || y > regionBottom) {
                    continue;
                }
                ActionStore.HitboxDef h = a.hitboxes.get(i);
                int color = i == hitboxIndex ? 0xFFFF60 : 0xFFFFFF;
                g.drawString(this.font, h.name + " [" + h.bone + "] " + (int) h.startTick + "-" + (int) h.endTick,
                        12, y + 2, color);
            }
        }

        // 内容控件（区域外隐藏，防止遮挡顶栏/底栏与误点）
        for (AbstractWidget w : contentWidgets) {
            w.visible = w.getY() + w.getHeight() > CONTENT_TOP && w.getY() < regionBottom;
            if (w.visible) {
                w.render(g, mouseX, mouseY, partialTick);
            }
        }
        g.disableScissor();

        if (maxScroll() > 0) {
            g.drawString(this.font, Component.translatable("gui.combatoverhaul.scroll_hint"),
                    this.width - 110, this.height - 14, 0x909090);
        }
        if (!status.isEmpty()) {
            g.drawString(this.font, status, 10, this.height - 14, 0x80FF80);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }
}
