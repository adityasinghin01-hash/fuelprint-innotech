package fuelprint.field;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.AutomationSettings;
import fuelprint.api.RecordSaved;
import fuelprint.api.RunEnded;
import fuelprint.api.RunPaused;
import fuelprint.api.RunStarted;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StoredRecord;
import fuelprint.field.command.FieldRecords;
import fuelprint.field.command.MissionHandlers;
import fuelprint.field.world.FieldWorld;
import fuelprint.field.world.MissionView;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the field world in step with saved records and runs (CORE-API sections 1 and 4). It never saves a
 * record: automatic steps go through {@link FieldAutomation}.
 * <ul>
 *   <li>{@code mission}, {@code lease}, {@code attempt}, {@code pairing} saved: the world mirrors the revision.</li>
 *   <li>A mission reaches AT_TARGET and {@code auto_capture} is on: automation sends {@code start_capture}.</li>
 *   <li>{@code RunStarted}: the drone is parked at the depot with a full battery.</li>
 *   <li>{@code RunEnded} (any reason): the drone stops and every lease is dropped at once; automation then sends
 *       {@code interrupt_field} to write the INTERRUPTED and REVOKED revisions.</li>
 * </ul>
 */
@Component
@Profile("!stub")
public class FieldRecordListener {

    private final FieldWorld world;
    private final RecordStore store;
    private final FieldPorts ports;
    private final FieldAutomation automation;

    public FieldRecordListener(FieldWorld world, RecordStore store, FieldPorts ports, FieldAutomation automation) {
        this.world = world;
        this.store = store;
        this.ports = ports;
        this.automation = automation;
    }

    @EventListener
    public void onRecordSaved(RecordSaved saved) {
        String type = saved.recordType();
        if (!FieldRecords.MISSION.equals(type) && !FieldRecords.LEASE.equals(type)
                && !FieldRecords.ATTEMPT.equals(type) && !FieldRecords.PAIRING.equals(type)) {
            return;
        }
        StoredRecord record = store.get(type, saved.recordId(), saved.revision()).orElse(null);
        if (record == null) {
            return;
        }
        switch (type) {
            case FieldRecords.MISSION -> {
                world.onMissionSaved(record);
                autoCapture(record);
            }
            case FieldRecords.LEASE -> world.onLeaseSaved(record);
            case FieldRecords.ATTEMPT -> world.onAttemptSaved(record);
            default -> world.onPairingSaved(record);
        }
    }

    private void autoCapture(StoredRecord record) {
        MissionView mission = MissionView.of(record).orElse(null);
        if (mission == null || !MissionView.AT_TARGET.equals(mission.state())
                || !world.droneId().equals(mission.vehicleId())
                || !ports.isOn(AutomationSettings.AUTO_CAPTURE)) {
            return;
        }
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("mission_id", mission.id());
        payload.put("method_id", mission.methodId());
        ArrayNode channels = payload.putArray("channel_ids");
        mission.channelIds().forEach(channels::add);
        automation.submit("start_capture", mission.runId(), payload);
    }

    @EventListener
    public void onRunStarted(RunStarted started) {
        world.startRun(started.runId());
    }

    @EventListener
    public void onRunPaused(RunPaused paused) {
        world.pause();
    }

    @EventListener
    public void onRunEnded(RunEnded ended) {
        world.endRun(ended.runId());
        if (MissionHandlers.InterruptField.hasOpenRecords(store, ended.runId())) {
            ObjectNode payload = JsonNodeFactory.instance.objectNode()
                    .put("run_id", ended.runId()).put("reason", ended.reason().name());
            automation.submit("interrupt_field", ended.runId(), payload);
        }
    }
}
