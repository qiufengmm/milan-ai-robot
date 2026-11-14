package com.milanai.robot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MilanAiRobotSpringbootApplication {

    public static void main(String[] args) {
        SpringApplication.run(MilanAiRobotSpringbootApplication.class, args);
    }

}
