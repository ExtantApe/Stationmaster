package com.extantape.stationmaster;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** /approve &lt;name&gt; and /profile &lt;name&gt; [edit], for admins in the game. */
final class PlayerCommands implements CommandExecutor, TabCompleter {

    private final StationmasterPlugin plugin;

    PlayerCommands(StationmasterPlugin plugin) { this.plugin = plugin; }

    static Component bad(String text) { return Component.text(text, NamedTextColor.RED); }
    static Component good(String text) { return Component.text(text, NamedTextColor.GREEN); }
    static Component plain(String text) { return Component.text(text, NamedTextColor.GRAY); }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!sender.hasPermission(ChangeService.PERMISSION)) {
            sender.sendMessage(bad("Only approvers can do that."));
            return true;
        }
        boolean approve = command.getName().equals("approve");
        if (args.length < 1 || args.length > 2 || (approve && args.length != 1) || (args.length == 2 && !args[1].equalsIgnoreCase("edit"))) {
            sender.sendMessage(plain(approve ? "Use /approve <name> to let someone in and choose their profiles." : "Use /profile <name> to see someone's profiles, or /profile <name> edit to change them."));
            return true;
        }
        boolean edit = approve || args.length == 2;
        if (edit && !(sender instanceof Player)) {
            sender.sendMessage(plain("The menu is for players. From the console, use: stationmaster apply <request-id> <uuid> <name> +profile -profile"));
            return true;
        }
        if (!plugin.settings().ok()) { sender.sendMessage(bad("Stationmaster's config is broken, so it can't change anything right now. " + plugin.settings().problems().get(0))); return true; }
        if (!plugin.store().ready()) { sender.sendMessage(bad("LuckPerms isn't running, so Stationmaster can't change anyone's profiles right now.")); return true; }

        sender.sendMessage(plain("Looking up " + args[0] + "…"));
        resolve(sender, args[0], who -> {
            if (sender instanceof Player p && p.getUniqueId().equals(who.id()) && edit) {
                sender.sendMessage(bad("You can't approve yourself or change your own profiles. Ask another admin."));
                return;
            }
            plugin.store().load(who.id(), who.name()).orTimeout(30, TimeUnit.SECONDS).whenComplete((state, error) -> plugin.runMain(() -> {
                if (error != null) {
                    sender.sendMessage(bad("LuckPerms couldn't load " + who.name() + "'s groups, so nothing can be changed right now."));
                    return;
                }
                if (edit) {
                    if (sender instanceof Player p && p.isOnline()) plugin.menus().open(p, who, approve, state);
                } else {
                    show(sender, who, state);
                }
            }));
        });
        return true;
    }

    private void resolve(CommandSender sender, String typed, Consumer<NameResolver.Resolved> then) {
        plugin.names().resolve(typed, plugin.settings().lookup()).whenComplete((who, error) -> plugin.runMain(() -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                sender.sendMessage(bad(cause instanceof NameResolver.NameProblem np ? np.getMessage() : "Couldn't look up " + typed + ". Nothing was changed."));
                return;
            }
            then.accept(who);
        }));
    }

    private void show(CommandSender sender, NameResolver.Resolved who, GroupStore.UserState state) {
        Settings s = plugin.settings();
        List<String> has = new ArrayList<>(), inherited = new ArrayList<>();
        for (Profile p : s.profiles().values()) {
            if (state.direct().contains(p.group())) has.add(p.label());
            else if (state.inherited().contains(p.group())) inherited.add(p.label());
        }
        boolean whitelisted = Bukkit.getOfflinePlayer(who.id()).isWhitelisted();
        sender.sendMessage(Component.text(who.name(), NamedTextColor.GOLD)
                .append(plain(whitelisted ? " is approved. " : " isn't approved yet. "))
                .append(plain(has.isEmpty() ? "No profiles." : "Profiles: ")).append(Component.text(has.isEmpty() ? "" : ChangeService.join(has) + ".", NamedTextColor.WHITE)));
        if (!inherited.isEmpty()) sender.sendMessage(plain("Also gets " + ChangeService.join(inherited) + " through another group."));
        if (sender instanceof Player p && !p.getUniqueId().equals(who.id())) {
            String cmd = whitelisted ? "/profile " + who.name() + " edit" : "/approve " + who.name();
            sender.sendMessage(Component.text(whitelisted ? "[Change profiles]" : "[Approve]", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand(cmd)).hoverEvent(HoverEvent.showText(plain("Opens the profile menu"))));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!sender.hasPermission(ChangeService.PERMISSION)) return List.of();
        if (args.length == 1) {
            String start = args[0].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(start)).toList();
        }
        if (args.length == 2 && command.getName().equals("profile")) return List.of("edit");
        return List.of();
    }
}
