package fuelprint.api;

/** Points at one saved record revision, like {@code wording.target} or {@code notification.cause}. */
public record Ref(String type, String id, long revision) {
}
