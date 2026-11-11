package com.buildbot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@SuppressWarnings({"unchecked", "rawtypes"})
public class BuildBotClient implements ClientModInitializer {
    public static BuildBotClient INSTANCE;
    public static BuildBotConfig config;
    public SchematicData schematic = null;
    public String botStatusText = "Idle";
    public int currentLayerY = 0;

    private final CustomPathfinder pathfinder = new CustomPathfinder();
    private final OrderAndSellManager orderSell = new OrderAndSellManager();
    private BlockPos easyPendingPos = null, activePos = null;
    private float savedYaw = 0f, savedPitch = 0f;
    private boolean hasSavedRot = false, openGui = false, wasF10 = false, isBotEating = false;
    private int buildPhase = 0, phaseTimer = 0, retryRound = 0, slotSync = 0, easyCd = 0, orderCd = 0;
    private static Field verifiedSlotField = null;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        config = BuildBotConfig.load();
        ClientSendMessageEvents.ALLOW_CHAT.register(msg -> {
            String s = msg.trim().toLowerCase();
            if (s.equals(".bb") || s.equals("#bb") || s.equals(".ae")) { openGui = true; return false; }
            return true;
        });
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> {
            if (config.autoResumeEnabled && config.hasSavedOrigin && schematic == null) {
                try { schematic = SchematicData.loadFromFile(config.activeSchematicName); } catch (Exception ignored) {}
            }
        });
        ClientTickEvents.START_CLIENT_TICK.register(this::onTick);
    }

    private int rand(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, Math.max(min + 1, max + 1));
    }

    private void onTick(MinecraftClient c) {
        ClientPlayerEntity p = c.player;
        if (p == null || c.world == null) return;
        if (slotSync > 0) slotSync--;
        if (easyCd > 0) easyCd--;
        if (orderCd > 0) orderCd--;

        if (easyPendingPos != null) {
            if (slotSync > 0) return;
            BlockState exp = getExpectedState(easyPendingPos);
            if (exp != null && !exp.isAir()) {
                c.options.sneakKey.setPressed(true);
                AntiSusRotator.placeBlockSmart(c, easyPendingPos, exp, Hand.MAIN_HAND);
                c.options.sneakKey.setPressed(false);
            }
            if (hasSavedRot) { p.setYaw(savedYaw); p.setPitch(savedPitch); hasSavedRot = false; }
            easyPendingPos = null; easyCd = rand(1, 3);
            return;
        }

        try {
            long win = GLFW.glfwGetCurrentContext();
            if (win != 0L) {
                boolean k = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_F10) == GLFW.GLFW_PRESS;
                if (k && !wasF10 && c.currentScreen == null) c.setScreen(new BuildBotScreen(null));
                wasF10 = k;
            }
        } catch (Throwable ignored) {}

        if (openGui && c.currentScreen == null) { openGui = false; c.setScreen(new BuildBotScreen(null)); return; }
        if (orderSell.isBusy()) { pathfinder.stopMovement(c); botStatusText = "Thao tac GUI..."; orderSell.tick(c, config); return; }
        if (c.currentScreen != null) return;
        if (config.autoEatEnabled && handleAutoEat(c, p)) return;
        orderSell.checkAndStartAutoSell(c, config);

        if (config.easyPlaceEnabled && c.options.useKey.isPressed() && easyCd == 0 && tryEasyPlace(c, p)) return;

        if (config.autoBuildEnabled && schematic == null) {
            syncFromLitematica(c);
            if (schematic == null) { config.autoBuildEnabled = false; return; }
        }
        if (!config.autoBuildEnabled || schematic == null) { pathfinder.stopMovement(c); return; }

        runBuildTick(c, p);
    }

    private boolean tryEasyPlace(MinecraftClient c, ClientPlayerEntity p) {
        List list = new ArrayList();
        if (c.crosshairTarget instanceof BlockHitResult hit) {
            list.add(hit.getBlockPos().offset(hit.getSide()));
            list.add(hit.getBlockPos());
        }
        Vec3d eye = p.getEyePos(), look = p.getRotationVec(1.0F);
        for (double d = 1.0; d <= 4.4; d += 0.25) {
            BlockPos rp = BlockPos.ofFloored(eye.add(look.multiply(d)));
            if (!list.contains(rp)) list.add(rp);
        }
        for (Object obj : list) {
            BlockPos pos = (BlockPos) obj;
            BlockState act = c.world.getBlockState(pos), exp = getExpectedState(pos);
            if (exp == null || exp.isAir()) continue;
            if (needsWater(act, exp)) {
                if (equipRaw(c, Items.WATER_BUCKET) && slotSync == 0) { AntiSusRotator.placeWaterOrLava(c, pos, exp); easyCd = rand(2, 4); }
                return true;
            }
            if (!act.isAir() || AntiSusRotator.findValidPlaceHit(c, pos, exp) == null) continue;
            if (!equipItem(c, exp) || slotSync > 0) return true;

            if (AntiSusRotator.needsStrictLookRotation(exp)) {
                savedYaw = p.getYaw(); savedPitch = p.getPitch(); hasSavedRot = true;
                float[] r = AntiSusRotator.getRequiredRotationForState(pos, exp, p);
                AntiSusRotator.stepSmoothRotation(c, r[0], r[1], 1.0f);
                easyPendingPos = pos; return true;
            }
            c.options.sneakKey.setPressed(true);
            boolean ok = AntiSusRotator.placeBlockSmart(c, pos, exp, Hand.MAIN_HAND);
            c.options.sneakKey.setPressed(false);
            if (ok) { easyCd = rand(1, 3); return true; }
        }
        return false;
    }

    private void runBuildTick(MinecraftClient c, ClientPlayerEntity p) {
        if (currentLayerY >= schematic.height) {
            config.autoBuildEnabled = false; config.save(); c.options.sneakKey.setPressed(false); pathfinder.stopMovement(c);
            botStatusText = "Hoan thanh 100%!"; p.sendMessage(Text.literal("§a[BuildBot] Hoan thanh 100% cong trinh!"), false);
            return;
        }

        long now = System.currentTimeMillis();
        List allUnbuilt = new ArrayList(), solidMiss = new ArrayList(), solidReady = new ArrayList(), fluidMiss = new ArrayList();
        Item missingType = null;

        for (int dy = 0; dy < config.layerHeight && (currentLayerY + dy) < schematic.height; dy++) {
            int y = currentLayerY + dy;
            for (int x = 0; x < schematic.width; x++) {
                for (int z = 0; z < schematic.length; z++) {
                    BlockPos wp = new BlockPos(config.originX + x, config.originY + y, config.originZ + z);
                    BlockState exp = getExpectedState(wp);
                    if (exp == null || exp.isAir()) continue;
                    Long last = (Long) AntiSusRotator.recentlyPlaced.get(wp);
                    if (last != null && (now - last) < 220L) { allUnbuilt.add(wp); continue; }

                    BlockState act = c.world.getBlockState(wp);
                    if (exp.isOf(Blocks.WATER) || exp.isOf(Blocks.LAVA)) {
                        if (!act.isOf(exp.getBlock())) {
                            allUnbuilt.add(wp);
                            if (hasItem(p, getSolidItem(exp))) fluidMiss.add(wp);
                            else if (missingType == null) missingType = getSolidItem(exp);
                        }
                        continue;
                    }
                    if (!isSolidMatch(act, exp)) {
                        allUnbuilt.add(wp);
                        Item req = getSolidItem(exp);
                        if (hasItem(p, req)) {
                            solidMiss.add(wp);
                            if (AntiSusRotator.findValidPlaceHit(c, wp, exp) != null) solidReady.add(wp);
                        } else if (missingType == null) missingType = req;
                    } else if (needsWater(act, exp)) {
                        allUnbuilt.add(wp);
                        if (hasItem(p, Items.WATER_BUCKET)) fluidMiss.add(wp);
                        else if (missingType == null) missingType = Items.WATER_BUCKET;
                    }
                }
            }
        }

        if (allUnbuilt.isEmpty()) {
            currentLayerY += config.layerHeight; retryRound = 0; buildPhase = 0; activePos = null;
            c.options.sneakKey.setPressed(false);
            p.sendMessage(Text.literal("§b[BuildBot] Xong tang! Len Y = " + currentLayerY), true);
            if (p.isOnGround()) p.jump();
            phaseTimer = rand(4, 7); return;
        }

        if (solidMiss.isEmpty() && fluidMiss.isEmpty()) {
            pathfinder.stopMovement(c); c.options.sneakKey.setPressed(false);
            if (missingType != null) {
                botStatusText = "Thieu: " + missingType.getName().getString();
                if (config.autoOrderEnabled && orderCd == 0) { orderCd = rand(80, 120); orderSell.startOrderFetch(c, missingType, 64); }
            }
            return;
        }

        if (solidMiss.isEmpty()) {
            fluidMiss.sort(Comparator.comparingDouble(o -> p.squaredDistanceTo(Vec3d.ofCenter((BlockPos) o))));
            BlockPos fp = (BlockPos) fluidMiss.get(0); BlockState fe = getExpectedState(fp);
            if (!pathfinder.tickMoveToTarget(c, fp, config)) return;
            if (phaseTimer > 0) { phaseTimer--; return; }
            if (equipRaw(c, fe.isOf(Blocks.LAVA) ? Items.LAVA_BUCKET : Items.WATER_BUCKET) && slotSync == 0) {
                AntiSusRotator.placeWaterOrLava(c, fp, fe);
                phaseTimer = rand(config.minPlaceDelayTicks + 1, config.maxPlaceDelayTicks + 3);
            }
            return;
        }

        List pool = solidReady.isEmpty() ? solidMiss : solidReady;
        pool.sort(Comparator.comparingInt(o -> getPriority(c, (BlockPos) o))
                .thenComparingDouble(o -> p.squaredDistanceTo(Vec3d.ofCenter((BlockPos) o))));

        BlockPos pos = (BlockPos) pool.get(0);
        BlockState exp = getExpectedState(pos), act = c.world.getBlockState(pos);
        if (!pos.equals(activePos)) { activePos = pos; buildPhase = 0; }

        if (!act.isAir() && !act.getFluidState().isStill() && !act.isReplaceable()) {
            if (config.autoMineEnabled && c.interactionManager != null) {
                c.interactionManager.updateBlockBreakingProgress(pos, Direction.UP); p.swingHand(Hand.MAIN_HAND);
            }
            return;
        }

        switch (buildPhase) {
            case 0 -> { // Di toi gan
                if (!equipItem(c, exp)) return;
                if (!pathfinder.tickMoveToTarget(c, pos, config)) {
                    botStatusText = "Di toi -> " + pos.toShortString();
                    if (pathfinder.isTimedOut(config.moveTimeoutSeconds)) retryRound++;
                    return;
                }
                c.options.sneakKey.setPressed(true); p.setSneaking(true);
                phaseTimer = rand(2, 4); buildPhase = 1;
            }
            case 1 -> { // Ngoi xuong & Ngam + Random Delay
                pathfinder.stopMovement(c); c.options.sneakKey.setPressed(true); p.setSneaking(true);
                float[] r = AntiSusRotator.getRequiredRotationForState(pos, exp, p);
                AntiSusRotator.stepSmoothRotation(c, r[0], r[1], 0.85f);
                if (phaseTimer > 0) { phaseTimer--; return; }
                if (slotSync > 0) return;
                buildPhase = 2;
            }
            case 2 -> { // Dat khoi khi dang ngoi
                c.options.sneakKey.setPressed(true);
                float[] r = AntiSusRotator.getRequiredRotationForState(pos, exp, p);
                AntiSusRotator.stepSmoothRotation(c, r[0], r[1], 1.0f);
                botStatusText = "Dat: " + exp.getBlock().getName().getString();
                AntiSusRotator.placeBlockSmart(c, pos, exp, Hand.MAIN_HAND);
                phaseTimer = rand(Math.max(1, config.minPlaceDelayTicks), Math.max(2, config.maxPlaceDelayTicks + 2));
                buildPhase = 3;
            }
            case 3 -> { // Cho Random Delay roi dung day di tiep
                if (phaseTimer > 0) { phaseTimer--; return; }
                c.options.sneakKey.setPressed(false); p.setSneaking(false);
                activePos = null; buildPhase = 0;
            }
        }
    }

    private int getPriority(MinecraftClient c, BlockPos wp) {
        BlockState st = getExpectedState(wp);
        if (st == null || st.isAir()) return 100;
        if (st.getBlock() instanceof HopperBlock) {
            Direction out = st.contains(Properties.HOPPER_FACING) ? st.get(Properties.HOPPER_FACING) : Direction.DOWN;
            if (AntiSusRotator.isSolidSupport(c, wp.offset(out))) return 15;
            int d = 0; BlockPos cur = wp; BlockState cs = st;
            while (d < 20 && cs != null && cs.getBlock() instanceof HopperBlock && cs.contains(Properties.HOPPER_FACING)) {
                cur = cur.offset(cs.get(Properties.HOPPER_FACING)); cs = getExpectedState(cur); d++;
            }
            return 30 + d;
        }
        return SchematicData.getDependencyOrder(st) * 10;
    }

    private boolean needsWater(BlockState a, BlockState e) {
        return e != null && e.contains(Properties.WATERLOGGED) && e.get(Properties.WATERLOGGED)
                && a.isOf(e.getBlock()) && a.contains(Properties.WATERLOGGED) && !a.get(Properties.WATERLOGGED);
    }

    private boolean isSolidMatch(BlockState a, BlockState e) {
        if (e == null || e.isAir()) return true;
        if (!a.isOf(e.getBlock())) return false;
        if (e.contains(Properties.HOPPER_FACING) && a.get(Properties.HOPPER_FACING) != e.get(Properties.HOPPER_FACING)) return false;
        if (e.contains(Properties.FACING) && a.get(Properties.FACING) != e.get(Properties.FACING)) return false;
        return !e.contains(Properties.HORIZONTAL_FACING) || a.get(Properties.HORIZONTAL_FACING) == e.get(Properties.HORIZONTAL_FACING);
    }

    private boolean hasItem(ClientPlayerEntity p, Item item) {
        if (item == Items.AIR) return false;
        for (int i = 0; i < 36; i++) if (p.getInventory().getStack(i).isOf(item)) return true;
        return false;
    }

    public static int getSelectedSlot(PlayerEntity p) {
        for (int i = 0; i < 9; i++) if (p.getInventory().getStack(i) == p.getMainHandStack() && !p.getMainHandStack().isEmpty()) return i;
        try { if (verifiedSlotField != null) return verifiedSlotField.getInt(p.getInventory()); } catch (Throwable ignored) {}
        return 0;
    }

    public static void setSelectedSlot(PlayerEntity p, int slot) {
        if (slot < 0 || slot > 8) return;
        Object inv = p.getInventory();
        try {
            if (verifiedSlotField != null) { verifiedSlotField.setInt(inv, slot); return; }
            for (Field f : inv.getClass().getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && !Modifier.isFinal(f.getModifiers()) && f.getType() == int.class) {
                    f.setAccessible(true); int old = f.getInt(inv);
                    if (old >= 0 && old <= 8) {
                        f.setInt(inv, slot);
                        if (p.getMainHandStack() == p.getInventory().getStack(slot)) { verifiedSlotField = f; return; }
                        f.setInt(inv, old);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private Item getSolidItem(BlockState s) {
        if (s.isOf(Blocks.WATER)) return Items.WATER_BUCKET;
        if (s.isOf(Blocks.LAVA)) return Items.LAVA_BUCKET;
        if (s.isOf(Blocks.REDSTONE_WIRE)) return Items.REDSTONE;
        return s.getBlock().asItem();
    }

    private boolean equipItem(MinecraftClient c, BlockState s) { return equipRaw(c, getSolidItem(s)); }

    private boolean equipRaw(MinecraftClient c, Item item) {
        if (item == Items.AIR || c.player == null) return false;
        ClientPlayerEntity p = c.player;
        if (p.getMainHandStack().isOf(item)) return true;
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getStack(i).isOf(item)) {
                setSelectedSlot(p, i); p.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(i));
                slotSync = rand(1, 3); return true;
            }
        }
        int cur = getSelectedSlot(p);
        for (int i = 9; i < 36; i++) {
            if (p.getInventory().getStack(i).isOf(item)) {
                pathfinder.stopMovement(c); orderSell.startInventoryFetch(c, i, cur); return false;
            }
        }
        return false;
    }

    private boolean handleAutoEat(MinecraftClient c, ClientPlayerEntity p) {
        if (p.getHungerManager().getFoodLevel() > config.hungerThreshold) {
            if (isBotEating) { c.options.useKey.setPressed(false); isBotEating = false; }
            return false;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.contains(DataComponentTypes.FOOD) && !s.isOf(Items.ROTTEN_FLESH)) {
                isBotEating = true; setSelectedSlot(p, i); c.options.useKey.setPressed(true);
                if (!p.isUsingItem() && c.interactionManager != null) c.interactionManager.interactItem(p, Hand.MAIN_HAND);
                return true;
            }
        }
        return false;
    }

    public void syncFromLitematica(MinecraftClient c) {
        SchematicData cap = SchematicData.captureFromLitematica(c, config);
        if (cap != null && c.player != null) {
            schematic = cap; currentLayerY = 0; retryRound = 0; buildPhase = 0; activePos = null;
            c.player.sendMessage(Text.literal("§a[BuildBot] Da nhan Litematica: §e" + SchematicData.getActiveLitematicaName()), false);
        }
    }

    public void loadSchematicAtPlayer(MinecraftClient c) { syncFromLitematica(c); }

    public void printMaterialList(MinecraftClient c) {
        if (c == null || c.player == null || schematic == null) return;
        c.player.sendMessage(Text.literal("§6=== Vat Lieu ==="), false);
        for (Object o : schematic.totalMaterials.entrySet()) {
            Map.Entry e = (Map.Entry) o; Block b = (Block) e.getKey(); int need = (Integer) e.getValue();
            int have = OrderAndSellManager.countItemInInventory(c.player, b.asItem());
            c.player.sendMessage(Text.literal("§f- " + b.getName().getString() + ": " + (have >= need ? "§a" : "§c") + have + "/" + need), false);
        }
    }

    private BlockState getExpectedState(BlockPos wp) {
        World lw = SchematicData.getLitematicaWorld();
        if (lw != null) {
            BlockState ls = lw.getBlockState(wp);
            if (ls != null && !ls.isAir()) return ls;
        }
        return schematic == null ? Blocks.AIR.getDefaultState() : schematic.getBlockState(wp.getX() - config.originX, wp.getY() - config.originY, wp.getZ() - config.originZ);
    }
            }
                    
