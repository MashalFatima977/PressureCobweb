package com.miru.pressurecobweb;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.PressurePlateBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashSet;
import java.util.Set;

public class PressureCobwebClient implements ClientModInitializer {

    private static final Set<BlockPos> knownPlates = new HashSet<>();

    private static boolean enabled = true;

    private static BlockPos pendingPlate = null;

    /*
     * 0 = idle
     * 1 = waiting before selecting cobweb
     * 2 = waiting before placement
     */
    private static int stage = 0;
    private static int ticks = 0;

    private static int originalHotbarSlot = -1;
    private static int swappedInventorySlot = -1;
    private static boolean swappedFromInventory = false;

    @Override
    public void onInitializeClient() {

        ClientTickEvents.END_CLIENT_TICK.register(
                PressureCobwebClient::onClientTick
        );

        ClientCommandRegistrationCallback.EVENT.register(
                PressureCobwebClient::registerCommands
        );
    }

    private static void registerCommands(
            CommandDispatcher<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> dispatcher,
            net.minecraft.command.CommandRegistryAccess registryAccess
    ) {

        dispatcher.register(
                ClientCommandManager.literal("cobweb")
                        .then(ClientCommandManager.literal("on")
                                .executes(context -> {

                                    enabled = true;

                                    context.getSource().sendFeedback(
                                            Text.literal("§aCobweb Auto-Place: ON")
                                    );

                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("off")
                                .executes(context -> {

                                    enabled = false;

                                    resetState();

                                    context.getSource().sendFeedback(
                                            Text.literal("§cCobweb Auto-Place: OFF")
                                    );

                                    return 1;
                                }))
        );
    }

    private static void onClientTick(MinecraftClient client) {

        if (client.player == null || client.world == null) {
            return;
        }

        if (client.interactionManager == null) {
            return;
        }

        if (!enabled) {
            return;
        }

        if (stage != 0) {
            handlePlacement(client);
            return;
        }

        detectNewPressurePlate(client);
    }

    private static void detectNewPressurePlate(MinecraftClient client) {

        ClientPlayerEntity player = client.player;

        BlockPos center = player.getBlockPos();

        Set<BlockPos> currentPlates = new HashSet<>();

        for (int x = -5; x <= 5; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -5; z <= 5; z++) {

                    BlockPos pos = center.add(x, y, z);

                    Block block = client.world
                            .getBlockState(pos)
                            .getBlock();

                    if (!(block instanceof PressurePlateBlock)) {
                        continue;
                    }

                    currentPlates.add(pos);

                    if (!knownPlates.contains(pos)) {

                        knownPlates.add(pos);

                        pendingPlate = pos.toImmutable();

                        /*
                         * Give Minecraft one tick to finish
                         * processing the plate placement.
                         */
                        stage = 1;
                        ticks = 1;

                        return;
                    }
                }
            }
        }

        knownPlates.removeIf(
                pos -> !currentPlates.contains(pos)
        );
    }

    private static void handlePlacement(MinecraftClient client) {

        if (pendingPlate == null) {
            resetState();
            return;
        }

        if (ticks > 0) {
            ticks--;
            return;
        }

        if (stage == 1) {

            if (!prepareCobweb(client)) {
                resetState();
                return;
            }

            /*
             * Give the selected hotbar slot one tick to sync
             * before sending the block placement.
             */
            stage = 2;
            ticks = 1;

            return;
        }

        if (stage == 2) {

            placeCobweb(client);

            resetState();
        }
    }

    private static boolean prepareCobweb(MinecraftClient client) {

        if (pendingPlate == null) {
            return false;
        }

        BlockPos platePos = pendingPlate;

        if (!(client.world.getBlockState(platePos).getBlock()
                instanceof PressurePlateBlock)) {
            return false;
        }

        /*
         * The block directly above the pressure plate
         * must be empty.
         */
        if (!client.world
                .getBlockState(platePos.up())
                .isAir()) {
            return false;
        }

        PlayerInventory inventory =
                client.player.getInventory();

        int cobwebSlot = findCobweb(inventory);

        if (cobwebSlot == -1) {
            return false;
        }

        originalHotbarSlot =
                inventory.getSelectedSlot();

        swappedInventorySlot = -1;
        swappedFromInventory = false;

        /*
         * Cobweb is already in hotbar.
         */
        if (cobwebSlot >= PlayerInventory.MAIN_SIZE) {

            int hotbarSlot =
                    cobwebSlot - PlayerInventory.MAIN_SIZE;

            inventory.setSelectedSlot(hotbarSlot);

            return true;
        }

        /*
         * Cobweb is in the main inventory.
         *
         * Use Minecraft's own inventory helper instead of
         * manually sending an incorrect screen slot index.
         */
        inventory.swapSlotWithHotbar(cobwebSlot);

        swappedInventorySlot = cobwebSlot;
        swappedFromInventory = true;

        return true;
    }

    private static void placeCobweb(MinecraftClient client) {

        if (pendingPlate == null) {
            return;
        }

        BlockPos platePos = pendingPlate;

        /*
         * Target the TOP face of the pressure plate.
         */
        BlockHitResult hit = new BlockHitResult(
                platePos.toCenterPos().add(
                        0.0,
                        0.5,
                        0.0
                ),
                Direction.UP,
                platePos,
                false
        );

        /*
         * Verify that the cobweb is actually selected.
         */
        if (!client.player.getInventory()
                .getSelectedStack()
                .isOf(Items.COBWEB)) {

            restoreInventory(client);
            return;
        }

        /*
         * Send the normal Minecraft block interaction.
         * The target block is the pressure plate and the
         * clicked face is its TOP face.
         */
        client.interactionManager.interactBlock(
                client.player,
                Hand.MAIN_HAND,
                hit
        );

        /*
         * Restore the original hotbar/inventory state.
         */
        restoreInventory(client);
    }

    private static int findCobweb(
            PlayerInventory inventory
    ) {

        /*
         * Search the 36 normal player inventory slots.
         */
        for (int slot = 0;
             slot < PlayerInventory.MAIN_SIZE;
             slot++) {

            ItemStack stack =
                    inventory.getStack(slot);

            if (!stack.isEmpty()
                    && stack.isOf(Items.COBWEB)) {

                return slot;
            }
        }

        /*
         * Search the 9 hotbar slots.
         */
        for (int slot = PlayerInventory.MAIN_SIZE;
             slot < PlayerInventory.MAIN_SIZE
                     + PlayerInventory.getHotbarSize();
             slot++) {

            ItemStack stack =
                    inventory.getStack(slot);

            if (!stack.isEmpty()
                    && stack.isOf(Items.COBWEB)) {

                return slot;
            }
        }

        return -1;
    }

    private static void restoreInventory(
            MinecraftClient client
    ) {

        if (client.player == null) {
            return;
        }

        PlayerInventory inventory =
                client.player.getInventory();

        /*
         * If the cobweb was swapped from main inventory,
         * swap that same inventory slot back with the
         * currently selected hotbar slot.
         */
        if (swappedFromInventory
                && swappedInventorySlot >= 0) {

            inventory.swapSlotWithHotbar(
                    swappedInventorySlot
            );

            /*
             * Restore the original selected hotbar slot.
             */
            if (originalHotbarSlot >= 0) {

                inventory.setSelectedSlot(
                        originalHotbarSlot
                );
            }

            return;
        }

        /*
         * Cobweb was already in the hotbar.
         */
        if (originalHotbarSlot >= 0) {

            inventory.setSelectedSlot(
                    originalHotbarSlot
            );
        }
    }

    private static void resetState() {

        pendingPlate = null;

        stage = 0;
        ticks = 0;

        originalHotbarSlot = -1;
        swappedInventorySlot = -1;
        swappedFromInventory = false;
    }
}
