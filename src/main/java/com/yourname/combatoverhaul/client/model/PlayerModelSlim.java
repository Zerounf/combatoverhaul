package com.yourname.combatoverhaul.client.model;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;

/** 判断当前玩家/实体渲染器实际是 Steve 还是 Alex 手臂。 */
public final class PlayerModelSlim {

    private PlayerModelSlim() {
    }

    public static boolean isSlim(LivingEntity entity) {
        try {
            var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
            if (renderer instanceof LivingEntityRenderer<?, ?> ler
                    && ler.getModel() instanceof HumanoidModel<?> hm
                    && hm.rightArm != null) {
                for (Field f : ModelPart.class.getDeclaredFields()) {
                    if (!java.util.List.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object value = f.get(hm.rightArm);
                    if (value instanceof java.util.List<?> list && !list.isEmpty()
                            && list.get(0) instanceof ModelPart.Cube cube) {
                        return cube.maxX - cube.minX < 3.5f;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return false;
    }
}
