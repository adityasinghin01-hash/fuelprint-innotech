package fuelprint.api;

/** Science: the automatic pre-check of one capture channel. */
public interface CaptureRules {

    /** The verdict for one channel. A person still reviews it. */
    ChannelVerdict judge(CaptureInput input);
}
