package fuelprint.core.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.AutomationSettings;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.command.AutomationBus;
import fuelprint.core.command.Command;
import fuelprint.core.command.CommandBus;
import fuelprint.core.command.ReasonCode;
import fuelprint.core.command.Receipt;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import fuelprint.core.store.RecordStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AutomationSettingsTest extends CoreSpringTest {

    static final Session PRESENTER = Session.station("s-settings", "laptop-5", Set.of(Role.PRESENTER, Role.BOARD));
    static final Session ANALYST = Session.station("s-settings-2", "laptop-1", Set.of(Role.ANALYST));

    @Autowired CommandBus bus;
    @Autowired AutomationBus automation;
    @Autowired AutomationSettings settings;
    @Autowired RecordStore store;

    Receipt change(String setting, Object value, Session session) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("setting", setting);
        if (value instanceof Boolean b) {
            payload.put("value", b);
        } else if (value instanceof Integer i) {
            payload.put("value", i);
        } else {
            payload.put("value", String.valueOf(value));
        }
        Receipt receipt = bus.send(new Command(Command.SCHEMA_VERSION, UUID.randomUUID().toString(),
                ChangeAutomationHandler.TYPE, null, null, payload), session);
        bus.awaitIdle();
        return receipt;
    }

    @AfterEach
    void backToDefaults() {
        for (String name : AutomationSettingsService.SWITCHES) {
            if (!settings.isOn(name)) {
                change(name, true, PRESENTER);
            }
        }
        if (settings.autopilotWaitS() != 60) {
            change("autopilot_wait_s", 60, PRESENTER);
        }
    }

    @Test
    void oneRunIndependentRecordWithTheDefaultsExists() {
        var record = store.get(AutomationSettingsService.TYPE, AutomationSettingsService.ID).orElseThrow();
        assertThat(record.runId()).isNull();
        assertThat(store.list(AutomationSettingsService.TYPE, null)).hasSize(1);
        for (String name : AutomationSettingsService.SWITCHES) {
            assertThat(settings.isOn(name)).as(name).isTrue();
        }
        assertThat(settings.autopilotWaitS()).isEqualTo(60);
    }

    @Test
    void thePresenterSwitchesAStepOffAndOn() {
        Receipt off = change(AutomationSettings.AUTO_SEAL, false, PRESENTER);
        assertThat(off.status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(off.actor()).isEqualTo("presenter@laptop-5");
        assertThat(settings.isOn(AutomationSettings.AUTO_SEAL)).isFalse();
        assertThat(settings.isOn(AutomationSettings.AUTO_PROPOSE)).isTrue();
        assertThat(change("autopilot_wait_s", 15, PRESENTER).status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(settings.autopilotWaitS()).isEqualTo(15);
    }

    @Test
    void badSettingsWrongRolesAndAutomationAreRefused() throws Exception {
        assertThat(change("auto_everything", true, PRESENTER).reasonCode()).isEqualTo(ReasonCode.INVALID);
        assertThat(change(AutomationSettings.AUTO_SEAL, "yes", PRESENTER).reasonCode()).isEqualTo(ReasonCode.INVALID);
        assertThat(change("autopilot_wait_s", 9999, PRESENTER).reasonCode()).isEqualTo(ReasonCode.INVALID);
        assertThat(change(AutomationSettings.AUTO_SEAL, false, ANALYST).reasonCode()).isEqualTo(ReasonCode.NOT_ALLOWED);
        Receipt auto = automation.submit(ChangeAutomationHandler.TYPE, JsonNodeFactory.instance.objectNode()
                .put("setting", AutomationSettings.AUTO_SEAL).put("value", false)).get(10, TimeUnit.SECONDS);
        assertThat(auto.reasonCode()).isEqualTo(ReasonCode.NOT_ALLOWED);
        assertThat(settings.isOn(AutomationSettings.AUTO_SEAL)).isTrue();
    }

    @Test
    void aStaleSettingsRevisionIsRejected() {
        long latest = store.latestRevision(AutomationSettingsService.TYPE, AutomationSettingsService.ID);
        Receipt stale = bus.send(new Command(Command.SCHEMA_VERSION, UUID.randomUUID().toString(),
                ChangeAutomationHandler.TYPE, null, latest - 1, JsonNodeFactory.instance.objectNode()
                .put("setting", AutomationSettings.AUTO_ALERTS).put("value", false)), PRESENTER);
        assertThat(stale.reasonCode()).isEqualTo(ReasonCode.STALE_REVISION);
        assertThat(settings.isOn(AutomationSettings.AUTO_ALERTS)).isTrue();
    }
}
