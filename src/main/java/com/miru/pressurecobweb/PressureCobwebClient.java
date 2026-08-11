package com.miru.pressurecobweb;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.block.PressurePlateBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.BlockItem;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class PressureCobwebClient implements ClientModInitializer {

    private static boolean enabled = true;

    private static BlockPos expectedPlate = null;

    private static int stage = 0;
    private static int ticks = 0;

    private static int originalHotbarSlot = -1;
    private static int swappedInventorySlot = -1;
    private static boolean swappedFromInventory = false;

    @Override
    public void onInitializeClient() {

        ClientTickEvents.END_CLIENT_TICK.register(
                PressureCobwebClient::onTick
        );

        ClientCommandRegistrationCallback.EVENT.register(
                PressureCobwebClient::registerCommands
        );

        /*
         * Capture ONLY the pressure-plate placement that
         * the player is currently attempting.
         */
        UseItemCallback.EVENT.register(
                (player, world, hand) -> {

                    if (!world.isClient || !enabled) {
                        return ActionResult.PASS;
                    }

                    ItemStack stack = player.getStackInHand(hand);

                    if (!(stack.getItem() instanceof BlockItem blockItem)) {
                        return ActionResult.PASS;
                    }

                    if (!(blockItem.getBlock()
                            instanceof PressurePlateBlock)) {
                        return ActionResult.PASS;
                    }

                    MinecraftClient client =
                            MinecraftClient.getInstance();

                    if (client.crosshairTarget
                            instanceof BlockHitResult hit) {

                        /*
                         * The pressure plate will be placed
                         * against the face that was clicked.
                         */
                        BlockPos placePos =
                                hit.getBlockPos()
                                        .offset(hit.getSide());

                        expectedPlate =
                                placePos.toImmutable();

                        /*
                         * Wait for the actual placement to
                         * appear in the client world.
                         */
                        stage = 1;
                        ticks = 1;
                    }

                    return ActionResult.PASS;
                }
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
                                            Text.literal(
                                                    "Cobweb Auto-Place: ON"
                                            )
                                    );

                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("off")
                                .executes(context -> {

                                    enabled = false;

                                    reset();

                                    context.getSource().sendFeedback(
                                            Text.literal(
                                                    "Cobweb Auto-Place: OFF"
                                            )
                                    );

                                    return 1;
                                }))
        );
    }

    private static void onTick(MinecraftClient client) {

        if (!enabled
                || client.player == null
                || client.world == null
                || client.interactionManager == null) {
            return;
        }

        if (stage == 0 || expectedPlate == null) {
            return;
        }

        if (ticks > 0) {
            ticks--;
            return;
        }

        /*
         * Stage 1:
         * Wait until the pressure plate actually exists.
         */
        if (stage == 1) {

            if (!(client.world
                    .getBlockState(expectedPlate)
                    .getBlock()
                    instanceof PressurePlateBlock)) {

                /*
                 * Give the server/client another tick.
                 */
                ticks = 1;
                return;
            }

            /*
             * The exact plate we just placed now exists.
             */
            if (!client.world
                    .getBlockState(expectedPlate.up())
                    .isAir()) {

                reset();
                return;
            }

            if (!prepareCobweb(client)) {
                reset();
                return;
            }

            /*
             * One tick for the inventory/selected item state
             * to settle before sending the placement.
             */
            stage = 2;
            ticks = 1;
            return;
        }

        /*
         * Stage 2:
         * Place cobweb on the exact top face.
         */
        if (stage == 2) {

            placeCobweb(client);

            reset();
        }
    }

    private static boolean prepareCobweb(
            MinecraftClient client
    ) {

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
         * Already in hotbar.
         */
        if (cobwebSlot >= PlayerInventory.MAIN_SIZE) {

            int hotbarSlot =
                    cobwebSlot - PlayerInventory.MAIN_SIZE;

            inventory.setSelectedSlot(hotbarSlot);

            return true;
        }

        /*
         * In main inventory:
         * swap it with the currently selected hotbar slot.
         */
        inventory.swapSlotWithHotbar(cobwebSlot);

        swappedInventorySlot = cobwebSlot;
        swappedFromInventory = true;

        return true;
    }

    private static void placeCobweb(
            MinecraftClient client
    ) {

        if (expectedPlate == null) {
            return;
        }

        PlayerInventory inventory =
                client.player.getInventory();

        /*
         * Confirm the selected stack is a cobweb.
         */
        if (!inventory.getSelectedStack()
                .isOf(Items.COBWEB)) {

            restoreInventory(client);
            return;
        }

        /*
         * EXACT TOP FACE of the pressure plate.
         */
        BlockHitResult hit = new BlockHitResult(
                expectedPlate.toCenterPos().add(
                        0.0,
                        0.5,
                        0.0
                ),
                Direction.UP,
                expectedPlate,
                false
        );

        client.interactionManager.interactBlock(
                client.player,
                Hand.MAIN_HAND,
                hit
        );

        restoreInventory(client);
    }

    private static int findCobweb(
            PlayerInventory inventory
    ) {

        /*
         * Main inventory.
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
         * Hotbar.
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

        if (swappedFromInventory
                && swappedInventorySlot >= 0) {

            inventory.swapSlotWithHotbar(
                    swappedInventorySlot
            );

            if (originalHotbarSlot >= 0) {
                inventory.setSelectedSlot(
                        originalHotbarSlot
                );
            }

        } else if (originalHotbarSlot >= 0) {

            inventory.setSelectedSlot(
                    originalHotbarSlot
            );
        }
    }

    private static void reset() {

        expectedPlate = null;

        stage = 0;
        ticks = 0;

        originalHotbarSlot = -1;
        swappedInventorySlot = -1;
        swappedFromInventory = false;
    }
}
