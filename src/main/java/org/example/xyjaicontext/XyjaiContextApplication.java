package org.example.xyjaicontext;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@EnableRetry
@SpringBootApplication
public class XyjaiContextApplication {

    public static void main(String[] args) {
        SpringApplication.run(XyjaiContextApplication.class, args);
    }

}
