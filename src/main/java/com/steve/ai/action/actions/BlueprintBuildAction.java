package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

public class BlueprintBuildAction extends BaseAction {
    private final List<BlockPlacement> placementQueue;
    private BlockPlacement currentPlacement;
    private int delayTicks;
    private int ticksStuck = 0;
    private double lastDistSqr = Double.MAX_VALUE;
    private int ticksRunning = 0;
    private int totalBlocks = 0;
    private int placedCount = 0;

    public BlueprintBuildAction(SteveEntity steve, Task task) {
        super(steve, task);
        this.placementQueue = new ArrayList<>();
        this.delayTicks = 0;

        parseBlueprint(task);
    }

    private void parseBlueprint(Task task) {
        Map<String, Object> params = task.getParameters();
        if (!params.containsKey("blocks"))
            return;

        List<Map<String, Object>> blocksList = (List<Map<String, Object>>) params.get("blocks");

        // Find a good spot to build
        BlockPos origin = findBuildOrigin();
        steve.sendChatMessage("I'm going to build at " + origin.toShortString());

        for (Map<String, Object> blockData : blocksList) {
            try {
                int x = ((Number) blockData.get("x")).intValue();
                int y = ((Number) blockData.get("y")).intValue();
                int z = ((Number) blockData.get("z")).intValue();
                String name = (String) blockData.get("name");

                Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(name));
                if (block == null || block == Blocks.AIR) {
                    block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:" + name));
                }

                if (block != null && block != Blocks.AIR) {
                    placementQueue.add(new BlockPlacement(origin.offset(x, y, z), block));
                }
            } catch (Exception e) {
                SteveMod.LOGGER.warn("Failed to parse blueprint block: {}", blockData);
            }
        }

        // Sort bottom-to-top to ensure support
        placementQueue.sort(Comparator.comparingInt(p -> p.pos.getY()));
        totalBlocks = placementQueue.size();
    }

    @Override
    protected void onStart() {
        if (placementQueue.isEmpty()) {
            steve.sendChatMessage("I couldn't figure out how to build that. The blueprint was empty.");
            markComplete(false, "Empty or invalid blueprint");
            return;
        }
        steve.sendChatMessage("Starting construction! I have " + totalBlocks + " blocks to place.");
        SteveMod.LOGGER.info("Starting blueprint build with {} blocks", placementQueue.size());
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        if (currentPlacement == null) {
            if (placementQueue.isEmpty()) {
                steve.sendChatMessage("I'm all done building!");
                markComplete(true, "Blueprint complete");
                return;
            }

            // Timeout check
            if (ticksRunning > 24000) { // 20 minutes max
                steve.sendChatMessage("This is taking too long, I'm going to stop for now.");
                markComplete(false, "Building timeout");
                return;
            }

            currentPlacement = placementQueue.remove(0);
        }

        // Skip air blocks in blueprint (don't destroy existing blocks with air)
        if (currentPlacement.block.defaultBlockState().isAir()) {
            currentPlacement = null;
            return;
        }

        double distSqr = steve.distanceToSqr(currentPlacement.pos.getX() + 0.5, currentPlacement.pos.getY() + 0.5,
                currentPlacement.pos.getZ() + 0.5);

        // Check if we are close enough to place
        if (distSqr < 25.0) { // Increased range slightly (5 blocks)
            // Look at the block
            steve.getLookControl().setLookAt(currentPlacement.pos.getX() + 0.5, currentPlacement.pos.getY() + 0.5,
                    currentPlacement.pos.getZ() + 0.5);

            if (ticksStuck > 10) {
                // If we are close but stuck (maybe can't see it?), try to place anyway
                placeBlock();
                ticksStuck = 0;
            } else {
                // Move to placement
                steve.getNavigation().moveTo(currentPlacement.pos.getX() + 0.5, currentPlacement.pos.getY() + 1.0,
                        currentPlacement.pos.getZ() + 0.5, 1.0);
            }
        } else {
            // Move to placement
            steve.getNavigation().moveTo(currentPlacement.pos.getX() + 0.5, currentPlacement.pos.getY() + 1.0,
                    currentPlacement.pos.getZ() + 0.5, 1.0);
        }

        // Check if stuck
        if (steve.getNavigation().isStuck() || (steve.getDeltaMovement().lengthSqr() < 0.0001 && distSqr > 4.0)) {
            ticksStuck++;
        } else {
            ticksStuck = 0;
        }

        if (ticksStuck > 200) { // 10 seconds
            if (distSqr > 100.0) {
                // Teleport closer if stuck and far away
                teleportToSafePos(currentPlacement.pos);
                steve.getNavigation().stop();
                ticksStuck = 0;
            } else {
                // Stuck but close. Try to jump.
                if (steve.onGround())
                    steve.getJumpControl().jump();
                ticksStuck = 180;
            }
            // Don't return, let it fall through to placement
        }

        // Try to place if close enough
        if (distSqr < 16.0) {
            placeBlock();
        }
    }

    private void placeBlock() {
        if (currentPlacement == null)
            return;

        // Only place if empty or replaceable (simple check)
        if (steve.level().isEmptyBlock(currentPlacement.pos)
                || steve.level().getBlockState(currentPlacement.pos).canBeReplaced()) {
            steve.level().setBlock(currentPlacement.pos, currentPlacement.block.defaultBlockState(), 3);
            steve.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            delayTicks = 4; // Short delay between placements
            currentPlacement = null; // Move to next
            placedCount++;

            // Progress update every 20 blocks
            if (placedCount % 20 == 0) {
                steve.sendChatMessage("Placed " + placedCount + " out of " + totalBlocks + " blocks.");
            }

        } else if (steve.level().getBlockState(currentPlacement.pos).getBlock() == currentPlacement.block) {
            // Already placed
            currentPlacement = null;
            placedCount++;
        }
    }

    @Override
    protected void onCancel() {
        placementQueue.clear();
        steve.sendChatMessage("Building cancelled.");
    }

    @Override
    public String getDescription() {
        return "Building custom blueprint";
    }

    private BlockPos findBuildOrigin() {
        // Try to find flat ground nearby
        BlockPos stevePos = steve.blockPosition();
        BlockPos bestPos = null;
        int bestScore = -1;

        // Scan 5x5 area around Steve (offset slightly forward)
        for (int x = -5; x <= 5; x += 2) {
            for (int z = -5; z <= 5; z += 2) {
                if (x == 0 && z == 0)
                    continue; // Don't build on self

                BlockPos pos = stevePos.offset(x, 0, z);
                int score = calculateFlatnessScore(pos);

                if (score > bestScore) {
                    bestScore = score;
                    bestPos = pos;
                }
            }
        }

        if (bestPos != null && bestScore > 5) {
            return bestPos;
        }

        // Fallback: Just build in front
        return stevePos.relative(steve.getDirection(), 5);
    }

    private int calculateFlatnessScore(BlockPos center) {
        int flatBlocks = 0;
        int airBlocks = 0;

        // Check 3x3 area
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos pos = center.offset(x, 0, z);
                // Check if ground is solid and space above is air
                if (!steve.level().isEmptyBlock(pos.below()) && steve.level().isEmptyBlock(pos)) {
                    flatBlocks++;
                }
                if (steve.level().isEmptyBlock(pos) && steve.level().isEmptyBlock(pos.above())) {
                    airBlocks++;
                }
            }
        }

        return flatBlocks + airBlocks;
    }

    private static class BlockPlacement {
        final BlockPos pos;
        final Block block;

        BlockPlacement(BlockPos pos, Block block) {
            this.pos = pos;
            this.block = block;
        }
    }
}
