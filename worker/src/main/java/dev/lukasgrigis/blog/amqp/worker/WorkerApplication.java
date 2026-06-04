package dev.lukasgrigis.blog.amqp.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WorkerApplication {

    static void main() {
        SpringApplication.run(WorkerApplication.class);
    }

}
