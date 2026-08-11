package com.miru.pressurecobweb;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
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

    /*
     * EXACT pressure plate that the player is currently placing.
     */
    private static BlockPos targetPlate = null;

    /*
     * 0 = idle
     * 1 = waiting for pressure plate
     * 2 = waiting after cobweb swap
     * 3 = waiting before restoring inventory
     */
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
         * Detect ONLY the pressure plate the player is
         * currently trying to place.
         */
        UseItemCallback.EVENT.register(
                (player, world, hand) -> {

                    if (!world.isClient() || !enabled) {
                        return ActionResult.PASS;
                    }

                    ItemStack stack =
                            player.getStackInHand(hand);

                    if (!(stack.getItem() instanceof BlockItem blockItem)) {
                        return ActionResult.PASS;
                    }

                    if (!(blockItem.getBlock()
                            instanceof PressurePlateBlock)) {
                        return ActionResult.PASS;
                    }

                    MinecraftClient client =
                            MinecraftClient.getInstance();

                    if (!(client.crosshairTarget
                            instanceof BlockHitResult hit)) {
                        return ActionResult.PASS;
                    }

                    /*
                     * Calculate the EXACT position where the
                     * pressure plate will be placed.
                     */
                    BlockPos placePos =
                            hit.getBlockPos()
                                    .offset(hit.getSide());

                    targetPlate =
                            placePos.toImmutable();

                    stage = 1;

                    /*
                     * Give Minecraft one tick to actually
                     * place the pressure plate.
                     */
                    ticks = 1;

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

    private static void onTick(MinecraftClient client) {

        if (!enabled
                || client.player == null
                || client.world == null
                || client.interactionManager == null) {
            return;
        }

        if (stage == 0 || targetPlate == null) {
            return;
        }

        if (ticks > 0) {
            ticks--;
            return;
        }

        /*
         * STEP 1
         *
         * Wait until the EXACT pressure plate that the
         * player just placed exists.
         */
        if (stage == 1) {

            if (!(client.world
                    .getBlockState(targetPlate)
                    .getBlock()
                    instanceof PressurePlateBlock)) {

                /*
                 * The server/client hasn't registered it yet.
                 */
                ticks = 1;
                return;
            }

            /*
             * The block directly above THIS plate must be empty.
             */
            if (!client.world
                    .getBlockState(targetPlate.up())
                    .isAir()) {

                reset();
                return;
            }

            if (!prepareCobweb(client)) {

                reset();
                return;
            }

            /*
             * Wait one tick after the inventory swap/selection.
             */
            stage = 2;
            ticks = 1;

            return;
        }

        /*
         * STEP 2
         *
         * Place cobweb on the EXACT TOP face of the
         * pressure plate.
         */
        if (stage == 2) {

            if (!placeCobweb(client)) {

                restoreInventory(client);
                reset();
                return;
            }

            /*
             * IMPORTANT:
             *
             * Do NOT immediately restore the inventory.
             * Give Minecraft/server time to process the
             * cobweb placement first.
             */
            stage = 3;
            ticks = 2;

            return;
        }

        /*
         * STEP 3
         *
         * Now restore the original hotbar/inventory state.
         */
        if (stage == 3) {

            restoreInventory(client);

            reset();
        }
    }

    private static boolean prepareCobweb(
            MinecraftClient client
    ) {

        PlayerInventory inventory =
                client.player.getInventory();

        int cobwebSlot =
                findCobweb(inventory);

        if (cobwebSlot == -1) {
            return false;
        }

        originalHotbarSlot =
                inventory.getSelectedSlot();

        swappedInventorySlot = -1;

        swappedFromInventory = false;

        /*
         * Cobweb is already in the hotbar.
         */
        if (cobwebSlot >= PlayerInventory.MAIN_SIZE) {

            int hotbarSlot =
                    cobwebSlot - PlayerInventory.MAIN_SIZE;

            inventory.setSelectedSlot(hotbarSlot);

            return true;
        }

        /*
         * Cobweb is in the MAIN inventory.
         *
         * ScreenHandler slot IDs are NOT the same as
         * PlayerInventory slot IDs.
         *
         * Main inventory slot 0 -> screen slot 9
         * Main inventory slot 1 -> screen slot 10
         * etc.
         */
        int screenSlot =
                9 + cobwebSlot;

        client.interactionManager.clickSlot(
                client.player.currentScreenHandler.syncId,
                screenSlot,
                originalHotbarSlot,
                SlotActionType.SWAP,
                client.player
        );

        swappedInventorySlot =
                cobwebSlot;

        swappedFromInventory = true;

        return true;
    }

    private static boolean placeCobweb(
            MinecraftClient client
    ) {

        if (targetPlate == null) {
            return false;
        }

        PlayerInventory inventory =
                client.player.getInventory();

        /*
         * Make absolutely sure the selected item is
         * actually the cobweb.
         */
        if (!inventory.getSelectedStack()
                .isOf(Items.COBWEB)) {

            return false;
        }

        /*
         * EXACT TOP FACE.
         *
         * The clicked block is targetPlate.
         * The clicked face is UP.
         * Minecraft therefore attempts to place the
         * cobweb in targetPlate.up().
         */
        BlockHitResult hit =
                new BlockHitResult(
                        targetPlate.toCenterPos()
                                .add(
                                        0.0,
                                        0.5,
                                        0.0
                                ),
                        Direction.UP,
                        targetPlate,
                        false
                );

        client.interactionManager.interactBlock(
                client.player,
                Hand.MAIN_HAND,
                hit
        );

        return true;
    }

    private static int findCobweb(
            PlayerInventory inventory
    ) {

        /*
         * Search main inventory.
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
         * Search hotbar.
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

        if (client.player == null
                || client.interactionManager == null) {
            return;
        }

        PlayerInventory inventory =
                client.player.getInventory();

        /*
         * Cobweb came from main inventory.
         *
         * Swap it back using the CORRECT screen slot ID.
         */
        if (swappedFromInventory
                && swappedInventorySlot >= 0
                && originalHotbarSlot >= 0) {

            int screenSlot =
                    9 + swappedInventorySlot;

            client.interactionManager.clickSlot(
                    client.player.currentScreenHandler.syncId,
                    screenSlot,
                    originalHotbarSlot,
                    SlotActionType.SWAP,
                    client.player
            );

            /*
             * Restore original selected slot.
             */
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

        targetPlate = null;

        stage = 0;

        ticks = 0;

        originalHotbarSlot = -1;

        swappedInventorySlot = -1;

        swappedFromInventory = false;
    }
}
