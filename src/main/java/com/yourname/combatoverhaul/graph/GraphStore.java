package com.yourname.combatoverhaul.graph;

import com.google.gson.Gson;
import com.yourname.combatoverhaul.store.SafePath;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 每个模型一张事件图：config/combatoverhaul/graphs/&lt;namespace&gt;/&lt;entity_path&gt;.json */
public final class GraphStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .disableHtmlEscaping().create();
    private static final Map<String, Graph> CACHE = new ConcurrentHashMap<>();

    private GraphStore() {
    }

    public static Path fileFor(String namespace, String path) {
        return FMLPaths.CONFIGDIR.get().resolve("combatoverhaul").resolve("graphs")
                .resolve(SafePath.safe(namespace)).resolve(SafePath.safe(path) + ".json");
    }

    public static Graph load(String namespace, String path) {
        String key = key(namespace, path);
        Graph cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Graph g = null;
        try {
            Path f = fileFor(namespace, path);
            if (Files.isRegularFile(f)) {
                g = Graph.parse(JsonParser.parseString(
                        Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject());
            }
        } catch (IOException | RuntimeException e) {
            com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.warn(
                    "[CombatOverhaul] 读取事件图失败 {}/{}", namespace, path, e);
        }
        if (g == null) {
            g = new Graph();
        }
        CACHE.put(key, g);
        return g;
    }

    public static boolean save(String namespace, String path, Graph graph) {
        try {
            Path f = fileFor(namespace, path);
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(graph.toJson()), StandardCharsets.UTF_8);
            CACHE.put(key(namespace, path), graph);
            return true;
        } catch (IOException e) {
            com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.error(
                    "[CombatOverhaul] 写入事件图失败 {}/{}", namespace, path, e);
            return false;
        }
    }

    public static void invalidate(String namespace, String path) {
        CACHE.remove(key(namespace, path));
    }

    private static String key(String namespace, String path) {
        return namespace + "/" + path;
    }

}
