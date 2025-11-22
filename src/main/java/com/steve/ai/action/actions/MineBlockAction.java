package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MineBlockAction extends BaseAction {
    private enum MiningState {
        SEARCHING,
        MOVING_TO_BLOCK,
        MINING,
        RETURNING
    }

    private MiningState currentState;
    private Block targetBlock;
    private int targetQuantity;
    private int minedCount;
    private BlockPos currentTargetBlockPos;
    private BlockPos returnPos; // Where to return items
    private int ticksRunning;
    private int ticksSinceLastPathCalc = 0;
    private final java.util.Set<BlockPos> veinMineQueue = new java.util.HashSet<>();
    private static final int MAX_TICKS = 12000; // 10 minutes max
    private static final int SEARCH_RADIUS = 32;

    public MineBlockAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        String blockName = task.getStringParameter("block");
        targetQuantity = task.getIntParameter("quantity", 1); // Default to 1
        minedCount = 0;
        ticksRunning = 0;
        currentState = MiningState.SEARCHING;

        targetBlock = parseBlock(blockName);

        if (targetBlock == null || targetBlock == Blocks.AIR) {
            result = ActionResult.failure("Invalid block type: " + blockName);
            return;
        }

        // Disable flying to prevent clipping and weird movement
        steve.setFlying(false);

        equipIronPickaxe();
        steve.sendChatMessage(
                "I'm going to mine " + targetQuantity + " " + targetBlock.getName().getString() + " for you.");
        SteveMod.LOGGER.info("Steve '{}' starting intelligent mining for {} {}. Return pos: {}",
                steve.getSteveName(), targetQuantity, targetBlock.getName().getString(), returnPos);
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        if (ticksRunning > MAX_TICKS) {
            steve.setFlying(false);
            steve.sendChatMessage("I couldn't finish mining in time. I got " + minedCount + " blocks. I am at "
                    + steve.blockPosition().toShortString());
            result = ActionResult.failure("Mining timeout. Found " + minedCount + " blocks.");
            return;
        }

        switch (currentState) {
            case SEARCHING:
                handleSearching();
                break;
            case MOVING_TO_BLOCK:
                handleMovingToBlock();
                break;
            case MINING:
                handleMining();
                break;
            case RETURNING:
                handleReturning();
                break;
        }
    }

    private void handleSearching() {
        steve.getNavigation().stop();

        // Scan nearby blocks
        BlockPos foundPos = null;

        // Priority: Vein mine queue
        if (!veinMineQueue.isEmpty()) {
            foundPos = veinMineQueue.iterator().next();
            veinMineQueue.remove(foundPos);
            // Verify it's still there
            if (!isSameOre(steve.level().getBlockState(foundPos).getBlock(), targetBlock)) {
                foundPos = null; // Block gone, search again
            }
        }

        if (foundPos == null) {
            foundPos = scanForTargetBlock();
        }

        if (foundPos != null) {
            currentTargetBlockPos = foundPos;
            currentState = MiningState.MOVING_TO_BLOCK;
            if (minedCount == 0) {
                steve.sendChatMessage(
                        "Found " + targetBlock.getName().getString() + " at " + foundPos.toShortString() + "!");
            }
            SteveMod.LOGGER.info("Found {} at {}", targetBlock.getName().getString(), foundPos);
        } else {
            // For deep ores (diamonds, etc.), dig downwards using staircase
            if (isDeepOre(targetBlock) && steve.getY() > 16) {
                digStaircase();
            } else {
                // Wander randomly if not found nearby
                if (steve.getNavigation().isDone()) {
                    // Increased wander radius to 16 to cover more ground
                    Vec3 randomPos = DefaultRandomPos.getPos(steve, 16, 7);
                    if (randomPos != null) {
                        steve.getNavigation().moveTo(randomPos.x, randomPos.y, randomPos.z, 1.0);
                    }
                }
            }
        }
    }

    private void digStaircase() {
        // Dig a 3x3 staircase down in the direction we are facing
        Direction dir = steve.getDirection();
        BlockPos pos = steve.blockPosition();

        // Pattern: Forward 1, Down 1. Clear 3 high, 2 wide for easy passage.
        BlockPos nextStep = pos.relative(dir).below();

        // Check if we can dig here (don't dig bedrock or unbreakable)
        if (steve.level().getBlockState(nextStep).getDestroySpeed(steve.level(), nextStep) < 0) {
            // Hit bedrock or something hard, turn around
            steve.setYRot(steve.getYRot() + 180);
            return;
        }

        // Clear area for staircase
        List<BlockPos> toClear = new ArrayList<>();
        toClear.add(nextStep); // The step
        toClear.add(nextStep.above()); // Head space
        toClear.add(nextStep.above(2)); // Extra head space

        // Widen the path
        Direction right = dir.getClockWise();
        toClear.add(nextStep.relative(right));
        toClear.add(nextStep.relative(right).above());
        toClear.add(nextStep.relative(right).above(2));

        boolean dug = false;
        for (BlockPos p : toClear) {
            if (!steve.level().isEmptyBlock(p)) {
                steve.level().destroyBlock(p, true);
                dug = true;
            }
        }

        if (dug) {
            steve.getNavigation().moveTo(nextStep.getX() + 0.5, nextStep.getY(), nextStep.getZ() + 0.5, 1.0);
            SteveMod.LOGGER.info("Digging staircase down to Y={}", nextStep.getY());
        } else {
            // If nothing to dig, just move
            steve.getNavigation().moveTo(nextStep.getX() + 0.5, nextStep.getY(), nextStep.getZ() + 0.5, 1.0);
        }
    }

    private int ticksStuck = 0;
    private double lastDistSqr = Double.MAX_VALUE;

    private void handleMovingToBlock() {
        if (currentTargetBlockPos == null) {
            currentState = MiningState.SEARCHING;
            return;
        }

        double distSqr = steve.blockPosition().distSqr(currentTargetBlockPos);

        // Check if we are stuck
        if (Math.abs(distSqr - lastDistSqr) < 0.1) {
            ticksStuck++;
        } else {
            ticksStuck = 0;
            lastDistSqr = distSqr;
        }

        // Teleport failsafe if stuck
        // User request: "teleport after some amount of time, maybe 10 seconds if they
        // arent within 10 blocks"
        if (ticksStuck > 200) { // 10 seconds
            if (distSqr > 100.0) { // > 10 blocks away
                SteveMod.LOGGER.info("Stuck moving to block (>10s, >10 blocks). Teleporting...");
                teleportToSafePos(currentTargetBlockPos);
                steve.getNavigation().stop();
                currentState = MiningState.MINING;
                ticksStuck = 0;
                return;
            } else {
                // Stuck but close (< 10 blocks).
                // Try to clear surroundings if we are in a hole/tunnel
                if (ticksStuck % 20 == 0) { // Every second
                    clearSurroundings();
                    if (steve.onGround())
                        steve.getJumpControl().jump();
                }

                // If we are digging deep and stuck, try to continue the staircase
                if (isDeepOre(targetBlock) && ticksStuck > 100) {
                    digStaircase();
                }

                // Reset stuck counter slightly to avoid spamming but keep checking
                ticksStuck = 180;
            }
        }

        if (distSqr <= 25.0) { // Increased range to 5 blocks (5^2 = 25)
            steve.getNavigation().stop();
            currentState = MiningState.MINING;
        } else {
            if (ticksSinceLastPathCalc++ > 10 || steve.getNavigation().isDone()) { // Check more frequently (0.5s)
                boolean pathFound = steve.getNavigation().moveTo(currentTargetBlockPos.getX(),
                        currentTargetBlockPos.getY(), currentTargetBlockPos.getZ(), 1.0);

                if (!pathFound && distSqr < 100.0) {
                    // If pathfinding fails and we are close, try to move directly or teleport
                    ticksStuck += 10; // Accelerate stuck detection
                } else if (!pathFound && distSqr > 100.0 && isDeepOre(targetBlock)
                        && currentTargetBlockPos.getY() < steve.getY()) {
                    // If pathfinding fails for deep ore, dig staircase
                    digStaircase();
                } else if (steve.isInWater() && !steve.isFlying()) {
                    // If stuck in water, try to jump/swim up
                    steve.getJumpControl().jump();
                }

                ticksSinceLastPathCalc = 0;
            }
        }
    }

    private void handleMining() {
        if (currentTargetBlockPos == null) {
            currentState = MiningState.SEARCHING;
            return;
        }

        BlockState state = steve.level().getBlockState(currentTargetBlockPos);
        if (!isSameOre(state.getBlock(), targetBlock)) {
            // Block is gone or changed
            currentState = MiningState.SEARCHING;
            return;
        }

        // Mine the block
        steve.swing(InteractionHand.MAIN_HAND, true);
        // Destroy block without dropping items (we simulate collection)
        steve.level().destroyBlock(currentTargetBlockPos, false);

        // Collect drops nearby
        collectDrops(currentTargetBlockPos);

        minedCount++;

        // Vein mining logic: If it's a log, scan for connected logs
        if (targetBlock.getName().getString().toLowerCase().contains("log")) {
            scanForVein(currentTargetBlockPos);
        }

        SteveMod.LOGGER.info("Mined {} ({}/{})", targetBlock.getName().getString(), minedCount, targetQuantity);

        if (minedCount >= targetQuantity) {
            currentState = MiningState.RETURNING;
            steve.sendChatMessage(
                    "I've collected enough " + targetBlock.getName().getString() + ". Coming back to you now.");
            SteveMod.LOGGER.info("Target quantity reached. Returning to {}", returnPos);
        } else {
            currentState = MiningState.SEARCHING;
        }
    }

    private void collectDrops(BlockPos pos) {
        // Scan for ItemEntities nearby and "collect" them
        AABB searchBox = new AABB(pos).inflate(4.0);
        List<ItemEntity> items = steve.level().getEntitiesOfClass(ItemEntity.class, searchBox);

        for (ItemEntity item : items) {
            if (!item.isAlive())
                continue;
            // Simulate pickup
            item.discard(); // Remove from world
        }
    }

    private void handleReturning() {
        // Always update return position to nearest player to avoid going to old
        // location
        Player player = steve.level().getNearestPlayer(steve, 100);
        if (player != null) {
            returnPos = player.blockPosition();
        } else if (returnPos == null) {
            returnPos = steve.blockPosition(); // Fallback
        }

        double distSqr = steve.blockPosition().distSqr(returnPos);

        // Check if we are stuck
        if (Math.abs(distSqr - lastDistSqr) < 0.1) {
            ticksStuck++;
        } else {
            ticksStuck = 0;
            lastDistSqr = distSqr;
        }

        // Teleport failsafe if stuck
        // User request: "teleport after some amount of time, maybe 10 seconds if they
        // arent within 10 blocks"
        if (ticksStuck > 200) { // 10 seconds
            if (distSqr > 100.0) { // > 10 blocks away
                SteveMod.LOGGER.info("Stuck returning (>10s, >10 blocks). Teleporting...");
                teleportToSafePos(returnPos);
                steve.getNavigation().stop();
                steve.setFlying(false);
                deliverItems(player);
                return;
            } else {
                // Stuck but close (< 10 blocks). Do NOT teleport.
                if (steve.onGround())
                    steve.getJumpControl().jump();
                ticksStuck = 180;
            }
        }

        if (distSqr <= 9.0) { // Within 3 blocks
            steve.getNavigation().stop();
            steve.setFlying(false);
            deliverItems(player);
        } else {
            if (ticksSinceLastPathCalc++ > 10 || steve.getNavigation().isDone()) {
                boolean pathFound = steve.getNavigation().moveTo(returnPos.getX(), returnPos.getY(), returnPos.getZ(),
                        1.0);

                if (!pathFound && distSqr < 100.0) {
                    ticksStuck += 10;
                }

                ticksSinceLastPathCalc = 0;
            }
        }
    }

    private void deliverItems(Player player) {
        ItemStack stack = new ItemStack(getDropForItem(targetBlock), minedCount);
        boolean added = false;

        if (player != null) {
            added = player.getInventory().add(stack);
            if (added) {
                steve.sendChatMessage(
                        "I put " + minedCount + " " + targetBlock.getName().getString() + " in your inventory.");
            } else {
                steve.sendChatMessage(
                        "Your inventory is full, so I dropped the " + targetBlock.getName().getString() + " here.");
            }
        }

        if (!added) {
            ItemEntity itemEntity = new ItemEntity(steve.level(), steve.getX(), steve.getY(), steve.getZ(), stack);
            steve.level().addFreshEntity(itemEntity);
        }

        steve.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        result = ActionResult
                .success("Mined " + minedCount + " " + targetBlock.getName().getString() + " and returned.");
    }

    private BlockPos scanForTargetBlock() {
        BlockPos stevePos = steve.blockPosition();
        List<BlockPos> found = new ArrayList<>();

        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int y = -SEARCH_RADIUS; y <= SEARCH_RADIUS; y++) {
                for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                    BlockPos pos = stevePos.offset(x, y, z);
                    if (isSameOre(steve.level().getBlockState(pos).getBlock(), targetBlock)) {
                        found.add(pos);
                    }
                }
            }
        }

        if (found.isEmpty())
            return null;

        // Return nearest, preferring non-water blocks
        return found.stream()
                .min(Comparator.comparingDouble(p -> {
                    double dist = p.distSqr(stevePos);
                    // Add penalty for underwater blocks
                    if (steve.level().getFluidState(p).isSource()) {
                        dist += 1000.0;
                    }
                    return dist;
                }))
                .orElse(null);
    }

    private void equipIronPickaxe() {
        steve.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE));
    }

    private boolean isDeepOre(Block block) {
        // Ores that spawn deep underground (Y < 20)
        return isSameOre(block, Blocks.DIAMOND_ORE) ||
                isSameOre(block, Blocks.REDSTONE_ORE) ||
                isSameOre(block, Blocks.LAPIS_ORE) ||
                isSameOre(block, Blocks.GOLD_ORE) ||
                isSameOre(block, Blocks.IRON_ORE) ||
                isSameOre(block, Blocks.COPPER_ORE);
    }

    private void scanForVein(BlockPos center) {
        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) { // Look up and adjacent
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0)
                        continue;
                    BlockPos pos = center.offset(x, y, z);
                    if (isSameOre(steve.level().getBlockState(pos).getBlock(), targetBlock)) {
                        // Only add to queue if we still need more blocks
                        if (minedCount + veinMineQueue.size() < targetQuantity) {
                            veinMineQueue.add(pos);
                        }
                    }
                }
            }
        }
    }

    private boolean isSameOre(Block a, Block b) {
        if (a == b)
            return true;

        // Check for deepslate variants
        if (a == Blocks.DIAMOND_ORE && b == Blocks.DEEPSLATE_DIAMOND_ORE)
            return true;
        if (a == Blocks.DEEPSLATE_DIAMOND_ORE && b == Blocks.DIAMOND_ORE)
            return true;

        if (a == Blocks.IRON_ORE && b == Blocks.DEEPSLATE_IRON_ORE)
            return true;
        if (a == Blocks.DEEPSLATE_IRON_ORE && b == Blocks.IRON_ORE)
            return true;

        if (a == Blocks.GOLD_ORE && b == Blocks.DEEPSLATE_GOLD_ORE)
            return true;
        if (a == Blocks.DEEPSLATE_GOLD_ORE && b == Blocks.GOLD_ORE)
            return true;

        if (a == Blocks.COPPER_ORE && b == Blocks.DEEPSLATE_COPPER_ORE)
            return true;
        if (a == Blocks.DEEPSLATE_COPPER_ORE && b == Blocks.COPPER_ORE)
            return true;

        if (a == Blocks.COAL_ORE && b == Blocks.DEEPSLATE_COAL_ORE)
            return true;
        if (a == Blocks.DEEPSLATE_COAL_ORE && b == Blocks.COAL_ORE)
            return true;

        if (a == Blocks.EMERALD_ORE && b == Blocks.DEEPSLATE_EMERALD_ORE)
            return true;
        if (a == Blocks.DEEPSLATE_EMERALD_ORE && b == Blocks.EMERALD_ORE)
            return true;

        if (a == Blocks.LAPIS_ORE && b == Blocks.DEEPSLATE_LAPIS_ORE)
            return true;
        if (a == Blocks.DEEPSLATE_LAPIS_ORE && b == Blocks.LAPIS_ORE)
            return true;

        if (a == Blocks.REDSTONE_ORE && b == Blocks.DEEPSLATE_REDSTONE_ORE)
            return true;
        if (a == Blocks.DEEPSLATE_REDSTONE_ORE && b == Blocks.REDSTONE_ORE)
            return true;

        return false;
    }

    private net.minecraft.world.item.Item getDropForItem(Block block) {
        if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE)
            return net.minecraft.world.item.Items.DIAMOND;
        if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE)
            return net.minecraft.world.item.Items.RAW_IRON;
        if (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE)
            return net.minecraft.world.item.Items.RAW_GOLD;
        if (block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE)
            return net.minecraft.world.item.Items.RAW_COPPER;
        if (block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE)
            return net.minecraft.world.item.Items.COAL;
        if (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE)
            return net.minecraft.world.item.Items.EMERALD;
        if (block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE)
            return net.minecraft.world.item.Items.LAPIS_LAZULI;
        if (block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE)
            return net.minecraft.world.item.Items.REDSTONE;

        return block.asItem();
    }

    private Block parseBlock(String blockName) {
        blockName = blockName.toLowerCase().replace(" ", "_");

        // Handle "minecraft:" prefix if present
        if (blockName.startsWith("minecraft:")) {
            blockName = blockName.substring(10);
        }

        Map<String, String> resourceToOre = new HashMap<>() {
            {
                // Ores
                put("iron", "iron_ore");
                put("diamond", "diamond_ore");
                put("coal", "coal_ore");
                put("gold", "gold_ore");
                put("copper", "copper_ore");
                put("redstone", "redstone_ore");
                put("lapis", "lapis_ore");
                put("emerald", "emerald_ore");

                // Trees/Logs - Map generic terms to Oak (most common)
                put("log", "oak_log");
                put("wood", "oak_log");
                put("tree", "oak_log");
                put("plank", "oak_planks");
                put("planks", "oak_planks");

                // Common blocks
                put("stone", "stone");
                put("cobblestone", "cobblestone");
                put("dirt", "dirt");
                put("grass", "grass_block");
                put("sand", "sand");
                put("gravel", "gravel");
            }
        };

        if (resourceToOre.containsKey(blockName)) {
            blockName = resourceToOre.get(blockName);
        }

        // Try to find the block
        ResourceLocation location = new ResourceLocation("minecraft", blockName);
        Block block = BuiltInRegistries.BLOCK.get(location);

        // If strictly AIR (default), it might be invalid unless they actually asked for
        // air
        if (block == Blocks.AIR && !blockName.equals("air")) {
            // Try adding _log if it ends with wood
            if (blockName.endsWith("wood")) {
                location = new ResourceLocation("minecraft", blockName.replace("wood", "log"));
                block = BuiltInRegistries.BLOCK.get(location);
            }
        }

        return block;
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
        steve.setFlying(false);
        steve.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    @Override
    public String getDescription() {
        return "Mining " + targetBlock.getName().getString() + " (" + currentState + ")";
    }

    private void clearSurroundings() {
        // Clear 3x3 area around head and feet to ensure we aren't trapped
        BlockPos head = steve.blockPosition().above();
        BlockPos feet = steve.blockPosition();

        List<BlockPos> toCheck = new ArrayList<>();
        // Check feet level
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                toCheck.add(feet.offset(x, 0, z));
            }
        }
        // Check head level
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                toCheck.add(head.offset(x, 0, z));
            }
        }

        for (BlockPos pos : toCheck) {
            if (pos.equals(feet))
                continue; // Don't dig the block we are standing on!

            BlockState state = steve.level().getBlockState(pos);
            if (!state.isAir() && state.getDestroySpeed(steve.level(), pos) >= 0) {
                // Don't mine the target block by accident here, we want to move to it
                if (state.getBlock() != targetBlock) {
                    steve.level().destroyBlock(pos, true); // Break it
                }
            }
        }
    }

    // Helper for random movement
    private static class DefaultRandomPos {
        static Vec3 getPos(SteveEntity entity, int radius, int verticalRange) {
            return net.minecraft.world.entity.ai.util.DefaultRandomPos.getPos(entity, radius, verticalRange);
        }
    }
}
