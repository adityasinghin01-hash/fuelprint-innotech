package fuelprint.core.command;

import java.util.Set;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.store.RecordChange;
import fuelprint.core.store.RecordKey;
import fuelprint.core.store.StoreView;
import org.springframework.stereotype.Component;

/** Test-only command: saves {@code payload.values} untouched as a {@code test_numbers} record. */
@Component
public class NumberEchoHandler implements CommandHandler {
    @Override public String commandType() { return "test_number_echo"; }
    @Override public Set<Role> allowedRoles() { return Set.of(Role.ANALYST); }
    @Override public RecordKey revisionSubject(Command c) { return null; }
    @Override public HandlerOutcome handle(Command c, Session s, StoreView view) {
        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        fields.set("values", c.payload().path("values"));
        return HandlerResult.builder()
                .save(RecordChange.create("test_numbers", c.payload().path("id").asText(), c.runId(), fields), "TEST_NUMBERS", null)
                .build();
    }
}
