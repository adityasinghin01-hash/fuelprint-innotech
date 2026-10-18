package fuelprint.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import fuelprint.core.CoreSpringTest;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** A guest session lasts until local midnight of the event day; the sign-up it owns is keyed by its session id. */
class GuestSessionExpiryTest extends CoreSpringTest {

    static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    @Autowired JdbcTemplate jdbc;

    /** A clock the test can move. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static Instant kolkata(String isoLocal) {
        return java.time.LocalDateTime.parse(isoLocal).atZone(KOLKATA).toInstant();
    }

    static String cookieValue(MockHttpServletResponse response) {
        String header = response.getHeader("Set-Cookie");
        return header.substring(header.indexOf('=') + 1, header.indexOf(';'));
    }

    static long maxAge(MockHttpServletResponse response) {
        String header = response.getHeader("Set-Cookie");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Max-Age=(\\d+)").matcher(header);
        assertThat(m.find()).isTrue();
        return Long.parseLong(m.group(1));
    }

    MockHttpServletRequest withCookie(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(CookieSessionService.COOKIE, token));
        return request;
    }

    @Test
    void aGuestCookieAndSessionEndAtLocalMidnightOfTheEventDay() {
        MutableClock clock = new MutableClock(kolkata("2026-10-22T10:15:00"));
        CookieSessionService sessions = new CookieSessionService(jdbc, clock, List.of(), "Asia/Kolkata");

        Session guest = sessions.createGuest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        sessions.writeCookie(response, guest);

        long untilMidnight = 13 * 3600 + 45 * 60; // 10:15 to 24:00
        assertThat(maxAge(response)).isEqualTo(untilMidnight);
        assertThat(response.getHeader("Set-Cookie")).contains("HttpOnly").contains("SameSite=Lax");
        String token = cookieValue(response);
        assertThat(token).isEqualTo(guest.sessionId());
        assertThat(guest.roles()).containsExactly(Role.GUEST);

        clock.advanceSeconds(untilMidnight - 1);
        assertThat(sessions.resolve(withCookie(token))).as("still valid one second before midnight").isPresent();
        clock.advanceSeconds(1);
        assertThat(sessions.resolve(withCookie(token))).as("gone at local midnight").isEmpty();
        // The server's own zone (UTC here) plays no part: 18:30 UTC is already 00:00 the next day in Kolkata.
    }

    @Test
    void aSessionMadeJustBeforeMidnightEndsAtThatMidnightNotTwelveHoursLater() {
        MutableClock clock = new MutableClock(kolkata("2026-10-22T23:50:00"));
        CookieSessionService sessions = new CookieSessionService(jdbc, clock, List.of(), "Asia/Kolkata");
        Session guest = sessions.createGuest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        sessions.writeCookie(response, guest);
        assertThat(maxAge(response)).isEqualTo(10 * 60);
        clock.advanceSeconds(10 * 60);
        assertThat(sessions.resolve(withCookie(guest.sessionId()))).isEmpty();
    }

    @Test
    void theZoneDecidesWhereMidnightIsAndOtherSessionKindsKeepTheirOwnLifetimes() {
        Instant instant = kolkata("2026-10-22T10:15:00");
        MutableClock clock = new MutableClock(instant);
        CookieSessionService utc = new CookieSessionService(jdbc, clock, List.of(), "UTC");
        // 04:45 UTC: 19 h 15 min to UTC midnight.
        assertThat(utc.untilLocalMidnightMs()).isEqualTo((19 * 3600 + 15 * 60) * 1000L);
        CookieSessionService kolkata = new CookieSessionService(jdbc, clock, List.of(), "Asia/Kolkata");
        assertThat(kolkata.untilLocalMidnightMs()).isEqualTo((13 * 3600 + 45 * 60) * 1000L);

        Session station = kolkata.createStation("laptop-1", Set.of("analyst"));
        Session visitor = kolkata.createVisitor("visitor-7f3a");
        clock.advanceSeconds(3 * 3600);
        assertThat(kolkata.resolve(withCookie(station.sessionId()))).as("station: 12 h").isPresent();
        assertThat(kolkata.resolve(withCookie(visitor.sessionId()))).as("visitor: 2 h").isEmpty();
    }
}
