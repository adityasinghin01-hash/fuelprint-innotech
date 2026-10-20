package fuelprint.science.sensor;

import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.RecordSaved;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StoredRecord;
import fuelprint.science.world.ScienceWorld;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Boundary tests of committed record routing; real full-loop sensor behavior is checked by the integration harness. */
class CaptureCollectionListenerTest {
    private final RecordStore store = mock(RecordStore.class);
    private final SensorField sensors = mock(SensorField.class);
    private final ScienceWorld world = mock(ScienceWorld.class);

    private StoredRecord attempt(boolean open) {
        ObjectNode body = JsonNodeFactory.instance.objectNode().put("mission_id", "mission").put("state", open ? "COLLECTING" : "COMPLETE");
        ObjectNode stage = body.putArray("stages").addObject().put("stage", "COLLECT").put("start_s", 210);
        if (open) stage.putNull("end_s"); else stage.put("end_s", 240);
        return new StoredRecord("attempt", "attempt", open ? 2 : 3, "current", "now", "automation", body);
    }

    private CaptureCollectionListener listener(String missionRun) {
        when(world.runId()).thenReturn(Optional.of("current"));
        when(store.get("mission", "mission")).thenReturn(Optional.of(new StoredRecord("mission", "mission", 1, missionRun,
                "now", "analyst", JsonNodeFactory.instance.objectNode().put("vehicle_id", "vehicle"))));
        return new CaptureCollectionListener(store, sensors, world);
    }

    @Test void armsTheExactCommittedCollectRevisionEvenWhenLatestIsClosed() {
        var listener = listener("current");
        when(store.get("attempt", "attempt", 2)).thenReturn(Optional.of(attempt(true)));
        when(store.get("attempt", "attempt")).thenReturn(Optional.of(attempt(false)));
        listener.onRecordSaved(new RecordSaved("attempt", "attempt", 2, "current", "automation", true));
        verify(sensors).collectionStarted("vehicle", 210);
        verifyNoMoreInteractions(sensors);
    }

    @Test void closedCollectionClearsTheFaultHookForRecoveryOrLaterAttempts() {
        var listener = listener("current");
        when(store.get("attempt", "attempt", 3)).thenReturn(Optional.of(attempt(false)));
        listener.onRecordSaved(new RecordSaved("attempt", "attempt", 3, "current", "automation", true));
        verify(sensors).collectionEnded("vehicle");
        verifyNoMoreInteractions(sensors);
    }

    @Test void eventsFromOtherRunsCannotArmTheCurrentInstrument() {
        var listener = listener("current");
        listener.onRecordSaved(new RecordSaved("attempt", "attempt", 2, "old", "automation", true));
        verifyNoInteractions(sensors);
    }

    @Test void missionFromAnotherRunCannotArmACollection() {
        var listener = listener("other");
        when(store.get("attempt", "attempt", 2)).thenReturn(Optional.of(attempt(true)));
        listener.onRecordSaved(new RecordSaved("attempt", "attempt", 2, "current", "automation", true));
        verifyNoInteractions(sensors);
    }
}
