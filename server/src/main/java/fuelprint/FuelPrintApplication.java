package fuelprint;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** FuelPrint server entry point. One process owns the clock, every record and every decision. */
@SpringBootApplication
public class FuelPrintApplication {

    public static void main(String[] args) {
        SpringApplication.run(FuelPrintApplication.class, args);
    }
}
