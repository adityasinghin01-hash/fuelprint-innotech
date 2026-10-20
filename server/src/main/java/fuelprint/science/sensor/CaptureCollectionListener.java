package fuelprint.science.sensor;

import com.fasterxml.jackson.databind.JsonNode;
import fuelprint.api.RecordSaved;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StoredRecord;
import fuelprint.science.world.ScienceWorld;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Routes the public committed attempt stages to the synthetic collection-fault hook; it never saves records. */
@Component
@Profile("!stub")
public class CaptureCollectionListener {
    private final RecordStore store;
    private final SensorField sensors;
    private final ScienceWorld world;

    public CaptureCollectionListener(RecordStore store, SensorField sensors, ScienceWorld world) {
        this.store = store;
        this.sensors = sensors;
        this.world = world;
    }

    @EventListener
    public void onRecordSaved(RecordSaved saved) {
        if (!"attempt".equals(saved.recordType()) || !world.runId().filter(run -> run.equals(saved.runId())).isPresent()) return;
        StoredRecord attempt = store.get("attempt", saved.recordId(), saved.revision()).orElse(null);
        if (attempt == null || !saved.runId().equals(attempt.runId())) return;
        StoredRecord mission = store.get("mission", attempt.body().path("mission_id").asText()).orElse(null);
        if (mission == null || !saved.runId().equals(mission.runId())) return;
        String vehicle = mission.body().path("vehicle_id").asText(null);
        if (vehicle == null) return;
        JsonNode collect = null;
        for (JsonNode stage : attempt.body().path("stages")) if ("COLLECT".equals(stage.path("stage").asText())) collect = stage;
        if ("COLLECTING".equals(attempt.body().path("state").asText()) && collect != null
                && collect.path("end_s").isNull() && collect.path("start_s").isNumber()
                && Double.isFinite(collect.path("start_s").asDouble())) {
            sensors.collectionStarted(vehicle, collect.path("start_s").asDouble());
        } else {
            sensors.collectionEnded(vehicle);
        }
    }
}
