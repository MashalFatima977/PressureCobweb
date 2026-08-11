```java
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

    /*
     * 0 = nothing
     * 1 = waiting to swap/select cobweb
     * 2 = waiting to place cobweb
     */
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

        /*
         * Handle the current automatic placement process first.
         */
        if (actionStage != 0) {
            handleAction(client);
            return;
        }

        /*
         * Look for a newly placed pressure plate.
         */
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

                        /*
                         * This pressure plate was not present
                         * during the previous scan.
                         */
                        if (!knownPlates.contains(pos)) {

                            knownPlates.add(pos);

                            pendingPlate = pos.toImmutable();

                            /*
                             * Wait two ticks so the newly placed
                             * pressure plate is fully registered.
                             */
                            actionStage = 1;
                            actionTicks = 2;

                            return;
                        }
                    }
                }
            }
        }

        /*
         * Remove pressure plates that no longer exist.
         */
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

        /*
         * Stage 1:
         * Find and select/swap the cobweb.
         */
        if (actionStage == 1) {

            prepareCobweb(client);

            /*
             * prepareCobweb() can fail if there is
             * no cobweb or the plate disappeared.
             */
            if (actionStage != 1) {
                return;
            }

            /*
             * Wait one tick after selecting/swapping
             * before placing.
             */
            actionStage = 2;
            actionTicks = 1;
            return;
        }

        /*
         * Stage 2:
         * Place the cobweb exactly above the pressure plate.
         */
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

        /*
         * Make sure the pressure plate still exists.
         */
        if (!(client.world.getBlockState(platePos).getBlock()
                instanceof PressurePlateBlock)) {

            resetState();
            return;
        }

        /*
         * The cobweb position must be directly above
         * the pressure plate.
         */
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

        /*
         * Cobweb is already in the hotbar.
         */
        if (cobwebSlot >= PlayerInventory.MAIN_SIZE) {

            int hotbarSlot =
                    cobwebSlot - PlayerInventory.MAIN_SIZE;

            inventory.setSelectedSlot(hotbarSlot);

        } else {

            /*
             * Cobweb is in the main inventory.
             *
             * Swap it with the currently selected
             * hotbar slot.
             */
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

        /*
         * The block directly above the pressure plate.
         */
        BlockPos cobwebPos = platePos.up();

        /*
         * Do not place if the target is no longer empty.
         */
        if (!client.world.getBlockState(cobwebPos).isAir()) {
            restoreInventory(client);
            resetState();
            return;
        }

        /*
         * Make sure the selected item is actually a cobweb.
         */
        if (!client.player.getInventory()
                .getSelectedStack()
                .isOf(Items.COBWEB)) {

            restoreInventory(client);
            resetState();
            return;
        }

        /*
         * Target the exact TOP face of the pressure plate.
         *
         * Clicking the top face causes Minecraft to place
         * the cobweb in the block position directly above it.
         */
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

        /*
         * Restore the player's original inventory/hotbar state.
         */
        restoreInventory(client);

        resetState();
    }

    private static int findCobweb(PlayerInventory inventory) {

        /*
         * Search the complete main inventory + hotbar.
         */
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

        /*
         * Cobweb originally came from the main inventory,
         * so swap it back into its original position.
         */
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

            /*
             * Cobweb was already in the hotbar.
             * Simply restore the original selected slot.
             */
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
```
