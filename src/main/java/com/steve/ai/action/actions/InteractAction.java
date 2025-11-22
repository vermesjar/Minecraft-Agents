package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;

public class InteractAction extends BaseAction {
    private final String targetType;
    private final String interactionType;
    private Entity targetEntity;
    private int searchDelay;

    public InteractAction(SteveEntity steve, Task task) {
        super(steve, task);
        Map<String, Object> params = task.getParameters();
        this.targetType = (String) params.getOrDefault("target", "cow");
        this.interactionType = (String) params.getOrDefault("type", "feed");
        this.searchDelay = 0;
    }

    @Override
    protected void onStart() {
        findTarget();
    }

    private void findTarget() {
        AABB searchBox = steve.getBoundingBox().inflate(20.0);
        List<Entity> entities = steve.level().getEntities(steve, searchBox, e -> {
            String name = e.getType().getDescription().getString().toLowerCase();
            return name.contains(targetType.toLowerCase());
        });

        if (!entities.isEmpty()) {
            // Find closest
            entities.sort((e1, e2) -> Double.compare(e1.distanceToSqr(steve), e2.distanceToSqr(steve)));
            targetEntity = entities.get(0);
            SteveMod.LOGGER.info("Found interaction target: {}", targetEntity.getName().getString());
        } else {
            markComplete(false, "Could not find " + targetType);
        }
    }

    @Override
    protected void onTick() {
        if (targetEntity == null || !targetEntity.isAlive()) {
            if (searchDelay-- <= 0) {
                findTarget();
                searchDelay = 20;
            }
            return;
        }

        if (steve.distanceToSqr(targetEntity) > 9.0) {
            steve.getNavigation().moveTo(targetEntity, 1.0);
        } else {
            performInteraction();
        }
    }

    private void performInteraction() {
        steve.getNavigation().stop();
        steve.lookAt(targetEntity, 30.0f, 30.0f);

        if (interactionType.equals("feed") && targetEntity instanceof Animal animal) {
            if (animal.isBaby()) {
                // Already baby, maybe grow it?
            }

            // Simulate right-click interaction
            // In a real implementation, we'd need to check held items and use specific
            // interaction logic
            // For now, we'll assume the agent has the item or "magic" interaction for the
            // prototype
            steve.swing(net.minecraft.world.InteractionHand.MAIN_HAND);

            // Logic to actually feed would go here (requires inventory management)
            // For now, we just simulate the attempt

            markComplete(true, "Interacted with " + targetType);
        } else {
            // Generic interaction
            steve.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            markComplete(true, "Interacted with " + targetType);
        }
    }

    @Override
    protected void onCancel() {
        targetEntity = null;
    }

    @Override
    public String getDescription() {
        return "Interacting with " + targetType;
    }
}
