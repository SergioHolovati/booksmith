package com.booksmith;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BooksmithApplication {

    public static void main(String[] args) {
        SpringApplication.run(BooksmithApplication.class, args);
    }
}
