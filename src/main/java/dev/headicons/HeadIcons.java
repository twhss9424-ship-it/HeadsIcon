package dev.headicons;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows player head icons in the tablist and in chat using Minecraft's native
 * player-head text component (no resource pack required). Needs clients on 1.21.9+.
 */
public final class HeadIcons extends JavaPlugin implements Listener {

    /** Built on the main thread, read from the async chat thread. */
    private final Map<UUID, Component> headCache = new ConcurrentHashMap<>();

    private boolean hat;
    private boolean tabEnabled;
    private Component tabSeparator;
    private boolean tabUseDisplayName;
    private boolean chatEnabled;
    private Component chatSeparator;
    private int refreshSeconds;
    private BukkitTask refreshTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        loadSettings();
        refreshAll();
    }

    @Override
    public void onDisable() {
        if (refreshTask != null) refreshTask.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) p.playerListName(null);
        headCache.clear();
    }

    private void loadSettings() {
        reloadConfig();
        var cfg = getConfig();
        hat = cfg.getBoolean("hat-layer", true);
        tabEnabled = cfg.getBoolean("tablist.enabled", true);
        tabSeparator = Component.text(cfg.getString("tablist.separator", " "));
        tabUseDisplayName = cfg.getBoolean("tablist.use-display-name", false);
        chatEnabled = cfg.getBoolean("chat.enabled", true);
        chatSeparator = Component.text(cfg.getString("chat.separator", " "));
        refreshSeconds = Math.max(0, cfg.getInt("refresh-seconds", 60));

        if (refreshTask != null) refreshTask.cancel();
        refreshTask = null;
        if (refreshSeconds > 0) {
            long ticks = refreshSeconds * 20L;
            refreshTask = Bukkit.getScheduler().runTaskTimer(this, this::refreshAll, ticks, ticks);
        }
    }

    /** Builds the head component. Must run on the main thread (reads the player profile). */
    private Component buildHead(Player player) {
        List<PlayerHeadObjectContents.ProfileProperty> props = new ArrayList<>();
        for (com.destroystokyo.paper.profile.ProfileProperty pp : player.getPlayerProfile().getProperties()) {
            if ("textures".equals(pp.getName())) {
                props.add(PlayerHeadObjectContents.property(pp.getName(), pp.getValue(), pp.getSignature()));
            }
        }
        // With textures present the client does not need to resolve the profile itself,
        // so this also works on offline-mode / proxied setups where the skin is known.
        var contents = ObjectContents.playerHead()
                .id(player.getUniqueId())
                .name(player.getName())
                .profileProperties(props)
                .hat(hat)
                .build();
        return Component.object(contents).color(NamedTextColor.WHITE);
    }

    private Component headOf(Player player) {
        return headCache.computeIfAbsent(player.getUniqueId(), id -> {
            if (Bukkit.isPrimaryThread()) return buildHead(player);
            return Component.empty(); // never touch the profile off-thread
        });
    }

    private void updateTab(Player player) {
        if (!tabEnabled || !player.hasPermission("headicons.tablist")) {
            player.playerListName(null);
            return;
        }
        Component name = tabUseDisplayName ? player.displayName() : Component.text(player.getName());
        player.playerListName(Component.empty()
                .append(headOf(player))
                .append(tabSeparator)
                .append(name));
    }

    private void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            headCache.put(p.getUniqueId(), buildHead(p));
            updateTab(p);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        headCache.put(p.getUniqueId(), buildHead(p));
        updateTab(p);
        // Skin properties are sometimes finalised a moment after join; re-apply once.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            headCache.put(p.getUniqueId(), buildHead(p));
            updateTab(p);
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        headCache.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!chatEnabled || !e.getPlayer().hasPermission("headicons.chat")) return;

        // Wrap whatever renderer is already set so other chat-format plugins keep working.
        ChatRenderer previous = e.renderer();
        UUID id = e.getPlayer().getUniqueId();
        e.renderer((source, sourceDisplayName, message, viewer) -> {
            Component rendered = previous.render(source, sourceDisplayName, message, viewer);
            Component head = headCache.get(id);
            if (head == null || head == Component.empty()) return rendered;
            return Component.empty().append(head).append(chatSeparator).append(rendered);
        });
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            refreshAll();
            sender.sendMessage(Component.text("HeadIcons reloaded.", NamedTextColor.GREEN));
            return true;
        }
        sender.sendMessage(Component.text("Usage: /" + label + " reload", NamedTextColor.GRAY));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String alias, @NotNull String[] args) {
        return args.length == 1 ? List.of("reload") : List.of();
    }
}
