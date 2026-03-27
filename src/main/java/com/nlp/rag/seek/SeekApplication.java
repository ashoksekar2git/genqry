package com.nlp.rag.seek;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
public class SeekApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeekApplication.class, args);
    }
}
