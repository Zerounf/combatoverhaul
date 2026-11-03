package com.yourname.combatoverhaul.store;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 模型配置文件存储：config/combatoverhaul/entities/&lt;modid&gt;/&lt;实体名&gt;.json
 *
 * <p>游戏内编辑与文件编辑双向同步：游戏内保存即写文件，游戏内打开即读文件。
 */
public final class ModelStore {
    /** 导出 JSON 的格式版本（结构变更时递增，用于将来迁移） */
    public static final int FORMAT_VERSION = 1;

    private ModelStore() {
    }

    /** 实体模型导出根目录 */
    public static Path entitiesRoot() {
        return FMLPaths.CONFIGDIR.get().resolve("combatoverhaul").resolve("entities");
    }

    /** 武器模型导出根目录：config/combatoverhaul/weapons/&lt;namespace&gt;/&lt;path&gt;.json */
    public static Path weaponsRoot() {
        return FMLPaths.CONFIGDIR.get().resolve("combatoverhaul").resolve("weapons");
    }

    /** 某武器模型的文件路径（ns/path 为物品注册名的命名空间与路径） */
    public static Path weaponFileFor(String ns, String path) {
        return weaponsRoot().resolve(sanitizeFileName(ns)).resolve(sanitizeFileName(path) + ".json");
    }

    public static boolean weaponExists(String ns, String path) {
        return Files.isRegularFile(weaponFileFor(ns, path));
    }

    public static boolean writeWeapon(String ns, String path, String content) {
        try {
            Path file = weaponFileFor(ns, path);
            writeAtomic(file, content);
            return true;
        } catch (IOException e) {
            com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.error("[CombatOverhaul] 写入武器模型失败: {}/{}", ns, path, e);
            return false;
        }
    }

    public static String readWeapon(String ns, String path) {
        try {
            Path file = weaponFileFor(ns, path);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.error("[CombatOverhaul] 读取武器模型失败: {}/{}", ns, path, e);
            return null;
        }
    }

    /** 实体注册名 -> 文件名（非法字符替换为下划线，避免路径问题） */
    public static String sanitizeFileName(String raw) {
        if (raw == null) {
            return "_";
        }
        String s = raw.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
        // "." / ".." 会逃出目标目录（例如白模名称输入 ".."）；空名也必须落成合法文件名。
        if (s.isEmpty() || s.equals(".") || s.equals("..")) {
            return "_";
        }
        return s;
    }

    /**
     * 解析可配置目录：绝对路径原样使用，相对路径以游戏目录（GAMEDIR）为基准。
     *
     * @param value    配置值，空/非法时用 fallback
     * @param fallback 相对游戏目录的默认路径，如 {@code config/combatoverhaul/blockbench}
     */
    public static Path resolveConfigured(String value, String fallback) {
        String s = value == null || value.isBlank() ? fallback : value.trim();
        Path p;
        try {
            p = Path.of(s);
        } catch (RuntimeException e) {
            p = Path.of(fallback);
        }
        return (p.isAbsolute() ? p : FMLPaths.GAMEDIR.get().resolve(p)).normalize();
    }

    /** 某实体的导出文件路径 */
    public static Path fileFor(String modid, String entityPath) {
        return entitiesRoot().resolve(sanitizeFileName(modid)).resolve(sanitizeFileName(entityPath) + ".json");
    }

    /** 该实体是否已导出过（已导出的文件在"补缺失"扫描时不会被覆盖，保留手动编辑） */
    public static boolean exists(String modid, String entityPath) {
        return Files.isRegularFile(fileFor(modid, entityPath));
    }

    /** 写文件（UTF-8，自动建目录）。返回是否成功。 */
    public static boolean write(String modid, String entityPath, String content) {
        try {
            Path file = fileFor(modid, entityPath);
            writeAtomic(file, content);
            return true;
        } catch (IOException e) {
            com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.error("[CombatOverhaul] 写入模型文件失败: {}/{}", modid, entityPath, e);
            return false;
        }
    }

    /**
     * 原子写：先写同目录 .tmp，再替换目标文件。
     * 直接 Files.writeString 到目标文件时，游戏崩溃/断电会留下半截 JSON，下一次保存又会把坏文件覆盖成空模型。
     */
    private static void writeAtomic(Path file, String content) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    /** 读文件内容（UTF-8）。文件不存在返回 null。 */
    public static String read(String modid, String entityPath) {
        try {
            Path file = fileFor(modid, entityPath);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.error("[CombatOverhaul] 读取模型文件失败: {}/{}", modid, entityPath, e);
            return null;
        }
    }
}
