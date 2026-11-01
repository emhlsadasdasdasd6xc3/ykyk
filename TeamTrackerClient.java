package com.example.teamtracker;

import com.example.teamtracker.PingManager.Ping;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.command.CommandSource;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class TeamTrackerClient implements ClientModInitializer {
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    /** Whisper command used to deliver pings. Change to "tell" or "w" if your server renames /msg. */
    private static final String WHISPER_COMMAND = "msg";
    private static final double PING_RANGE = 256.0;
    private static final long PING_EXPIRE_MS = 5 * 60 * 1000L;
    private static final long PING_COOLDOWN_MS = 2000L;
    private static final double PING_REACHED_DIST = 4.0;

    // Marker format: [TT|sender|x|y|z|dimension]
    private static final Pattern MARKER = Pattern.compile(
        "\\[TT\\|([A-Za-z0-9_]{1,16})\\|(-?\\d+)\\|(-?\\d+)\\|(-?\\d+)\\|([a-z0-9_.\\-:/]+)\\]");

    private static KeyBinding pingKey;
    private static long lastPingSent = 0;

    @Override
    public void onInitializeClient() {
        TeamManager.load();
        registerCommands();

        pingKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.teamtracker.ping", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.teamtracker"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (pingKey.wasPressed()) sendPing(client);
        });

        // Intercept (and hide) ping whispers, both from game messages and signed chat.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> handleIncoming(message));
        ClientReceiveMessageEvents.ALLOW_CHAT.register(
            (Text message, SignedMessage signed, GameProfile sender, MessageType.Parameters params, Instant ts) ->
                handleIncoming(message));

        HudRenderCallback.EVENT.register(TeamTrackerClient::renderHud);
    }

    // ---------------------------------------------------------------- commands

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(literal("team")
                .then(literal("add")
                    .then(argument("username", StringArgumentType.word())
                        .suggests((c, b) -> CommandSource.suggestMatching(onlineNames(), b))
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "username");
                            boolean added = TeamManager.add(name);
                            ctx.getSource().sendFeedback(added
                                ? Text.literal("Added " + name + " to your team.").formatted(Formatting.GREEN)
                                : Text.literal(name + " is already on your team.").formatted(Formatting.YELLOW));
                            return 1;
                        })))
                .then(literal("remove")
                    .then(argument("username", StringArgumentType.word())
                        .suggests((c, b) -> CommandSource.suggestMatching(TeamManager.all(), b))
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "username");
                            boolean removed = TeamManager.remove(name);
                            if (removed) PingManager.remove(name);
                            ctx.getSource().sendFeedback(removed
                                ? Text.literal("Removed " + name + ".").formatted(Formatting.RED)
                                : Text.literal(name + " isn't on your team.").formatted(Formatting.YELLOW));
                            return 1;
                        })))
                .then(literal("list").executes(ctx -> {
                    List<String> all = TeamManager.all();
                    ctx.getSource().sendFeedback(Text.literal(all.isEmpty()
                        ? "Your team is empty." : "Team: " + String.join(", ", all)));
                    return 1;
                }))
                .then(literal("clearpings").executes(ctx -> {
                    PingManager.clear();
                    ctx.getSource().sendFeedback(Text.literal("Pings cleared.").formatted(Formatting.RED));
                    return 1;
                }))
                .then(literal("clear").executes(ctx -> {
                    TeamManager.clear();
                    PingManager.clear();
                    ctx.getSource().sendFeedback(Text.literal("Team cleared.").formatted(Formatting.RED));
                    return 1;
                }))));
    }

    private static List<String> onlineNames() {
        var net = MinecraftClient.getInstance().getNetworkHandler();
        if (net == null) return List.of();
        return net.getPlayerList().stream().map(e -> e.getProfile().getName()).toList();
    }

    // ------------------------------------------------------------------- pings

    private static void actionBar(MinecraftClient mc, Text text) {
        if (mc.player != null) mc.player.sendMessage(text, true);
    }

    private static void sendPing(MinecraftClient mc) {
        ClientPlayerEntity me = mc.player;
        if (me == null || mc.world == null || mc.getNetworkHandler() == null) return;

        long now = System.currentTimeMillis();
        if (now - lastPingSent < PING_COOLDOWN_MS) return;

        HitResult hit = me.raycast(PING_RANGE, 1.0f, false);
        if (hit.getType() == HitResult.Type.MISS) {
            actionBar(mc, Text.literal("Nothing in sight to ping.").formatted(Formatting.YELLOW));
            return;
        }
        BlockPos pos = BlockPos.ofFloored(hit.getPos());
        String dim = mc.world.getRegistryKey().getValue().toString();
        String myName = mc.getSession().getUsername();
        String marker = "[TT|" + myName + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ() + "|" + dim + "]";

        int sent = 0;
        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            String n = entry.getProfile().getName();
            if (!n.equalsIgnoreCase(myName) && TeamManager.contains(n)) {
                mc.getNetworkHandler().sendChatCommand(WHISPER_COMMAND + " " + n + " " + marker);
                sent++;
            }
        }
        lastPingSent = now;
        actionBar(mc, sent > 0
            ? Text.literal("Pinged " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                + " to " + sent + " teammate" + (sent == 1 ? "" : "s")).formatted(Formatting.GOLD)
            : Text.literal("No teammates online to ping.").formatted(Formatting.YELLOW));
    }

    /** Returns false to hide the message from chat. */
    private static boolean handleIncoming(Text message) {
        Matcher m = MARKER.matcher(message.getString());
        if (!m.find()) return true;

        MinecraftClient mc = MinecraftClient.getInstance();
        String sender = m.group(1);
        if (mc.player != null && !sender.equalsIgnoreCase(mc.getSession().getUsername())
                && TeamManager.contains(sender)) {
            PingManager.set(new Ping(sender,
                Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                m.group(5), System.currentTimeMillis()));
            actionBar(mc, Text.literal(sender + " pinged a location.").formatted(Formatting.GOLD));
            mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
        }
        return false; // hide marker (incoming whisper or our own echo)
    }

    // --------------------------------------------------------------------- HUD

    private static String arrow(ClientPlayerEntity me, double dx, double dz) {
        // Minecraft yaw: 0 = +Z (south), 90 = -X (west)
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float rel = MathHelper.wrapDegrees(targetYaw - me.getYaw());
        return ARROWS[Math.floorMod(Math.round(rel / 45f), 8)];
    }

    private static String vertical(int dy) {
        return Math.abs(dy) >= 3 ? (dy > 0 ? "  ▲" : "  ▼") + Math.abs(dy) : "";
    }

    private static void renderHud(DrawContext ctx, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity me = mc.player;
        if (me == null || mc.world == null || mc.options.hudHidden) return;

        int x = 6, y = 6;

        // Teammates currently loaded around you
        for (String name : TeamManager.all()) {
            AbstractClientPlayerEntity target = null;
            for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
                if (p.getGameProfile().getName().equalsIgnoreCase(name)) { target = p; break; }
            }

            String line;
            int color;
            if (target == null || target == me) {
                line = name + "  (out of range)";
                color = 0xFF888888;
            } else {
                double dx = target.getX() - me.getX();
                double dz = target.getZ() - me.getZ();
                int dy = (int) Math.round(target.getY() - me.getY());
                line = arrow(me, dx, dz) + " " + name + "  " + Math.round(me.distanceTo(target)) + "m" + vertical(dy);
                color = 0xFF55FF55;
            }
            ctx.drawTextWithShadow(mc.textRenderer, line, x, y, color);
            y += 11;
        }

        // Pings from teammates (work at any distance)
        long now = System.currentTimeMillis();
        String hereDim = mc.world.getRegistryKey().getValue().toString();
        for (Ping p : PingManager.all()) {
            if (now - p.time() > PING_EXPIRE_MS) { PingManager.remove(p.sender()); continue; }

            String line;
            if (!p.dimension().equals(hereDim)) {
                String d = p.dimension().substring(p.dimension().indexOf(':') + 1);
                line = "◆ " + p.sender() + "'s ping (in " + d + ")";
            } else {
                double dx = p.x() + 0.5 - me.getX();
                double dy = p.y() - me.getY();
                double dz = p.z() + 0.5 - me.getZ();
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist < PING_REACHED_DIST) { PingManager.remove(p.sender()); continue; }
                line = arrow(me, dx, dz) + " " + p.sender() + "'s ping  " + Math.round(dist) + "m"
                    + vertical((int) Math.round(dy));
            }
            ctx.drawTextWithShadow(mc.textRenderer, line, x, y, 0xFFFFD700);
            y += 11;
        }
    }
}
