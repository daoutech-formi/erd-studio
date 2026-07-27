package com.daou.erdstudio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ErdStudioApplication {

    public static void main(String[] args) {
        SpringApplication.run(ErdStudioApplication.class, args);
    }
}
