package com.yourname.combatoverhaul.client.screen;

import com.yourname.combatoverhaul.client.screen.ui.EditorDraw;
import com.yourname.combatoverhaul.rig.Rig;
import com.yourname.combatoverhaul.rig.RigStore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 点状 rig 编辑器：左侧点名，中间俯视点图（x/y），右侧精确 pivot。
 * 拖动点直接改 pivot，保存到 config/combatoverhaul/rigs/ns/path.json。
 */
public final class RigEditorScreen extends Screen {

    private final String rigId;
    private Rig rig;
    private int selected;
    private boolean dragging;
    private double lastX;
    private double lastY;
    private EditBox px;
    private EditBox py;
    private EditBox pz;
    private String status = "";

    private int listW = 170;
    private int propW = 160;

    public RigEditorScreen(String rigId) {
        super(Component.literal("Rig 点编辑"));
        this.rigId = rigId == null || rigId.isBlank() ? "combatoverhaul:humanoid" : rigId;
        this.rig = load();
    }

    private Rig load() {
        try {
            Rig r = RigStore.get(new ResourceLocation(rigId));
            return r == null ? Rig.humanoid() : r;
        } catch (RuntimeException e) {
            return Rig.humanoid();
        }
    }

    @Override
    protected void init() {
        clearWidgets();
        listW = Math.min(190, Math.max(130, width / 5));
        propW = Math.min(180, Math.max(130, width / 6));
        addRenderableWidget(Button.builder(Component.literal("保存"), b -> save())
                .bounds(width - propW + 4, height - 28, (propW - 12) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(width - propW + 8 + (propW - 12) / 2, height - 28,
                        (propW - 12) / 2, 20).build());
        int x = width - propW + 8;
        px = new EditBox(font, x, 96, propW - 16, 18, Component.literal("x"));
        py = new EditBox(font, x, 142, propW - 16, 18, Component.literal("y"));
        pz = new EditBox(font, x, 188, propW - 16, 18, Component.literal("z"));
        for (EditBox b : new EditBox[]{px, py, pz}) {
            addRenderableWidget(b);
        }
        px.setResponder(v -> setPivot(0, v));
        py.setResponder(v -> setPivot(1, v));
        pz.setResponder(v -> setPivot(2, v));
        syncFields();
    }

    private Rig.Point point() {
        if (rig == null || rig.points.isEmpty()) {
            return null;
        }
        selected = Math.max(0, Math.min(selected, rig.points.size() - 1));
        return rig.points.get(selected);
    }

    private void syncFields() {
        Rig.Point p = point();
        if (p == null) {
            return;
        }
        if (px != null && !px.isFocused()) {
            px.setValue(fmt(p.pivot[0]));
        }
        if (py != null && !py.isFocused()) {
            py.setValue(fmt(p.pivot[1]));
        }
        if (pz != null && !pz.isFocused()) {
            pz.setValue(fmt(p.pivot[2]));
        }
    }

    private void setPivot(int axis, String text) {
        Rig.Point p = point();
        if (p == null) {
            return;
        }
        try {
            p.pivot[axis] = Float.parseFloat(text.trim());
        } catch (RuntimeException ignored) {
        }
    }

    private void save() {
        try {
            ResourceLocation id = new ResourceLocation(rigId);
            status = RigStore.save(id, rig) ? "已保存 " + rigId : "保存失败";
        } catch (RuntimeException e) {
            status = "rig id 无效";
        }
    }

    private int cx() {
        return listW + (width - listW - propW) / 2;
    }

    private int cy() {
        return height / 2;
    }

    private static int sx(Rig.Point p, int cx) {
        return cx + Math.round(p.pivot[0] * 2f);
    }

    private static int sy(Rig.Point p, int cy) {
        return cy + Math.round(p.pivot[1] * 2f);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.fill(0, 0, width, height, 0xFF11151B);
        g.fill(0, 0, listW, height, 0xFF20242C);
        g.fill(width - propW, 0, width, height, 0xFF20242C);
        g.drawString(font, "Rig 点编辑  " + rigId, 6, 6, 0xFFFFFFFF, false);
        g.drawString(font, "点", 6, 24, 0xFF9FA8B8, false);
        int y = 36;
        for (int i = 0; i < rig.points.size(); i++) {
            Rig.Point p = rig.points.get(i);
            boolean sel = i == selected;
            g.fill(4, y, listW - 4, y + 16, sel ? 0xFF3A4A6A : 0x00000000);
            g.drawString(font, p.name + (p.semantic == null || p.semantic.isBlank()
                    ? "" : "  (" + p.semantic + ")"), 8, y + 4,
                    sel ? 0xFFFFFFFF : 0xFFB8C0D0, false);
            y += 17;
            if (y > height - 30) {
                break;
            }
        }
        int cx = cx();
        int cy = cy();
        g.fill(listW, 0, width - propW, height, 0xFF0B0E13);
        // 网格
        for (int i = -20; i <= 20; i++) {
            int gx = cx + i * 16;
            int gy = cy + i * 16;
            if (gx > listW && gx < width - propW) {
                g.fill(gx, 20, gx + 1, height - 20, 0x221F6FFF);
            }
            if (gy > 20 && gy < height - 20) {
                g.fill(listW, gy, width - propW, gy + 1, 0x221F6FFF);
            }
        }
        for (Rig.Point p : rig.points) {
            if (p.parent == null || p.parent.isBlank()) {
                continue;
            }
            Rig.Point par = rig.point(p.parent);
            if (par == null) {
                continue;
            }
            EditorDraw.line(g, sx(par, cx), sy(par, cy), sx(p, cx), sy(p, cy), 0xFF43506A);
        }
        for (Rig.Point p : rig.points) {
            boolean sel = p == point();
            int x = sx(p, cx);
            int yy = sy(p, cy);
            g.fill(x - 3, yy - 3, x + 4, yy + 4, sel ? 0xFFFFFF55 : 0xFF55CCFF);
            if (sel) {
                g.drawString(font, p.name, x + 6, yy - 4, 0xFFFFFF88, false);
            }
        }
        g.drawString(font, "拖动点改 x/y；右侧精确输入", listW + 8, 20,
                0xFF9FA8B8, false);
        g.drawString(font, "参数", width - propW + 8, 78, 0xFF9FA8B8, false);
        g.drawString(font, "x", width - propW + 8, 86, 0xFF9FA8B8, false);
        g.drawString(font, "y", width - propW + 8, 132, 0xFF9FA8B8, false);
        g.drawString(font, "z", width - propW + 8, 178, 0xFF9FA8B8, false);
        Rig.Point p = point();
        if (p != null) {
            g.drawString(font, "parent: " + p.parent, width - propW + 8, 216, 0xFFB8C0D0, false);
            g.drawString(font, "semantic: " + p.semantic, width - propW + 8, 230, 0xFFB8C0D0, false);
        }
        if (!status.isBlank()) {
            g.drawString(font, status, width - propW + 8, 250, 0xFF80FF80, false);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        if (mx < listW) {
            int idx = (int) ((my - 36) / 17);
            if (idx >= 0 && idx < rig.points.size()) {
                selected = idx;
                syncFields();
                return true;
            }
            return false;
        }
        if (mx > listW && mx < width - propW) {
            int cx = cx();
            int cy = cy();
            for (int i = 0; i < rig.points.size(); i++) {
                Rig.Point p = rig.points.get(i);
                if (Math.hypot(mx - sx(p, cx), my - sy(p, cy)) <= 8) {
                    selected = i;
                    dragging = true;
                    lastX = mx;
                    lastY = my;
                    syncFields();
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging && button == 0) {
            Rig.Point p = point();
            if (p != null) {
                p.pivot[0] += (float) (mx - lastX) / 2f;
                p.pivot[1] += (float) (my - lastY) / 2f;
                lastX = mx;
                lastY = my;
                syncFields();
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging && button == 0) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    private static String fmt(float f) {
        return String.valueOf(Math.round(f * 100f) / 100f);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
