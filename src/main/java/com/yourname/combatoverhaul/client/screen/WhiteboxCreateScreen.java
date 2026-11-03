package com.yourname.combatoverhaul.client.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.yourname.combatoverhaul.combat.ActionCache;
import com.yourname.combatoverhaul.store.ModelStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 白模长方体武器创建：以坐标原点为手持位置，八个卦限分别独立设定长宽高，
 * 组合成一根整体长方体（典型用途：一根极长的棍/枪，用于给一批武器批量套动作）。
 *
 * <p>生成 weapons/whitebox/&lt;名称&gt;.json：format 1、单根骨骼 root（枢轴 0,0,0）、
 * 每个非零卦限一个立方体（origin 从原点沿卦限符号延伸 size），无 texture 字段
 * （编辑器预览走灰白棋盘兜底）。判定箱用「自动几何」按实际手持武器模型计算。
 *
 * <p>坐标约定与实体模型一致：模型空间 +Y 朝下（指向脚底），因此朝上方向是 -Y 卦限。
 */
public class WhiteboxCreateScreen extends Screen {
    /** 每卦限 3 个尺寸框（X/Y/Z）+ 卦限符号标签 */
    private static final int BOX_W = 44;
    private static final int ROW_H = 46;

    /** 8 个卦限的符号表 {sx, sy, sz}（模型空间坐标符号） */
    private static final int[][] OCTANTS = {
            {+1, +1, +1}, {-1, +1, +1},
            {+1, -1, +1}, {-1, -1, +1},
            {+1, +1, -1}, {-1, +1, -1},
            {+1, -1, -1}, {-1, -1, -1},
    };

    private final Screen parent;

    private EditBox nameBox;
    private final List<EditBox> octantBoxes = new ArrayList<>();
    /** 重建界面（resize/GUI 缩放）时保留已输入的尺寸 */
    private final int[] sizes = new int[OCTANTS.length * 3];
    private String status = "";

    public WhiteboxCreateScreen(Screen parent) {
        super(Component.translatable("gui.combatoverhaul.whitebox_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // 先把旧输入框里的数值收进 sizes，再重建；否则 resize 会把用户输入的尺寸清空
        for (int i = 0; i < octantBoxes.size() && i < sizes.length; i++) {
            try {
                sizes[i] = Math.max(0, Integer.parseInt(octantBoxes.get(i).getValue().trim()));
            } catch (NumberFormatException ignored) {
                sizes[i] = 0;
            }
        }
        octantBoxes.clear(); // 窗口 resize 会重跑 init，避免输入框累积
        int mid = this.width / 2;

        nameBox = new EditBox(this.font, mid - 100, 44, 200, 16,
                Component.translatable("gui.combatoverhaul.whitebox_name"));
        nameBox.setMaxLength(48);
        nameBox.setHint(Component.translatable("gui.combatoverhaul.whitebox_name_hint"));
        this.addRenderableWidget(nameBox);

        // 卦限网格：2 列 × 4 行；每行 = 卦限符号 + 宽X/高Y/厚Z 三个框
        // 小窗口下压缩行高，保证底部“创建/取消”按钮不会被挤出屏幕。
        int colW = 190;
        int x0 = mid - colW - 12;
        int x1 = mid + 12;
        gridY0 = 84;
        rowH = Math.min(ROW_H, Math.max(20, (this.height - 120) / 4));
        int y0 = gridY0;
        for (int i = 0; i < OCTANTS.length; i++) {
            int col = i % 2;
            int row = i / 2;
            int x = col == 0 ? x0 : x1;
            int y = y0 + row * rowH;
            int[] s = OCTANTS[i];
            for (int k = 0; k < 3; k++) {
                EditBox box = new EditBox(this.font, x + k * (BOX_W + 6), y + 12, BOX_W, 14,
                        Component.translatable("gui.combatoverhaul.whitebox_" + XYZ_NAMES[k]));
                box.setMaxLength(4);
                box.setFilter(v -> v.isEmpty() || v.matches("\\d{1,4}"));
                box.setHint(Component.literal("0"));
                if (sizes[octantBoxes.size()] > 0) {
                    box.setValue(String.valueOf(sizes[octantBoxes.size()]));
                }
                octantBoxes.add(box);
                this.addRenderableWidget(box);
            }
        }

        int by = y0 + 4 * rowH + 4;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.whitebox_create"),
                        b -> create()).bounds(mid - 100, by, 96, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.cancel"),
                        b -> this.minecraft.setScreen(parent)).bounds(mid + 4, by, 96, 20).build());
    }

    private int gridY0 = 84;
    private int rowH = ROW_H;

    private static final String[] XYZ_NAMES = {"x", "y", "z"};

    /** 组装并保存白模；成功后进入 3D 编辑器 */
    private void create() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) {
            status = Component.translatable("gui.combatoverhaul.whitebox_need_name").getString();
            return;
        }
        String safe = ModelStore.sanitizeFileName(name);
        if (ModelStore.weaponExists("whitebox", safe)) {
            // 覆盖会连带清掉该白模上已有的动作/授权；让用户换名或先手动删除
            status = Component.translatable("gui.combatoverhaul.whitebox_name_exists", safe).getString();
            return;
        }
        // 至少一个卦限非零
        int[] sizes = new int[OCTANTS.length * 3];
        boolean any = false;
        for (int i = 0; i < octantBoxes.size(); i++) {
            String v = octantBoxes.get(i).getValue().trim();
            int n = 0;
            try {
                n = Integer.parseInt(v);
            } catch (NumberFormatException ignored) {
            }
            n = Math.max(0, Math.min(2048, n));
            sizes[i] = n;
            any |= n > 0;
        }
        if (!any) {
            status = Component.translatable("gui.combatoverhaul.whitebox_need_size").getString();
            return;
        }

        JsonObject json = new JsonObject();
        json.addProperty("format", ModelStore.FORMAT_VERSION);
        json.addProperty("note", "whitebox " + name);
        JsonObject roots = new JsonObject();
        JsonObject root = new JsonObject();
        JsonArray pivot = new JsonArray();
        pivot.add(0);
        pivot.add(0);
        pivot.add(0);
        root.add("pivot", pivot);
        JsonArray cubes = new JsonArray();
        for (int i = 0; i < OCTANTS.length; i++) {
            int w = sizes[i * 3], h = sizes[i * 3 + 1], d = sizes[i * 3 + 2];
            if (w <= 0 || h <= 0 || d <= 0) {
                continue; // 任一轴为 0 视为跳过该卦限
            }
            int[] s = OCTANTS[i];
            ParsedCubeJson.addCube(cubes,
                    s[0] > 0 ? 0 : -w,
                    s[1] > 0 ? 0 : -h,
                    s[2] > 0 ? 0 : -d,
                    w, h, d);
        }
        root.add("cubes", cubes);
        roots.add("root", root);
        json.add("roots", roots);

        if (!ModelStore.writeWeapon("whitebox", safe, json.toString())) {
            status = Component.translatable("gui.combatoverhaul.write_failed").getString();
            return;
        }
        ActionCache.invalidate("whitebox", safe, true);
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new ModelEditorScreen(parent, "whitebox", safe, true));
    }

    /** 立方体 JSON 组装小工具（origin + size，无 UV → 编辑器棋盘兜底） */
    private static final class ParsedCubeJson {
        static void addCube(JsonArray cubes, int ox, int oy, int oz, int sx, int sy, int sz) {
            JsonObject c = new JsonObject();
            JsonArray origin = new JsonArray();
            origin.add(ox);
            origin.add(oy);
            origin.add(oz);
            c.add("origin", origin);
            JsonArray size = new JsonArray();
            size.add(sx);
            size.add(sy);
            size.add(sz);
            c.add("size", size);
            cubes.add(c);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);
        g.drawCenteredString(this.font,
                Component.translatable("gui.combatoverhaul.whitebox_hint").getString(),
                this.width / 2, 30, 0x909090);
        g.drawString(this.font, Component.translatable("gui.combatoverhaul.whitebox_name").getString(),
                nameBox.getX(), nameBox.getY() - 10, 0xC0C0C0);

        int mid = this.width / 2;
        int colW = 190;
        int y0 = gridY0;
        for (int i = 0; i < OCTANTS.length; i++) {
            int col = i % 2;
            int row = i / 2;
            int x = (col == 0 ? mid - colW - 12 : mid + 12);
            int y = y0 + row * rowH;
            int[] s = OCTANTS[i];
            String tag = (s[0] > 0 ? "+X" : "-X") + (s[1] > 0 ? "+Y" : "-Y") + (s[2] > 0 ? "+Z" : "-Z");
            g.drawString(this.font, tag, x, y, 0x7FD8FF);
            g.drawString(this.font, Component.translatable("gui.combatoverhaul.whitebox_axes").getString(),
                    x + 46, y, 0x909090);
        }
        if (!status.isEmpty()) {
            g.drawString(this.font, status, this.width / 2 - this.font.width(status) / 2,
                    this.height - 24, 0xFF8080);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
