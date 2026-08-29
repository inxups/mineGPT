package com.inxups.minegpt.fabric;

import com.inxups.minegpt.shared.ClientPlatform;
import com.inxups.minegpt.shared.MineGptClient;
import com.inxups.minegpt.shared.PlayerContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client entry point for the Fabric build. */
public final class MineGptFabricClient implements ClientModInitializer {
    public static final String MOD_ID = "minegpt";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private MineGptClient mineGpt;

    @Override
    public void onInitializeClient() {
        mineGpt = new MineGptClient(
                FabricLoader.getInstance().getConfigDir().resolve("minegpt.json"),
                new FabricPlatform());
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> !mineGpt.handleChat(message));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("minegpt")
                        .then(ClientCommandManager.literal("pair")
                                .then(ClientCommandManager.argument("token", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            mineGpt.pair(StringArgumentType.getString(context, "token"));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("status")
                                .executes(context -> {
                                    mineGpt.showStatus();
                                    return 1;
                                }))));
        mineGpt.start();
        LOGGER.info("MineGPT Fabric client initialized");
    }

    private static final class FabricPlatform implements ClientPlatform {
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
