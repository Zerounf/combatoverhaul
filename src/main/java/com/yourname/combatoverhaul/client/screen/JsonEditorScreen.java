package com.yourname.combatoverhaul.client.screen;

import com.google.gson.JsonParser;
import com.yourname.combatoverhaul.combat.ActionCache;
import com.yourname.combatoverhaul.store.ModelStore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * JSON 文本编辑器：读取实体模型文件内容，游戏内编辑后写回
 * config/combatoverhaul/entities/&lt;modid&gt;/&lt;entity&gt;.json。
 *
 * <p>保存时校验 JSON 语法并提示（语法无效仍允许保存原样，避免丢失编辑内容）。
 */
public class JsonEditorScreen extends Screen {
    private final Screen parent;
    private final String modid;
    private final String entityPath;

    private MultiLineEditBox box;
    private String status = "";

    public JsonEditorScreen(Screen parent, String modid, String entityPath) {
        super(Component.translatable("gui.combatoverhaul.json_title", modid, entityPath));
        this.parent = parent;
        this.modid = modid;
        this.entityPath = entityPath;
    }

    @Override
    protected void init() {
        // 窗口 resize 会重新执行 init：此时必须保留用户正在编辑的文本，
        // 不能从磁盘重读把未保存的修改覆盖掉。
        String content;
        if (box != null) {
            content = box.getValue();
        } else {
            content = ModelStore.read(modid, entityPath);
            if (content == null) {
                content = Component.translatable("gui.combatoverhaul.file_missing").getString();
            }
        }

        box = new MultiLineEditBox(this.font, 20, 40, this.width - 40, this.height - 84,
                Component.literal(""), Component.literal(""));
        box.setCharacterLimit(Integer.MAX_VALUE);
        box.setValue(content);
        this.addRenderableWidget(box);

        int y = this.height - 32;
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.combatoverhaul.save"), b -> save())
                .bounds(this.width / 2 - 105, y, 100, 20).build());
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.combatoverhaul.back"), b -> onClose())
                .bounds(this.width / 2 + 5, y, 100, 20).build());

        status = Component.translatable("gui.combatoverhaul.file_path",
                ModelStore.fileFor(modid, entityPath)).getString();
    }

    private void save() {
        String text = box.getValue();
        boolean valid;
        try {
            JsonParser.parseString(text);
            valid = true;
        } catch (RuntimeException e) {
            valid = false;
        }
        boolean written = ModelStore.write(modid, entityPath, text);
        if (written) {
            // 游戏内渲染/判定每帧走 ActionCache；不失效会一直用旧 ParsedModel
            ActionCache.invalidate(modid, entityPath, false);
        }
        status = Component.translatable(
                written ? "gui.combatoverhaul.saved" : "gui.combatoverhaul.write_failed").getString()
                + (valid
                ? Component.translatable("gui.combatoverhaul.json_ok").getString()
                : Component.translatable("gui.combatoverhaul.json_bad").getString());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        g.drawCenteredString(this.font, status, this.width / 2, this.height - 44, 0xFFA0FFA0);
    }

    @Override
    public void tick() {
        super.tick();
        box.tick();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
