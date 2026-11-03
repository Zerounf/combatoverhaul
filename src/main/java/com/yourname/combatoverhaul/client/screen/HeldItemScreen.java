package com.yourname.combatoverhaul.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 手持物品选择器：搜索游戏内物品，确认后把「物品 + 挂接骨骼」写回实体模型编辑器
 * （随「保存模型」持久化到实体模型 JSON 的 held_items 字段）。
 *
 * <p>渲染规则：直接调用游戏内原版物品渲染（第三人称右手方式），挂在骨骼上随动画运动；
 * 同一生物可同时在多个不同骨骼上手持多个不同物品。
 */
public class HeldItemScreen extends Screen {
    private static final int ROW_H = 16;

    private final Screen parent;
    private final String boneName;
    private final String currentItem;
    private final Consumer<String> onPick;

    private final List<ResourceLocation> allItems = new ArrayList<>();
    private final List<ResourceLocation> filtered = new ArrayList<>();
    private EditBox searchBox;
    private String search = "";
    private int selected = -1;
    private int scroll = 0;
    private String status = "";

    private int lx0, lx1, ly0, ly1;

    /**
     * @param onPick 确认回调：物品注册名（清除时为 null）；界面随后自动返回编辑器
     */
    public HeldItemScreen(Screen parent, String boneName, String currentItem, Consumer<String> onPick) {
        super(Component.translatable("gui.combatoverhaul.held_title", boneName));
        this.parent = parent;
        this.boneName = boneName;
        this.currentItem = currentItem;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        lx0 = 15;
        lx1 = this.width - 15;
        ly0 = 46;
        ly1 = this.height - 60;

        if (allItems.isEmpty()) {
            for (ResourceLocation key : ForgeRegistries.ITEMS.getKeys()) {
                allItems.add(key);
            }
            allItems.sort((a, b) -> (a.getNamespace() + a.getPath()).compareTo(b.getNamespace() + b.getPath()));
        }
        refreshFiltered();
        if (currentItem != null && !currentItem.isEmpty()) {
            ResourceLocation cur = ResourceLocation.tryParse(currentItem);
            int idx = cur != null ? filtered.indexOf(cur) : -1;
            if (idx >= 0) {
                selected = idx;
                int cols = Math.max(1, (lx1 - lx0 - 8) / 180);
                scroll = Math.max(0, (idx / cols) * ROW_H - (ly1 - ly0) / 2);
            }
        }

        searchBox = new EditBox(this.font, lx0 + 110, 24, 220, 16,
                Component.translatable("gui.combatoverhaul.weapon_search"));
        searchBox.setMaxLength(64);
        searchBox.setResponder(s -> {
            search = s.trim().toLowerCase(Locale.ROOT);
            refreshFiltered();
        });
        this.addRenderableWidget(searchBox);

        int y = this.height - 46;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.held_confirm"), b -> confirm())
                .bounds(lx0, y, 80, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.held_clear"), b -> clear())
                .bounds(lx0 + 88, y, 80, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.back"), b -> onClose())
                .bounds(lx0 + 176, y, 80, 20).build());
    }

    private void refreshFiltered() {
        filtered.clear();
        for (ResourceLocation id : allItems) {
            if (search.isEmpty() || (id.getNamespace() + ":" + id.getPath()).contains(search)) {
                filtered.add(id);
            }
        }
        selected = -1;
        scroll = 0;
    }

    private void confirm() {
        if (selected < 0 || selected >= filtered.size()) {
            status = Component.translatable("gui.combatoverhaul.weapon_select").getString();
            return;
        }
        onPick.accept(filtered.get(selected).toString());
        this.minecraft.setScreen(parent);
    }

    private void clear() {
        onPick.accept(null);
        this.minecraft.setScreen(parent);
    }

    // ------------------------------------------------------------------ 渲染

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
        g.drawString(this.font, Component.translatable("gui.combatoverhaul.weapon_search").getString(), lx0, 29, 0xFFFFFF);
        g.drawString(this.font, Component.translatable("gui.combatoverhaul.weapon_items", filtered.size()).getString(),
                lx0, ly0 - 12, 0xFFFFFF);

        g.fill(lx0, ly0, lx1, ly1, 0x88000000);
        g.renderOutline(lx0, ly0, lx1 - lx0, ly1 - ly0, 0xFF555555);

        int cols = Math.max(1, (lx1 - lx0 - 8) / 180);
        g.enableScissor(lx0, ly0, lx1, ly1);
        for (int i = 0; i < filtered.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int x = lx0 + 4 + col * 180;
            int y = ly0 + 2 + row * ROW_H - scroll;
            if (y > ly1) {
                break;
            }
            if (y < ly0 - ROW_H) {
                continue;
            }
            boolean sel = i == selected;
            if (sel) {
                g.fill(x - 2, y, x + 176, y + ROW_H, 0x663399FF);
            }
            ResourceLocation id = filtered.get(i);
            Item item = ForgeRegistries.ITEMS.getValue(id);
            if (item != null) {
                g.renderItem(new ItemStack(item), x, y);
            }
            String label = id.getNamespace() + ":" + id.getPath();
            g.drawString(this.font, clip(label, 140), x + 20, y + 4, sel ? 0xFFFFFF : 0xC8C8C8);
        }
        g.disableScissor();

        super.render(g, mouseX, mouseY, partialTick);

        g.drawString(this.font, Component.translatable("gui.combatoverhaul.held_hint", boneName).getString(),
                lx0, this.height - 24, 0x909090);
        if (!status.isEmpty()) {
            g.drawCenteredString(this.font, status, this.width / 2, this.height - 66, 0xFFA0FFA0);
        }
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
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (mouseX >= lx0 && mouseX <= lx1 && mouseY >= ly0 && mouseY <= ly1) {
            int cols = Math.max(1, (lx1 - lx0 - 8) / 180);
            int col = (int) ((mouseX - lx0 - 4) / 180);
            int row = (int) ((mouseY - ly0 - 2 + scroll) / ROW_H);
            int idx = row * cols + col;
            // 注意不能用「可见行数」做上限——滚动后 row 含 scroll 偏移，会被错误拒绝
            if (col >= 0 && col < cols && row >= 0 && idx >= 0 && idx < filtered.size()) {
                selected = idx;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= lx0 && mouseX <= lx1 && mouseY >= ly0 && mouseY <= ly1) {
            int cols = Math.max(1, (lx1 - lx0 - 8) / 180);
            int rows = (filtered.size() + cols - 1) / cols;
            int maxScroll = Math.max(0, rows * ROW_H - (ly1 - ly0 - 4));
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) delta * ROW_H * 3));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
