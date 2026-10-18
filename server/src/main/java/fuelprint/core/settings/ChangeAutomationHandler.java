package fuelprint.core.settings;

import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.command.Command;
import fuelprint.core.command.CommandHandler;
import fuelprint.core.command.HandlerOutcome;
import fuelprint.core.command.HandlerResult;
import fuelprint.core.command.Rejection;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import fuelprint.core.store.RecordChange;
import fuelprint.core.store.RecordKey;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import org.springframework.stereotype.Component;

/**
 * {@code change_automation} (presenter): {@code setting} is one of the {@code automation_settings} fields;
 * {@code value} is true/false, or whole seconds 0 to 600 for {@code autopilot_wait_s}. Subject record: the
 * settings record, so {@code expected_revision} guards two laptops changing it at once.
 */
@Component
public class ChangeAutomationHandler implements CommandHandler {

    public static final String TYPE = "change_automation";

    @Override
    public String commandType() {
        return TYPE;
    }

    @Override
    public Set<Role> allowedRoles() {
        return Set.of(Role.PRESENTER);
    }

    @Override
    public RecordKey revisionSubject(Command command) {
        return new RecordKey(AutomationSettingsService.TYPE, AutomationSettingsService.ID);
    }

    @Override
    public HandlerOutcome handle(Command command, Session session, StoreView store) {
        String setting = command.payload().path("setting").asText(null);
        JsonNode value = command.payload().get("value");
        boolean isSwitch = AutomationSettingsService.SWITCHES.contains(setting);
        boolean isWait = AutomationSettingsService.AUTOPILOT_WAIT_S.equals(setting);
        if (!isSwitch && !isWait) {
            return Rejection.invalid("setting must be one of " + String.join(", ", AutomationSettingsService.SWITCHES)
                    + ", " + AutomationSettingsService.AUTOPILOT_WAIT_S + ".");
        }
        if (value == null || isSwitch && !value.isBoolean()) {
            return Rejection.invalid(setting + " takes true or false.");
        }
        if (isWait && (!value.isIntegralNumber() || value.asInt() < 0 || value.asInt() > AutomationSettingsService.MAX_WAIT_S)) {
            return Rejection.invalid("autopilot_wait_s takes whole seconds from 0 to 600.");
        }
        Optional<StoredRecord> current = store.get(AutomationSettingsService.TYPE, AutomationSettingsService.ID);
        ObjectNode fields;
        RecordChange change;
        if (current.isPresent()) {
            fields = current.get().body();
            RecordChange.COMMON_FIELDS.forEach(fields::remove);
            fields.set(setting, value);
            change = RecordChange.next(current.get(), fields);
        } else {
            fields = AutomationSettingsService.defaults();
            fields.set(setting, value);
            change = RecordChange.create(AutomationSettingsService.TYPE, AutomationSettingsService.ID, null, fields);
        }
        return HandlerResult.builder().save(change, "AUTOMATION_SETTINGS_CHANGED", null).build();
    }
}
