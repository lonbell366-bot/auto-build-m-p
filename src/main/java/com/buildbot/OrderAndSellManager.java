package com.buildbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

public class OrderAndSellManager {
    public enum TaskState { NONE, ORDER_STEP_1_OPEN, ORDER_STEP_3_FIND_ITEM, ORDER_STEP_4_CHEST_ICON, ORDER_STEP_5_COLLECT, SELL_STEP_1_OPEN }
    public TaskState state = TaskState.NONE;

    private Item missingItem = null;
    private int amountNeeded = 0;
    private int tickWait = 0;
    private int pagesChecked = 0;

    public boolean isBusy() {
        return state != TaskState.NONE;
    }

    public void startOrderFetch(MinecraftClient client, Item item, int countNeeded) {
        if (client.player == null) return;
        this.missingItem = item;
        this.amountNeeded = countNeeded;
        this.pagesChecked = 0;
        this.state = TaskState.ORDER_STEP_1_OPEN;
        this.tickWait = 12;
        client.player.networkHandler.sendChatCommand("order");
        client.player.sendMessage(Text.literal("§e[BuildBot] Dang mo /order de lay them: " + item.getName().getString()), false);
    }

    public void checkAndStartAutoSell(MinecraftClient client, BuildBotConfig config) {
        if (!config.autoSellEnabled || isBusy() || client.player == null) return;
        int emptyBuckets = countItemInInventory(client.player, Items.BUCKET);
        if (emptyBuckets >= config.emptyBucketSellThreshold) {
            this.state = TaskState.SELL_STEP_1_OPEN;
            this.tickWait = 12;
            client.player.networkHandler.sendChatCommand("sell");
            client.player.sendMessage(Text.literal("§a[BuildBot] Du " + emptyBuckets + " xo rong -> Tu dong /sell..."), false);
        }
    }

    public void tick(MinecraftClient client, BuildBotConfig config) {
        if (state == TaskState.NONE || client.player == null) return;
        if (tickWait > 0) {
            tickWait--;
            return;
        }

        ClientPlayerEntity player = client.player;
        ScreenHandler handler = player.currentScreenHandler;
        if (handler == null || handler == player.playerScreenHandler) {
            state = TaskState.NONE;
            return;
        }

        switch (state) {
            case ORDER_STEP_1_OPEN -> {
                int slot = findSlotByName(handler, "Your Orders");
                if (slot != -1) {
                    clickSlot(client, handler.syncId, slot, SlotActionType.PICKUP);
                    state = TaskState.ORDER_STEP_3_FIND_ITEM;
                    tickWait = 12;
                } else {
                    state = TaskState.NONE;
                }
            }
            case ORDER_STEP_3_FIND_ITEM -> {
                String id = Registries.ITEM.getId(missingItem).toString();
                String targetName = config.orderItemDisplayNames.getOrDefault(id, missingItem.getName().getString());
                int itemSlot = findSlotByNameOrType(handler, targetName, missingItem);

                if (itemSlot != -1) {
                    clickSlot(client, handler.syncId, itemSlot, SlotActionType.PICKUP);
                    state = TaskState.ORDER_STEP_4_CHEST_ICON;
                    tickWait = 12;
                } else {
                    int nextPageSlot = findSlotByName(handler, "Next Page");
                    if (nextPageSlot != -1 && pagesChecked < 5) {
                        pagesChecked++;
                        clickSlot(client, handler.syncId, nextPageSlot, SlotActionType.PICKUP);
                        tickWait = 12;
                    } else {
                        player.sendMessage(Text.literal("§c[BuildBot] Khong thay don hang cho: " + targetName), false);
                        player.closeHandledScreen();
                        state = TaskState.NONE;
                    }
                }
            }
            case ORDER_STEP_4_CHEST_ICON -> {
                int chestSlot = findSlotByItem(handler, Items.CHEST);
                if (chestSlot != -1) {
                    clickSlot(client, handler.syncId, chestSlot, SlotActionType.PICKUP);
                    state = TaskState.ORDER_STEP_5_COLLECT;
                    tickWait = 12;
                } else {
                    player.closeHandledScreen();
                    state = TaskState.NONE;
                }
            }
            case ORDER_STEP_5_COLLECT -> {
                int currentHave = countItemInInventory(player, missingItem);
                if (currentHave >= amountNeeded) {
                    player.closeHandledScreen();
                    state = TaskState.NONE;
                    return;
                }
                int collectSlot = findSlotByItemInTopGui(handler, missingItem);
                if (collectSlot != -1) {
                    clickSlot(client, handler.syncId, collectSlot, SlotActionType.QUICK_MOVE);
                    tickWait = 6;
                } else {
                    player.closeHandledScreen();
                    state = TaskState.NONE;
                }
            }
            case SELL_STEP_1_OPEN -> {
                int bucketSlot = findSlotInPlayerInventory(handler, Items.BUCKET);
                if (bucketSlot != -1) {
                    clickSlot(client, handler.syncId, bucketSlot, SlotActionType.QUICK_MOVE);
                    tickWait = 5;
                } else {
                    player.closeHandledScreen();
                    state = TaskState.NONE;
                }
            }
            default -> state = TaskState.NONE;
        }
    }

    private void clickSlot(MinecraftClient client, int syncId, int slotId, SlotActionType type) {
        if (client.interactionManager != null && client.player != null) {
            client.interactionManager.clickSlot(syncId, slotId, 0, type, client.player);
        }
    }

    private int findSlotByName(ScreenHandler handler, String keyword) {
        for (Slot slot : handler.slots) {
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty() && stack.getName().getString().toLowerCase().contains(keyword.toLowerCase())) {
                return slot.id;
            }
        }
        return -1;
    }

    private int findSlotByNameOrType(ScreenHandler handler, String name, Item item) {
        for (Slot slot : handler.slots) {
            ItemStack s = slot.getStack();
            if (!s.isEmpty() && (s.isOf(item) || s.getName().getString().toLowerCase().contains(name.toLowerCase()))) {
                return slot.id;
            }
        }
        return -1;
    }

    private int findSlotByItem(ScreenHandler handler, Item item) {
        for (Slot slot : handler.slots) {
            if (slot.getStack().isOf(item)) return slot.id;
        }
        return -1;
    }

    private int findSlotByItemInTopGui(ScreenHandler handler, Item item) {
        int topSize = Math.max(0, handler.slots.size() - 36);
        for (int i = 0; i < topSize; i++) {
            if (handler.slots.get(i).getStack().isOf(item)) return i;
        }
        return -1;
    }

    private int findSlotInPlayerInventory(ScreenHandler handler, Item item) {
        int start = Math.max(0, handler.slots.size() - 36);
        for (int i = start; i < handler.slots.size(); i++) {
            if (handler.slots.get(i).getStack().isOf(item)) return i;
        }
        return -1;
    }

    public static int countItemInInventory(ClientPlayerEntity player, Item item) {
        int total = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.isOf(item)) total += s.getCount();
        }
        return total;
    }
}
