package fuelprint.core.command;

/** What a handler returns: either a {@link HandlerResult} to save, or a {@link Rejection}. */
public sealed interface HandlerOutcome permits HandlerResult, Rejection {
}
