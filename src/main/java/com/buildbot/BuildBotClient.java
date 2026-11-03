package com.buildbot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.*;
import net.minecraft.block.enums.ComparatorMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class BuildBotClient implements ClientModInitializer {
    public static BuildBotClient INSTANCE;
    public static BuildBotConfig config;

    private SchematicData schematic = null;
    private final CustomPathfinder pathfinder = new CustomPathfinder();
    private final OrderAndSellManager orderSellManager = new OrderAndSellManager();

    private int currentLayerY = 0;
    private int retryRound = 0;
    private int actionDelayTimer = 0;
    private boolean openGuiNextTick = false;

    private KeyBinding f10MenuKey;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        config = BuildBotConfig.load();

        f10MenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Mo Bang Dieu Khien BuildBot (F10)", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F10, "DonutSMP BuildBot"
        ));

        ClientSendMessageEvents.ALLOW_CHAT.register(msg -> {
            String clean = msg.trim();
            if (clean.equalsIgnoreCase(".bb") || clean.equalsIgnoreCase("#bb")) {
                openGuiNextTick = true;
                return false;
            }
            if (clean.toLowerCase().startsWith("/buildbot automine ")) {
                config.autoMineEnabled = clean.toLowerCase().endsWith("on");
                config.save();
                return false;
            }
            return true;
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (config.autoResumeEnabled && config.hasSavedOrigin && schematic == null) {
                try {
                    schematic = SchematicData.loadFromFile(config.activeSchematicName);
                } catch (Exception ignored) {}
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient || !config.easyPlaceEnabled || schematic == null || hand != Hand.MAIN_HAND) {
                return ActionResult.PASS;
            }
            BlockPos placePos = hitResult.getBlockPos().offset(hitResult.getSide());
            BlockState targetState = getExpectedStateAtWorldPos(placePos);
            if (targetState != null && !targetState.isAir()) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (equipItemForState(client, targetState)) {
                    AntiSusRotator.placeBlockSmart(client, placePos, targetState, Hand.MAIN_HAND);
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.PASS;
        });

        ClientTickEvents.START_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        if (openGuiNextTick && client.currentScreen == null) {
            openGuiNextTick = false;
            client.setScreen(new BuildBotScreen(null));
            return;
        }

        while (f10MenuKey.wasPressed()) {
            client.setScreen(new BuildBotScreen(client.currentScreen));
        }

        if (orderSellManager.isBusy()) {
            orderSellManager.tick(client, config);
            return;
        }

        if (client.currentScreen != null) return;

        if (config.autoEatEnabled && handleAutoEat(client, player)) {
            return;
        }

        orderSellManager.checkAndStartAutoSell(client, config);

        if (!config.autoBuildEnabled || schematic == null) {
            pathfinder.stopMovement(client);
            return;
        }

        if (actionDelayTimer > 0) {
            actionDelayTimer--;
            return;
        }

        runBuildLayerTick(client, player);
    }

    private void runBuildLayerTick(MinecraftClient client, ClientPlayerEntity player) {
        if (currentLayerY >= schematic.height) {
            config.autoBuildEnabled = false;
            config.save();
            pathfinder.stopMovement(client);
            player.sendMessage(Text.literal("§a[BuildBot] Da hoan thanh 100% cong trinh!"), false);
            return;
        }

        List<BlockPos> candidates = new ArrayList<>();
        for (int dy = 0; dy < config.layerHeight && (currentLayerY + dy) < schematic.height; dy++) {
            int y = currentLayerY + dy;
            for (int x = 0; x < schematic.width; x++) {
                for (int z = 0; z < schematic.length; z++) {
                    BlockState target = schematic.getBlockState(x, y, z);
                    BlockPos worldPos = new BlockPos(config.originX + x, config.originY + y, config.originZ + z);
                    BlockState actual = client.world.getBlockState(worldPos);

                    if (!isBlockMatching(actual, target)) {
                        if (retryRound < config.maxRetryRounds - 1 && !hasNeighborSupport(client, worldPos) && !target.isAir()) {
                            continue;
                        }
                        candidates.add(worldPos);
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            if (hasAnyRemainingInLayer(client, currentLayerY) && retryRound < config.maxRetryRounds) {
                retryRound++;
                return;
            }
            retryRound = 0;
            currentLayerY += config.layerHeight;
            player.sendMessage(Text.literal("§b[BuildBot] Xong tang! Chuyen len tang Y = " + currentLayerY), true);
            return;
        }

        candidates.sort(Comparator
                .comparingInt((BlockPos p) -> SchematicData.getDependencyOrder(getExpectedStateAtWorldPos(p)))
                .thenComparingDouble(p -> player.squaredDistanceTo(Vec3d.ofCenter(p))));

        BlockPos targetPos = candidates.get(0);
        BlockState expectedState = getExpectedStateAtWorldPos(targetPos);
        BlockState currentWorldState = client.world.getBlockState(targetPos);

        if (!pathfinder.tickMoveToTarget(client, targetPos, config)) {
            if (pathfinder.isTimedOut(config.moveTimeoutSeconds)) {
                player.sendMessage(Text.literal("§e[BuildBot] Qua 20s khong toi duoc " + targetPos.toShortString() + ", chuyen khoi khac."), true);
                retryRound++;
            }
            return;
        }

        if (!currentWorldState.isAir() && !currentWorldState.getFluidState().isStill()) {
            if (currentWorldState.isOf(expectedState.getBlock()) && tuneRedstoneComponent(client, player, targetPos, currentWorldState, expectedState)) {
                resetRandomDelay();
                return;
            }
            if (config.autoMineEnabled && client.interactionManager != null) {
                float[] angles = AntiSusRotator.getRequiredRotationForState(targetPos, expectedState, player);
                if (config.smoothLookAntiSus && !AntiSusRotator.stepSmoothRotation(client, angles[0], angles[1], config.smoothRotationSpeed)) {
                    return;
                }
                client.interactionManager.updateBlockBreakingProgress(targetPos, Direction.UP);
                player.swingHand(Hand.MAIN_HAND);
            }
            return;
        }

        if (expectedState.isAir()) return;

        if (!equipItemForState(client, expectedState)) {
            if (config.autoOrderEnabled) {
                Item neededItem = getItemForState(expectedState);
                if (neededItem != Items.AIR) {
                    int missingCount = countMissingInCurrentLayer(client, neededItem);
                    orderSellManager.startOrderFetch(client, neededItem, missingCount + 16);
                }
            }
            return;
        }

        float[] reqAngles = AntiSusRotator.getRequiredRotationForState(targetPos, expectedState, player);
        if (config.smoothLookAntiSus && !AntiSusRotator.stepSmoothRotation(client, reqAngles[0], reqAngles[1], config.smoothRotationSpeed)) {
            return;
        }

        AntiSusRotator.placeBlockSmart(client, targetPos, expectedState, Hand.MAIN_HAND);

        if (expectedState.contains(Properties.WATERLOGGED) && expectedState.get(Properties.WATERLOGGED)) {
            if (equipSpecificItem(client, Items.WATER_BUCKET) && client.interactionManager != null) {
                client.interactionManager.interactBlock(player, Hand.MAIN_HAND,
                        new BlockHitResult(Vec3d.ofCenter(targetPos), Direction.UP, targetPos, false));
            }
        }

        resetRandomDelay();
    }

    private boolean tuneRedstoneComponent(MinecraftClient client, ClientPlayerEntity player, BlockPos pos, BlockState actual, BlockState expected) {
        if (actual.getBlock() instanceof RepeaterBlock) {
            if (!Objects.equals(actual.get(Properties.DELAY), expected.get(Properties.DELAY))) {
                client.interactionManager.interactBlock(player, Hand.MAIN_HAND,
                        new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false));
                player.swingHand(Hand.MAIN_HAND);
                return true;
            }
        } else if (actual.getBlock() instanceof ComparatorBlock) {
            ComparatorMode actMode = actual.get(Properties.COMPARATOR_MODE);
            ComparatorMode expMode = expected.get(Properties.COMPARATOR_MODE);
            if (actMode != expMode) {
                client.interactionManager.interactBlock(player, Hand.MAIN_HAND,
                        new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false));
                player.swingHand(Hand.MAIN_HAND);
                return true;
            }
        }
        return false;
    }

    private boolean equipItemForState(MinecraftClient client, BlockState state) {
        Item item = getItemForState(state);
        return item != Items.AIR && equipSpecificItem(client, item);
    }

    private Item getItemForState(BlockState state) {
        if (state.isOf(Blocks.WATER)) return Items.WATER_BUCKET;
        if (state.isOf(Blocks.LAVA)) return Items.LAVA_BUCKET;
        if (state.isOf(Blocks.REDSTONE_WIRE)) return Items.REDSTONE;
        return state.getBlock().asItem();
    }

    private boolean equipSpecificItem(MinecraftClient client, Item targetItem) {
        ClientPlayerEntity player = client.player;
        if (player == null) return false;

        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).isOf(targetItem)) {
                if (player.getInventory().selectedSlot != i) {
                    player.getInventory().selectedSlot = i;
                    player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(i));
                }
                return true;
            }
        }

        for (int i = 9; i < 36; i++) {
            if (player.getInventory().getStack(i).isOf(targetItem)) {
                int hotbarSlot = player.getInventory().selectedSlot;
                if (client.interactionManager != null) {
                    client.interactionManager.clickSlot(
                            player.playerScreenHandler.syncId, i, hotbarSlot, SlotActionType.SWAP, player
                    );
                    player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(hotbarSlot));
                    return true;
                }
            }
        }
        return false;
    }

    private boolean handleAutoEat(MinecraftClient client, ClientPlayerEntity player) {
        if (player.getHungerManager().getFoodLevel() > config.hungerThreshold) {
            if (player.isUsingItem() && player.getMainHandStack().contains(DataComponentTypes.FOOD)) {
                client.options.useKey.setPressed(false);
            }
            return false;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.contains(DataComponentTypes.FOOD) && !stack.isOf(Items.ROTTEN_FLESH) && !stack.isOf(Items.SPIDER_EYE)) {
                player.getInventory().selectedSlot = i;
                client.options.useKey.setPressed(true);
                if (!player.isUsingItem() && client.interactionManager != null) {
                    client.interactionManager.interactItem(player, Hand.MAIN_HAND);
                }
                return true;
            }
        }
        return false;
    }

    public void loadSchematicAtPlayer(MinecraftClient client) {
        if (client == null || client.player == null) return;
        try {
            schematic = SchematicData.loadFromFile(config.activeSchematicName);
            BlockPos p = client.player.getBlockPos();
            config.originX = p.getX();
            config.originY = p.getY();
            config.originZ = p.getZ();
            config.hasSavedOrigin = true;
            config.save();
            currentLayerY = 0;
            retryRound = 0;
            client.player.sendMessage(Text.literal("§a[BuildBot] Da nap " + config.activeSchematicName + " (" + schematic.width + "x" + schematic.height + "x" + schematic.length + ") tai " + p.toShortString()), false);
            printMaterialList(client);
        } catch (Exception e) {
            client.player.sendMessage(Text.literal("§c[BuildBot] Loi nap Schematic: " + e.getMessage()), false);
        }
    }

    public void printMaterialList(MinecraftClient client) {
        if (client == null || client.player == null || schematic == null) return;
        client.player.sendMessage(Text.literal("§6=== Danh Sach Vat Lieu Can Dung ==="), false);
        for (Map.Entry<Block, Integer> entry : schematic.totalMaterials.entrySet()) {
            Item item = entry.getKey().asItem();
            int have = OrderAndSellManager.countItemInInventory(client.player, item);
            int need = entry.getValue();
            String color = (have >= need) ? "§a" : "§c";
            client.player.sendMessage(Text.literal("§f- " + entry.getKey().getName().getString() + ": " + color + have + "/" + need), false);
        }
    }

    private BlockState getExpectedStateAtWorldPos(BlockPos worldPos) {
        if (schematic == null) return null;
        int rx = worldPos.getX() - config.originX;
        int ry = worldPos.getY() - config.originY;
        int rz = worldPos.getZ() - config.originZ;
        return schematic.getBlockState(rx, ry, rz);
    }

    private boolean isBlockMatching(BlockState actual, BlockState expected) {
        if (expected == null) return true;
        if (expected.isAir()) return actual.isAir();
        if (!actual.isOf(expected.getBlock())) return false;
        if (expected.contains(Properties.FACING) && actual.get(Properties.FACING) != expected.get(Properties.FACING)) return false;
        if (expected.contains(Properties.HORIZONTAL_FACING) && actual.get(Properties.HORIZONTAL_FACING) != expected.get(Properties.HORIZONTAL_FACING)) return false;
        if (expected.contains(Properties.DELAY) && !Objects.equals(actual.get(Properties.DELAY), expected.get(Properties.DELAY))) return false;
        if (expected.contains(Properties.COMPARATOR_MODE) && actual.get(Properties.COMPARATOR_MODE) != expected.get(Properties.COMPARATOR_MODE)) return false;
        return true;
    }

    private boolean hasNeighborSupport(MinecraftClient client, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (!client.world.getBlockState(pos.offset(dir)).isAir()) return true;
        }
        return false;
    }

    private boolean hasAnyRemainingInLayer(MinecraftClient client, int y) {
        for (int x = 0; x < schematic.width; x++) {
            for (int z = 0; z < schematic.length; z++) {
                BlockState exp = schematic.getBlockState(x, y, z);
                BlockPos wp = new BlockPos(config.originX + x, config.originY + y, config.originZ + z);
                if (!isBlockMatching(client.world.getBlockState(wp), exp)) return true;
            }
        }
        return false;
    }

    private int countMissingInCurrentLayer(MinecraftClient client, Item item) {
        int missing = 0;
        for (int x = 0; x < schematic.width; x++) {
            for (int z = 0; z < schematic.length; z++) {
                BlockState exp = schematic.getBlockState(x, currentLayerY, z);
                BlockPos wp = new BlockPos(config.originX + x, config.originY + currentLayerY, config.originZ + z);
                if (!isBlockMatching(client.world.getBlockState(wp), exp) && getItemForState(exp) == item) {
                    missing++;
                }
            }
        }
        return Math.max(1, missing);
    }

    private void resetRandomDelay() {
        actionDelayTimer = ThreadLocalRandom.current().nextInt(config.minPlaceDelayTicks, config.maxPlaceDelayTicks + 1);
    }
                                      }
                  
