package com.extantape.stationmaster;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The in-game profile picker: a chest menu where each profile toggles on or off, then Approve or Save. Nothing changes
 * until that button is pressed, and every click on the menu is cancelled, so no item can be taken out of it.
 */
final class PickerMenus implements Listener {

    static final int SIZE = 54, CANCEL = 45, INFO = 49, CONFIRM = 53, HEAD = 4;
    static final int[] PROFILE_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    static final int[] TOGGLE_SLOTS = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    static final int[] WARNING_SLOTS = {20, 22, 24, 30, 32};

    enum Phase { PICK, CONFIRM, BUSY, DONE }

    final class Menu implements InventoryHolder {
        final UUID admin;
        final NameResolver.Resolved target;
        final boolean approving;
        final Set<String> startOn = new LinkedHashSet<>();     // profile ids they have directly
        final Set<String> inheritedOnly = new LinkedHashSet<>(); // profile ids they get through another group
        final Set<String> wanted = new LinkedHashSet<>();
        final Map<Integer, String> slots = new HashMap<>();
        List<Planner.Warning> warnings = List.of();
        Phase phase = Phase.PICK;
        final Inventory inventory;

        Menu(Player admin, NameResolver.Resolved target, boolean approving, GroupStore.UserState state, Settings settings) {
            this.admin = admin.getUniqueId();
            this.target = target;
            this.approving = approving;
            for (Profile p : settings.profiles().values()) {
                if (state.direct().contains(p.group())) startOn.add(p.id());
                else if (state.inherited().contains(p.group())) inheritedOnly.add(p.id());
            }
            wanted.addAll(startOn);
            inventory = Bukkit.createInventory(this, SIZE, Component.text((approving ? "Approve " : "Profiles: ") + target.name()));
        }

        @Override public @NotNull Inventory getInventory() { return inventory; }

        Set<String> adds() { Set<String> s = new LinkedHashSet<>(wanted); s.removeAll(startOn); return s; }
        Set<String> removes() { Set<String> s = new LinkedHashSet<>(startOn); s.removeAll(wanted); return s; }
    }

    private final StationmasterPlugin plugin;
    private final Map<UUID, Menu> open = new HashMap<>();

    PickerMenus(StationmasterPlugin plugin) { this.plugin = plugin; }

    void open(Player admin, NameResolver.Resolved target, boolean approving, GroupStore.UserState state) {
        if (!admin.hasPermission(ChangeService.PERMISSION)) return;
        if (admin.getUniqueId().equals(target.id())) return;
        Menu menu = new Menu(admin, target, approving, state, plugin.settings());
        drawPick(menu);
        open.put(admin.getUniqueId(), menu);
        admin.openInventory(menu.inventory);
    }

    void closeAll() {
        for (Menu m : new ArrayList<>(open.values())) {
            Player p = Bukkit.getPlayer(m.admin);
            if (p != null) p.closeInventory();
        }
        open.clear();
    }

    // ---------- drawing ----------

    private void drawPick(Menu m) {
        Inventory inv = m.inventory;
        inv.clear();
        m.slots.clear();
        Settings s = plugin.settings();
        inv.setItem(HEAD, head(m));
        int pi = 0, ti = 0;
        for (Profile p : s.profiles().values()) {
            int[] slots = p.toggle() ? TOGGLE_SLOTS : PROFILE_SLOTS;
            int index = p.toggle() ? ti++ : pi++;
            if (index >= slots.length) continue;
            inv.setItem(slots[index], profileItem(m, p));
            m.slots.put(slots[index], p.id());
        }
        inv.setItem(CANCEL, item(Material.BARRIER, Component.text("Close without changes", NamedTextColor.RED), List.of()));
        inv.setItem(INFO, item(Material.BOOK, Component.text("How this works", NamedTextColor.GOLD), List.of(
                plain("Click a profile to turn it on or off."),
                plain("Nothing changes until you press"),
                plain(m.approving ? "Approve." : "Save."))));
        inv.setItem(CONFIRM, confirmItem(m));
    }

    private ItemStack confirmItem(Menu m) {
        List<Component> lore = new ArrayList<>();
        Settings s = plugin.settings();
        if (m.approving) lore.add(plain("Adds " + m.target.name() + " to the whitelist."));
        if (!m.adds().isEmpty()) lore.add(Component.text("Add: " + labels(s, m.adds()), NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        if (!m.removes().isEmpty()) lore.add(Component.text("Remove: " + labels(s, m.removes()), NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        if (m.approving && m.wanted.isEmpty()) lore.add(plain("No profiles: just the starting areas."));
        if (!m.approving && m.adds().isEmpty() && m.removes().isEmpty()) lore.add(plain("Nothing to save yet."));
        return item(Material.LIME_CONCRETE, Component.text(m.approving ? "Approve " + m.target.name() : "Save changes", NamedTextColor.GREEN), lore);
    }

    private ItemStack profileItem(Menu m, Profile p) {
        Material icon = Material.matchMaterial(p.icon());
        if (icon == null || !icon.isItem() || icon.isAir()) icon = Material.PAPER;
        boolean on = m.wanted.contains(p.id()), was = m.startOn.contains(p.id());
        List<Component> lore = new ArrayList<>();
        for (String line : p.description()) for (String w : wrap(line, 34)) lore.add(plain(w));
        String status;
        NamedTextColor color;
        if (m.inheritedOnly.contains(p.id())) { status = "Comes from another group"; color = NamedTextColor.AQUA; on = true; }
        else if (on && was) { status = "On"; color = NamedTextColor.GREEN; }
        else if (on) { status = "Will be added"; color = NamedTextColor.GREEN; }
        else if (was) { status = "Will be removed"; color = NamedTextColor.RED; }
        else { status = "Off"; color = NamedTextColor.DARK_GRAY; }
        lore.add(Component.text(status, color).decoration(TextDecoration.ITALIC, false));
        if (p.confirm() != null && !on) lore.add(Component.text("Asks for a yes before it's added", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        ItemStack it = item(icon, Component.text(p.label(), on ? NamedTextColor.GREEN : NamedTextColor.WHITE).decoration(TextDecoration.BOLD, on), lore);
        ItemMeta meta = it.getItemMeta();
        meta.setEnchantmentGlintOverride(on);
        it.setItemMeta(meta);
        return it;
    }

    private void drawConfirm(Menu m) {
        Inventory inv = m.inventory;
        inv.clear();
        m.slots.clear();
        inv.setItem(HEAD, head(m));
        int i = 0;
        for (Planner.Warning w : m.warnings) {
            if (i >= WARNING_SLOTS.length) break;
            List<Component> lore = new ArrayList<>();
            for (String line : wrap(w.text(), 34)) lore.add(Component.text(line, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
            inv.setItem(WARNING_SLOTS[i++], item(Material.YELLOW_BANNER, Component.text("Please check", NamedTextColor.YELLOW), lore));
        }
        inv.setItem(CANCEL, item(Material.ARROW, Component.text("Go back", NamedTextColor.WHITE), List.of(plain("Nothing has changed yet."))));
        inv.setItem(CONFIRM, item(Material.LIME_CONCRETE, Component.text("Yes, go ahead", NamedTextColor.GREEN), List.of(plain("Saves the change for " + m.target.name() + "."))));
    }

    private void drawBusy(Menu m) {
        m.inventory.clear();
        m.slots.clear();
        m.inventory.setItem(22, item(Material.CLOCK, Component.text("Saving…", NamedTextColor.GOLD), List.of()));
    }

    private ItemStack head(Menu m) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setPlayerProfile(Bukkit.createProfile(m.target.id(), m.target.name()));
        meta.displayName(Component.text(m.target.name(), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        head.setItemMeta(meta);
        return head;
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(material);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private static Component plain(String s) { return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false); }

    private static String labels(Settings s, Set<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) { Profile p = s.profiles().get(id); out.add(p == null ? id : p.label()); }
        return ChangeService.join(out);
    }

    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) { out.add(line.toString()); line.setLength(0); }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    // ---------- clicks ----------

    private Menu menuOf(Inventory top) {
        return top != null && top.getHolder(false) instanceof Menu m ? m : null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        Menu m = menuOf(event.getView().getTopInventory());
        if (m == null) return;
        event.setCancelled(true); // every click while the menu is open: shift, number keys, double-clicks, both inventories
        if (event.getClickedInventory() != m.inventory) return;
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        if (!(event.getWhoClicked() instanceof Player admin) || !admin.getUniqueId().equals(m.admin) || open.get(m.admin) != m) return;
        if (!admin.hasPermission(ChangeService.PERMISSION)) {
            m.phase = Phase.DONE;
            Bukkit.getScheduler().runTask(plugin, () -> admin.closeInventory());
            admin.sendMessage(PlayerCommands.bad("You no longer have permission to change profiles."));
            return;
        }
        int slot = event.getRawSlot();
        switch (m.phase) {
            case PICK -> clickPick(admin, m, slot);
            case CONFIRM -> {
                if (slot == CANCEL) { m.phase = Phase.PICK; drawPick(m); }
                else if (slot == CONFIRM) submit(admin, m, true);
            }
            default -> { }
        }
    }

    private void clickPick(Player admin, Menu m, int slot) {
        if (slot == CANCEL) {
            m.phase = Phase.DONE;
            Bukkit.getScheduler().runTask(plugin, () -> admin.closeInventory());
            admin.sendMessage(PlayerCommands.plain("Closed. Nothing was changed."));
            return;
        }
        if (slot == CONFIRM) {
            if (!m.approving && m.adds().isEmpty() && m.removes().isEmpty()) return;
            submit(admin, m, false);
            return;
        }
        String id = m.slots.get(slot);
        if (id == null) return;
        Profile p = plugin.settings().profiles().get(id);
        if (p == null) return;
        if (m.inheritedOnly.contains(id)) {
            admin.sendMessage(PlayerCommands.plain(m.target.name() + " gets " + p.label() + " through another group, which only the console can change."));
            return;
        }
        if (!m.wanted.remove(id)) m.wanted.add(id);
        drawPick(m);
    }

    private void submit(Player admin, Menu m, boolean confirming) {
        Set<String> confirmed = new LinkedHashSet<>();
        if (confirming) for (Planner.Warning w : m.warnings) confirmed.add(w.code());
        m.phase = Phase.BUSY;
        drawBusy(m);
        var request = new ChangeService.Request(null, new ChangeService.Actor(admin.getName(), admin.getUniqueId(), "menu"),
                m.target.id(), m.target.name(), m.adds(), m.removes(), confirmed, m.approving);
        plugin.changes().submit(request).whenComplete((result, error) -> plugin.runMain(() -> {
            Player p = Bukkit.getPlayer(m.admin);
            boolean stillOpen = p != null && open.get(m.admin) == m && menuOf(p.getOpenInventory().getTopInventory()) == m;
            if (error != null || result == null) {
                m.phase = Phase.DONE;
                if (p != null) { p.sendMessage(PlayerCommands.bad("Something went wrong, so the change stopped. See the server log.")); if (stillOpen) p.closeInventory(); }
                return;
            }
            if (result.status().equals("warn")) {
                if (stillOpen) {
                    m.warnings = result.warnings();
                    m.phase = Phase.CONFIRM;
                    drawConfirm(m);
                } else {
                    m.phase = Phase.DONE;
                    if (p != null) p.sendMessage(PlayerCommands.plain("That change needed a yes, and the menu was closed, so nothing was changed."));
                }
                return;
            }
            m.phase = Phase.DONE;
            if (p != null) {
                p.sendMessage(result.ok() ? PlayerCommands.good(result.message()) : PlayerCommands.bad(result.message()));
                if (stillOpen) p.closeInventory();
            }
        }));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (menuOf(event.getView().getTopInventory()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(InventoryMoveItemEvent event) {
        if (menuOf(event.getSource()) != null || menuOf(event.getDestination()) != null) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Menu m = menuOf(event.getInventory());
        if (m == null || open.get(m.admin) != m) return;
        open.remove(m.admin);
        if ((m.phase == Phase.PICK || m.phase == Phase.CONFIRM) && event.getPlayer() instanceof Player p
                && (!m.adds().isEmpty() || !m.removes().isEmpty() || m.approving)) {
            p.sendMessage(PlayerCommands.plain("Closed. Nothing was changed."));
        }
        if (m.phase == Phase.PICK || m.phase == Phase.CONFIRM) m.phase = Phase.DONE;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Menu m = open.remove(event.getPlayer().getUniqueId());
        if (m != null && m.phase != Phase.BUSY) m.phase = Phase.DONE;
    }
}
