package fuelprint.science.rank;

import fuelprint.api.Analysis;
import fuelprint.api.AnalysisInput;
import fuelprint.api.Ranker;
import fuelprint.api.InletHistory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The real {@link Ranker} bean (FP-COMPAT-3). Fixed nodes come from the world registry, followed by optional
 * {@link InletHistory} beans for vehicle windows. The latest fit
 * snapshots are kept in memory for the planner.
 */
@Component
@Profile("!stub")
public class ScienceRanker implements Ranker {

    private static final int SNAPSHOTS_KEPT = 64;

    private final CompatRanker ranker;
    private final InletTrack.Source inlets;
    private final Map<List<String>, FitSnapshot> snapshots = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<List<String>, FitSnapshot> eldest) {
                    return size() > SNAPSHOTS_KEPT;
                }
            });

    @Autowired
    public ScienceRanker(ObjectProvider<InletHistory> histories, ObjectProvider<InletTrack.Source> fixedInlets) {
        this(new CompatRanker(new FitEngine(WorldProfile.load(), ResponseModel.TEACHING), Cutoff.load()),
                java.util.stream.Stream.concat(fixedInlets.orderedStream(),
                        histories.orderedStream().map(InletTrack::history)).toList());
    }

    public ScienceRanker(CompatRanker ranker, List<InletTrack.Source> extraInlets) {
        this.ranker = ranker;
        List<InletTrack.Source> all = new ArrayList<>();
        all.add(InletTrack.fixedNodes(ranker.engine().world()));
        all.addAll(extraInlets);
        this.inlets = InletTrack.firstOf(all);
    }

    @Override
    public Analysis analyze(AnalysisInput input) {
        CompatRanker.Result r = ranker.analyze(input, inlets);
        if (r.snapshot() != null) {
            snapshots.put(r.analysis().inputWindowIds(), r.snapshot());
        }
        return r.analysis();
    }

    /** The fit snapshot behind an analysis made by this server, if still held. */
    public Optional<FitSnapshot> snapshotFor(Analysis analysis) {
        return Optional.ofNullable(snapshots.get(analysis.inputWindowIds()));
    }

    public CompatRanker engine() {
        return ranker;
    }
}
