package com.inxups.minegpt.neoforge;

import com.inxups.minegpt.shared.ClientPlatform;
import com.inxups.minegpt.shared.MineGptClient;
import com.inxups.minegpt.shared.PlayerContext;
import com.mojang.logging.LogUtils;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

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
