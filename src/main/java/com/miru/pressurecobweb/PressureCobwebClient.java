package com.miru.pressurecobweb;

import com.mojang.brigadier.CommandDispatcher;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;

import net.minecraft.block.Block;
import net.minecraft.block.PressurePlateBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

@Environment(EnvType.CLIENT)
public class PressureCobwebClient implements ClientModInitializer {

    private static boolean enabled = true;

    private static BlockPos pendingPlate = null;

    private static int originalHotbarSlot = -1;

    private static int sourceInventorySlot = -1;

    private static boolean swappedFromInventory = false;

    private static int stage = 0;

    private static int ticks = 0;

    @Override
    public void onInitializeClient() {

        /*
         * Detect the player's attempt to place a pressure plate.
         */
        UseItemCallback.EVENT.register((player, world, hand) -> {

            if (!world.isClient() || !enabled) {
                return ActionResult.PASS;
            }

            ItemStack stack = player.getStackInHand(hand);

            if (!(stack.getItem() instanceof BlockItem blockItem)) {
                return ActionResult.PASS;
            }

            /*
             * Works with every pressure plate because all
             * pressure plates use PressurePlateBlock.
             */
            Block block = blockItem.getBlock();

            if (!(block instanceof PressurePlateBlock)) {
                return ActionResult.PASS;
            }

            MinecraftClient client = MinecraftClient.getInstance();

            if (!(client.crosshairTarget instanceof BlockHitResult hit)) {
                return ActionResult.PASS;
            }

            /*
             * Normal floor pressure-plate placement.
             */
            if (hit.getSide() != Direction.UP) {
                return ActionResult.PASS;
            }

            BlockPos platePos =
                    hit.getBlockPos()
                            .offset(Direction.UP)
                            .toImmutable();

            /*
             * Save the exact new pressure-plate position.
             */
            pendingPlate = platePos;

            originalHotbarSlot =
                    player.getInventory().getSelectedSlot();

            sourceInventorySlot = -1;
            swappedFromInventory = false;

            /*
             * Wait until vanilla has actually placed
             * the pressure plate.
             */
            stage = 1;
            ticks = 1;

            return ActionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(
                PressureCobwebClient::tick
        );

        ClientCommandRegistrationCallback.EVENT.register(
                PressureCobwebClient::registerCommands
        );
    }

    private static void registerCommands(
            CommandDispatcher<
                    net.fabricmc.fabric.api.client.command.v2
                            .FabricClientCommandSource> dispatcher,
            net.minecraft.command.CommandRegistryAccess registry
    ) {

        dispatcher.register(
                ClientCommandManager.literal("cobweb")
                        .then(
                                ClientCommandManager.literal("on")
                                        .executes(context -> {

                                            enabled = true;

                                            context.getSource()
                                                    .sendFeedback(
                                                            Text.literal(
                                                                    "Cobweb Auto-Place: ON"
                                                            )
                                                    );

                                            return 1;
                                        })
                        )
                        .then(
                                ClientCommandManager.literal("off")
                                        .executes(context -> {

                                            enabled = false;

                                            restoreInventory(
                                                    MinecraftClient.getInstance()
                                            );

                                            reset();

                                            context.getSource()
                                                    .sendFeedback(
                                                            Text.literal(
                                                                    "Cobweb Auto-Place: OFF"
                                                            )
                                                    );

                                            return 1;
                                        })
                        )
        );
    }

    private static void tick(MinecraftClient client) {

        if (!enabled
                || client.player == null
                || client.world == null
                || client.interactionManager == null) {
            return;
        }

        if (pendingPlate == null || stage == 0) {
            return;
        }

        if (ticks > 0) {
            ticks--;
            return;
        }

        /*
         * ==========================================
         * STAGE 1
         * Wait for the pressure plate to exist.
         * ==========================================
         */
        if (stage == 1) {

            if (!(client.world
                    .getBlockState(pendingPlate)
                    .getBlock()
                    instanceof PressurePlateBlock)) {

                /*
                 * Vanilla has not finished placing it yet.
                 */
                ticks = 1;
                return;
            }

            BlockPos webPos = pendingPlate.up();

            /*
             * Never replace another block.
             */
            if (!client.world
                    .getBlockState(webPos)
                    .isAir()) {

                reset();
                return;
            }

            /*
             * Find cobweb.
             */
            if (!prepareCobweb(client)) {

                reset();
                return;
            }

            /*
             * Wait one tick for inventory selection/swap.
             */
            stage = 2;
            ticks = 1;

            return;
        }

        /*
         * ==========================================
         * STAGE 2
         * Make sure cobweb is actually selected.
         * ==========================================
         */
        if (stage == 2) {

            ItemStack selected =
                    client.player
                            .getInventory()
                            .getSelectedStack();

            if (!selected.isOf(Items.COBWEB)) {

                /*
                 * Wait for inventory synchronization.
                 */
                ticks = 1;
                return;
            }

            stage = 3;
            ticks = 0;

            return;
        }

        /*
         * ==========================================
         * STAGE 3
         * Place cobweb directly on top of plate.
         * ==========================================
         */
        if (stage == 3) {

            boolean placed = placeCobweb(client);

            if (!placed) {

                restoreInventory(client);
                reset();

                return;
            }

            /*
             * Give Minecraft time to process placement.
             */
            stage = 4;
            ticks = 2;

            return;
        }

        /*
         * ==========================================
         * STAGE 4
         * Restore the original selected slot.
         * ==========================================
         */
        if (stage == 4) {

            restoreInventory(client);
            reset();
        }
    }

    private static boolean prepareCobweb(
            MinecraftClient client
    ) {

        PlayerInventory inventory =
                client.player.getInventory();

        int selected =
                inventory.getSelectedSlot();

        /*
         * ==========================================
         * HOTBAR
         *
         * PlayerInventory indexes 0-8 are hotbar.
         * ==========================================
         */
        for (int slot = 0;
             slot < PlayerInventory.getHotbarSize();
             slot++) {

            ItemStack stack =
                    inventory.getStack(slot);

            if (!stack.isEmpty()
                    && stack.isOf(Items.COBWEB)) {

                inventory.setSelectedSlot(slot);

                swappedFromInventory = false;
                sourceInventorySlot = -1;

                return true;
            }
        }

        /*
         * ==========================================
         * MAIN INVENTORY
         *
         * PlayerInventory indexes 9-35 are main
         * inventory.
         * ==========================================
         */
        for (int slot = PlayerInventory.getHotbarSize();
             slot < PlayerInventory.MAIN_SIZE;
             slot++) {

            ItemStack stack =
                    inventory.getStack(slot);

            if (!stack.isEmpty()
                    && stack.isOf(Items.COBWEB)) {

                sourceInventorySlot = slot;

                /*
                 * PlayerScreenHandler:
                 *
                 * inventory index 9 -> screen slot 9
                 * inventory index 10 -> screen slot 10
                 * etc.
                 */
                int screenSlot = slot;

                client.interactionManager.clickSlot(
                        client.player
                                .playerScreenHandler
                                .syncId,

                        screenSlot,

                        selected,

                        SlotActionType.SWAP,

                        client.player
                );

                swappedFromInventory = true;

                return true;
            }
        }

        return false;
    }

    private static boolean placeCobweb(
            MinecraftClient client
    ) {

        if (pendingPlate == null) {
            return false;
        }

        /*
         * Confirm the plate still exists.
         */
        if (!(client.world
                .getBlockState(pendingPlate)
                .getBlock()
                instanceof PressurePlateBlock)) {

            return false;
        }

        /*
         * Exact position one block ABOVE the plate.
         */
        BlockPos webPos =
                pendingPlate.up();

        /*
         * Must be empty.
         */
        if (!client.world
                .getBlockState(webPos)
                .isAir()) {

            return false;
        }

        /*
         * Must currently hold cobweb.
         */
        if (!client.player
                .getInventory()
                .getSelectedStack()
                .isOf(Items.COBWEB)) {

            return false;
        }

        /*
         * Target the TOP face of the exact pressure plate.
         *
         * This tells Minecraft:
         *
         * "Place the held block against this face."
         */
        BlockHitResult hit =
                new BlockHitResult(
                        pendingPlate.toCenterPos()
                                .add(0.0, 0.5, 0.0),

                        Direction.UP,

                        pendingPlate,

                        false
                );

        ActionResult result =
                client.interactionManager.interactBlock(
                        client.player,
                        Hand.MAIN_HAND,
                        hit
                );

        return result != ActionResult.FAIL;
    }

    private static void restoreInventory(
            MinecraftClient client
    ) {

        if (client.player == null
                || client.interactionManager == null) {
            return;
        }

        PlayerInventory inventory =
                client.player.getInventory();

        /*
         * Cobweb came from main inventory.
         * Swap it back.
         */
        if (swappedFromInventory
                && sourceInventorySlot >= 0
                && originalHotbarSlot >= 0) {

            client.interactionManager.clickSlot(
                    client.player
                            .playerScreenHandler
                            .syncId,

                    sourceInventorySlot,

                    originalHotbarSlot,

                    SlotActionType.SWAP,

                    client.player
            );

            inventory.setSelectedSlot(
                    originalHotbarSlot
            );

            return;
        }

        /*
         * Cobweb was already in hotbar.
         */
        if (originalHotbarSlot >= 0) {

            inventory.setSelectedSlot(
                    originalHotbarSlot
            );
        }
    }

    private static void reset() {

        pendingPlate = null;

        originalHotbarSlot = -1;

        sourceInventorySlot = -1;

        swappedFromInventory = false;

        stage = 0;

        ticks = 0;
    }
}
