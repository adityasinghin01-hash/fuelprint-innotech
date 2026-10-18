package fuelprint.core;

import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * In fake replay mode ({@code fp.mode=replay}) the stubs must be the only science, field, ai and alerts beans
 * (CORE-API section 7), so the {@code stub} profile is switched on even if another profile was asked for.
 * Registered in {@code META-INF/spring.factories}.
 */
public class ReplayModeProfiles implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!"replay".equalsIgnoreCase(environment.getProperty("fp.mode", "live").trim())) {
            return;
        }
        boolean hasStub = Arrays.asList(environment.getActiveProfiles()).contains("stub");
        if (!hasStub) {
            environment.addActiveProfile("stub");
        }
    }
}
