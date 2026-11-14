package com.yourname.combatoverhaul.client.screen;

import com.yourname.combatoverhaul.client.model.WeaponModelGenerator;
import com.yourname.combatoverhaul.store.ActionStore;
import com.yourname.combatoverhaul.store.ModelStore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 武器授权编辑（武器模型动作）：把当前武器/白模模型的动作授权给若干武器物品。
 * 目标列表 = 已有模型配置的武器（●）∪ 全部注册物品；授权时缺模型会自动生成。
 */
public class WeaponGrantScreen extends ActionSelectionScreen {

    private boolean writeAll = false;
    private Button btnWriteAll;

    /** 已有模型文件的武器 id（●标记） */
    private final Set<String> withModel = new HashSet<>();
    /** 授权时自动生成模型失败的物品 id（标红） */
    private final Set<String> failed = new HashSet<>();

    public WeaponGrantScreen(Screen combatParent, String modid, String path, int actionIndex) {
        super(combatParent, modid, path, true, actionIndex, 104, 84,
                Component.translatable("gui.combatoverhaul.grant_title", modid, path));
    }

    /** 本次勾选/取消作用到的动作集合（整套=全部动作；否则仅当前动作） */
    private List<ActionStore.Action> targets() {
        List<ActionStore.Action> out = new ArrayList<>();
        if (writeAll || actionIndex < 0) {
            out.addAll(actions);
        } else if (action() != null) {
            out.add(action());
        }
        return out;
    }

    private boolean checked(String id) {
        for (ActionStore.Action a : targets()) {
            if (a.weapons.contains(id)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void rebuildIds() {
        withModel.clear();
        List<String> ids = new ArrayList<>();
        try {
            Path root = ModelStore.weaponsRoot();
            if (Files.isDirectory(root)) {
                try (var modStream = Files.list(root)) {
                    for (Path dir : modStream.filter(Files::isDirectory).toList()) {
                        String ns = dir.getFileName().toString();
                        if (ns.equals("whitebox")) {
                            continue;
                        }
                        try (var fileStream = Files.list(dir)) {
                            for (Path f : fileStream.toList()) {
                                String n = f.getFileName().toString();
                                if (!n.endsWith(".json") || n.endsWith(".actions.json")) {
                                    continue;
                                }
                                String id = ns + ":" + n.substring(0, n.length() - 5);
                                if (withModel.add(id)) {
                                    ids.add(id);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            String s = id.toString();
            if (withModel.add(s)) {
                ids.add(s);
            }
        }
        list.setVisibleRows(visibleRows());
        list.setAll(ids);
    }

    @Override
    protected void addExtraWidgets() {
        btnWriteAll = Button.builder(Component.empty(), b -> {
                    writeAll = !writeAll;
                    refreshWriteAll();
                }).bounds(10, 46, 110, 16).build();
        addRenderableWidget(btnWriteAll);
        refreshWriteAll();
    }

    private void refreshWriteAll() {
        if (btnWriteAll != null) {
            btnWriteAll.setMessage(Component.translatable(writeAll
                    ? "gui.combatoverhaul.grant_write_all_on" : "gui.combatoverhaul.grant_write_all"));
        }
    }

    @Override
    protected void toggle(String id) {
        List<ActionStore.Action> ts = targets();
        if (ts.isEmpty()) {
            status = Component.translatable("gui.combatoverhaul.no_action").getString();
            return;
        }
        if (checked(id)) {
            for (ActionStore.Action a : ts) {
                a.weapons.remove(id);
            }
            return;
        }
        for (ActionStore.Action a : ts) {
            if (!a.weapons.contains(id)) {
                a.weapons.add(id);
            }
        }
        String[] nsp = id.split(":", 2);
        if (nsp.length == 2 && !ModelStore.weaponExists(nsp[0], nsp[1]) && !failed.contains(id)) {
            String[] diag = new String[1];
            if (WeaponModelGenerator.ensureModel(id, diag)) {
                withModel.add(id);
                status = Component.translatable("gui.combatoverhaul.grant_generated", id).getString();
            } else {
                failed.add(id);
                status = Component.translatable("gui.combatoverhaul.grant_generate_failed",
                        id + " (" + diag[0] + ")").getString();
            }
        }
    }

    @Override
    protected void batchSet(boolean check) {
        List<ActionStore.Action> ts = targets();
        if (ts.isEmpty()) {
            status = Component.translatable("gui.combatoverhaul.no_action").getString();
            return;
        }
        int generated = 0;
        int genFailed = 0;
        for (String id : list.filtered) {
            if (check) {
                if (checked(id)) {
                    continue;
                }
                for (ActionStore.Action a : ts) {
                    if (!a.weapons.contains(id)) {
                        a.weapons.add(id);
                    }
                }
                String[] nsp = id.split(":", 2);
                if (nsp.length == 2 && !ModelStore.weaponExists(nsp[0], nsp[1])) {
                    String[] diag = new String[1];
                    if (WeaponModelGenerator.ensureModel(id, diag)) {
                        withModel.add(id);
                        generated++;
                    } else {
                        failed.add(id);
                        genFailed++;
                    }
                }
            } else {
                for (ActionStore.Action a : ts) {
                    a.weapons.remove(id);
                }
            }
        }
        if (check && generated + genFailed > 0) {
            status = Component.translatable("gui.combatoverhaul.grant_batch_done",
                    generated, genFailed).getString();
        }
    }

    @Override
    protected String actionLabel() {
        return "gui.combatoverhaul.combat_action";
    }

    @Override
    protected String hintKey() {
        return "gui.combatoverhaul.grant_hint";
    }

    @Override
    protected String searchKey() {
        return "gui.combatoverhaul.grant_search";
    }

    @Override
    protected void drawRow(GuiGraphics g, String id, int y, int mouseX, int mouseY) {
        boolean ck = checked(id);
        boolean bad = failed.contains(id);
        boolean has = withModel.contains(id);
        if (mouseX >= LIST_X && mouseX <= width - 10 && mouseY >= y && mouseY < y + ROW_H) {
            g.fill(LIST_X, y, width - 10, y + ROW_H, 0x33FFFFFF);
        }
        String mark = has ? "§a●" : "§8○";
        int color = bad ? 0xFF6060 : ck ? 0x80FF80 : 0xC0C0C0;
        g.drawString(font, (ck ? "§a[✓] §f" : "§7[ ] ") + mark + " " + id,
                LIST_X + 2, y + 2, color);
    }

    @Override
    protected void renderExtra(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, Component.translatable("gui.combatoverhaul.grant_count",
                checkedCount(), list.filtered.size()), 196, 28, 0x909090);
        g.drawString(font, Component.translatable("gui.combatoverhaul.grant_legend"),
                10, 70, 0x909090);
    }

    /** 当前授权目标集合中已勾选的武器数 */
    private int checkedCount() {
        Set<String> set = new HashSet<>();
        for (ActionStore.Action act : targets()) {
            set.addAll(act.weapons);
        }
        return set.size();
    }

    @Override
    protected Screen backScreen() {
        return new CombatPanelScreen(returnParent, modid, path, true, actionIndex);
    }
}
