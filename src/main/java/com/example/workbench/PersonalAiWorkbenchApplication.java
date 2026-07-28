package com.example.workbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PersonalAiWorkbenchApplication {

    public static void main(String[] args) {
        SpringApplication.run(PersonalAiWorkbenchApplication.class, args);
    }
}
