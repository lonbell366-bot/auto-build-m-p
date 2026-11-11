package com.buildbot;

import net.minecraft.block.*;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@SuppressWarnings({"unchecked", "rawtypes"})
public class AntiSusRotator {

    public static final Map recentlyPlaced = new ConcurrentHashMap();

    public static boolean stepSmoothRotation(MinecraftClient client, float targetY, float targetP, float speed) {
        ClientPlayerEntity player = client.player;
        if (player == null) return true;

        float jitterY = (ThreadLocalRandom.current().nextFloat() - 0.5f) * 0.35f;
        float jitterP = (ThreadLocalRandom.current().nextFloat() - 0.5f) * 0.25f;

        float diffYaw = MathHelper.wrapDegrees((targetY + jitterY) - player.getYaw());
        float diffPitch = (targetP + jitterP) - player.getPitch();

        float gcd = getMouseGCD(client);
        if (gcd > 0.0001f) {
            diffYaw -= (diffYaw % gcd);
            diffPitch -= (diffPitch % gcd);
        }

        player.setYaw(player.getYaw() + diffYaw);
        player.setPitch(MathHelper.clamp(player.getPitch() + diffPitch, -90.0f, 90.0f));
        return true;
    }

    private static float getMouseGCD(MinecraftClient client) {
        double s = client.options.getMouseSensitivity().getValue();
        double f = s * 0.6 + 0.2;
        return (float) (f * f * f * 1.2);
    }

    public static boolean needsStrictLookRotation(BlockState state) {
        Block b = state.getBlock();
        if (b instanceof HopperBlock) return false;
        return state.contains(Properties.FACING) || state.contains(Properties.HORIZONTAL_FACING);
    }

    /**
     * Tim mat bam chuan xac: Rieng voi HOPPER bat buoc phai bam vao dung khoi ma voi Hopper tro toi!
     */
    public static BlockHitResult findValidPlaceHit(MinecraftClient client, BlockPos targetPos, BlockState targetState) {
        if (client.world == null || client.player == null || targetState == null) return null;
        Vec3d eye = client.player.getEyePos();
        Block block = targetState.getBlock();

        // QUY TAC RIENG CHO HOPPER: Phai dat khoi dich truoc roi moi dat Hopper bam vao khoi do!
        if (block instanceof HopperBlock) {
            Direction outDir = targetState.contains(Properties.HOPPER_FACING)
                    ? targetState.get(Properties.HOPPER_FACING) : Direction.DOWN;

            if (outDir == Direction.DOWN) {
                // Hopper chi xuong duoi: Chi duoc bam vao mat TREN cua khoi duoi day hoac mat DUOI cua khoi tren dau
                BlockPos below = targetPos.down();
                if (isConfirmedSolidSupport(client, below) && eye.squaredDistanceTo(Vec3d.ofCenter(below)) <= 22.0) {
                    return buildHitResult(below, Direction.UP, targetState);
                }
                BlockPos above = targetPos.up();
                if (isConfirmedSolidSupport(client, above) && eye.squaredDistanceTo(Vec3d.ofCenter(above)) <= 22.0) {
                    return buildHitResult(above, Direction.DOWN, targetState);
                }
                return null; // Khong bam vao mat ngang vi se lam Hopper bi quay ngang sai huong!
            } else {
                // Hopper chi ngang (NORTH, SOUTH, EAST, WEST): BAT BUOC khoi o huong outDir phai co san!
                BlockPos targetSupport = targetPos.offset(outDir);
                if (isConfirmedSolidSupport(client, targetSupport) && eye.squaredDistanceTo(Vec3d.ofCenter(targetSupport)) <= 22.0) {
                    return buildHitResult(targetSupport, outDir.getOpposite(), targetState);
                }
                return null; // Khoi dich chua xay -> Cho xay khoi dich truoc!
            }
        }

        // Cac khoi binh thuong khac
        Direction[] order = new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        BlockPos bestSupport = null;
        Direction bestFace = Direction.UP;
        double bestScore = Double.MAX_VALUE;

        for (Direction dir : order) {
            BlockPos neighbor = targetPos.offset(dir);
            if (!isSolidSupport(client, neighbor)) continue;

            Direction clickFace = dir.getOpposite();
            Vec3d faceCenter = Vec3d.ofCenter(neighbor).add(Vec3d.of(clickFace.getVector()).multiply(0.5));
            double d = eye.squaredDistanceTo(faceCenter);
            if (d > 22.0) continue;

            if (dir == Direction.DOWN) d -= 5.0;
            if (!isInteractiveBlock(client.world.getBlockState(neighbor).getBlock())) d -= 6.0;

            if (d < bestScore) {
                bestScore = d;
                bestSupport = neighbor;
                bestFace = clickFace;
            }
        }

        return bestSupport != null ? buildHitResult(bestSupport, bestFace, targetState) : null;
    }

    private static BlockHitResult buildHitResult(BlockPos supportPos, Direction clickFace, BlockState targetState) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double rx = rnd.nextDouble(-0.1, 0.1);
        double rz = rnd.nextDouble(-0.1, 0.1);
        double yOff = 0.5 + rnd.nextDouble(-0.06, 0.06);

        if (clickFace.getAxis().isHorizontal()) {
            if (targetState.contains(Properties.SLAB_TYPE) && targetState.get(Properties.SLAB_TYPE) == SlabType.TOP) yOff = 0.82;
            else if (targetState.contains(Properties.BLOCK_HALF) && targetState.get(Properties.BLOCK_HALF) == BlockHalf.TOP) yOff = 0.82;
            else if (targetState.contains(Properties.SLAB_TYPE) || targetState.contains(Properties.BLOCK_HALF)) yOff = 0.18;
        }
        Vec3d center = new Vec3d(supportPos.getX() + 0.5 + rx, supportPos.getY() + yOff, supportPos.getZ() + 0.5 + rz);
        Vec3d hitVec = center.add(Vec3d.of(clickFace.getVector()).multiply(0.5));
        return new BlockHitResult(hitVec, clickFace, supportPos, false);
    }

    public static boolean isConfirmedSolidSupport(MinecraftClient client, BlockPos pos) {
        if (!isSolidSupport(client, pos)) return false;
        Long t = (Long) recentlyPlaced.get(pos);
        return t == null || (System.currentTimeMillis() - t) >= 220L;
    }

    public static boolean isSolidSupport(MinecraftClient client, BlockPos pos) {
        if (client.world == null) return false;
        BlockState s = client.world.getBlockState(pos);
        return !s.isAir() && s.getFluidState().isEmpty() && !s.isReplaceable();
    }

    public static boolean isInteractiveBlock(Block b) {
        return b instanceof BlockWithEntity || b instanceof AbstractRedstoneGateBlock
                || b instanceof DoorBlock || b instanceof TrapdoorBlock
                || b instanceof FenceGateBlock || b instanceof CraftingTableBlock
                || b instanceof AnvilBlock || b instanceof LeverBlock || b instanceof ButtonBlock
                || b instanceof HopperBlock;
    }

    public static boolean requiresSneakToPlace(MinecraftClient client, BlockPos targetPos, BlockState targetState) {
        BlockHitResult hit = findValidPlaceHit(client, targetPos, targetState);
        if (hit == null || client.world == null) return false;
        Block supportBlock = client.world.getBlockState(hit.getBlockPos()).getBlock();
        return isInteractiveBlock(supportBlock) || (targetState.getBlock() instanceof HopperBlock);
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
        double dx = targetVec.x - eye.x, dy = targetVec.y - eye.y, dz = targetVec.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        float aimYaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float aimPitch = (float) (-(MathHelper.atan2(dy, dist) * (180.0 / Math.PI)));
        return new float[]{aimYaw, aimPitch};
    }

    public static boolean placeBlockSmart(MinecraftClient client, BlockPos targetPos, BlockState targetState, Hand hand) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null || client.world == null) return false;

        if (!targetState.getCollisionShape(client.world, targetPos).isEmpty()
                && player.getBoundingBox().intersects(new Box(targetPos).contract(0.05))) {
            return false;
        }

        BlockHitResult hitResult = findValidPlaceHit(client, targetPos, targetState);
        if (hitResult == null) return false;

        player.setSprinting(false);
        ActionResult res = client.interactionManager.interactBlock(player, hand, hitResult);
        player.swingHand(hand);
        recentlyPlaced.put(targetPos.toImmutable(), System.currentTimeMillis());
        return res.isAccepted() || !client.world.getBlockState(targetPos).isAir();
    }

    public static boolean placeWaterOrLava(MinecraftClient client, BlockPos targetPos, BlockState expectedState) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null || client.world == null) return false;

        BlockState actual = client.world.getBlockState(targetPos);
        BlockHitResult hit;

        if (!actual.isAir() && expectedState.contains(Properties.WATERLOGGED)) {
            hit = new BlockHitResult(Vec3d.ofCenter(targetPos), Direction.UP, targetPos, false);
        } else {
            hit = findValidPlaceHit(client, targetPos, expectedState);
            if (hit == null) hit = new BlockHitResult(Vec3d.ofCenter(targetPos), Direction.UP, targetPos, false);
        }

        Vec3d hitVec = hit.getPos();
        Vec3d eye = player.getEyePos();
        double dx = hitVec.x - eye.x, dy = hitVec.y - eye.y, dz = hitVec.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) (-(MathHelper.atan2(dy, dist) * (180.0 / Math.PI)));

        float origY = player.getYaw(), origP = player.getPitch();
        stepSmoothRotation(client, yaw, pitch, 1.0f);

        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        player.swingHand(Hand.MAIN_HAND);

        player.setYaw(origY);
        player.setPitch(origP);
        recentlyPlaced.put(targetPos.toImmutable(), System.currentTimeMillis());
        return true;
    }

    private static float[] directionToAngles(Direction dir) {
        return switch (dir) {
            case UP -> new float[]{0.0f, -89.0f};
            case DOWN -> new float[]{0.0f, 89.0f};
            case SOUTH -> new float[]{0.0f, 0.0f};
            case WEST -> new float[]{90.0f, 0.0f};
            case NORTH -> new float[]{180.0f, 0.0f};
            case EAST -> new float[]{-90.0f, 0.0f};
        };
    }
            }
