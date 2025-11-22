package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;

public class PathfindAction extends BaseAction {
    private BlockPos targetPos;
    private int ticksRunning;
    private static final int MAX_TICKS = 600; // 30 seconds timeout

    public PathfindAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        int x = task.getIntParameter("x", 0);
        int y = task.getIntParameter("y", 0);
        int z = task.getIntParameter("z", 0);

        targetPos = new BlockPos(x, y, z);
        ticksRunning = 0;

        steve.getNavigation().moveTo(x, y, z, 1.0);
    }

    private int ticksStuck = 0;
    private double lastDistSqr = Double.MAX_VALUE;

    @Override
    protected void onTick() {
        ticksRunning++;

        double distSqr = steve.blockPosition().distSqr(targetPos);

        if (distSqr < 4.0) { // Within 2 blocks
            result = ActionResult.success("Reached target position");
            return;
        }

        // Check if we are stuck
        if (Math.abs(distSqr - lastDistSqr) < 0.1) {
            ticksStuck++;
        } else {
            ticksStuck = 0;
            lastDistSqr = distSqr;
        }

        // Teleport failsafe if stuck close to target
        if (ticksStuck > 60 && distSqr < 100.0) {
            steve.setPos(targetPos.getX() + 0.5, targetPos.getY() + 1.0, targetPos.getZ() + 0.5);
            result = ActionResult.success("Reached target position (teleported)");
            return;
        }

        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Pathfinding timeout");
            return;
        }

        if (steve.getNavigation().isDone() && distSqr >= 4.0) {
            boolean pathFound = steve.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 1.0);
            if (!pathFound && distSqr < 100.0) {
                ticksStuck += 10;
            }
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Pathfind to " + targetPos;
    }
}
