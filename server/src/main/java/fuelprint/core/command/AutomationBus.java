package fuelprint.core.command;

import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.Ids;
import org.springframework.stereotype.Component;

/**
 * Sends a normal command as the server's own {@code automation} actor (AGREEMENT sections 1 rule 7 and 12).
 * The command gets a server-made UUID, goes through the same {@link CommandBus} with the same checks, and
 * its receipt says {@code actor: automation, automatic: true}.
 *
 * <p>Safe to call from an event listener: the command is queued on the single command thread and runs
 * after the current command has finished, never inside the caller's transaction. Do not wait for the
 * returned future on the command thread (the future refuses it); chain with {@code thenAccept} instead.
 * Automation never retries a rejected command in a loop: look at the receipt and stop that step.
 */
@Component
public class AutomationBus {

    private final CommandBus bus;

    public AutomationBus(CommandBus bus) {
        this.bus = bus;
    }

    /** Queues {@code commandType} with {@code payload}, no run and no expected revision. */
    public CompletableFuture<Receipt> submit(String commandType, ObjectNode payload) {
        return submit(commandType, payload, null, null);
    }

    /** Queues {@code commandType} for {@code runId} (may be null) with {@code payload}; the CORE-API section 1 form. */
    public CompletableFuture<Receipt> submit(String commandType, String runId, ObjectNode payload) {
        return submit(commandType, payload, runId, null);
    }

    /**
     * Queues {@code commandType} with {@code payload} for {@code runId} (may be null), checked against
     * {@code expectedRevision} of the handler's subject record (may be null).
     */
    public CompletableFuture<Receipt> submit(String commandType, ObjectNode payload, String runId,
                                             Long expectedRevision) {
        ObjectNode body = payload == null ? JsonNodeFactory.instance.objectNode() : payload;
        Command command = new Command(Command.SCHEMA_VERSION, Ids.newId(), commandType, runId, expectedRevision, body);
        return bus.enqueue(command, Session.automation());
    }
}
