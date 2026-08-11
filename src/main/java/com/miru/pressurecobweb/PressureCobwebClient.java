package com.miru.pressurecobweb;

import com.mojang.brigadier.CommandDispatcher;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;

import net.minecraft.block.BlockState;
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

    private static int cobwebInventorySlot = -1;

    private static boolean swapped = false;

    /*
     * 0 = nothing
     * 1 = waiting for pressure plate
     * 2 = waiting for cobweb swap
     * 3 = waiting for cobweb placement
     * 4 = restoring inventory
     */
    private static int stage = 0;

    private static int ticks = 0;

    @Override
    public void onInitializeClient() {

        /*
         * Detect when the player USES a pressure plate.
         *
         * We don't try to place cobweb here.
         * We only remember where the plate should appear.
         */
        UseItemCallback.EVENT.register((player, world, hand) -> {

            if (!world.isClient() || !enabled) {
                return ActionResult.PASS;
            }

            ItemStack stack = player.getStackInHand(hand);

            if (!(stack.getItem() instanceof BlockItem blockItem)) {
                return ActionResult.PASS;
            }

            if (!(blockItem.getBlock() instanceof PressurePlateBlock)) {
                return ActionResult.PASS;
            }

            MinecraftClient client = MinecraftClient.getInstance();

            if (!(client.crosshairTarget instanceof BlockHitResult hit)) {
                return ActionResult.PASS;
            }

            /*
             * Exact block position where the pressure plate
             * will be placed.
             */
            BlockPos platePos =
                    hit.getBlockPos()
                            .offset(hit.getSide())
                            .toImmutable();

            pendingPlate = platePos;

            originalHotbarSlot =
                    player.getInventory().getSelectedSlot();

            cobwebInventorySlot = -1;
            swapped = false;

            /*
             * Wait for Minecraft to actually place the plate.
             */
            stage = 1;
            ticks = 1;

            return ActionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(
                PressureCobwebClient::tick
        );

        ClientCommandRegistrationCallback.EVENT.register(
                PressureCobwebClient::commands
        );
    }

    private static void commands(
            CommandDispatcher<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> dispatcher,
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

                                            restore(
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
         * ============================================
         * STAGE 1
         *
         * Wait until the pressure plate ACTUALLY
         * exists in the world.
         * ============================================
         */
        if (stage == 1) {

            BlockState state =
                    client.world.getBlockState(pendingPlate);

            if (!(state.getBlock()
                    instanceof PressurePlateBlock)) {

                /*
                 * Plate hasn't appeared yet.
                 */
                ticks = 1;
                return;
            }

            /*
             * Check block above the pressure plate.
             */
            BlockPos cobwebPos =
                    pendingPlate.up();

            if (!client.world
                    .getBlockState(cobwebPos)
                    .isAir()) {

                reset();
                return;
            }

            /*
             * Find cobweb.
             */
            if (!swapCobwebIntoSelectedSlot(client)) {

                reset();
                return;
            }

            /*
             * Give inventory packet one tick.
             */
            stage = 2;
            ticks = 1;

            return;
        }

        /*
         * ============================================
         * STAGE 2
         *
         * Make sure cobweb REALLY reached the
         * selected hotbar slot.
         * ============================================
         */
        if (stage == 2) {

            PlayerInventory inventory =
                    client.player.getInventory();

            ItemStack selected =
                    inventory.getSelectedStack();

            if (!selected.isOf(Items.COBWEB)) {

                /*
                 * Server/client inventory hasn't synced.
                 */
                ticks = 1;
                return;
            }

            stage = 3;
            ticks = 0;

            return;
        }

        /*
         * ============================================
         * STAGE 3
         *
         * Place cobweb directly ON TOP of the
         * pressure plate.
         * ============================================
         */
        if (stage == 3) {

            if (placeCobweb(client)) {

                /*
                 * Give Minecraft time to process
                 * the placement.
                 */
                stage = 4;
                ticks = 2;

            } else {

                restore(client);
                reset();
            }

            return;
        }

        /*
         * ============================================
         * STAGE 4
         *
         * Restore original hotbar/inventory.
         * ============================================
         */
        if (stage == 4) {

            restore(client);

            reset();
        }
    }

    private static boolean swapCobwebIntoSelectedSlot(
            MinecraftClient client
    ) {

        PlayerInventory inventory =
                client.player.getInventory();

        int selected =
                inventory.getSelectedSlot();

        /*
         * First search the hotbar.
         */
        for (int i = PlayerInventory.MAIN_SIZE;
             i < PlayerInventory.MAIN_SIZE
                     + PlayerInventory.getHotbarSize();
             i++) {

            ItemStack stack =
                    inventory.getStack(i);

            if (!stack.isEmpty()
                    && stack.isOf(Items.COBWEB)) {

                int hotbar =
                        i - PlayerInventory.MAIN_SIZE;

                /*
                 * Just select existing cobweb slot.
                 */
                inventory.setSelectedSlot(hotbar);

                swapped = false;
                cobwebInventorySlot = -1;

                return true;
            }
        }

        /*
         * Cobweb is not in hotbar.
         *
         * Search main inventory.
         */
        for (int i = 0;
             i < PlayerInventory.MAIN_SIZE;
             i++) {

            ItemStack stack =
                    inventory.getStack(i);

            if (!stack.isEmpty()
                    && stack.isOf(Items.COBWEB)) {

                cobwebInventorySlot = i;

                /*
                 * PlayerScreenHandler inventory slots:
                 *
                 * 9 + inventory index
                 *
                 * Main inventory indexes 0-26 map to
                 * screen slots 9-35.
                 */
                int screenSlot = 9 + i;

                /*
                 * SWAP button:
                 *
                 * 0 = hotbar 1
                 * 1 = hotbar 2
                 * ...
                 * 8 = hotbar 9
                 */
                client.interactionManager.clickSlot(
                        client.player.playerScreenHandler.syncId,
                        screenSlot,
                        selected,
                        SlotActionType.SWAP,
                        client.player
                );

                swapped = true;

                return true;
            }
        }

        /*
         * No cobweb found.
         */
        return false;
    }

    private static boolean placeCobweb(
            MinecraftClient client
    ) {

        if (pendingPlate == null) {
            return false;
        }

        PlayerInventory inventory =
                client.player.getInventory();

        /*
         * Must actually be holding cobweb.
         */
        if (!inventory.getSelectedStack()
                .isOf(Items.COBWEB)) {

            return false;
        }

        /*
         * Confirm exact pressure plate still exists.
         */
        if (!(client.world
                .getBlockState(pendingPlate)
                .getBlock()
                instanceof PressurePlateBlock)) {

            return false;
        }

        /*
         * Exact block above plate.
         */
        BlockPos cobwebPos =
                pendingPlate.up();

        /*
         * It must be empty.
         */
        if (!client.world
                .getBlockState(cobwebPos)
                .isAir()) {

            return false;
        }

        /*
         * Hit the TOP face of the pressure plate.
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

        /*
         * Fabric/Minecraft may return SUCCESS or
         * SUCCESS_SERVER depending on version.
         *
         * FAIL means it definitely failed.
         */
        return result != ActionResult.FAIL;
    }

    private static void restore(
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
         * Swap it back into the same inventory slot.
         */
        if (swapped
                && cobwebInventorySlot >= 0
                && originalHotbarSlot >= 0) {

            int screenSlot =
                    9 + cobwebInventorySlot;

            client.interactionManager.clickSlot(
                    client.player.playerScreenHandler.syncId,
                    screenSlot,
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
         *
         * Simply return to the slot the player
         * originally had selected.
         */
        if (originalHotbarSlot >= 0) {

            inventory.setSelectedSlot(
                    originalHotbarSlot
            );
        }
    }

    private static void reset() {

        pendingPlate = null;

        stage = 0;

        ticks = 0;

        originalHotbarSlot = -1;

        cobwebInventorySlot = -1;

        swapped = false;
    }
}
