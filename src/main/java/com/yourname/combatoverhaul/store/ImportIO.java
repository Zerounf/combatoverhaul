package com.yourname.combatoverhaul.store;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.combatoverhaul.CombatOverhaulMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/** Geo / 动画导入共用的文件枚举与 JSON 读取。 */
public final class ImportIO {

    private ImportIO() {
    }

    public static List<String> listJson(Path dir, String label) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            CombatOverhaulMod.LOGGER.warn("[CombatOverhaul] 无法创建{}目录 {}", label, dir, e);
        }
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase().endsWith(".json"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            CombatOverhaulMod.LOGGER.error("[CombatOverhaul] 读取{}目录失败", label, e);
            return List.of();
        }
    }

    /** 读取并解析 UTF-8 JSON 对象；IO/语法错误由调用方统一转换成用户提示。 */
    public static JsonObject readJson(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
