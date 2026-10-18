package fuelprint.api;

/** The automatic pre-check result of one capture channel (same values as {@code screening_record.decision}). */
public enum ChannelStatus {
    VALID,
    LIMITED,
    REJECTED
}
