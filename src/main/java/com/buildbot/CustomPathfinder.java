package com.buildbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.*;

@SuppressWarnings({"unchecked", "rawtypes"})
public class CustomPathfinder {
    private List currentPath = new ArrayList();
    private final Deque placedBridgeBlocks = new ArrayDeque();
    private long moveStartTime = 0;
    private BlockPos currentGoal = null;
    private boolean isBotMoving = false; // Chi nha phim 1 lan khi bot dung

    public void startMovingTo(BlockPos target) {
        this.currentGoal = target;
        this.moveStartTime = System.currentTimeMillis();
    }

    public boolean isTimedOut(int timeoutSeconds) {
        return moveStartTime > 0 && (System.currentTimeMillis() - moveStartTime) > timeoutSeconds * 1000L;
    }

    public void stopMovement(MinecraftClient client) {
        if (isBotMoving && client.options != null) {
            client.options.forwardKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            client.options.sneakKey.setPressed(false);
            isBotMoving = false;
        }
        currentPath.clear();
        currentGoal = null;
        moveStartTime = 0;
    }

    public boolean tickMoveToTarget(MinecraftClient client, BlockPos targetPos, BuildBotConfig config) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return false;

        if (currentGoal == null || !currentGoal.equals(targetPos)) {
            startMovingTo(targetPos);
            currentPath = computeSimpleAStar(client, player.getBlockPos(), targetPos);
        }

        double distSq = player.squaredDistanceTo(Vec3d.ofCenter(targetPos));
        if (distSq <= 16.0) {
            stopMovement(client);
            if (config.reclaimBridgeEnabled && !placedBridgeBlocks.isEmpty()) {
                BlockPos lastBridge = (BlockPos) placedBridgeBlocks.peekLast();
                if (lastBridge != null && !lastBridge.equals(player.getBlockPos().down()) && player.squaredDistanceTo(Vec3d.ofCenter(lastBridge)) <= 16.0) {
                    if (client.interactionManager != null) {
                        client.interactionManager.updateBlockBreakingProgress(lastBridge, Direction.UP);
                        player.swingHand(Hand.MAIN_HAND);
                        if (client.world.getBlockState(lastBridge).isAir()) {
                            placedBridgeBlocks.pollLast();
                        }
                    }
                }
            }
            return true;
        }

        isBotMoving = true;
        BlockPos nextStep = currentPath.isEmpty() ? targetPos : (BlockPos) currentPath.get(0);
        if (player.getBlockPos().getSquaredDistance(nextStep) <= 1.5 && !currentPath.isEmpty()) {
            currentPath.remove(0);
        }

        double dx = (nextStep.getX() + 0.5) - player.getX();
        double dz = (nextStep.getZ() + 0.5) - player.getZ();
        float moveYaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        AntiSusRotator.stepSmoothRotation(client, moveYaw, player.getPitch(), config.smoothRotationSpeed);

        client.options.forwardKey.setPressed(true);

        if (player.horizontalCollision && player.isOnGround()) {
            client.options.jumpKey.setPressed(true);
        } else {
            client.options.jumpKey.setPressed(false);
        }

        BlockPos belowNext = player.getBlockPos().down();
        if (config.autoBridgeEnabled && client.world.getBlockState(belowNext).isAir()) {
            client.options.sneakKey.setPressed(true);
            int cobbleSlot = findCobblestoneInHotbar(player);
            if (cobbleSlot != -1 && client.interactionManager != null) {
                BuildBotClient.setSelectedSlot(player, cobbleSlot);
                BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(belowNext), Direction.UP, belowNext, false);
                client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
                player.swingHand(Hand.MAIN_HAND);
                placedBridgeBlocks.addLast(belowNext);
            }
        } else {
            client.options.sneakKey.setPressed(false);
        }

        return false;
    }

    private int findCobblestoneInHotbar(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).isOf(Items.COBBLESTONE)) return i;
        }
        return -1;
    }

    private List computeSimpleAStar(MinecraftClient client, BlockPos start, BlockPos goal) {
        PriorityQueue open = new PriorityQueue(Comparator.comparingDouble(n -> ((Node) n).f));
        Map allNodes = new HashMap();
        Set closed = new HashSet();

        Node startNode = new Node(start, null, 0, start.getSquaredDistance(goal));
        open.add(startNode);
        allNodes.put(start, startNode);

        int iterations = 0;
        while (!open.isEmpty() && iterations++ < 250) {
            Node current = (Node) open.poll();
            if (current.pos.getSquaredDistance(goal) <= 9.0) {
                List path = new ArrayList();
                Node curr = current;
                while (curr != null) {
                    path.add(0, curr.pos);
                    curr = curr.parent;
                }
                return path;
            }
            closed.add(current.pos);

            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos neighbor = current.pos.offset(dir);
                if (closed.contains(neighbor)) continue;

                if (!client.world.getBlockState(neighbor).isAir()) {
                    neighbor = neighbor.up();
                    if (!client.world.getBlockState(neighbor).isAir()) continue;
                }

                double gScore = current.g + 1.0;
                Node nextNode = (Node) allNodes.getOrDefault(neighbor, new Node(neighbor, current, Double.MAX_VALUE, 0));
                if (gScore < nextNode.g) {
                    nextNode.parent = current;
                    nextNode.g = gScore;
                    nextNode.f = gScore + Math.sqrt(neighbor.getSquaredDistance(goal));
                    allNodes.put(neighbor, nextNode);
                    open.add(nextNode);
                }
            }
        }
        return new ArrayList(Collections.singletonList(goal));
    }

    private static class Node {
        BlockPos pos;
        Node parent;
        double g, f;

        Node(BlockPos pos, Node parent, double g, double f) {
            this.pos = pos;
            this.parent = parent;
            this.g = g;
            this.f = f;
        }
    }
}
