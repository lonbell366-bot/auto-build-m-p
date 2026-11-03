package com.buildbot;

import net.minecraft.block.*;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.ThreadLocalRandom;

public class AntiSusRotator {

    public static boolean stepSmoothRotation(MinecraftClient client, float targetY, float targetP, float speed) {
        ClientPlayerEntity player = client.player;
        if (player == null) return true;

        float currentYaw = player.getYaw();
        float currentPitch = player.getPitch();

        float diffYaw = MathHelper.wrapDegrees(targetY - currentYaw);
        float diffPitch = targetP - currentPitch;

        if (Math.abs(diffYaw) < 1.8f && Math.abs(diffPitch) < 1.8f) {
            return true;
        }

        float jitter = (ThreadLocalRandom.current().nextFloat() - 0.5f) * 0.35f;
        float stepYaw = diffYaw * speed + jitter;
        float stepPitch = diffPitch * speed + jitter;

        float gcd = getMouseGCD(client);
        stepYaw -= (stepYaw % gcd);
        stepPitch -= (stepPitch % gcd);

        player.setYaw(currentYaw + stepYaw);
        player.setPitch(MathHelper.clamp(currentPitch + stepPitch, -90.0f, 90.0f));
        return false;
    }

    private static float getMouseGCD(MinecraftClient client) {
        double sensitivity = client.options.getMouseSensitivity().getValue();
        double f = sensitivity * 0.6 + 0.2;
        return (float) (f * f * f * 1.2);
    }

    public static float[] getRequiredRotationForState(BlockPos pos, BlockState targetState, ClientPlayerEntity player) {
        Block block = targetState.getBlock();

        if (targetState.contains(Properties.FACING) && !(block instanceof HopperBlock)) {
            Direction facing = targetState.get(Properties.FACING);
            Direction lookDir = (block instanceof ObserverBlock) ? facing : facing.getOpposite();
            return directionToAngles(lookDir);
        }

        if (targetState.contains(Properties.HORIZONTAL_FACING)) {
            Direction facing = targetState.get(Properties.HORIZONTAL_FACING);
            Direction lookDir = (block instanceof AbstractRedstoneGateBlock || block instanceof ChestBlock || block instanceof AbstractFurnaceBlock)
                    ? facing.getOpposite() : facing;
            return directionToAngles(lookDir);
        }

        Vec3d eye = player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        double dx = center.x - eye.x;
        double dy = center.y - eye.y;
        double dz = center.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) (-(MathHelper.atan2(dy, dist) * (180.0 / Math.PI)));
        return new float[]{yaw, pitch};
    }

    public static void placeBlockSmart(MinecraftClient client, BlockPos pos, BlockState targetState, Hand hand) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) return;

        Block block = targetState.getBlock();
        Direction clickSide = Direction.UP;
        double yOffset = 0.5;

        if (block instanceof HopperBlock && targetState.contains(Properties.HOPPER_FACING)) {
            Direction hopperDir = targetState.get(Properties.HOPPER_FACING);
            clickSide = (hopperDir == Direction.DOWN) ? Direction.UP : hopperDir.getOpposite();
        } else if (targetState.contains(Properties.SLAB_TYPE) && targetState.get(Properties.SLAB_TYPE) == SlabType.TOP) {
            clickSide = Direction.DOWN;
            yOffset = 0.9;
        } else if (targetState.contains(Properties.BLOCK_HALF) && targetState.get(Properties.BLOCK_HALF) == BlockHalf.TOP) {
            clickSide = Direction.DOWN;
            yOffset = 0.9;
        }

        Vec3d hitVec = new Vec3d(pos.getX() + 0.5, pos.getY() + yOffset, pos.getZ() + 0.5);
        BlockHitResult hitResult = new BlockHitResult(hitVec, clickSide, pos, false);

        client.interactionManager.interactBlock(player, hand, hitResult);
        player.swingHand(hand);
    }

    private static float[] directionToAngles(Direction dir) {
        return switch (dir) {
            case UP -> new float[]{0.0f, -90.0f};
            case DOWN -> new float[]{0.0f, 90.0f};
            case SOUTH -> new float[]{0.0f, 0.0f};
            case WEST -> new float[]{90.0f, 0.0f};
            case NORTH -> new float[]{180.0f, 0.0f};
            case EAST -> new float[]{-90.0f, 0.0f};
        };
    }
}
