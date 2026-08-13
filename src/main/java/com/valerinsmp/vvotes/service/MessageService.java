package com.valerinsmp.vvotes.service;

import com.valerinsmp.vvotes.VVotesPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** MiniMessage-only presentation with an immutable, atomically replaceable catalog. */
public final class MessageService {
    private static final int MAX_LINE_LENGTH = 4_096;
    private static final String REPOSITORY = "https://github.com/ValerinSMP/vVotes";
    private final VVotesPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private volatile MessageCatalog catalog;

    public MessageService(VVotesPlugin plugin) {
        this.plugin = plugin;
        apply(loadCandidate());
    }

    public MessageCatalog loadCandidate() {
        Path path = plugin.getDataFolder().toPath().resolve("messages.yml");
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(stripBom(text));
            Map<String, List<String>> entries = new HashMap<>();
            flatten(yaml, "", entries);
            if (!entries.containsKey("messages.prefix")) {
                throw new IllegalArgumentException("Falta messages.prefix");
            }
            return new MessageCatalog(Map.copyOf(entries));
        } catch (Exception exception) {
            throw new IllegalStateException("messages.yml invalido: " + exception.getMessage(), exception);
        }
    }

    public void apply(MessageCatalog candidate) {
        this.catalog = candidate;
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Map.of());
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        if (sender == null) return;
        for (Component component : messages(key, placeholders)) sender.sendMessage(component);
    }

    public List<Component> messages(String key, Map<String, String> placeholders) {
        List<String> lines = raw(key);
        if (lines.isEmpty()) return List.of();
        Component prefix = deserialize(raw("prefix").stream().findFirst().orElse(""), Map.of(), false);
        List<Component> result = new ArrayList<>(lines.size());
        for (String line : lines) result.add(deserialize(line, placeholders, true, prefix));
        return List.copyOf(result);
    }

    public Component titlePart(String key, Map<String, String> placeholders) {
        return first("titles." + key, placeholders);
    }

    public Component actionbar(String key, Map<String, String> placeholders) {
        return first("actionbar." + key, placeholders);
    }

    public Component component(String template, Map<String, String> placeholders) {
        Component prefix = deserialize(raw("prefix").stream().findFirst().orElse(""), Map.of(), false);
        return deserialize(template, placeholders, true, prefix);
    }

    public void sendPublicHelp(CommandSender sender) {
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("vVotes · comandos", NamedTextColor.GREEN));
        sender.sendMessage(command("/vote", "Ayuda y enlaces de votación"));
        sender.sendMessage(command("/votestats", "Tus estadísticas de votos"));
        sender.sendMessage(command("/vvotes toggle", "Activar o silenciar anuncios"));
        sender.sendMessage(command("/vvotes about", "Información del plugin"));
        sender.sendMessage(Component.empty());
    }

    public void sendAdminHelp(CommandSender sender, int requestedPage) {
        int page = Math.max(1, Math.min(2, requestedPage));
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("vVotes · administración " + page + "/2", NamedTextColor.GREEN));
        if (page == 1) {
            sender.sendMessage(command("/vvotesadmin reload", "Recargar configuración y presentación"));
            sender.sendMessage(command("/vvotesadmin add <jugador> <cantidad>", "Registrar votos manuales"));
            sender.sendMessage(command("/vvotesadmin adddaily|removedaily <jugador> <cantidad>", "Ajustar contador diario"));
            sender.sendMessage(command("/vvotesadmin resetmonthly <jugador>", "Reiniciar contador mensual"));
        } else {
            sender.sendMessage(command("/vvotesadmin addglobaldaily|removeglobaldaily <cantidad>", "Ajustar contador global"));
            sender.sendMessage(command("/vvotesadmin drawmonthly [YYYY-MM]", "Ejecutar sorteo mensual"));
            sender.sendMessage(command("/vvotesadmin drawhistory|topmonth [YYYY-MM]", "Consultar sorteos"));
            sender.sendMessage(command("/vvotesadmin ambiguous", "Listar grants de resultado incierto"));
        }
        Component navigation = Component.text(page == 1 ? "[Página siguiente]" : "[Página anterior]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/vvotesadmin help " + (page == 1 ? 2 : 1)))
                .hoverEvent(HoverEvent.showText(Component.text("Abrir página " + (page == 1 ? 2 : 1))));
        sender.sendMessage(navigation);
        sender.sendMessage(Component.empty());
    }

    public void sendAbout(CommandSender sender) {
        var meta = plugin.getPluginMeta();
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text(meta.getName() + " " + meta.getVersion(), NamedTextColor.GREEN));
        sender.sendMessage(Component.text(meta.getDescription() == null ? "Sistema de votos de ValerinSMP." : meta.getDescription(),
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Autor: " + String.join(", ", meta.getAuthors()), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Paper 1.21.11+ · Java 21", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Repositorio", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.openUrl(REPOSITORY))
                .hoverEvent(HoverEvent.showText(Component.text("Abrir " + REPOSITORY))));
        sender.sendMessage(Component.empty());
    }

    private Component first(String path, Map<String, String> placeholders) {
        List<String> lines = catalog.entries().getOrDefault(path, List.of());
        if (lines.isEmpty()) return Component.empty();
        Component prefix = deserialize(raw("prefix").stream().findFirst().orElse(""), Map.of(), false);
        return deserialize(lines.getFirst(), placeholders, true, prefix);
    }

    private List<String> raw(String key) {
        MessageCatalog current = catalog;
        List<String> direct = current.entries().get(key);
        if (direct != null) return direct;
        return current.entries().getOrDefault("messages." + key, List.of());
    }

    private Component deserialize(String line, Map<String, String> placeholders, boolean includePrefix,
                                  Component... prefix) {
        TagResolver.Builder resolver = TagResolver.builder();
        if (includePrefix) resolver.resolver(Placeholder.component("prefix", prefix.length == 0 ? Component.empty() : prefix[0]));
        placeholders.forEach((name, value) -> {
            String safeName = name.toLowerCase(Locale.ROOT);
            if (safeName.matches("[a-z0-9_]+")) resolver.resolver(Placeholder.unparsed(safeName, value == null ? "" : value));
        });
        return line.isEmpty() ? Component.empty() : miniMessage.deserialize(line, resolver.build());
    }

    private Component command(String syntax, String description) {
        return Component.text(syntax, NamedTextColor.GREEN)
                .clickEvent(ClickEvent.suggestCommand(syntax.replaceAll(" <[^>]+>", "")))
                .hoverEvent(HoverEvent.showText(Component.text(description)))
                .append(Component.text(" — " + description, NamedTextColor.GRAY));
    }

    private void flatten(ConfigurationSection section, String prefix, Map<String, List<String>> output) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) {
                flatten(child, path, output);
                continue;
            }
            List<String> values = section.isList(key) ? section.getStringList(key)
                    : List.of(section.getString(key, ""));
            if (values.size() > 128) throw new IllegalArgumentException(path + " tiene demasiadas lineas");
            for (String value : values) {
                if (value.length() > MAX_LINE_LENGTH) throw new IllegalArgumentException(path + " tiene una linea demasiado larga");
            }
            output.put(path, List.copyOf(values));
        }
    }

    private String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    public record MessageCatalog(Map<String, List<String>> entries) { }
}
