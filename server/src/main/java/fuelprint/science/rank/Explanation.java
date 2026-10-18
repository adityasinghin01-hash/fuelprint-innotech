package fuelprint.science.rank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A candidate explanation: BACKGROUND (no registered source), a single source, or a pair of sources. */
public record Explanation(String id, List<String> sources) {

    public static final String BACKGROUND = "BACKGROUND";

    public Explanation {
        sources = List.copyOf(sources);
    }

    public boolean isPair() {
        return sources.size() == 2;
    }

    public boolean isSingle() {
        return sources.size() == 1;
    }

    /** BACKGROUND, then the 6 singles, then the 15 pairs, in registry order. */
    public static List<Explanation> enumerate(List<String> sourceIds) {
        List<Explanation> list = new ArrayList<>();
        list.add(new Explanation(BACKGROUND, List.of()));
        for (String s : sourceIds) {
            list.add(new Explanation(s, List.of(s)));
        }
        for (int i = 0; i < sourceIds.size(); i++) {
            for (int j = i + 1; j < sourceIds.size(); j++) {
                list.add(of(List.of(sourceIds.get(i), sourceIds.get(j))));
            }
        }
        return Collections.unmodifiableList(list);
    }

    public static Explanation of(List<String> sources) {
        if (sources.isEmpty()) {
            return new Explanation(BACKGROUND, List.of());
        }
        return new Explanation(String.join("+", sources), sources);
    }
}
