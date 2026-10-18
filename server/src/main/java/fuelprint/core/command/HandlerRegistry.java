package fuelprint.core.command;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * All command handlers, one per {@code command_type}. Refuses at start-up a duplicate type, a handler
 * with no roles, or one of the five human-only commands that lists {@code automation}
 * (AGREEMENT ground rule 8).
 */
@Component
public class HandlerRegistry {

    /** The five commands only a person may send. The automation actor is always refused for them. */
    public static final Set<String> HUMAN_ONLY_COMMANDS = Set.of(
            "approve_mission", "reject_mission", "review_capture", "revise_finding", "record_board_decision");

    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    private final Map<String, CommandHandler> handlers = new HashMap<>();

    public HandlerRegistry(List<CommandHandler> handlers) {
        for (CommandHandler handler : handlers) {
            register(handler);
        }
    }

    private void register(CommandHandler handler) {
        String type = handler.commandType();
        if (type == null || !TYPE.matcher(type).matches()) {
            throw new IllegalStateException("Command type must be lower snake case: " + type);
        }
        Set<Role> roles = handler.allowedRoles();
        if (roles == null || roles.isEmpty()) {
            throw new IllegalStateException("Handler for " + type + " allows no role");
        }
        if (HUMAN_ONLY_COMMANDS.contains(type) && roles.contains(Role.AUTOMATION)) {
            throw new IllegalStateException(type + " is human-only; it must not allow the automation role");
        }
        CommandHandler previous = handlers.putIfAbsent(type, handler);
        if (previous != null) {
            throw new IllegalStateException("Two handlers for " + type + ": " + previous.getClass().getName()
                    + " and " + handler.getClass().getName());
        }
    }

    /** The handler for {@code commandType}, if one is registered. */
    public Optional<CommandHandler> find(String commandType) {
        return Optional.ofNullable(handlers.get(commandType));
    }
}
