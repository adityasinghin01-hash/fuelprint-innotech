package fuelprint.field;

import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.command.AutomationBus;
import fuelprint.core.command.Receipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Sends field's automatic steps through the core {@link AutomationBus} as the {@code automation} actor.
 * The bus is looked up lazily: the command bus needs the field handlers, and the handlers need the world
 * that submits here. A rejected automatic command is logged and never retried in a loop (AGREEMENT section 12).
 */
@Component
@Profile("!stub")
public class FieldAutomation {

    private static final Logger log = LoggerFactory.getLogger(FieldAutomation.class);

    private final ObjectProvider<AutomationBus> bus;

    public FieldAutomation(ObjectProvider<AutomationBus> bus) {
        this.bus = bus;
    }

    /** Queues {@code commandType} for {@code runId}; the future completes with its receipt. */
    public CompletableFuture<Receipt> submit(String commandType, String runId, ObjectNode payload) {
        AutomationBus automation = bus.getIfAvailable();
        if (automation == null) {
            log.warn("No AutomationBus; automatic {} was not sent", commandType);
            return CompletableFuture.failedFuture(new IllegalStateException("No AutomationBus"));
        }
        return automation.submit(commandType, payload, runId, null).whenComplete((receipt, error) -> {
            if (error != null) {
                log.warn("Automatic {} failed to run", commandType, error);
            } else if (!receipt.applied()) {
                log.info("Automatic {} was {} ({}): {}", commandType, receipt.status(), receipt.reasonCode(),
                        receipt.reasonText());
            }
        });
    }
}
