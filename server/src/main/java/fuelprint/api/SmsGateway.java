package fuelprint.api;

/**
 * Alerts: sends one SMS. LOG_ONLY in tests, on teammates' laptops and in fake mode. Implementations never
 * log, store or return the phone number.
 */
public interface SmsGateway {

    /** Sends {@code text} to {@code phoneE164} (like {@code +919812345678}). */
    SendResult send(String phoneE164, String text);
}
