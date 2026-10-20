package fuelprint.api;

import java.util.List;

/** Science: the automatic pre-check of one capture channel. */
public interface CaptureRules {

    /** The verdict for one channel. A person still reviews it. */
    ChannelVerdict judge(CaptureInput input);

    /** Measured background and collection windows; a person still decides whether to admit them. */
    default List<Window> windows(CaptureInput input) { return List.of(); }
}
