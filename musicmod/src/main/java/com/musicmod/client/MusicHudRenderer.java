package com.musicmod.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/**
 * Draws a subtle "Now Playing" overlay in the bottom-left corner.
 * Fades out after DISPLAY_DURATION_TICKS ticks.
 *
 * 1.21.4: No API changes needed here — DrawContext + RenderTickCounter signatures
 *         are the same. renderBackground removal doesn't affect HUD rendering.
 */
@Environment(EnvType.CLIENT)
public class MusicHudRenderer {

    private static final int DISPLAY_DURATION_TICKS = 200; // ~10 seconds
    private static final int FADE_TICKS = 40;

    private String songName     = "";
    private String playlistName = "";
    private int ticksRemaining  = 0;

    private static final MusicHudRenderer INSTANCE = new MusicHudRenderer();
    public static MusicHudRenderer get() { return INSTANCE; }

    private MusicHudRenderer() {}

    public void showNowPlaying(String song, String playlist) {
        this.songName      = song;
        this.playlistName  = playlist;
        this.ticksRemaining = DISPLAY_DURATION_TICKS;
    }

    public void clear() {
        ticksRemaining = 0;
        songName       = "";
        playlistName   = "";
    }

    public void tick() {
        if (ticksRemaining > 0) ticksRemaining--;
    }

    public void render(DrawContext ctx, RenderTickCounter counter) {
        if (ticksRemaining <= 0 || songName.isEmpty()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden) return;

        float alpha = ticksRemaining < FADE_TICKS
                ? (float) ticksRemaining / FADE_TICKS
                : 1.0f;
        int a = (int)(alpha * 200);
        if (a <= 0) return;

        int x = 8;
        int y = ctx.getScaledWindowHeight() - 36;

        String line1 = "\u266a " + songName;
        String line2 = playlistName.isEmpty() ? "" : "Playlist: " + playlistName;

        int w = Math.max(
                mc.textRenderer.getWidth(line1),
                line2.isEmpty() ? 0 : mc.textRenderer.getWidth(line2)
        ) + 8;
        int h = line2.isEmpty() ? 14 : 22;

        // Background fill
        ctx.fill(x - 4, y - 2, x + w, y + h, (a << 24));

        // Text
        ctx.drawTextWithShadow(mc.textRenderer, line1, x, y, (a << 24) | 0xFFFFFF);
        if (!line2.isEmpty()) {
            ctx.drawTextWithShadow(mc.textRenderer, line2, x, y + 10, (a << 24) | 0xAAAAAA);
        }
    }
}
