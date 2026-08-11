```java
package com.miru.pressurecobweb;

import com.mojang.brigadier.CommandDispatcher;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
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

    private static BlockPos targetPlate = null;

    /*
     * 0 = idle
     * 1 = waiting for plate
     * 2 = waiting for inventory swap
     * 3 = placing cobweb
     * 4 = waiting before restore
     */
    private static int stage = 0;

    private static int ticks = 0;

    private static int originalHotbarSlot = -1;

    private static int inventorySlot = -1;

    private static boolean swappedFromInventory = false;

    @Override
    public void onInitializeClient() {

        ClientTickEvents.END_CLIENT_TICK.register(
                PressureCobwebClient::tick
        );

        ClientCommandRegistrationCallback.EVENT.register(
                PressureCobwebClient::commands
        );

        /*
         * Detect the pressure plate the player is
         * actually trying to place.
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
                     * Position where the pressure plate
                     * will be placed.
                     */
                    targetPlate =
                            hit.getBlockPos()
                                    .offset(hit.getSide())
                                    .toImmutable();

                    /*
                     * Save the currently selected hotbar slot
                     * BEFORE doing anything.
                     */
                    originalHotbarSlot =
                            player.getInventory().getSelectedSlot();

                    inventorySlot = -1;
                    swappedFromInventory = false;

                    stage = 1;

                    /*
                     * Wait for server/client to actually
                     * create the plate.
                     */
                    ticks = 2;

                    return ActionResult.PASS;
                }
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

        if (stage == 0 || targetPlate == null) {
            return;
        }

        if (ticks > 0) {
            ticks--;
            return;
        }

        /*
         * ==========================================
         * STAGE 1
         * Wait until the pressure plate exists.
         * ==========================================
         */
        if (stage == 1) {

            if (!(client.world
                    .getBlockState(targetPlate)
                    .getBlock()
                    instanceof PressurePlateBlock)) {

                /*
                 * Server has not updated the plate yet.
                 */
                ticks = 1;
                return;
            }

            BlockPos cobwebPos =
                    targetPlate.up();

            /*
             * Must be completely empty above the plate.
             */
            if (!client.world
                    .getBlockState(cobwebPos)
                    .isAir()) {

                reset();
                return;
            }

            /*
             * Find cobweb and move it into the
             * currently selected hotbar slot.
             */
            if (!selectCobweb(client)) {

                reset();
                return;
            }

            /*
             * Give the inventory update one tick.
             */
            stage = 2;
            ticks = 1;

            return;
        }

        /*
         * ==========================================
         * STAGE 2
         * Verify cobweb is actually selected.
         * ==========================================
         */
        if (stage == 2) {

            PlayerInventory inv =
                    client.player.getInventory();

            if (!inv.getSelectedStack()
                    .isOf(Items.COBWEB)) {

                /*
                 * Inventory swap did not happen yet.
                 * Try once more.
                 */
                if (!retrySwap(client)) {
                    restore(client);
                    reset();
                    return;
                }

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
         * Place cobweb.
         * ==========================================
         */
        if (stage == 3) {

            if (!placeCobweb(client)) {

                restore(client);
                reset();
                return;
            }

            /*
             * Wait for placement packet/server update.
             */
            stage = 4;
            ticks = 2;

            return;
        }

        /*
         * ==========================================
         * STAGE 4
         * Restore original inventory state.
         * ==========================================
         */
        if (stage == 4) {

            restore(client);
            reset();
        }
    }

    private static boolean selectCobweb(
            MinecraftClient client
    ) {

        PlayerInventory inv =
                client.player.getInventory();

        int slot = findCobweb(inv);

        if (slot == -1) {
            return false;
        }

        /*
         * Always remember the real selected slot.
         */
        originalHotbarSlot =
                inv.getSelectedSlot();

        /*
         * ==========================================
         * COBWEB ALREADY IN HOTBAR
         * ==========================================
         */
        if (slot >= PlayerInventory.MAIN_SIZE) {

            int hotbarSlot =
                    slot - PlayerInventory.MAIN_SIZE;

            inv.setSelectedSlot(hotbarSlot);

            swappedFromInventory = false;
            inventorySlot = -1;

            return true;
        }

        /*
         * ==========================================
         * COBWEB IN MAIN INVENTORY
         * ==========================================
         *
         * PlayerScreenHandler slot IDs:
         *
         * 9  = inventory slot 0
         * 10 = inventory slot 1
         * ...
         * 35 = inventory slot 26
         *
         * 36-44 = hotbar
         */
        int screenSlot =
                9 + slot;

        /*
         * SWAP button is the selected hotbar slot.
         *
         * SlotActionType.SWAP exchanges the clicked
         * inventory slot with that hotbar slot.
         */
        client.interactionManager.clickSlot(
                client.player.playerScreenHandler.syncId,
                screenSlot,
                originalHotbarSlot,
                SlotActionType.SWAP,
                client.player
        );

        inventorySlot = slot;
        swappedFromInventory = true;

        return true;
    }

    private static boolean retrySwap(
            MinecraftClient client
    ) {

        if (!swappedFromInventory
                || inventorySlot < 0
                || originalHotbarSlot < 0) {
            return false;
        }

        PlayerInventory inv =
                client.player.getInventory();

        /*
         * If cobweb already arrived in selected slot,
         * nothing more is needed.
         */
        if (inv.getSelectedStack()
                .isOf(Items.COBWEB)) {
            return true;
        }

        int screenSlot =
                9 + inventorySlot;

        client.interactionManager.clickSlot(
                client.player.playerScreenHandler.syncId,
                screenSlot,
                originalHotbarSlot,
                SlotActionType.SWAP,
                client.player
        );

        return true;
    }

    private static boolean placeCobweb(
            MinecraftClient client
    ) {

        if (targetPlate == null) {
            return false;
        }

        PlayerInventory inv =
                client.player.getInventory();

        /*
         * Make absolutely sure the selected item
         * is a cobweb.
         */
        if (!inv.getSelectedStack()
                .isOf(Items.COBWEB)) {
            return false;
        }

        /*
         * Make sure this is still the exact plate.
         */
        if (!(client.world
                .getBlockState(targetPlate)
                .getBlock()
                instanceof PressurePlateBlock)) {
            return false;
        }

        BlockPos cobwebPos =
                targetPlate.up();

        /*
         * The block above must still be empty.
         */
        if (!client.world
                .getBlockState(cobwebPos)
                .isAir()) {
            return false;
        }

        /*
         * Hit the TOP FACE of the exact pressure plate.
         *
         * Plate center:
         * X + 0.5
         * Y + 0.5
         * Z + 0.5
         *
         * Top face is slightly above the center.
         */
        BlockHitResult hit =
                new BlockHitResult(
                        targetPlate.toCenterPos()
                                .add(0.0, 0.5, 0.0),
                        Direction.UP,
                        targetPlate,
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

    private static int findCobweb(
            PlayerInventory inv
    ) {

        /*
         * Search normal inventory first.
         */
        for (int i = 0;
             i < PlayerInventory.MAIN_SIZE;
             i++) {

            ItemStack stack =
                    inv.getStack(i);

            if (!stack.isEmpty()
                    && stack.isOf(Items.COBWEB)) {

                return i;
            }
        }

        /*
         * Search hotbar.
         */
        for (int i = PlayerInventory.MAIN_SIZE;
             i < PlayerInventory.MAIN_SIZE
                     + PlayerInventory.getHotbarSize();
             i++) {

            ItemStack stack =
                    inv.getStack(i);

            if (!stack.isEmpty()
                    && stack.isOf(Items.COBWEB)) {

                return i;
            }
        }

        return -1;
    }

    private static void restore(
            MinecraftClient client
    ) {

        if (client.player == null
                || client.interactionManager == null) {
            return;
        }

        PlayerInventory inv =
                client.player.getInventory();

        /*
         * Cobweb originally came from main inventory.
         *
         * Swap the original item back.
         */
        if (swappedFromInventory
                && inventorySlot >= 0
                && originalHotbarSlot >= 0) {

            /*
             * If selected slot no longer contains the
             * original item, perform the swap back.
             */
            int screenSlot =
                    9 + inventorySlot;

            client.interactionManager.clickSlot(
                    client.player.playerScreenHandler.syncId,
                    screenSlot,
                    originalHotbarSlot,
                    SlotActionType.SWAP,
                    client.player
            );

            inv.setSelectedSlot(
                    originalHotbarSlot
            );

            return;
        }

        /*
         * Cobweb was already in hotbar.
         * Just return to the original slot.
         */
        if (originalHotbarSlot >= 0) {

            inv.setSelectedSlot(
                    originalHotbarSlot
            );
        }
    }

    private static void reset() {

        targetPlate = null;

        stage = 0;

        ticks = 0;

        originalHotbarSlot = -1;

        inventorySlot = -1;

        swappedFromInventory = false;
    }
}
```
