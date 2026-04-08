package com.orca.carryon;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CarryOnMod implements ModInitializer {
    public static final String MOD_ID = "carry-on";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Store carried data per player
    private static final Map<UUID, CarriedData> carriedItems = new HashMap<>();

    public static class CarriedData {
        public final boolean isBlockEntity;
        public final NbtCompound nbt;
        public final BlockState blockState; // for block entities
        public final Identifier entityType; // for mobs

        public CarriedData(BlockState blockState, NbtCompound nbt) {
            this.isBlockEntity = true;
            this.blockState = blockState;
            this.nbt = nbt;
            this.entityType = null;
        }

        public CarriedData(Identifier entityType, NbtCompound nbt) {
            this.isBlockEntity = false;
            this.blockState = null;
            this.nbt = nbt;
            this.entityType = entityType;
        }
    }

    public static boolean isCarrying(PlayerEntity player) {
        return carriedItems.containsKey(player.getUuid());
    }

    public static CarriedData getCarried(PlayerEntity player) {
        return carriedItems.get(player.getUuid());
    }

    public static void setCarrying(PlayerEntity player, CarriedData data) {
        carriedItems.put(player.getUuid(), data);
        applySlowness(player);
    }

    public static void clearCarrying(PlayerEntity player) {
        carriedItems.remove(player.getUuid());
    }

    private static void applySlowness(PlayerEntity player) {
        // Apply slowness effect while carrying
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 1, false, false, true));
    }

    @Override
    public void onInitialize() {
        LOGGER.info("Carry On mod initialized! Sneak + right-click to pick up entities!");

        // Handle right-clicking on blocks (pick up or place block entities)
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient || hand != Hand.MAIN_HAND) {
                return ActionResult.PASS;
            }

            if (!player.isSneaking()) {
                return ActionResult.PASS;
            }

            // If already carrying, try to place
            if (isCarrying(player)) {
                return tryPlace(player, world, hitResult);
            }

            // Try to pick up block entity
            return tryPickUpBlock(player, world, hitResult.getBlockPos());
        });

        // Handle right-clicking on entities (pick up mobs)
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient || hand != Hand.MAIN_HAND) {
                return ActionResult.PASS;
            }

            if (!player.isSneaking()) {
                return ActionResult.PASS;
            }

            // If already carrying, don't allow picking up another
            if (isCarrying(player)) {
                return ActionResult.PASS;
            }

            // Try to pick up mob
            return tryPickUpMob(player, world, entity);
        });

        // Prevent attacks while carrying
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (isCarrying(player)) {
                if (!world.isClient) {
                    player.sendMessage(Text.literal("Cannot attack while carrying!"), true);
                }
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // Prevent block breaking while carrying
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (isCarrying(player)) {
                if (!world.isClient) {
                    player.sendMessage(Text.literal("Cannot break blocks while carrying!"), true);
                }
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // Prevent item use while carrying
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (isCarrying(player)) {
                if (!world.isClient) {
                    player.sendMessage(Text.literal("Cannot use items while carrying!"), true);
                }
                return TypedActionResult.fail(player.getStackInHand(hand));
            }
            return TypedActionResult.pass(player.getStackInHand(hand));
        });

        // Tick event to maintain slowness
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (isCarrying(player)) {
                    applySlowness(player);
                }
            }
        });
    }

    private ActionResult tryPickUpBlock(PlayerEntity player, World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        BlockEntity blockEntity = world.getBlockEntity(pos);

        if (blockEntity == null) {
            return ActionResult.PASS;
        }

        // Check if this is a carryable block entity
        if (!isCarryableBlockEntity(state)) {
            return ActionResult.PASS;
        }

        // Save block entity data
        NbtCompound nbt = blockEntity.createNbt(world.getRegistryManager());

        // Store the carried data
        setCarrying(player, new CarriedData(state, nbt));

        // Remove the block from the world
        world.removeBlockEntity(pos);
        world.setBlockState(pos, Blocks.AIR.getDefaultState());

        player.sendMessage(Text.literal("Picked up " + getBlockName(state) + "! Sneak + right-click to place."), true);

        return ActionResult.SUCCESS;
    }

    private ActionResult tryPickUpMob(PlayerEntity player, World world, Entity entity) {
        if (!(entity instanceof MobEntity mob)) {
            return ActionResult.PASS;
        }

        // Don't allow picking up bosses
        if (entity.getType() == EntityType.ENDER_DRAGON || entity.getType() == EntityType.WITHER) {
            player.sendMessage(Text.literal("Cannot pick up boss mobs!"), true);
            return ActionResult.FAIL;
        }

        // Save mob data
        NbtCompound nbt = new NbtCompound();
        mob.writeNbt(nbt);

        Identifier entityId = Registries.ENTITY_TYPE.getId(mob.getType());

        // Store the carried data
        setCarrying(player, new CarriedData(entityId, nbt));

        // Remove the mob from the world
        mob.discard();

        player.sendMessage(Text.literal("Picked up " + mob.getName().getString() + "! Sneak + right-click to place."), true);

        return ActionResult.SUCCESS;
    }

    private ActionResult tryPlace(PlayerEntity player, World world, BlockHitResult hitResult) {
        CarriedData carried = getCarried(player);
        if (carried == null) {
            return ActionResult.PASS;
        }

        BlockPos placePos = hitResult.getBlockPos().offset(hitResult.getSide());

        if (carried.isBlockEntity) {
            // Place block entity
            if (!world.getBlockState(placePos).isAir()) {
                player.sendMessage(Text.literal("Cannot place here - block in the way!"), true);
                return ActionResult.FAIL;
            }

            // Set the block state
            world.setBlockState(placePos, carried.blockState);

            // Create and configure the block entity
            BlockEntity newBlockEntity = world.getBlockEntity(placePos);
            if (newBlockEntity != null) {
                NbtCompound nbt = carried.nbt.copy();
                // Update position in NBT
                nbt.putInt("x", placePos.getX());
                nbt.putInt("y", placePos.getY());
                nbt.putInt("z", placePos.getZ());
                newBlockEntity.read(nbt, world.getRegistryManager());
            }

            player.sendMessage(Text.literal("Placed " + getBlockName(carried.blockState) + "!"), true);
        } else {
            // Place mob
            EntityType<?> type = Registries.ENTITY_TYPE.get(carried.entityType);
            Entity entity = type.create(world);

            if (entity != null) {
                NbtCompound nbt = carried.nbt.copy();
                entity.readNbt(nbt);
                entity.setPosition(placePos.getX() + 0.5, placePos.getY(), placePos.getZ() + 0.5);
                world.spawnEntity(entity);

                player.sendMessage(Text.literal("Placed " + entity.getName().getString() + "!"), true);
            }
        }

        clearCarrying(player);
        return ActionResult.SUCCESS;
    }

    private boolean isCarryableBlockEntity(BlockState state) {
        // Allow carrying chests, furnaces, spawners, barrels, hoppers, etc.
        return state.isOf(Blocks.CHEST) ||
               state.isOf(Blocks.TRAPPED_CHEST) ||
               state.isOf(Blocks.FURNACE) ||
               state.isOf(Blocks.BLAST_FURNACE) ||
               state.isOf(Blocks.SMOKER) ||
               state.isOf(Blocks.SPAWNER) ||
               state.isOf(Blocks.BARREL) ||
               state.isOf(Blocks.HOPPER) ||
               state.isOf(Blocks.DROPPER) ||
               state.isOf(Blocks.DISPENSER) ||
               state.isOf(Blocks.BREWING_STAND) ||
               state.isOf(Blocks.ENCHANTING_TABLE) ||
               state.isOf(Blocks.BEACON) ||
               state.isOf(Blocks.SHULKER_BOX) ||
               state.isOf(Blocks.WHITE_SHULKER_BOX) ||
               state.isOf(Blocks.ORANGE_SHULKER_BOX) ||
               state.isOf(Blocks.MAGENTA_SHULKER_BOX) ||
               state.isOf(Blocks.LIGHT_BLUE_SHULKER_BOX) ||
               state.isOf(Blocks.YELLOW_SHULKER_BOX) ||
               state.isOf(Blocks.LIME_SHULKER_BOX) ||
               state.isOf(Blocks.PINK_SHULKER_BOX) ||
               state.isOf(Blocks.GRAY_SHULKER_BOX) ||
               state.isOf(Blocks.LIGHT_GRAY_SHULKER_BOX) ||
               state.isOf(Blocks.CYAN_SHULKER_BOX) ||
               state.isOf(Blocks.PURPLE_SHULKER_BOX) ||
               state.isOf(Blocks.BLUE_SHULKER_BOX) ||
               state.isOf(Blocks.BROWN_SHULKER_BOX) ||
               state.isOf(Blocks.GREEN_SHULKER_BOX) ||
               state.isOf(Blocks.RED_SHULKER_BOX) ||
               state.isOf(Blocks.BLACK_SHULKER_BOX) ||
               state.isOf(Blocks.JUKEBOX) ||
               state.isOf(Blocks.LECTERN) ||
               state.isOf(Blocks.BELL) ||
               state.isOf(Blocks.CAMPFIRE) ||
               state.isOf(Blocks.SOUL_CAMPFIRE) ||
               state.isOf(Blocks.BEEHIVE) ||
               state.isOf(Blocks.BEE_NEST);
    }

    private String getBlockName(BlockState state) {
        String name = state.getBlock().getTranslationKey();
        // Simple name extraction from translation key
        name = name.replace("block.minecraft.", "").replace("_", " ");
        return name;
    }
}
