package com.musicmod.client;

import com.google.gson.*;
import com.musicmod.network.MusicPackets;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.*;

@Environment(EnvType.CLIENT)
public class MusicScreen extends Screen {

    private static final int PANEL_W  = 160;
    private static final int HEADER_H = 24;
    private static final int FOOTER_H = 72;
    private static final int ROW_H    = 14;

    private final List<PlaylistEntry> playlists = new ArrayList<>();
    private final List<SongEntry>     library   = new ArrayList<>();
    private String nowPlayingSong     = "";
    private String nowPlayingPlaylist = "";

    private int selectedPlaylist = -1;
    private int selectedSong     = -1;
    private int playlistScroll   = 0;
    private int songScroll       = 0;

    private TextFieldWidget urlField;
    private TextFieldWidget playlistNameField;
    private String feedback       = "";
    private long   feedbackExpiry = 0;

    public MusicScreen() { super(Text.literal("Music Manager")); }

    record PlaylistEntry(String name, int songCount, boolean shuffle) {}
    record SongEntry(String name, String url, boolean resolved) {}

    public void applySync(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            library.clear();
            if (root.has("library")) {
                for (JsonElement el : root.getAsJsonArray("library")) {
                    JsonObject o = el.getAsJsonObject();
                    library.add(new SongEntry(o.get("name").getAsString(), o.get("url").getAsString(),
                            o.has("resolved") && o.get("resolved").getAsBoolean()));
                }
            }
            playlists.clear();
            if (root.has("playlists")) {
                for (JsonElement el : root.getAsJsonArray("playlists")) {
                    JsonObject o = el.getAsJsonObject();
                    playlists.add(new PlaylistEntry(o.get("name").getAsString(),
                            o.has("songs") ? o.getAsJsonArray("songs").size() : 0,
                            o.has("shuffle") && o.get("shuffle").getAsBoolean()));
                }
            }
            if (root.has("session")) {
                JsonObject s = root.getAsJsonObject("session");
                nowPlayingSong     = s.has("song")     ? s.get("song").getAsString()     : "";
                nowPlayingPlaylist = s.has("playlist") ? s.get("playlist").getAsString() : "";
            }
            if (selectedPlaylist >= playlists.size()) selectedPlaylist = -1;
        } catch (Exception e) { setFeedback("\u26a0 Sync error: " + e.getMessage()); }
    }

    public void setFeedback(String msg)                     { feedback = msg; feedbackExpiry = System.currentTimeMillis() + 6000; }
    public void setNowPlaying(String song, String playlist) { nowPlayingSong = song; nowPlayingPlaylist = playlist; }

    @Override
    protected void init() {
        super.init();
        int cx = width / 2, footerY = height - FOOTER_H, panelBot = footerY - 4;

        urlField = new TextFieldWidget(textRenderer, cx - 180, footerY + 8, 360, 14, Text.literal("url"));
        urlField.setMaxLength(1024);
        urlField.setPlaceholder(Text.literal("Paste YouTube, Spotify, or direct audio URL\u2026"));
        addSelectableChild(urlField);
        setInitialFocus(urlField);

        playlistNameField = new TextFieldWidget(textRenderer, cx - 180, footerY + 28, 200, 14, Text.literal("playlist"));
        playlistNameField.setMaxLength(64);
        playlistNameField.setPlaceholder(Text.literal("Playlist name (optional)"));
        addSelectableChild(playlistNameField);
        if (selectedPlaylist >= 0 && selectedPlaylist < playlists.size())
            playlistNameField.setText(playlists.get(selectedPlaylist).name());

        addDrawableChild(ButtonWidget.builder(Text.literal("Add to Playlist"), btn -> onAddToPlaylist())
                .dimensions(cx + 26, footerY + 26, 110, 16).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Library Only"), btn -> onAddToLibrary())
                .dimensions(cx + 140, footerY + 26, 90, 16).build());

        int ctrlY = footerY + 50;
        addDrawableChild(ButtonWidget.builder(Text.literal("\u25b6 Play"),  btn -> sendAction("play",  "")).dimensions(cx - 180, ctrlY, 56, 16).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("\u23ed Skip"),  btn -> sendAction("skip",  "")).dimensions(cx - 120, ctrlY, 56, 16).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("\u23f9 Stop"),  btn -> sendAction("stop",  "")).dimensions(cx -  60, ctrlY, 56, 16).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("\u25b6 Play"), btn -> onPlayPlaylist()).dimensions(8,   panelBot - 18, 54, 16).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("+ New"),       btn -> onCreatePlaylist()).dimensions(66,  panelBot - 18, 50, 16).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("\u2715"),       btn -> onDeletePlaylist()).dimensions(120, panelBot - 18, 18, 16).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Remove"),      btn -> onRemoveSong()).dimensions(PANEL_W + 16, panelBot - 18, 60, 16).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);

        int panelTop = HEADER_H + 4, panelBot = height - FOOTER_H - 24;
        int rightX = PANEL_W + 8, rightW = width - rightX - 8;
        int rowTop = panelTop + 14, maxVis = (panelBot - panelTop - 14) / ROW_H;

        // Header
        ctx.fill(0, 0, width, HEADER_H, 0xDD000000);
        ctx.drawCenteredTextWithShadow(textRenderer, "\ud83c\udfb5 Music Manager", width / 2, 7, 0xFFFFFF);
        if (!nowPlayingSong.isEmpty()) {
            String np = "\u266a " + nowPlayingSong + (nowPlayingPlaylist.isEmpty() ? "" : "  [" + nowPlayingPlaylist + "]");
            ctx.drawTextWithShadow(textRenderer, np, width - textRenderer.getWidth(np) - 8, 7, 0x55FF55);
        }

        // Playlist panel
        ctx.fill(4, panelTop, PANEL_W + 4, panelBot, 0xAA111111);
        drawBorder(ctx, 4, panelTop, PANEL_W, panelBot - panelTop);
        ctx.drawTextWithShadow(textRenderer, "Playlists", 10, panelTop + 3, 0xAAAAAA);
        for (int i = playlistScroll; i < playlists.size() && i < playlistScroll + maxVis; i++) {
            PlaylistEntry pl = playlists.get(i);
            int ry = rowTop + (i - playlistScroll) * ROW_H;
            boolean sel = i == selectedPlaylist, hov = mouseX >= 6 && mouseX <= PANEL_W + 2 && mouseY >= ry && mouseY < ry + ROW_H;
            ctx.fill(6, ry, PANEL_W + 2, ry + ROW_H - 1, sel ? 0xBB2255AA : hov ? 0x44FFFFFF : 0);
            ctx.drawTextWithShadow(textRenderer, pl.name() + " (" + pl.songCount() + ")" + (pl.shuffle() ? " \ud83d\udd00" : ""),
                    10, ry + 2, sel ? 0xFFFFFF : 0xCCCCCC);
        }

        // Song panel
        ctx.fill(rightX, panelTop, rightX + rightW, panelBot, 0xAA111111);
        drawBorder(ctx, rightX, panelTop, rightW, panelBot - panelTop);
        String hdr = selectedPlaylist >= 0 && selectedPlaylist < playlists.size()
                ? "Songs \u2014 " + playlists.get(selectedPlaylist).name() : "Song Library";
        ctx.drawTextWithShadow(textRenderer, hdr, rightX + 6, panelTop + 3, 0xAAAAAA);
        List<SongEntry> songs = library;
        for (int i = songScroll; i < songs.size() && i < songScroll + maxVis; i++) {
            SongEntry s = songs.get(i);
            int ry = rowTop + (i - songScroll) * ROW_H;
            boolean sel = i == selectedSong, hov = mouseX >= rightX + 2 && mouseX <= rightX + rightW - 2 && mouseY >= ry && mouseY < ry + ROW_H;
            ctx.fill(rightX + 2, ry, rightX + rightW - 2, ry + ROW_H - 1, sel ? 0xBB555522 : hov ? 0x44FFFFFF : 0);
            ctx.drawTextWithShadow(textRenderer, (s.resolved() ? "\u266a " : "\u23f3 ") + s.name(),
                    rightX + 6, ry + 2, s.resolved() ? 0xFFFFFF : 0x888888);
        }

        // Footer
        int footerY = height - FOOTER_H;
        ctx.fill(0, footerY, width, height, 0xCC000000);
        ctx.drawTextWithShadow(textRenderer, "URL:",      8, footerY + 10, 0xAAAAAA);
        ctx.drawTextWithShadow(textRenderer, "Playlist:", 8, footerY + 30, 0xAAAAAA);
        if (!feedback.isEmpty() && System.currentTimeMillis() < feedbackExpiry)
            ctx.drawTextWithShadow(textRenderer, feedback, width / 2 + 20, footerY + 54, 0xFFDD44);

        urlField.render(ctx, mouseX, mouseY, delta);
        playlistNameField.render(ctx, mouseX, mouseY, delta);
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void drawBorder(DrawContext ctx, int x, int y, int w, int h) {
        int c = 0xFF444444;
        ctx.fill(x, y, x + w, y + 1, c);
        ctx.fill(x, y + h - 1, x + w, y + h, c);
        ctx.fill(x, y, x + 1, y + h, c);
        ctx.fill(x + w - 1, y, x + w, y + h, c);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int panelTop = HEADER_H + 4, panelBot = height - FOOTER_H - 24, rowTop = panelTop + 14;
        int rightX = PANEL_W + 8, rightW = width - rightX - 8;

        if (mx >= 6 && mx <= PANEL_W + 2 && my >= rowTop && my < panelBot) {
            int idx = (int)(my - rowTop) / ROW_H + playlistScroll;
            if (idx >= 0 && idx < playlists.size()) {
                selectedPlaylist = idx; selectedSong = -1; songScroll = 0;
                playlistNameField.setText(playlists.get(idx).name()); return true;
            }
        }
        if (mx >= rightX + 2 && mx <= rightX + rightW - 2 && my >= rowTop && my < panelBot) {
            int idx = (int)(my - rowTop) / ROW_H + songScroll;
            if (idx >= 0 && idx < library.size()) { selectedSong = idx; return true; }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        if (mx < PANEL_W + 8) playlistScroll = Math.max(0, playlistScroll - (int) vAmount);
        else songScroll = Math.max(0, songScroll - (int) vAmount);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { close(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void onAddToPlaylist() {
        String url = urlField.getText().trim(), pl = playlistNameField.getText().trim();
        if (url.isEmpty()) { setFeedback("\u26a0 Paste a URL first."); return; }
        if (pl.isEmpty())  { setFeedback("\u26a0 Enter a playlist name."); return; }
        ClientPlayNetworking.send(new MusicPackets.AddSongPayload(url, pl));
        urlField.setText(""); setFeedback("\u23f3 Adding to " + pl + "\u2026");
    }

    private void onAddToLibrary() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) { setFeedback("\u26a0 Paste a URL first."); return; }
        ClientPlayNetworking.send(new MusicPackets.AddSongPayload(url, ""));
        urlField.setText(""); setFeedback("\u23f3 Resolving\u2026");
    }

    private void onPlayPlaylist() {
        if (selectedPlaylist < 0 || selectedPlaylist >= playlists.size()) { setFeedback("\u26a0 Select a playlist first."); return; }
        sendAction("playlist_play", playlists.get(selectedPlaylist).name());
    }

    private void onCreatePlaylist() {
        String name = playlistNameField.getText().trim();
        if (name.isEmpty()) { setFeedback("\u26a0 Enter a playlist name."); return; }
        sendAction("create", name); setFeedback("\u23f3 Creating: " + name);
    }

    private void onDeletePlaylist() {
        if (selectedPlaylist < 0 || selectedPlaylist >= playlists.size()) { setFeedback("\u26a0 Select a playlist first."); return; }
        sendAction("delete", playlists.get(selectedPlaylist).name()); selectedPlaylist = -1;
    }

    private void onRemoveSong() {
        if (selectedSong < 0 || selectedSong >= library.size()) { setFeedback("\u26a0 Select a song first."); return; }
        String song = library.get(selectedSong).name();
        if (selectedPlaylist >= 0 && selectedPlaylist < playlists.size())
            sendAction("remove_from_playlist", playlists.get(selectedPlaylist).name() + ":" + song);
        else sendAction("remove_from_library", song);
        selectedSong = -1;
    }

    private void sendAction(String action, String arg) {
        ClientPlayNetworking.send(new MusicPackets.PlaylistActionPayload(action, arg));
    }

    @Override public boolean shouldPause()      { return false; }
    @Override public boolean shouldCloseOnEsc() { return true; }

    public boolean isBlurEnabled() {
        return false;
    }
}
