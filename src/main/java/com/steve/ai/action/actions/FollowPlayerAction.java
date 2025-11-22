package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.world.entity.player.Player;

import java.util.List;

public class FollowPlayerAction extends BaseAction {
    private String playerName;
    private Player targetPlayer;
    private int ticksRunning;
    private int ticksStuck = 0;
    private double lastDistSqr = Double.MAX_VALUE;
    private static final int MAX_TICKS = 6000; // 5 minutes

    public FollowPlayerAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        playerName = task.getStringParameter("player");
        ticksRunning = 0;

        // Disable flying for safer movement
        steve.setFlying(false);

        steve.sendChatMessage("Coming to you!");

        findPlayer();

        if (targetPlayer == null) {
            steve.setFlying(false);
            result = ActionResult.failure("Player not found: " + playerName);
        }
    }

    @Override
    protected void onTick() {
        ticksRunning++;

        if (ticksRunning > MAX_TICKS) {
            steve.setFlying(false);
            steve.sendChatMessage("I'm tired of following. I am at " + steve.blockPosition().toShortString());
            result = ActionResult.success("Stopped following");
            return;
        }

        if (targetPlayer == null || !targetPlayer.isAlive() || targetPlayer.isRemoved()) {
            findPlayer();
            if (targetPlayer == null) {
                result = ActionResult.failure("Lost track of player");
                return;
            }
        }

        double distance = steve.distanceTo(targetPlayer);

        // Smart flying: Fly if far, land if close
        if (distance > 10.0 && !steve.isFlying()) {
            steve.setFlying(true);
        } else if (distance < 5.0 && steve.isFlying()) {
            steve.setFlying(false);
        }

        steve.getLookControl().setLookAt(targetPlayer, 30.0F, 30.0F);

        if (distance > 3.0) {
            steve.getNavigation().moveTo(targetPlayer, 1.0);
        } else if (distance < 2.0) {
        } else if (distance < 2.0) {
            steve.getNavigation().stop();
        }

        // Stuck detection
        double distSqr = steve.distanceToSqr(targetPlayer);
        if (Math.abs(distSqr - lastDistSqr) < 0.1 && distance > 3.0) {
            ticksStuck++;
        } else {
            ticksStuck = 0;
            lastDistSqr = distSqr;
        }

        if (ticksStuck > 200) { // 10 seconds
            if (distance > 10.0) {
                com.steve.ai.SteveMod.LOGGER.info("Stuck following (>10s, >10 blocks). Teleporting to player...");
                teleportToSafePos(targetPlayer.blockPosition());
                ticksStuck = 0;
            } else {
                // Stuck but close. Jump.
                if (steve.onGround())
                    steve.getJumpControl().jump();
                ticksStuck = 180;
            }
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
        steve.setFlying(false);
    }

    @Override
    public String getDescription() {
        return "Follow player " + playerName;
    }

    private void findPlayer() {
        java.util.List<? extends Player> players = steve.level().players();

        // First try exact name match
        for (Player player : players) {
            if (player.getName().getString().equalsIgnoreCase(playerName)) {
                targetPlayer = player;
                return;
            }
        }

        if (playerName != null && (playerName.contains("PLAYER") || playerName.contains("NAME") ||
                playerName.equalsIgnoreCase("me") || playerName.equalsIgnoreCase("you") || playerName.isEmpty())) {
            Player nearest = null;
            double nearestDistance = Double.MAX_VALUE;

            for (Player player : players) {
                double distance = steve.distanceTo(player);
                if (distance < nearestDistance) {
                    nearest = player;
                    nearestDistance = distance;
                }
            }

            if (nearest != null) {
                targetPlayer = nearest;
                playerName = nearest.getName().getString(); // Update to actual name
                com.steve.ai.SteveMod.LOGGER.info("Steve '{}' following nearest player: {}",
                        steve.getSteveName(), playerName);
            }
        }
    }
}
