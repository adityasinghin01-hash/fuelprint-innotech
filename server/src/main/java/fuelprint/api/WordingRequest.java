package fuelprint.api;

/**
 * A request for plain words, for {@link WordingService#write}. Only public record text: never hidden
 * truth and never a phone number.
 *
 * @param target     the record revision to describe
 * @param purpose    what the text is for
 * @param publicText the public text of the target record
 */
public record WordingRequest(Ref target, WordingPurpose purpose, String publicText) {
}
