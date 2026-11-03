package com.yourname.combatoverhaul.client.screen;

import com.yourname.combatoverhaul.store.ActionStore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 组合动作编辑器：父 action 的 segments 列表 + 当前 segment 的 clip/zone/weight/start/blend。
 *
 * <p>预览不在这里另开 3D 视口：保存后回到 {@link ModelEditorScreen}，选中父 action 按 Play，
 * 预览渲染器已经会调用 {@link ActionStore#apply} 合成 segments。
 */
public final class CompositeActionScreen extends Screen {

    private final Screen parent;
    private final String modid;
    private final String path;
    private final boolean weapon;
    private final List<ActionStore.Action> actions;
    private final int actionIndex;

    private int segmentIndex = 0;
    private EditBox clipBox;
    private EditBox zoneBox;
    private EditBox weightBox;
    private EditBox startBox;
    private EditBox enterBox;
    private EditBox exitBox;
    private boolean updating = false;
    private String status = "";

    public CompositeActionScreen(Screen parent, String modid, String path, boolean weapon,
                                 List<ActionStore.Action> actions, int actionIndex) {
        super(Component.translatable("gui.combatoverhaul.composite_title"));
        this.parent = parent;
        this.modid = modid;
        this.path = path;
        this.weapon = weapon;
        this.actions = actions;
        this.actionIndex = actionIndex;
    }

    private ActionStore.Action action() {
        return actionIndex >= 0 && actionIndex < actions.size() ? actions.get(actionIndex) : null;
    }

    private ActionStore.Segment segment() {
        ActionStore.Action a = action();
        if (a == null || a.segments.isEmpty()) {
            return null;
        }
        segmentIndex = Math.max(0, Math.min(segmentIndex, a.segments.size() - 1));
        return a.segments.get(segmentIndex);
    }

    @Override
    protected void init() {
        ActionStore.Action a = action();
        if (a == null) {
            return;
        }
        int left = 20;
        int top = 58;
        int fieldW = 150;
        clipBox = new EditBox(this.font, left, top, fieldW, 18, Component.literal("clip"));
        zoneBox = new EditBox(this.font, left, top + 28, fieldW, 18, Component.literal("zone"));
        weightBox = new EditBox(this.font, left, top + 56, 60, 18, Component.literal("weight"));
        startBox = new EditBox(this.font, left + 70, top + 56, 60, 18, Component.literal("start"));
        enterBox = new EditBox(this.font, left, top + 84, 60, 18, Component.literal("enter"));
        exitBox = new EditBox(this.font, left + 70, top + 84, 60, 18, Component.literal("exit"));
        this.addRenderableWidget(clipBox);
        this.addRenderableWidget(zoneBox);
        this.addRenderableWidget(weightBox);
        this.addRenderableWidget(startBox);
        this.addRenderableWidget(enterBox);
        this.addRenderableWidget(exitBox);

        clipBox.setResponder(v -> {
            ActionStore.Segment s = segment();
            if (!updating && s != null) {
                s.clip = v.trim();
            }
        });
        zoneBox.setResponder(v -> {
            ActionStore.Segment s = segment();
            if (!updating && s != null) {
                s.zone = v.trim();
            }
        });
        weightBox.setResponder(v -> withSegment(s -> s.weight = parse(v, s.weight, 0f, 4f)));
        startBox.setResponder(v -> withSegment(s -> s.startTick = parse(v, s.startTick, 0f, 10000f)));
        enterBox.setResponder(v -> withSegment(s -> {
            if (s.blend == null) s.blend = new ActionStore.Blend();
            s.blend.enterTicks = parse(v, s.blend.enterTicks, 0f, 1000f);
        }));
        exitBox.setResponder(v -> withSegment(s -> {
            if (s.blend == null) s.blend = new ActionStore.Blend();
            s.blend.exitTicks = parse(v, s.blend.exitTicks, 0f, 1000f);
        }));

        int bx = 20;
        int by = this.height - 58;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.add_segment"),
                        b -> addSegment())
                .bounds(bx, by, 100, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.del_segment"),
                        b -> removeSegment())
                .bounds(bx + 104, by, 100, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("<"),
                        b -> selectSegment(-1))
                .bounds(bx + 208, by, 20, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"),
                        b -> selectSegment(1))
                .bounds(bx + 232, by, 20, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.save_actions"),
                        b -> save())
                .bounds(this.width - 150, by, 120, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.back"),
                        b -> onClose())
                .bounds(this.width - 26, 8, 18, 18).build());
        syncFields();
    }

    private interface SegmentEdit {
        void apply(ActionStore.Segment s);
    }

    private void withSegment(SegmentEdit edit) {
        if (updating) {
            return;
        }
        ActionStore.Segment s = segment();
        if (s != null) {
            edit.apply(s);
        }
    }

    private static float parse(String text, float def, float min, float max) {
        try {
            return Math.max(min, Math.min(max, Float.parseFloat(text.trim())));
        } catch (RuntimeException e) {
            return def;
        }
    }

    private void syncFields() {
        ActionStore.Segment s = segment();
        updating = true;
        if (s == null) {
            clipBox.setValue("");
            zoneBox.setValue("");
            weightBox.setValue("1");
            startBox.setValue("0");
            enterBox.setValue("0");
            exitBox.setValue("0");
        } else {
            clipBox.setValue(s.clip == null ? "" : s.clip);
            zoneBox.setValue(s.zone == null ? "" : s.zone);
            weightBox.setValue(String.valueOf(s.weight));
            startBox.setValue(String.valueOf(s.startTick));
            enterBox.setValue(String.valueOf(s.blend == null ? 0f : s.blend.enterTicks));
            exitBox.setValue(String.valueOf(s.blend == null ? 0f : s.blend.exitTicks));
        }
        updating = false;
    }

    private void selectSegment(int dir) {
        ActionStore.Action a = action();
        if (a == null || a.segments.isEmpty()) {
            segmentIndex = 0;
        } else {
            segmentIndex = ((segmentIndex + dir) % a.segments.size() + a.segments.size()) % a.segments.size();
        }
        syncFields();
    }

    private void addSegment() {
        ActionStore.Action a = action();
        if (a == null) {
            return;
        }
        ActionStore.Segment s = new ActionStore.Segment();
        for (ActionStore.Action other : actions) {
            if (other != a && !other.internal) {
                s.clip = other.name;
                break;
            }
        }
        a.segments.add(s);
        segmentIndex = a.segments.size() - 1;
        syncFields();
    }

    private void removeSegment() {
        ActionStore.Action a = action();
        if (a == null || a.segments.isEmpty()) {
            return;
        }
        a.segments.remove(segmentIndex);
        segmentIndex = Math.max(0, Math.min(segmentIndex, a.segments.size() - 1));
        syncFields();
    }

    private void save() {
        boolean ok = ActionStore.save(modid, path, weapon, actions);
        status = Component.translatable(ok
                ? "gui.combatoverhaul.saved_actions"
                : "gui.combatoverhaul.write_failed").getString();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);
        ActionStore.Action a = action();
        if (a == null) {
            g.drawString(this.font, "no action", 20, 60, 0xFF5555);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }
        g.drawString(this.font, "action: " + a.name + "  length=" + a.lengthTicks
                + "  segments=" + a.segments.size(), 20, 34, 0xAAAAAA);
        // segment 列表（点击 < > 选择；当前项高亮）
        int y = 58;
        for (int i = 0; i < a.segments.size(); i++) {
            ActionStore.Segment s = a.segments.get(i);
            int color = i == segmentIndex ? 0xFFFF66 : 0xAAAAAA;
            String text = (s.clip.isEmpty() ? "(empty)" : s.clip)
                    + "  zone=" + (s.zone == null || s.zone.isEmpty() ? "(clip)" : s.zone)
                    + "  w=" + s.weight + "  start=" + s.startTick;
            g.drawString(this.font, text, this.width - 300, y + i * 18, color);
        }
        g.drawString(this.font, "clip", 20, 46, 0xAAAAAA);
        g.drawString(this.font, "zone (empty=clip)", 20, 74, 0xAAAAAA);
        g.drawString(this.font, "weight", 20, 102, 0xAAAAAA);
        g.drawString(this.font, "start", 90, 102, 0xAAAAAA);
        g.drawString(this.font, "blend enter", 20, 130, 0xAAAAAA);
        g.drawString(this.font, "blend exit", 90, 130, 0xAAAAAA);
        g.drawString(this.font, "进入编辑器 -> 选中父 action -> Play 预览组合", 20, this.height - 84, 0x888888);
        if (!status.isEmpty()) {
            g.drawString(this.font, status, 20, this.height - 74, 0xFFFFFF);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
