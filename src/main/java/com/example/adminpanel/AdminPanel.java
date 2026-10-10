package com.example.adminpanel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

/**
 * Admin panel with a simple built-in anti-cheat and a /report system.
 *
 * <p>Only Spigot API that exists since 1.16 is used, so the same jar runs on
 * 1.16.5 - 1.21.x and newer, as long as the API is not removed.
 */
public class AdminPanel extends JavaPlugin implements Listener {

    /** Checks that can be toggled from the GUI. */
    static final String CHECK_FLY = "fly";
    static final String CHECK_SPEED = "speed";
    static final String CHECK_KILLAURA = "killaura";

    /** Max violation level before a kick. */
    static final int KICK_VL = 20;
    /** Violation level from which a warning is shown to the player. */
    static final int WARN_VL = 10;
    /** Attacks per second that are still considered legit. */
    static final int MAX_ATTACKS_PER_SECOND = 8;
    /** How long a player may be airborne before it counts as flying (ms). */
    static final long MAX_AIR_TIME_MS = 3000L;

    private final Map<UUID, Integer> violationLevels = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAttackTime = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> attackCount = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastGroundTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSpeedFlag = new ConcurrentHashMap<>();
    private final Set<String> enabledChecks = ConcurrentHashMap.newKeySet();
    private boolean alertsEnabled = true;

    /** Reports by reported player UUID, kept even while that player is offline. */
    private final Map<UUID, List<Report>> reports = new ConcurrentHashMap<>();
    /** Names of reported players, so the menu still works offline. */
    private final Map<UUID, String> reportedNames = new ConcurrentHashMap<>();
    /** State saved while an admin spectates a reported player. */
    private final Map<UUID, SpectateState> spectating = new ConcurrentHashMap<>();

    /** "JUMP_BOOST" on 1.20.5+, "JUMP" on older versions. */
    private PotionEffectType jumpBoost;

    @Override
    public void onEnable() {
        jumpBoost = findPotionEffect("JUMP_BOOST", "JUMP");

        getLogger().info("AdminPanel (anti-cheat & reports) enabled. Server: " + Bukkit.getBukkitVersion());
        Bukkit.getPluginManager().registerEvents(this, this);

        enabledChecks.clear();
        enabledChecks.addAll(Arrays.asList(CHECK_FLY, CHECK_SPEED, CHECK_KILLAURA));
        violationLevels.clear();
        reports.clear();
        reportedNames.clear();
        spectating.clear();

        if (getCommand("admin") != null) {
            getCommand("admin").setExecutor(this);
        }
        if (getCommand("report") != null) {
            getCommand("report").setExecutor(this);
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("AdminPanel disabled!");
        violationLevels.clear();
        reports.clear();
        reportedNames.clear();
        spectating.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);

        if (name.equals("admin")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "This command can only be used by players!");
                return true;
            }
            if (!player.hasPermission("adminpanel.use")) {
                player.sendMessage(ChatColor.RED + "You don't have permission to use this command!");
                return true;
            }
            if (leaveSpectate(player)) {
                return true;
            }
            openCustomAdminMenu(player);
            return true;
        }

        if (name.equals("report")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "This command can only be used by players!");
                return true;
            }
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Usage: /report <player> <reason>");
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                target = matchPlayer(args[0]);
            }
            if (target == null) {
                player.sendMessage(ChatColor.RED + "Player '" + args[0] + "' is not online!");
                return true;
            }
            if (target.equals(player)) {
                player.sendMessage(ChatColor.RED + "You can't report yourself!");
                return true;
            }

            String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            UUID targetId = target.getUniqueId();
            reportedNames.put(targetId, target.getName());
            reports.computeIfAbsent(targetId, key -> new ArrayList<>())
                    .add(new Report(player.getUniqueId(), player.getName(), target.getName(), reason));

            player.sendMessage(ChatColor.GREEN + "Report sent: " + reason);
            getLogger().info(player.getName() + " reported " + target.getName() + ": " + reason);

            for (Player admin : Bukkit.getOnlinePlayers()) {
                if (!admin.equals(player) && admin.hasPermission("adminpanel.alert")) {
                    admin.sendMessage(ChatColor.GOLD + "[Report] " + player.getName() + " -> "
                            + target.getName() + ": " + reason);
                }
            }
            return true;
        }

        return false;
    }

    /** Case-insensitive online player lookup - /report used to require an exact name. */
    private Player matchPlayer(String name) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) {
                return online;
            }
        }
        return null;
    }

    private static PotionEffectType findPotionEffect(String... names) {
        for (String candidate : names) {
            PotionEffectType type = PotionEffectType.getByName(candidate);
            if (type != null) {
                return type;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Menus
    // ------------------------------------------------------------------

    private Inventory createMenu(MenuHolder holder, int size, String title) {
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inventory);
        return inventory;
    }

    private void openCustomAdminMenu(Player player) {
        MenuHolder holder = new MenuHolder(MenuHolder.Type.MAIN);
        Inventory menu = createMenu(holder, 36,
                ChatColor.DARK_GREEN + "Custom Admin Panel v" + getDescription().getVersion());

        menu.setItem(10, createItem(Material.BARRIER, ChatColor.RED + "Ban Player",
                Collections.singletonList(ChatColor.GRAY + "Select player to ban")));
        menu.setItem(12, createItem(Material.SHEARS, ChatColor.YELLOW + "Kick Player",
                Collections.singletonList(ChatColor.GRAY + "Select player to kick")));
        menu.setItem(14, createItem(Material.ENDER_PEARL, ChatColor.GREEN + "Teleport to Player",
                Collections.singletonList(ChatColor.GRAY + "Click to TP")));
        menu.setItem(16, createItem(Material.DIAMOND_SWORD, ChatColor.BLUE + "Change Gamemode",
                Collections.singletonList(ChatColor.GRAY + "Cycle gamemode")));
        menu.setItem(20, createItem(Material.ENDER_EYE, ChatColor.LIGHT_PURPLE + "AntiCheat Controls",
                Arrays.asList(ChatColor.GRAY + "Manage anti-cheat settings",
                        ChatColor.GRAY + "View violations and toggle checks")));
        menu.setItem(22, createItem(Material.PAPER, ChatColor.DARK_AQUA + "Player Reports",
                Arrays.asList(ChatColor.GRAY + "View and investigate reports",
                        ChatColor.GRAY + "Click player to spectate",
                        ChatColor.GRAY + "Reports: " + totalReports())));
        menu.setItem(24, createItem(Material.BOOK, ChatColor.AQUA + "View AntiCheat Logs",
                Collections.singletonList(ChatColor.GRAY + "Top violators")));
        menu.setItem(26, createItem(Material.REDSTONE, ChatColor.DARK_RED + "Reset Stats",
                Collections.singletonList(ChatColor.GRAY + "Reset all violations")));

        player.openInventory(menu);
    }

    private int totalReports() {
        int total = 0;
        for (List<Report> list : reports.values()) {
            total += list.size();
        }
        return total;
    }

    private void openAntiCheatMenu(Player player) {
        MenuHolder holder = new MenuHolder(MenuHolder.Type.ANTICHEAT);
        Inventory menu = createMenu(holder, 27, ChatColor.LIGHT_PURPLE + "AntiCheat Controls");

        menu.setItem(10, createItem(Material.FEATHER,
                ChatColor.WHITE + capitalize(CHECK_FLY) + " Check: " + onOff(enabledChecks.contains(CHECK_FLY)),
                Collections.singletonList(ChatColor.GRAY + "Toggle fly detection")));
        menu.setItem(12, createItem(Material.SUGAR,
                ChatColor.WHITE + capitalize(CHECK_SPEED) + " Check: " + onOff(enabledChecks.contains(CHECK_SPEED)),
                Collections.singletonList(ChatColor.GRAY + "Toggle speed detection")));
        menu.setItem(14, createItem(Material.IRON_SWORD,
                ChatColor.WHITE + capitalize(CHECK_KILLAURA) + " Check: " + onOff(enabledChecks.contains(CHECK_KILLAURA)),
                Collections.singletonList(ChatColor.GRAY + "Toggle kill aura detection")));
        menu.setItem(16, createItem(Material.BELL,
                ChatColor.WHITE + "Alerts: " + onOff(alertsEnabled),
                Collections.singletonList(ChatColor.GRAY + "Toggle admin alerts")));
        menu.setItem(22, createItem(Material.ARROW, ChatColor.RED + "Back to Main",
                Collections.singletonList(ChatColor.GRAY + "Return")));

        player.openInventory(menu);
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private String onOff(boolean value) {
        return value ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF";
    }

    /** Reports menu: shows every reported player, online or not. */
    private void openReportsMenu(Player player) {
        List<UUID> ids = new ArrayList<>(reports.keySet());
        ids.sort((left, right) -> Integer.compare(reports.getOrDefault(right, List.of()).size(),
                reports.getOrDefault(left, List.of()).size()));

        int size = Math.min(54, Math.max(9, ((ids.size() + 8) / 9) * 9));
        MenuHolder holder = new MenuHolder(MenuHolder.Type.REPORTS);
        Inventory menu = createMenu(holder, size, ChatColor.DARK_AQUA + "Player Reports");

        if (ids.isEmpty()) {
            menu.setItem(4, createItem(Material.PAPER, ChatColor.GRAY + "No reports yet", List.of()));
            player.openInventory(menu);
            return;
        }

        int slot = 0;
        for (UUID id : ids) {
            if (slot >= size) {
                break;
            }
            List<Report> list = reports.getOrDefault(id, List.of());
            String name = reportedNames.getOrDefault(id, "Unknown");

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Reports: " + list.size());
            if (!list.isEmpty()) {
                lore.add(ChatColor.GRAY + "Latest: " + list.get(list.size() - 1).getSummary());
            }
            lore.add(ChatColor.YELLOW + "Click to spectate & TP");

            holder.setTarget(slot, id);
            menu.setItem(slot, createPlayerHead(name, ChatColor.GOLD + name, lore));
            slot++;
        }

        player.openInventory(menu);
    }

    private void openViolationList(Player player) {
        List<Map.Entry<UUID, Integer>> entries = new ArrayList<>(violationLevels.entrySet());
        entries.sort(Comparator.comparingInt(entry -> entry.getValue() == null ? 0 : entry.getValue()));
        Collections.reverse(entries);
        if (entries.size() > 5) {
            entries = new ArrayList<>(entries.subList(0, 5));
        }

        MenuHolder holder = new MenuHolder(MenuHolder.Type.VIOLATORS);
        Inventory menu = createMenu(holder, 27, ChatColor.RED + "Top Violators");

        if (entries.isEmpty()) {
            menu.setItem(13, createItem(Material.BOOK, ChatColor.GRAY + "No violations recorded", List.of()));
        } else {
            int slot = 0;
            for (Map.Entry<UUID, Integer> entry : entries) {
                UUID id = entry.getKey();
                int level = entry.getValue() == null ? 0 : entry.getValue();
                String name = nameOf(id);
                holder.setTarget(slot, id);
                menu.setItem(slot, createPlayerHead(name,
                        ChatColor.RED + name + ChatColor.GRAY + " (VL: " + level + ")",
                        Collections.singletonList(ChatColor.GRAY + "Click to teleport")));
                slot += 2;
            }
        }

        player.openInventory(menu);
    }

    private String nameOf(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            return online.getName();
        }
        String known = reportedNames.get(id);
        if (known != null) {
            return known;
        }
        return id.toString().substring(0, 8);
    }

    private void openPlayerList(Player player, MenuHolder.Type type, String title) {
        List<Player> players = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(player)) {
                players.add(online);
            }
        }

        int size = Math.min(54, Math.max(9, ((players.size() + 8) / 9) * 9));
        MenuHolder holder = new MenuHolder(type);
        Inventory menu = createMenu(holder, size, title);

        int slot = 0;
        for (Player target : players) {
            if (slot >= size) {
                break;
            }
            holder.setTarget(slot, target.getUniqueId());
            menu.setItem(slot, createPlayerHead(target.getName(), ChatColor.WHITE + target.getName(),
                    Collections.singletonList(ChatColor.GRAY + "UUID: " + target.getUniqueId())));
            slot++;
        }

        player.openInventory(menu);
    }

    private ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(name);
        if (lore != null) {
            meta.setLore(new ArrayList<>(lore));
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createPlayerHead(String ownerName, String displayName, List<String> lore) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (meta == null) {
            return head;
        }
        meta.setDisplayName(displayName);
        if (lore != null) {
            meta.setLore(new ArrayList<>(lore));
        }
        if (meta instanceof SkullMeta && ownerName != null) {
            SkullMeta skullMeta = (SkullMeta) meta;
            try {
                skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(ownerName));
            } catch (Throwable ignored) {
                // Skin lookup can fail (offline mode / no connection) - keep the default skin.
            }
        }
        head.setItemMeta(meta);
        return head;
    }

    private void cycleGamemode(Player player) {
        GameMode next = switch (player.getGameMode()) {
            case SURVIVAL -> GameMode.CREATIVE;
            case CREATIVE -> GameMode.ADVENTURE;
            case ADVENTURE -> GameMode.SPECTATOR;
            default -> GameMode.SURVIVAL;
        };
        player.setGameMode(next);
        player.sendMessage(ChatColor.BLUE + "Gamemode changed to " + next.name() + "!");
    }

    // ------------------------------------------------------------------
    // Inventory handling
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder holder)) {
            // Only our own menus are touched, so chests and the player inventory keep working.
            return;
        }
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // Clicking the bottom half of the view is not a menu action.
        if (event.getRawSlot() >= event.getInventory().getSize()) {
            return;
        }

        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }

        int slot = event.getRawSlot();
        UUID targetId = holder.getTarget(slot);
        Player target = targetId == null ? null : Bukkit.getPlayer(targetId);

        switch (holder.getType()) {
            case MAIN -> handleMainClick(player, slot);
            case ANTICHEAT -> handleAntiCheatClick(player, slot);
            case REPORTS -> handleReportClick(player, targetId);
            case VIOLATORS -> handleViolatorClick(player, targetId);
            case BAN -> punish(player, target, "ban");
            case KICK -> punish(player, target, "kick");
            case TELEPORT -> punish(player, target, "tp");
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    private void handleMainClick(Player player, int slot) {
        switch (slot) {
            case 10 -> openPlayerList(player, MenuHolder.Type.BAN, ChatColor.RED + "Select Player to Ban");
            case 12 -> openPlayerList(player, MenuHolder.Type.KICK, ChatColor.YELLOW + "Select Player to Kick");
            case 14 -> openPlayerList(player, MenuHolder.Type.TELEPORT, ChatColor.GREEN + "Select Player to Teleport To");
            case 16 -> cycleGamemode(player);
            case 20 -> openAntiCheatMenu(player);
            case 22 -> openReportsMenu(player);
            case 24 -> openViolationList(player);
            case 26 -> {
                violationLevels.clear();
                player.sendMessage(ChatColor.GREEN + "All violation stats reset!");
            }
            default -> {
            }
        }
    }

    private void handleAntiCheatClick(Player player, int slot) {
        switch (slot) {
            case 10 -> toggleCheck(CHECK_FLY);
            case 12 -> toggleCheck(CHECK_SPEED);
            case 14 -> toggleCheck(CHECK_KILLAURA);
            case 16 -> {
                alertsEnabled = !alertsEnabled;
                player.sendMessage(ChatColor.YELLOW + "Alerts " + (alertsEnabled ? "enabled" : "disabled") + "!");
            }
            case 22 -> {
                openCustomAdminMenu(player);
                return;
            }
            default -> {
                return;
            }
        }
        openAntiCheatMenu(player);
    }

    private void handleReportClick(Player player, UUID targetId) {
        player.closeInventory();
        if (targetId == null) {
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline()) {
            player.sendMessage(ChatColor.RED + nameOf(targetId) + " is offline - nothing to spectate.");
            return;
        }
        startSpectate(player, target);
    }

    private void handleViolatorClick(Player player, UUID targetId) {
        player.closeInventory();
        if (targetId == null) {
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            player.sendMessage(ChatColor.RED + "Player " + nameOf(targetId) + " is offline!");
            return;
        }
        player.teleport(target.getLocation());
        player.sendMessage(ChatColor.GREEN + "Teleported to " + target.getName()
                + " (VL: " + violationLevels.getOrDefault(targetId, 0) + ")");
    }

    private void punish(Player admin, Player target, String action) {
        admin.closeInventory();
        if (target == null) {
            admin.sendMessage(ChatColor.RED + "Player not found!");
            return;
        }
        if (target.equals(admin)) {
            admin.sendMessage(ChatColor.RED + "You can't do that to yourself!");
            return;
        }

        switch (action) {
            case "ban" -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + target.getName());
                admin.sendMessage(ChatColor.RED + "Banned " + target.getName() + "!");
            }
            case "kick" -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "kick " + target.getName());
                admin.sendMessage(ChatColor.YELLOW + "Kicked " + target.getName() + "!");
            }
            case "tp" -> {
                admin.teleport(target.getLocation());
                admin.sendMessage(ChatColor.GREEN + "Teleported to " + target.getName() + "!");
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------
    // Spectate mode
    // ------------------------------------------------------------------

    private void startSpectate(Player admin, Player target) {
        spectating.put(admin.getUniqueId(), new SpectateState(admin.getGameMode(), admin.getLocation().clone()));
        admin.setGameMode(GameMode.SPECTATOR);
        admin.teleport(target.getLocation());
        admin.sendMessage(ChatColor.YELLOW + "Spectating " + target.getName()
                + ". Use /admin to go back to your previous position.");
    }

    /** @return true if the admin was in spectate mode and has been returned. */
    private boolean leaveSpectate(Player admin) {
        SpectateState state = spectating.remove(admin.getUniqueId());
        if (state == null) {
            return false;
        }
        admin.setGameMode(state.gamemode);
        admin.teleport(state.location);
        admin.sendMessage(ChatColor.GREEN + "Back at your previous position (" + state.gamemode.name() + ").");
        return true;
    }

    private static final class SpectateState {
        private final GameMode gamemode;
        private final Location location;

        private SpectateState(GameMode gamemode, Location location) {
            this.gamemode = gamemode;
            this.location = location;
        }
    }

    // ------------------------------------------------------------------
    // Anti-cheat
    // ------------------------------------------------------------------

    private void toggleCheck(String check) {
        if (enabledChecks.remove(check)) {
            getLogger().info("AntiCheat check '" + check + "' toggled: OFF");
        } else {
            enabledChecks.add(check);
            getLogger().info("AntiCheat check '" + check + "' toggled: ON");
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || enabledChecks.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        boolean immune = player.hasPermission("adminpanel.use")
                || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR
                || player.isFlying()
                || player.getAllowFlight()
                || player.isInsideVehicle()
                || player.isSwimming();
        if (immune) {
            lastGroundTime.put(id, now);
            return;
        }

        Location from = event.getFrom();
        if (enabledChecks.contains(CHECK_FLY)) {
            checkFly(player, id, from, to, now);
        }
        if (enabledChecks.contains(CHECK_SPEED)) {
            checkSpeed(player, id, from, to, now);
        }
    }

    private void checkFly(Player player, UUID id, Location from, Location to, long now) {
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        if (player.getWorld() != to.getWorld()) {
            return;
        }

        // Something directly under the player means they stand, climb or swim.
        Material below = to.clone().subtract(0, 0.5, 0).getBlock().getType();
        boolean supported = below.isSolid()
                || below == Material.WATER
                || below == Material.LAVA
                || below == Material.COBWEB
                || below == Material.LADDER
                || below == Material.VINE
                || below == Material.BUBBLE_COLUMN;
        if (supported) {
            lastGroundTime.put(id, now);
            return;
        }

        // Being pushed upwards by something else is not flying.
        if (player.getVelocity().getY() > 0.4) {
            lastGroundTime.put(id, now);
            return;
        }

        long airTime = now - lastGroundTime.getOrDefault(id, now - MAX_AIR_TIME_MS - 1);
        if (airTime > MAX_AIR_TIME_MS) {
            getLogger().info("Fly flag for " + player.getName() + " - air time: " + airTime + "ms");
            addViolation(player, CHECK_FLY, 1);
            lastGroundTime.put(id, now);
        }
    }

    private void checkSpeed(Player player, UUID id, Location from, Location to, long now) {
        long last = lastSpeedFlag.getOrDefault(id, 0L);
        if (now - last < 1000L) {
            return;
        }

        // Horizontal distance only: falling or jumping is not speed hacking.
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= 0.05) {
            return;
        }

        double threshold = player.isSprinting() ? 1.0 : 0.5;
        if (hasPotion(player, jumpBoost)) {
            threshold += 0.2;
        }
        if (hasPotion(player, PotionEffectType.SPEED)) {
            threshold += 0.4;
        }

        Material ground = to.getBlock().getType();
        if (ground == Material.ICE || ground == Material.PACKED_ICE || ground == Material.BLUE_ICE
                || ground == Material.FROSTED_ICE || ground == Material.SOUL_SAND
                || ground == Material.LADDER || ground == Material.VINE) {
            threshold += 0.4;
        }

        if (distance > threshold) {
            getLogger().info("Speed flag for " + player.getName() + " - dist: " + round(distance)
                    + ", threshold: " + round(threshold));
            addViolation(player, CHECK_SPEED, (int) Math.ceil(distance * 5.0));
            lastSpeedFlag.put(id, now);
        }
    }

    private boolean hasPotion(Player player, PotionEffectType type) {
        return type != null && player.hasPotionEffect(type);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!enabledChecks.contains(CHECK_KILLAURA)) {
            return;
        }
        Entity damager = event.getDamager();
        if (!(damager instanceof Player player)) {
            return;
        }
        if (player.hasPermission("adminpanel.use") || player.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (now - lastAttackTime.getOrDefault(id, 0L) > 1000L) {
            attackCount.put(id, 0);
        }

        lastAttackTime.put(id, now);
        int hits = attackCount.getOrDefault(id, 0) + 1;
        attackCount.put(id, hits);

        if (hits > MAX_ATTACKS_PER_SECOND) {
            getLogger().info("KillAura flag for " + player.getName() + " - attacks: " + hits);
            addViolation(player, CHECK_KILLAURA, 3);
            event.setCancelled(true);
            attackCount.put(id, 0);
        }
    }

    private void addViolation(Player player, String check, int amount) {
        if (amount <= 0 || !player.isOnline()) {
            return;
        }
        UUID id = player.getUniqueId();
        int level = violationLevels.getOrDefault(id, 0) + amount;
        violationLevels.put(id, level);

        String text = player.getName() + " (" + check + ", VL: " + level + ")";
        if (alertsEnabled) {
            Bukkit.broadcast(ChatColor.RED + "AntiCheat Alert: " + text, "adminpanel.alert");
        }
        getLogger().warning(player.getName() + " AntiCheat: " + check + " VL: " + level);

        if (level > KICK_VL) {
            player.kickPlayer(ChatColor.RED + "Kicked for cheating: " + check + " (VL: " + level + ")");
            violationLevels.remove(id);
        } else if (level > WARN_VL) {
            player.sendMessage(ChatColor.RED + "Warning: Suspicious activity (" + check + ", VL: " + level + ")!");
        }
    }

    // ------------------------------------------------------------------
    // State handling
    // ------------------------------------------------------------------

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        violationLevels.putIfAbsent(event.getPlayer().getUniqueId(), 0);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastAttackTime.remove(id);
        attackCount.remove(id);
        lastGroundTime.remove(id);
        lastSpeedFlag.remove(id);
        spectating.remove(id);
        // Reports and violation levels are kept on purpose: a report should stay
        // reviewable and a cheater must not wipe their record by logging out.
    }
}
