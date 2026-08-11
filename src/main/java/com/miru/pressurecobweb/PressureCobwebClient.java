package com.miru.pressurecobweb;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.PressurePlateBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashSet;
import java.util.Set;

public class PressureCobwebClient implements ClientModInitializer {

    private static final Set<BlockPos> knownPlates = new HashSet<>();

    private static BlockPos pendingPlate = null;

    private static int actionStage = 0;
    private static int actionTicks = 0;

    private static int originalHotbarSlot = -1;
    private static int swappedInventorySlot = -1;
    private static boolean swappedFromInventory = false;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(
                PressureCobwebClient::onClientTick
        );
    }

    private static void onClientTick(MinecraftClient client) {

        if (client.player == null || client.world == null) {
            return;
        }

        if (client.interactionManager == null) {
            return;
        }

        if (actionStage != 0) {
            handleAction(client);
            return;
        }

        detectNewPressurePlate(client);
    }

    private static void detectNewPressurePlate(MinecraftClient client) {

        ClientPlayerEntity player = client.player;
        BlockPos center = player.getBlockPos();

        Set<BlockPos> currentPlates = new HashSet<>();

        for (int x = -4; x <= 4; x++) {
            for (int y = -2; y <= 3; y++) {
                for (int z = -4; z <= 4; z++) {

                    BlockPos pos = center.add(x, y, z);

                    Block block = client.world
                            .getBlockState(pos)
                            .getBlock();

                    if (block instanceof PressurePlateBlock) {

                        currentPlates.add(pos);

                        if (!knownPlates.contains(pos)) {

                            knownPlates.add(pos);

                            pendingPlate = pos.toImmutable();

                            actionStage = 1;
                            actionTicks = 2;

                            return;
                        }
                    }
                }
            }
        }

        knownPlates.removeIf(pos -> !currentPlates.contains(pos));
    }

    private static void handleAction(MinecraftClient client) {

        if (pendingPlate == null) {
            resetState();
            return;
        }

        if (actionTicks > 0) {
            actionTicks--;
            return;
        }

        if (actionStage == 1) {

            prepareCobweb(client);

            if (actionStage != 1) {
                return;
            }

            actionStage = 2;
            actionTicks = 1;
            return;
        }

        if (actionStage == 2) {
            placeCobweb(client);
        }
    }

    private static void prepareCobweb(MinecraftClient client) {

        BlockPos platePos = pendingPlate;

        if (platePos == null) {
            resetState();
            return;
        }

        if (!(client.world.getBlockState(platePos).getBlock()
                instanceof PressurePlateBlock)) {

            resetState();
            return;
        }

        BlockPos cobwebPos = platePos.up();

        if (!client.world.getBlockState(cobwebPos).isAir()) {
            resetState();
            return;
        }

        PlayerInventory inventory = client.player.getInventory();

        int cobwebSlot = findCobweb(inventory);

        if (cobwebSlot == -1) {
            resetState();
            return;
        }

        originalHotbarSlot = inventory.getSelectedSlot();
        swappedInventorySlot = -1;
        swappedFromInventory = false;

        if (cobwebSlot >= PlayerInventory.MAIN_SIZE) {

            int hotbarSlot =
                    cobwebSlot - PlayerInventory.MAIN_SIZE;

            inventory.setSelectedSlot(hotbarSlot);

        } else {

            client.interactionManager.clickSlot(
                    client.player.currentScreenHandler.syncId,
                    cobwebSlot,
                    originalHotbarSlot,
                    SlotActionType.SWAP,
                    client.player
            );

            swappedFromInventory = true;
            swappedInventorySlot = cobwebSlot;
        }
    }

    private static void placeCobweb(MinecraftClient client) {

        BlockPos platePos = pendingPlate;

        if (platePos == null) {
            resetState();
            return;
        }

        BlockPos cobwebPos = platePos.up();

        if (!client.world.getBlockState(cobwebPos).isAir()) {
            restoreInventory(client);
            resetState();
            return;
        }

        if (!client.player.getInventory()
                .getSelectedStack()
                .isOf(Items.COBWEB)) {

            restoreInventory(client);
            resetState();
            return;
        }

        BlockHitResult hit = new BlockHitResult(
                platePos.toCenterPos().add(0.0, 0.5, 0.0),
                Direction.UP,
                platePos,
                false
        );

        client.interactionManager.interactBlock(
                client.player,
                Hand.MAIN_HAND,
                hit
        );

        restoreInventory(client);
        resetState();
    }

    private static int findCobweb(PlayerInventory inventory) {

        for (int slot = 0;
             slot < PlayerInventory.MAIN_SIZE + PlayerInventory.getHotbarSize();
             slot++) {

            ItemStack stack = inventory.getStack(slot);

            if (!stack.isEmpty()
                    && stack.isOf(Items.COBWEB)) {

                return slot;
            }
        }

        return -1;
    }

    private static void restoreInventory(MinecraftClient client) {

        if (client.player == null) {
            return;
        }

        if (client.interactionManager == null) {
            return;
        }

        PlayerInventory inventory = client.player.getInventory();

        if (swappedFromInventory
                && swappedInventorySlot >= 0
                && originalHotbarSlot >= 0) {

            client.interactionManager.clickSlot(
                    client.player.currentScreenHandler.syncId,
                    swappedInventorySlot,
                    originalHotbarSlot,
                    SlotActionType.SWAP,
                    client.player
            );

        } else if (originalHotbarSlot >= 0) {

            inventory.setSelectedSlot(originalHotbarSlot);
        }
    }

    private static void resetState() {

        pendingPlate = null;

        actionStage = 0;
        actionTicks = 0;

        originalHotbarSlot = -1;
        swappedInventorySlot = -1;
        swappedFromInventory = false;
    }
}
