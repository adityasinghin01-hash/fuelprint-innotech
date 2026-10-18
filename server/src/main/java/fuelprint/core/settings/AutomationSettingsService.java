package fuelprint.core.settings;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.AutomationSettings;
import fuelprint.api.RecordSaved;
import fuelprint.core.command.CommandBus;
import fuelprint.core.command.HandlerResult;
import fuelprint.core.command.Session;
import fuelprint.core.store.RecordChange;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StoredRecord;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The one {@code automation_settings} record (AGREEMENT section 6, not tied to a run) and the
 * {@link AutomationSettings} view of it. Made at start-up with the defaults (every step on, wait 60 s); changed
 * only by {@code change_automation}.
 */
@Component
@Order(1)
public class AutomationSettingsService implements AutomationSettings, ApplicationRunner {

    public static final String TYPE = "automation_settings";
    /** The fixed id of the single settings record (the fixture's id). */
    public static final String ID = "3390ed7e-d71d-5b19-b1ee-85438491991d";
    public static final String AUTOPILOT_WAIT_S = "autopilot_wait_s";
    public static final List<String> SWITCHES = List.of(AUTO_PROPOSE, AUTO_AUTOPILOT, AUTO_CAPTURE, AUTO_SEAL,
            AUTO_ALERTS, AUTO_RETRAIN);
    public static final int DEFAULT_WAIT_S = 60;
    public static final int MAX_WAIT_S = 600;

    private final RecordStore store;
    private final CommandBus bus;
    private volatile ObjectNode current;

    public AutomationSettingsService(RecordStore store, CommandBus bus) {
        this.store = store;
        this.bus = bus;
    }

    /** The default fields: every automatic step on, autopilot wait 60 s. */
    public static ObjectNode defaults() {
        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        SWITCHES.forEach(name -> fields.put(name, true));
        fields.put(AUTOPILOT_WAIT_S, DEFAULT_WAIT_S);
        return fields;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (store.get(TYPE, ID).isEmpty()) {
            bus.saveWithoutCommand(Session.AUTOMATION_ACTOR, HandlerResult.builder()
                    .save(RecordChange.create(TYPE, ID, null, defaults()), "AUTOMATION_SETTINGS_CREATED", null)
                    .build(), false).get(30, TimeUnit.SECONDS);
        }
        reload();
    }

    @EventListener
    public void on(RecordSaved saved) {
        if (TYPE.equals(saved.recordType())) {
            reload();
        }
    }

    private void reload() {
        current = store.get(TYPE, ID).map(StoredRecord::body).orElse(null);
    }

    /** The settings record, if made. */
    public Optional<StoredRecord> record() {
        return store.get(TYPE, ID);
    }

    @Override
    public boolean isOn(String setting) {
        if (!SWITCHES.contains(setting)) {
            throw new IllegalArgumentException("Unknown automation setting " + setting);
        }
        ObjectNode settings = current;
        return settings == null || settings.path(setting).asBoolean(true);
    }

    @Override
    public int autopilotWaitS() {
        ObjectNode settings = current;
        return settings == null ? DEFAULT_WAIT_S : settings.path(AUTOPILOT_WAIT_S).asInt(DEFAULT_WAIT_S);
    }
}
