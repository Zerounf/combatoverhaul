package com.yourname.combatoverhaul.client.screen;

import com.yourname.combatoverhaul.client.model.BoneSplitter;
import com.yourname.combatoverhaul.client.screen.ui.EditorButton;
import com.yourname.combatoverhaul.client.screen.ui.EditorTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 分段关节设置页：选择切分轴、段数、每段百分比。
 *
 * <p>确认后把比例交给 {@link ModelEditorScreen#applyBoneSplit}，由它真正切 cube、
 * 建子骨骼链并自动蒙皮。取消不会改模型。
 */
public class JointSplitScreen extends Screen {

    private static final int PANEL_W_MAX = 380;
    private static final int PANEL_H_MAX = 340;
    private static final int RATIO_COLS = 2;

    private final ModelEditorScreen parent;
    private final String boneName;
    private int axis;
    private int count;
    private float[] ratios; // 归一化比例；EditBox 显示百分比
    private EditBox[] boxes = new EditBox[0];
    private EditorButton axisButton;
    private String status = "";

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int buttonY;
    private int rowsTop;
    private int rowSpace;
    private int colW;

    public JointSplitScreen(ModelEditorScreen parent, String boneName, int axis, float[] initialRatios) {
        super(Component.literal("分段关节"));
        this.parent = parent;
        this.boneName = boneName;
        this.axis = Math.max(0, Math.min(2, axis));
        float[] safe = initialRatios == null ? new float[]{0.5f, 0.5f} : initialRatios.clone();
        if (safe.length < BoneSplitter.MIN_SEGMENTS) {
            safe = new float[]{0.5f, 0.5f};
        }
        if (safe.length > BoneSplitter.MAX_SEGMENTS) {
            float[] cut = new float[BoneSplitter.MAX_SEGMENTS];
            System.arraycopy(safe, 0, cut, 0, cut.length);
            safe = cut;
        }
        this.ratios = normalize(safe);
        this.count = safe.length;
    }

    @Override
    protected void init() {
        // 小窗口（GUI scale 大）时面板不能固定 380x340，否则确认按钮会掉到屏幕外。
        this.panelW = Math.max(300, Math.min(PANEL_W_MAX, this.width - 12));
        this.panelH = Math.min(PANEL_H_MAX, Math.max(160, this.height - 8));
        this.panelX = (this.width - panelW) / 2;
        this.panelY = Math.max(4, (this.height - panelH) / 2);
        this.buttonY = panelY + panelH - 24;
        this.rowsTop = panelY + (panelH < 280 ? 80 : panelH < 320 ? 88 : 96);
        this.rowSpace = panelH < 280 ? 20 : panelH < 320 ? 22 : 26;
        this.colW = Math.max(130, (panelW - 40) / 2);
        rebuild();
    }

    private void rebuild() {
        this.clearWidgets();
        int x = panelX + 20;
        int y = panelY + 44;

        axisButton = new EditorButton(x, y, 130, 18,
                Component.literal("轴：" + axisName(axis) + "（切换）"),
                b -> {
                    axis = (axis + 1) % 3;
                    b.setMessage(Component.literal("轴：" + axisName(axis) + "（切换）"));
                });
        this.addRenderableWidget(axisButton);

        this.addRenderableWidget(new EditorButton(x + 150, y, 22, 18, Component.literal("-"),
                b -> setCount(count - 1)));
        this.addRenderableWidget(new EditorButton(x + 240, y, 22, 18, Component.literal("+"),
                b -> setCount(count + 1)));
        this.addRenderableWidget(new EditorButton(x + 178, y, 56, 18,
                Component.literal("段数 " + count), b -> {
        }));

        boxes = new EditBox[count];
        int bx = x;
        int by = rowsTop + 8;
        for (int i = 0; i < count; i++) {
            int col = i % RATIO_COLS;
            int row = i / RATIO_COLS;
            int px = bx + col * colW;
            int py = by + row * rowSpace;
            EditBox box = new EditBox(this.font, px + 34, py, 70, 18, Component.literal(""));
            box.setMaxLength(6);
            box.setValue(fmt(ratios[i] * 100f));
            boxes[i] = box;
            this.addRenderableWidget(box);
        }

        int buttonW = Math.max(112, (panelW - 52) / 2);
        this.addRenderableWidget(new EditorButton(panelX + 20, buttonY, buttonW, 20,
                Component.literal("确认分段并蒙皮"), b -> confirm(), true));
        this.addRenderableWidget(new EditorButton(panelX + 20 + buttonW + 12, buttonY, buttonW, 20,
                Component.literal("取消"), b -> onClose()));
    }

    private void setCount(int next) {
        int n = Math.max(BoneSplitter.MIN_SEGMENTS, Math.min(BoneSplitter.MAX_SEGMENTS, next));
        if (n == count) {
            return;
        }
        count = n;
        float[] even = new float[count];
        java.util.Arrays.fill(even, 1f / count);
        ratios = even;
        rebuild();
    }

    private void confirm() {
        float[] pct = new float[count];
        float sum = 0f;
        for (int i = 0; i < count; i++) {
            try {
                pct[i] = Float.parseFloat(boxes[i].getValue().trim());
            } catch (RuntimeException e) {
                status = "第 " + (i + 1) + " 段不是数字";
                return;
            }
            if (!Float.isFinite(pct[i]) || pct[i] <= 0f) {
                status = "每段比例必须大于 0%";
                return;
            }
            sum += pct[i];
        }
        if (Math.abs(sum - 100f) > 0.55f) {
            status = "比例总和必须为 100%，当前 " + fmt(sum) + "%";
            return;
        }
        float[] fractions = new float[count];
        for (int i = 0; i < count; i++) {
            fractions[i] = pct[i] / 100f;
        }
        String error = parent.applyBoneSplit(boneName, axis, fractions);
        if (error != null) {
            status = error;
            return;
        }
        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, EditorTheme.BG_PANEL);
        g.renderOutline(panelX, panelY, panelW, panelH, EditorTheme.BORDER);
        g.drawString(this.font, "分段关节", panelX + 20, panelY + 12, EditorTheme.TEXT, false);
        g.drawString(this.font, "骨骼：" + boneName, panelX + 20, panelY + 28,
                EditorTheme.TEXT_DIM, false);

        // 比例条预览
        int barX = panelX + 20;
        int barY = panelY + 72;
        int barW = panelW - 40;
        float[] preview = currentRatios();
        float cursor = 0f;
        for (int i = 0; i < count; i++) {
            int x0 = barX + (int) (barW * cursor);
            cursor += preview[i];
            int x1 = barX + (int) (barW * cursor);
            int color = i == 0 ? 0xFF4F8FD0 : i == 1 ? 0xFFD07A4F : i == 2 ? 0xFF8FD04F
                    : i == 3 ? 0xFFD04FA0 : i == 4 ? 0xFF4FD0C0 : 0xFFC0A04F;
            g.fill(x0, barY, Math.max(x0 + 1, x1), barY + 8, color);
            g.renderOutline(x0, barY, Math.max(1, x1 - x0), 8, EditorTheme.BORDER);
        }

        // 标签（EditBox 之上）
        for (int i = 0; i < count; i++) {
            int col = i % RATIO_COLS;
            int row = i / RATIO_COLS;
            int px = panelX + 20 + col * colW;
            int py = rowsTop + 10 + row * rowSpace;
            g.drawString(this.font, "段 " + (i + 1), px, py + 5, EditorTheme.TEXT_DIM, false);
            g.drawString(this.font, "%", px + 108, py + 5, EditorTheme.TEXT_DIM, false);
        }

        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(this.font, "总比例：" + fmt(currentPercentTotal()) + "%",
                panelX + 20, buttonY - 42, Math.abs(currentPercentTotal() - 100f) < 0.55f
                        ? EditorTheme.OK : 0xFFFF7777, false);
        if (!status.isBlank()) {
            g.drawString(this.font, status, panelX + 20, buttonY - 26, 0xFFFF7777, false);
        }
        if (panelH >= 300) {
            g.drawString(this.font, "原骨骼保留名称/动作通道，后续段成为子骨骼链。",
                    panelX + 20, buttonY - 12, EditorTheme.TEXT_MUTED, false);
        }
    }

    private float[] currentRatios() {
        float[] pct = new float[count];
        float sum = 0f;
        for (int i = 0; i < count; i++) {
            try {
                float v = Float.parseFloat(boxes[i].getValue().trim());
                if (Float.isFinite(v) && v > 0f) {
                    pct[i] = v;
                    sum += v;
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (sum <= 1.0e-6f) {
            return ratios;
        }
        for (int i = 0; i < count; i++) {
            pct[i] /= sum;
        }
        return pct;
    }

    private float currentPercentTotal() {
        float sum = 0f;
        for (EditBox box : boxes) {
            if (box == null) {
                continue;
            }
            try {
                float v = Float.parseFloat(box.getValue().trim());
                if (Float.isFinite(v) && v > 0f) {
                    sum += v;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return sum;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter 也可以确认；即使按钮布局异常，用户仍有一条明确的提交路径。
        if (keyCode == 257 || keyCode == 335) {
            confirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void tick() {
        super.tick();
        for (EditBox box : boxes) {
            if (box != null) {
                box.tick();
            }
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    private static float[] normalize(float[] values) {
        float sum = 0f;
        for (float v : values) {
            sum += Math.max(0f, v);
        }
        float[] out = new float[values.length];
        if (sum <= 1.0e-6f) {
            java.util.Arrays.fill(out, 1f / out.length);
            return out;
        }
        for (int i = 0; i < values.length; i++) {
            out[i] = Math.max(0f, values[i]) / sum;
        }
        return out;
    }

    private static String axisName(int axis) {
        return axis == 0 ? "X" : axis == 1 ? "Y" : "Z";
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
