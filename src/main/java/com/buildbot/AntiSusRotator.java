package com.buildbot;

import net.minecraft.block.*;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;

import java.util.concurrent.ThreadLocalRandom;

public class AntiSusRotator {

    public static boolean stepSmoothRotation(MinecraftClient client, float targetY, float targetP, float speed) {
        ClientPlayerEntity player = client.player;
        if (player == null) return true;

        float currentYaw = player.getYaw();
        float currentPitch = player.getPitch();
        float diffYaw = MathHelper.wrapDegrees(targetY - currentYaw);
        float diffPitch = targetP - currentPitch;

        if (Math.abs(diffYaw) < 1.6f && Math.abs(diffPitch) < 1.6f) {
            return true;
        }

        float humanNoise = (ThreadLocalRandom.current().nextFloat() - 0.5f) * 0.25f;
        float factor = MathHelper.clamp(speed + humanNoise * 0.1f, 0.15f, 0.85f);
        float stepYaw = diffYaw * factor + humanNoise;
        float stepPitch = diffPitch * factor + humanNoise;

        float gcd = getMouseGCD(client);
        stepYaw -= (stepYaw % gcd);
        stepPitch -= (stepPitch % gcd);

        player.setYaw(currentYaw + stepYaw);
        player.setPitch(MathHelper.clamp(currentPitch + stepPitch, -90.0f, 90.0f));
        return false;
    }

    private static float getMouseGCD(MinecraftClient client) {
        double s = client.options.getMouseSensitivity().getValue();
        double f = s * 0.6 + 0.2;
        return (float) (f * f * f * 1.2);
    }

    /**
     * Tim khoi ran xung quanh lam diem tua that su de khong bi Ghost Block (AirPlace)
     */
    public static BlockHitResult findValidPlaceHit(MinecraftClient client, BlockPos targetPos, BlockState targetState) {
        if (client.world == null || client.player == null) return null;
        Block block = targetState.getBlock();

        // 1. Neu la Hopper: Uu tien bam vao khoi nam o huong voi Hopper tro toi
        if (block instanceof HopperBlock && targetState.contains(Properties.HOPPER_FACING)) {
            Direction outDir = targetState.get(Properties.HOPPER_FACING);
            BlockPos support = targetPos.offset(outDir);
            if (isSolidSupport(client, support)) {
                Direction clickFace = outDir.getOpposite();
                return buildHitResult(support, clickFace, targetState);
            }
        }

        // 2. Quet 6 huong xung quanh de tim khoi ran lam diem tua (Uu tien mat duoi DOWN truoc)
        Direction[] order = new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        BlockPos bestSupport = null;
        Direction bestFace = Direction.UP;
        double bestDist = Double.MAX_VALUE;
        Vec3d eye = client.player.getEyePos();

        for (Direction dir : order) {
            BlockPos neighbor = targetPos.offset(dir);
            if (!isSolidSupport(client, neighbor)) continue;

            Direction clickFace = dir.getOpposite();
            Vec3d faceCenter = Vec3d.ofCenter(neighbor).add(Vec3d.of(clickFace.getVector()).multiply(0.5));
            double d = eye.squaredDistanceTo(faceCenter);

            // Uu tien khoi khong phai la Ruong/Hopper de tranh mo GUI
            if (!isInteractiveBlock(client.world.getBlockState(neighbor).getBlock())) {
                d -= 10.0;
            }
            if (d < bestDist) {
                bestDist = d;
                bestSupport = neighbor;
                bestFace = clickFace;
            }
        }

        if (bestSupport != null) {
            return buildHitResult(bestSupport, bestFace, targetState);
        }
        return null;
    }

    private static BlockHitResult buildHitResult(BlockPos supportPos, Direction clickFace, BlockState targetState) {
        double yOff = 0.5;
        if (clickFace.getAxis().isHorizontal()) {
            if (targetState.contains(Properties.SLAB_TYPE) && targetState.get(Properties.SLAB_TYPE) == SlabType.TOP) yOff = 0.85;
            else if (targetState.contains(Properties.BLOCK_HALF) && targetState.get(Properties.BLOCK_HALF) == BlockHalf.TOP) yOff = 0.85;
            else if (targetState.contains(Properties.SLAB_TYPE) || targetState.contains(Properties.BLOCK_HALF)) yOff = 0.15;
        }
        Vec3d center = new Vec3d(supportPos.getX() + 0.5, supportPos.getY() + yOff, supportPos.getZ() + 0.5);
        Vec3d hitVec = center.add(Vec3d.of(clickFace.getVector()).multiply(0.5));
        return new BlockHitResult(hitVec, clickFace, supportPos, false);
    }

    public static boolean isSolidSupport(MinecraftClient client, BlockPos pos) {
        if (client.world == null) return false;
        BlockState s = client.world.getBlockState(pos);
        return !s.isAir() && s.getFluidState().isEmpty() && !(s.getBlock() instanceof FireBlock);
    }

    public static boolean isInteractiveBlock(Block b) {
        return b instanceof BlockWithEntity || b instanceof AbstractRedstoneGateBlock
                || b instanceof DoorBlock || b instanceof TrapdoorBlock
                || b instanceof FenceGateBlock || b instanceof CraftingTableBlock
                || b instanceof AnvilBlock || b instanceof LeverBlock || b instanceof ButtonBlock;
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

        BlockHitResult hit = findValidPlaceHit(MinecraftClient.getInstance(), pos, targetState);
        Vec3d targetVec = (hit != null) ? hit.getPos() : Vec3d.ofCenter(pos);
        Vec3d eye = player.getEyePos();
        double dx = targetVec.x - eye.x;
        double dy = targetVec.y - eye.y;
        double dz = targetVec.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) (-(MathHelper.atan2(dy, dist) * (180.0 / Math.PI)));
        return new float[]{yaw, pitch};
    }

    public static boolean placeBlockSmart(MinecraftClient client, BlockPos targetPos, BlockState targetState, Hand hand) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null || client.world == null) return false;

        BlockHitResult hitResult = findValidPlaceHit(client, targetPos, targetState);
        if (hitResult == null) return false;

        Block supportBlock = client.world.getBlockState(hitResult.getBlockPos()).getBlock();
        boolean needSneak = isInteractiveBlock(supportBlock) || (targetState.getBlock() instanceof HopperBlock);

        boolean wasSneaking = client.options.sneakKey.isPressed();
        if (needSneak && !wasSneaking) {
            client.options.sneakKey.setPressed(true);
            player.setSneaking(true);
        }

        ActionResult res = client.interactionManager.interactBlock(player, hand, hitResult);
        if (res.isAccepted()) {
            player.swingHand(hand);
        } else {
            player.networkHandler.sendPacket(new HandSwingC2SPacket(hand));
        }

        if (needSneak && !wasSneaking) {
            client.options.sneakKey.setPressed(false);
        }
        return true;
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
