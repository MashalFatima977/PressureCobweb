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
     * 2 = waiting after cobweb selection
     * 3 = waiting after placement
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
         * Detect ONLY the pressure plate that the player
         * is currently placing.
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
                     * Exact position where Minecraft will place
                     * the pressure plate.
                     */
                    targetPlate =
                            hit.getBlockPos()
                                    .offset(hit.getSide())
                                    .toImmutable();

                    stage = 1;

                    /*
                     * Wait for the real pressure plate placement.
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
         * WAIT FOR THE EXACT PRESSURE PLATE.
         */
        if (stage == 1) {

            if (!(client.world
                    .getBlockState(targetPlate)
                    .getBlock()
                    instanceof PressurePlateBlock)) {

                /*
                 * Plate has not arrived from the server yet.
                 */
                ticks = 1;
                return;
            }

            /*
             * Only target THIS plate.
             */
            BlockPos cobwebPos =
                    targetPlate.up();

            /*
             * Don't touch it if something is already above it.
             */
            if (!client.world
                    .getBlockState(cobwebPos)
                    .isAir()) {

                reset();
                return;
            }

            if (!selectCobweb(client)) {

                reset();
                return;
            }

            /*
             * Give the selected slot/swap one tick to sync.
             */
            stage = 2;
            ticks = 1;

            return;
        }

        /*
         * PLACE COBWEB.
         */
        if (stage == 2) {

            if (!placeCobweb(client)) {

                restore(client);
                reset();
                return;
            }

            /*
             * Do NOT restore immediately.
             *
             * Let the placement packet finish first.
             */
            stage = 3;
            ticks = 2;

            return;
        }

        /*
         * RESTORE ORIGINAL HOTBAR/INVENTORY.
         */
        if (stage == 3) {

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

        originalHotbarSlot =
                inv.getSelectedSlot();

        inventorySlot = -1;

        swappedFromInventory = false;

        /*
         * COBWEB ALREADY IN HOTBAR
         */
        if (slot >= PlayerInventory.MAIN_SIZE) {

            int hotbar =
                    slot - PlayerInventory.MAIN_SIZE;

            inv.setSelectedSlot(hotbar);

            return true;
        }

        /*
         * COBWEB IN MAIN INVENTORY.
         *
         * Player inventory screen slots:
         *
         * main inventory starts at slot 9
         * hotbar starts at slot 36
         */
        int screenSlot = 9 + slot;

        client.interactionManager.clickSlot(
                client.player.currentScreenHandler.syncId,
                screenSlot,
                originalHotbarSlot,
                SlotActionType.SWAP,
                client.player
        );

        inventorySlot = slot;

        swappedFromInventory = true;

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
         * Confirm cobweb is REALLY selected.
         */
        if (!inv.getSelectedStack()
                .isOf(Items.COBWEB)) {
            return false;
        }

        /*
         * Make sure the target is still the same
         * pressure plate.
         */
        if (!(client.world
                .getBlockState(targetPlate)
                .getBlock()
                instanceof PressurePlateBlock)) {
            return false;
        }

        /*
         * Make sure the EXACT position above the plate
         * is empty.
         */
        if (!client.world
                .getBlockState(targetPlate.up())
                .isAir()) {
            return false;
        }

        /*
         * CLICK THE TOP FACE OF THE EXACT PLATE.
         *
         * This tells Minecraft:
         *
         * "Place the selected cobweb against THIS plate,
         * on its UP face."
         */
        BlockHitResult hit =
                new BlockHitResult(
                        targetPlate.toCenterPos()
                                .add(0.0, 0.45, 0.0),
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

        /*
         * Only continue if Minecraft accepted the interaction.
         */
        return result != ActionResult.FAIL;
    }

    private static int findCobweb(
            PlayerInventory inv
    ) {

        /*
         * Main inventory.
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
         * Hotbar.
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
         * Cobweb came from main inventory.
         */
        if (swappedFromInventory
                && inventorySlot >= 0
                && originalHotbarSlot >= 0) {

            int screenSlot =
                    9 + inventorySlot;

            client.interactionManager.clickSlot(
                    client.player.currentScreenHandler.syncId,
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
