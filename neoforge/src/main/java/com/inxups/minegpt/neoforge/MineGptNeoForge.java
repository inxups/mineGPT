package com.inxups.minegpt.neoforge;

import com.inxups.minegpt.shared.ClientPlatform;
import com.inxups.minegpt.shared.ChunkInfo;
import com.inxups.minegpt.shared.ChunkQuery;
import com.inxups.minegpt.shared.GameQuery;
import com.inxups.minegpt.shared.GameQueryResult;
import com.inxups.minegpt.shared.MineGptClient;
import com.inxups.minegpt.shared.PlayerContext;
import com.mojang.logging.LogUtils;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Client entry point for the NeoForge build. */
@Mod(value = MineGptNeoForge.MOD_ID, dist = Dist.CLIENT)
public final class MineGptNeoForge {
    public static final String MOD_ID = "minegpt";
    private static final Logger LOGGER = LogUtils.getLogger();
    private final MineGptClient mineGpt;

    public MineGptNeoForge() {
        mineGpt = new MineGptClient(FMLPaths.CONFIGDIR.get().resolve("minegpt.json"), new NeoForgePlatform());
        NeoForge.EVENT_BUS.addListener(this::onClientChat);
        NeoForge.EVENT_BUS.addListener(this::registerClientCommands);
        mineGpt.start();
        LOGGER.info("MineGPT NeoForge client initialized");
    }

    private void onClientChat(ClientChatEvent event) {
        if (mineGpt.handleChat(event.getMessage())) {
            event.setCanceled(true);
        }
    }

    private void registerClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("minegpt")
                .then(Commands.literal("pair")
                        .then(Commands.argument("token", StringArgumentType.greedyString())
                                .executes(context -> {
                                    mineGpt.pair(StringArgumentType.getString(context, "token"));
                                    return 1;
                                })))
                .then(Commands.literal("status")
                        .executes(context -> {
                            mineGpt.showStatus();
                            return 1;
                        })));
    }

    private static final class NeoForgePlatform implements ClientPlatform {
        @Override
        public Path gameDirectory() {
            return FMLPaths.GAMEDIR.get();
        }

        @Override
        public PlayerContext captureContext() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) {
                return new PlayerContext("unknown", "unknown", "unknown", 0, 0, 0, 0, 0, "unknown");
            }
            String serverAddress = minecraft.getCurrentServer() != null
                    ? minecraft.getCurrentServer().ip
                    : minecraft.hasSingleplayerServer() ? "singleplayer" : "unknown";
            String gameMode = minecraft.gameMode == null ? "unknown" : minecraft.gameMode.getPlayerMode().getName();
            return new PlayerContext(
                    minecraft.player.getGameProfile().getName(),
                    serverAddress,
                    minecraft.player.level().dimension().location().toString(),
                    minecraft.player.getX(),
                    minecraft.player.getY(),
                    minecraft.player.getZ(),
                    minecraft.player.getHealth(),
                    minecraft.player.getFoodData().getFoodLevel(),
                    gameMode);
        }

        @Override
        public ChunkInfo readChunkInfo(ChunkQuery query) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.level == null) {
                return ChunkInfo.unavailable("No Minecraft world is currently open.");
            }
            if (query == null || !query.isValid()) {
                return ChunkInfo.unavailable("Provide both chunk coordinates or neither.");
            }
            int chunkX = query.usesPlayerChunk() ? minecraft.player.chunkPosition().x : query.chunkX();
            int chunkZ = query.usesPlayerChunk() ? minecraft.player.chunkPosition().z : query.chunkZ();
            LevelChunk chunk = minecraft.level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (chunk == null) {
                return ChunkInfo.unavailable("Chunk " + chunkX + ", " + chunkZ + " is not loaded by this client.");
            }

            int minY = minecraft.level.getMinBuildHeight();
            int maxY = minecraft.level.getMaxBuildHeight();
            int[] surfaceHeights = new int[256];
            String[] surfaceBlocks = new String[256];
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    int index = localZ * 16 + localX;
                    int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ) - 1;
                    surfaceY = Math.max(minY, Math.min(surfaceY, maxY - 1));
                    surfaceHeights[index] = surfaceY;
                    surfaceBlocks[index] = blockId(chunk.getBlockState(new BlockPos(
                            (chunkX << 4) + localX, surfaceY, (chunkZ << 4) + localZ)));
                }
            }
            return new ChunkInfo(true, null, minecraft.level.dimension().location().toString(),
                    chunkX, chunkZ, minecraft.level.getGameTime(), minY, maxY,
                    surfaceHeights, surfaceBlocks);
        }

        private static String blockId(net.minecraft.world.level.block.state.BlockState state) {
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            return id.length() <= 64 ? id : id.substring(0, 64) + "...";
        }

        @Override
        public GameQueryResult readGameQuery(GameQuery query) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.level == null || query == null || query.kind() == null) {
                return GameQueryResult.unavailable("No Minecraft world is currently open.");
            }
            return switch (query.kind()) {
                case "player_state" -> GameQueryResult.available(playerState(minecraft));
                case "target" -> GameQueryResult.available(target(minecraft));
                case "inventory" -> GameQueryResult.available(inventory(minecraft));
                case "nearby_entities" -> nearbyEntities(minecraft, query.radius());
                case "block" -> block(minecraft, query.x(), query.y(), query.z());
                case "chunk_section" -> chunkSection(minecraft, query.chunkX(), query.chunkZ(), query.sectionY());
                case "environment" -> GameQueryResult.available(environment(minecraft));
                default -> GameQueryResult.unavailable("Unknown game-data query: " + query.kind());
            };
        }

        private static Map<String, Object> playerState(Minecraft minecraft) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("player_name", minecraft.player.getGameProfile().getName());
            result.put("dimension", minecraft.level.dimension().location().toString());
            result.put("x", minecraft.player.getX());
            result.put("y", minecraft.player.getY());
            result.put("z", minecraft.player.getZ());
            result.put("health", minecraft.player.getHealth());
            result.put("max_health", minecraft.player.getMaxHealth());
            result.put("hunger", minecraft.player.getFoodData().getFoodLevel());
            result.put("saturation", minecraft.player.getFoodData().getSaturationLevel());
            result.put("experience_level", minecraft.player.experienceLevel);
            result.put("experience_progress", minecraft.player.experienceProgress);
            result.put("game_mode", minecraft.gameMode == null ? "unknown" : minecraft.gameMode.getPlayerMode().getName());
            return result;
        }

        private static Map<String, Object> target(Minecraft minecraft) {
            HitResult hit = minecraft.hitResult;
            if (hit == null || hit.getType() == HitResult.Type.MISS) {
                return Map.of("target_type", "miss");
            }
            if (hit instanceof BlockHitResult blockHit) {
                BlockPos position = blockHit.getBlockPos();
                Map<String, Object> result = new LinkedHashMap<>(blockSummary(minecraft, position));
                result.put("target_type", "block");
                result.put("face", blockHit.getDirection().getName());
                return result;
            }
            if (hit instanceof EntityHitResult entityHit) {
                Map<String, Object> result = new LinkedHashMap<>(entitySummary(entityHit.getEntity(), minecraft.player));
                result.put("target_type", "entity");
                return result;
            }
            return Map.of("target_type", "miss");
        }

        private static Map<String, Object> inventory(Minecraft minecraft) {
            List<Map<String, Object>> occupied = new ArrayList<>();
            for (int slot = 0; slot < minecraft.player.getInventory().getContainerSize(); slot++) {
                ItemStack stack = minecraft.player.getInventory().getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("slot", slot);
                item.put("slot_kind", inventorySlotKind(slot));
                item.put("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                item.put("count", stack.getCount());
                if (stack.isDamageableItem()) {
                    item.put("durability_remaining", stack.getMaxDamage() - stack.getDamageValue());
                    item.put("max_durability", stack.getMaxDamage());
                }
                occupied.add(item);
            }
            return Map.of("container_slots", minecraft.player.getInventory().getContainerSize(), "occupied_slots", occupied);
        }

        private static String inventorySlotKind(int slot) {
            if (slot < 9) {
                return "hotbar";
            }
            if (slot < 36) {
                return "main_inventory";
            }
            return switch (slot) {
                case 36 -> "armor_feet";
                case 37 -> "armor_legs";
                case 38 -> "armor_chest";
                case 39 -> "armor_head";
                case 40 -> "offhand";
                default -> "other";
            };
        }

        private static GameQueryResult nearbyEntities(Minecraft minecraft, Integer requestedRadius) {
            if (requestedRadius == null || requestedRadius < 1 || requestedRadius > 64) {
                return GameQueryResult.unavailable("radius must be between 1 and 64.");
            }
            List<Entity> entities = new ArrayList<>(minecraft.level.getEntities(minecraft.player,
                    minecraft.player.getBoundingBox().inflate(requestedRadius), entity -> entity != minecraft.player));
            entities.sort(Comparator.comparingDouble(minecraft.player::distanceToSqr));
            int total = entities.size();
            List<Map<String, Object>> visible = entities.stream().limit(64)
                    .map(entity -> entitySummary(entity, minecraft.player)).toList();
            return GameQueryResult.available(Map.of(
                    "radius", requestedRadius,
                    "entity_count", total,
                    "truncated", total > visible.size(),
                    "entities", visible));
        }

        private static GameQueryResult block(Minecraft minecraft, Integer x, Integer y, Integer z) {
            if (x == null || y == null || z == null) {
                return GameQueryResult.unavailable("x, y, and z are required.");
            }
            if (y < minecraft.level.getMinBuildHeight() || y >= minecraft.level.getMaxBuildHeight()) {
                return GameQueryResult.unavailable("y is outside this dimension's build height.");
            }
            if (minecraft.level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
                return GameQueryResult.unavailable("The requested block's chunk is not loaded by this client.");
            }
            return GameQueryResult.available(blockSummary(minecraft, new BlockPos(x, y, z)));
        }

        private static GameQueryResult chunkSection(Minecraft minecraft, Integer chunkX, Integer chunkZ, Integer sectionY) {
            if (chunkX == null || chunkZ == null || sectionY == null) {
                return GameQueryResult.unavailable("chunk_x, chunk_z, and section_y are required.");
            }
            LevelChunk chunk = minecraft.level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (chunk == null) {
                return GameQueryResult.unavailable("Chunk " + chunkX + ", " + chunkZ + " is not loaded by this client.");
            }
            int minSection = Math.floorDiv(minecraft.level.getMinBuildHeight(), 16);
            int maxSection = Math.floorDiv(minecraft.level.getMaxBuildHeight() - 1, 16);
            if (sectionY < minSection || sectionY > maxSection) {
                return GameQueryResult.unavailable("section_y is outside this dimension's build height.");
            }
            Map<String, Integer> counts = new HashMap<>();
            int baseY = sectionY * 16;
            for (int localY = 0; localY < 16; localY++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        String id = blockId(chunk.getBlockState(new BlockPos(
                                (chunkX << 4) + localX, baseY + localY, (chunkZ << 4) + localZ)));
                        counts.merge(id, 1, Integer::sum);
                    }
                }
            }
            List<Map.Entry<String, Integer>> sorted = counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(64)
                    .toList();
            Map<String, Integer> blockCounts = new LinkedHashMap<>();
            sorted.forEach(entry -> blockCounts.put(entry.getKey(), entry.getValue()));
            return GameQueryResult.available(Map.of(
                    "chunk_x", chunkX,
                    "chunk_z", chunkZ,
                    "section_y", sectionY,
                    "min_y", baseY,
                    "max_y", baseY + 15,
                    "unique_block_count", counts.size(),
                    "truncated", counts.size() > blockCounts.size(),
                    "block_counts", blockCounts));
        }

        private static Map<String, Object> environment(Minecraft minecraft) {
            BlockPos position = minecraft.player.blockPosition();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dimension", minecraft.level.dimension().location().toString());
            result.put("biome", minecraft.level.getBiome(position).unwrapKey()
                    .map(key -> key.location().toString()).orElse("unknown"));
            result.put("game_time", minecraft.level.getGameTime());
            result.put("day_time", minecraft.level.getDayTime());
            result.put("moon_phase", minecraft.level.getMoonPhase());
            result.put("raining", minecraft.level.isRaining());
            result.put("thundering", minecraft.level.isThundering());
            result.put("difficulty", minecraft.level.getDifficulty().getKey());
            result.put("block_light", minecraft.level.getBrightness(LightLayer.BLOCK, position));
            result.put("sky_light", minecraft.level.getBrightness(LightLayer.SKY, position));
            return result;
        }

        private static Map<String, Object> blockSummary(Minecraft minecraft, BlockPos position) {
            BlockState state = minecraft.level.getBlockState(position);
            BlockEntity blockEntity = minecraft.level.getBlockEntity(position);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("x", position.getX());
            result.put("y", position.getY());
            result.put("z", position.getZ());
            result.put("block", blockId(state));
            result.put("state", state.toString());
            result.put("block_light", minecraft.level.getBrightness(LightLayer.BLOCK, position));
            result.put("sky_light", minecraft.level.getBrightness(LightLayer.SKY, position));
            if (blockEntity != null) {
                result.put("block_entity_type", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString());
            }
            return result;
        }

        private static Map<String, Object> entitySummary(Entity entity, net.minecraft.world.entity.player.Player player) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            result.put("name", entity.getName().getString());
            result.put("x", entity.getX());
            result.put("y", entity.getY());
            result.put("z", entity.getZ());
            result.put("distance", Math.sqrt(player.distanceToSqr(entity)));
            if (entity instanceof LivingEntity living) {
                result.put("health", living.getHealth());
                result.put("max_health", living.getMaxHealth());
            }
            return result;
        }

        @Override
        public void executeOnClientThread(Runnable action) {
            Minecraft.getInstance().execute(action);
        }

        @Override
        public void showLocalMessage(String message) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal(message), false);
            }
        }
    }
}
