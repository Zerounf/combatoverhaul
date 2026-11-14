package com.yourname.combatoverhaul.rig;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.yourname.combatoverhaul.store.SafePath;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 点状 rig 文件：config/combatoverhaul/rigs/&lt;namespace&gt;/&lt;path&gt;.json */
public final class RigStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .disableHtmlEscaping().create();
    private static final Map<String, Rig> CACHE = new ConcurrentHashMap<>();

    private RigStore() {
    }

    public static Path root() {
        return FMLPaths.CONFIGDIR.get().resolve("combatoverhaul").resolve("rigs");
    }

    public static Path fileFor(ResourceLocation id) {
        return root().resolve(SafePath.safe(id.getNamespace()))
                .resolve(SafePath.safe(id.getPath()) + ".json");
    }

    public static Rig get(ResourceLocation id) {
        if (id == null) {
            return null;
        }
        String key = id.toString();
        Rig cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Rig loaded = load(id);
        if (loaded == null && "combatoverhaul:humanoid".equals(key)) {
            loaded = Rig.humanoid();
        }
        if (loaded != null) {
            CACHE.put(key, loaded);
        }
        return loaded;
    }

    public static Rig load(ResourceLocation id) {
        try {
            Path f = fileFor(id);
            if (!Files.isRegularFile(f)) {
                return null;
            }
            String text = Files.readString(f, StandardCharsets.UTF_8);
            return Rig.parse(JsonParser.parseString(text).getAsJsonObject(), id.toString());
        } catch (IOException | RuntimeException e) {
            com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.warn(
                    "[CombatOverhaul] 读取 rig 失败 {}", id, e);
            return null;
        }
    }

    public static boolean save(ResourceLocation id, Rig rig) {
        try {
            Path f = fileFor(id);
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(rig.toJson()), StandardCharsets.UTF_8);
            CACHE.put(id.toString(), rig);
            return true;
        } catch (IOException e) {
            com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.error(
                    "[CombatOverhaul] 写入 rig 失败 {}", id, e);
            return false;
        }
    }

    public static void invalidate(ResourceLocation id) {
        if (id != null) {
            CACHE.remove(id.toString());
        }
    }

}
