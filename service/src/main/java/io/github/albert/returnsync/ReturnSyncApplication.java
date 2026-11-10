package io.github.albert.returnsync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ReturnSyncApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReturnSyncApplication.class, args);
    }
}
