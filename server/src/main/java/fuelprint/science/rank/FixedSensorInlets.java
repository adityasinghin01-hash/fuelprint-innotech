package fuelprint.science.rank;

import fuelprint.api.Enu;
import fuelprint.api.Window;
import fuelprint.core.store.RecordStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Resolves real fixed-sensor UUIDs from their public, run-scoped sensor records. */
@Component
@Profile("!stub")
public final class FixedSensorInlets implements InletTrack.Source {
    private final RecordStore records;

    public FixedSensorInlets(RecordStore records) {
        this.records = records;
    }

    @Override
    public Optional<InletTrack> track(String runId, Window window) {
        if (runId == null || !Double.isFinite(window.startS()) || !Double.isFinite(window.endS())
                || window.startS() > window.endS()) {
            return Optional.empty();
        }
        return records.get("sensor", window.sensorOrVehicleId()).filter(r -> runId.equals(r.runId())).flatMap(r -> {
            var inlet = r.body().path("inlet");
            for (String axis : List.of("east", "north", "up")) {
                if (!inlet.path(axis).isNumber() || !Double.isFinite(inlet.path(axis).asDouble())) {
                    return Optional.empty();
                }
            }
            Enu pose = new Enu(inlet.path("east").asDouble(), inlet.path("north").asDouble(),
                    inlet.path("up").asDouble());
            return Optional.of(InletTrack.fixed(pose));
        });
    }
}
