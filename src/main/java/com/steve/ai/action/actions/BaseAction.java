package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;

public abstract class BaseAction {
    protected final SteveEntity steve;
    protected final Task task;
    protected ActionResult result;
    protected boolean started = false;
    protected boolean cancelled = false;

    public BaseAction(SteveEntity steve, Task task) {
        this.steve = steve;
        this.task = task;
    }

    public void start() {
        if (started)
            return;
        started = true;
        onStart();
    }

    public void tick() {
        if (!started || isComplete())
            return;
        onTick();
    }

    public void cancel() {
        cancelled = true;
        result = ActionResult.failure("Action cancelled");
        onCancel();
    }

    public boolean isComplete() {
        return result != null || cancelled;
    }

    public ActionResult getResult() {
        return result;
    }

    protected void markComplete(boolean success, String message) {
        if (isComplete())
            return;

        if (success) {
            result = ActionResult.success(message);
        } else {
            result = ActionResult.failure(message);
        }
    }

    protected abstract void onStart();

    protected abstract void onTick();

    protected abstract void onCancel();

    public abstract String getDescription();

    /**
     * Teleports the entity to a safe position near the target block.
     * Scans a 3x3 area for a valid standing spot (2 air blocks above a solid
     * block).
     * If no safe spot is found nearby, tries to teleport to the surface.
     */
    protected void teleportToSafePos(net.minecraft.core.BlockPos target) {
        net.minecraft.core.BlockPos safePos = findSafePos(target);
        if (safePos != null) {
            steve.setPos(safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5);
            com.steve.ai.SteveMod.LOGGER.info("Teleported to safe pos: {}", safePos);
        } else {
            // Fallback: Try to find surface
            int surfaceY = steve.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    target.getX(), target.getZ());
            steve.setPos(target.getX() + 0.5, surfaceY, target.getZ() + 0.5);
            com.steve.ai.SteveMod.LOGGER.warn("Could not find safe pos near {}, teleported to surface Y={}", target,
                    surfaceY);
        }
        steve.setDeltaMovement(0, 0, 0); // Stop movement
    }

    private net.minecraft.core.BlockPos findSafePos(net.minecraft.core.BlockPos center) {
        // Check center first
        if (isSafePos(center))
            return center;

        // Check 3x3 area around center
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = -1; y <= 2; y++) { // Check slightly up/down
                    if (x == 0 && y == 0 && z == 0)
                        continue;
                    net.minecraft.core.BlockPos pos = center.offset(x, y, z);
                    if (isSafePos(pos)) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    private boolean isSafePos(net.minecraft.core.BlockPos pos) {
        net.minecraft.world.level.Level level = steve.level();
        // Needs solid ground below (or at least something we can stand on)
        // We check if the block below has a collision shape that is not empty
        if (level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty())
            return false;

        // Needs space for body (no collision at pos and pos.above())
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() &&
                level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
    }
}
