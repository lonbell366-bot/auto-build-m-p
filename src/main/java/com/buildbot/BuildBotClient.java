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
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
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
    private BlockPos preparedPos = null;
    private int retryRound = 0, delayTimer = 0, slotSyncCooldown = 0, easyPlaceCooldown = 0;
    private boolean openGui = false, wasF10 = false, isBotEating = false, isInternalPlace = false;

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

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        if (slotSyncCooldown > 0) slotSyncCooldown--;
        if (easyPlaceCooldown > 0) easyPlaceCooldown--;

        try {
            long win = GLFW.glfwGetCurrentContext();
            if (win != 0L) {
                boolean p = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_F10) == GLFW.GLFW_PRESS;
                if (p && !wasF10 && client.currentScreen == null) client.setScreen(new BuildBotScreen(null));
                wasF10 = p;
            }
        } catch (Throwable ignored) {}

        if (openGui && client.currentScreen == null) { openGui = false; client.setScreen(new BuildBotScreen(null)); return; }
        if (orderSell.isBusy()) { orderSell.tick(client, config); return; }
        if (client.currentScreen != null) return;
        if (config.autoEatEnabled && handleAutoEat(client, player)) return;

        orderSell.checkAndStartAutoSell(client, config);

        if (config.easyPlaceEnabled && client.options.useKey.isPressed() && easyPlaceCooldown == 0) {
            if (tryEasyPlace(client, player)) return;
        }

        if (config.autoBuildEnabled && schematic == null) {
            syncFromLitematica(client);
            if (schematic == null) { config.autoBuildEnabled = false; return; }
        }
        if (!config.autoBuildEnabled || schematic == null) {
            pathfinder.stopMovement(client);
            return;
        }
        if (delayTimer > 0) { delayTimer--; return; }

        runBuildTick(client, player);
    }

    private boolean tryEasyPlace(MinecraftClient client, ClientPlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0F);
        for (double d = 1.0; d <= 4.4; d += 0.3) {
            BlockPos pos = BlockPos.ofFloored(eye.add(look.multiply(d)));
            if (!client.world.getBlockState(pos).isAir()) continue;
            BlockState exp = getExpectedState(pos);
            if (exp == null || exp.isAir() || AntiSusRotator.findValidPlaceHit(client, pos, exp) == null) continue;
            if (!equipItem(client, exp)) continue;

            float[] rot = AntiSusRotator.getRequiredRotationForState(pos, exp, player);
            player.setYaw(rot[0]); player.setPitch(rot[1]);

            boolean needSneak = AntiSusRotator.requiresSneakToPlace(client, pos, exp);
            if (needSneak) client.options.sneakKey.setPressed(true);

            isInternalPlace = true;
            boolean ok = AntiSusRotator.placeBlockSmart(client, pos, exp, Hand.MAIN_HAND);
            isInternalPlace = false;

            if (needSneak) client.options.sneakKey.setPressed(false);
            if (ok) { easyPlaceCooldown = 2; return true; }
        }
        return false;
    }

    private void runBuildTick(MinecraftClient client, ClientPlayerEntity player) {
        if (currentLayerY >= schematic.height) {
            config.autoBuildEnabled = false; config.save(); pathfinder.stopMovement(client);
            player.sendMessage(Text.literal("§a[BuildBot] Hoan thanh cong trinh!"), false);
            return;
        }

        long now = System.currentTimeMillis();
        List missing = new ArrayList();
        List ready = new ArrayList();

        for (int dy = 0; dy < config.layerHeight && (currentLayerY + dy) < schematic.height; dy++) {
            int y = currentLayerY + dy;
            for (int x = 0; x < schematic.width; x++) {
                for (int z = 0; z < schematic.length; z++) {
                    BlockPos wp = new BlockPos(config.originX + x, config.originY + y, config.originZ + z);
                    Long last = (Long) AntiSusRotator.recentlyPlaced.get(wp);
                    if (last != null && (now - last) < 250L) continue;

                    BlockState exp = getExpectedState(wp);
                    if (!isMatching(client.world.getBlockState(wp), exp)) {
                        missing.add(wp);
                        if (exp.isAir() || AntiSusRotator.findValidPlaceHit(client, wp, exp) != null) ready.add(wp);
                    }
                }
            }
        }

        if (missing.isEmpty()) {
            currentLayerY += config.layerHeight; retryRound = 0; preparedPos = null;
            player.sendMessage(Text.literal("§b[BuildBot] Len tang Y = " + currentLayerY), true);
            return;
        }

        ready.sort(Comparator.comparingInt(p -> SchematicData.getDependencyOrder(getExpectedState((BlockPos) p)))
                .thenComparingDouble(p -> player.squaredDistanceTo(Vec3d.ofCenter((BlockPos) p))));

        BlockPos pos = (BlockPos) (ready.isEmpty() ? missing.get(0) : ready.get(0));
        BlockState exp = getExpectedState(pos);

        if (!pathfinder.tickMoveToTarget(client, pos, config)) {
            preparedPos = null;
            if (pathfinder.isTimedOut(config.moveTimeoutSeconds)) retryRound++;
            return;
        }
        if (delayTimer > 0) { delayTimer--; return; }
        if (exp.isAir() || !equipItem(client, exp)) return;

        boolean needSneak = AntiSusRotator.requiresSneakToPlace(client, pos, exp);
        if (needSneak) client.options.sneakKey.setPressed(true);
        else client.options.sneakKey.setPressed(false);

        float[] rot = AntiSusRotator.getRequiredRotationForState(pos, exp, player);
        if (!AntiSusRotator.stepSmoothRotation(client, rot[0], rot[1], 0.9f) || slotSyncCooldown > 0 || !pos.equals(preparedPos)) {
            preparedPos = pos; return;
        }

        if (AntiSusRotator.placeBlockSmart(client, pos, exp, Hand.MAIN_HAND)) resetDelay();
        preparedPos = null;
    }

    public static int getSelectedSlot(PlayerEntity p) {
        try {
            for (Field f : p.getInventory().getClass().getDeclaredFields()) {
                if (f.getType() == int.class) {
                    f.setAccessible(true); int v = f.getInt(p.getInventory());
                    if (v >= 0 && v <= 8) return v;
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    public static void setSelectedSlot(PlayerEntity p, int slot) {
        if (slot < 0 || slot > 8) return;
        try {
            for (Field f : p.getInventory().getClass().getDeclaredFields()) {
                if (f.getType() == int.class) {
                    f.setAccessible(true); int v = f.getInt(p.getInventory());
                    if (v >= 0 && v <= 8) { f.setInt(p.getInventory(), slot); return; }
                }
            }
        } catch (Throwable ignored) {}
    }

    private Item getItem(BlockState s) {
        if (s.isOf(Blocks.WATER)) return Items.WATER_BUCKET;
        if (s.isOf(Blocks.LAVA)) return Items.LAVA_BUCKET;
        return s.getBlock().asItem();
    }

    private boolean equipItem(MinecraftClient c, BlockState s) {
        Item item = getItem(s);
        if (item == Items.AIR) return false;
        ClientPlayerEntity p = c.player;
        if (p.getMainHandStack().isOf(item)) return true;

        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getStack(i).isOf(item)) {
                setSelectedSlot(p, i);
                p.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(i));
                slotSyncCooldown = 1;
                return true;
            }
        }
        int cur = getSelectedSlot(p);
        for (int i = 9; i < 36; i++) {
            if (p.getInventory().getStack(i).isOf(item) && c.interactionManager != null) {
                c.interactionManager.clickSlot(p.playerScreenHandler.syncId, i, cur, SlotActionType.SWAP, p);
                p.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(cur));
                slotSyncCooldown = 2;
                return true;
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
        if (cap != null) {
            schematic = cap; currentLayerY = 0; retryRound = 0;
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

    private boolean isMatching(BlockState a, BlockState e) {
        if (e == null || e.isAir()) return a.isAir();
        return a.isOf(e.getBlock());
    }

    private void resetDelay() {
        delayTimer = ThreadLocalRandom.current().nextInt(Math.max(1, config.minPlaceDelayTicks), Math.max(2, config.maxPlaceDelayTicks) + 1);
    }
                                              }
