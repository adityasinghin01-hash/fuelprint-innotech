package fuelprint.api;

/**
 * The result of one {@link SmsGateway#send}. Never contains the phone number.
 *
 * @param status        SENT, LOGGED_ONLY or FAILED
 * @param gatewayMode   REAL or LOG_ONLY
 * @param attempts      how many tries were made (1 plus up to 2 retries)
 * @param failureReason why it failed, or null
 */
public record SendResult(SendStatus status, GatewayMode gatewayMode, int attempts, String failureReason) {
}
