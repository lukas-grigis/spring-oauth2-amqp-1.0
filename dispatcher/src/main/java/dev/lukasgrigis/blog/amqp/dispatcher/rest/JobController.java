package dev.lukasgrigis.blog.amqp.dispatcher.rest;

import dev.lukasgrigis.blog.amqp.dispatcher.messaging.JobPublisher;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/jobs")
public class JobController {

    private final JobPublisher publisher;

    public JobController(JobPublisher publisher) {
        this.publisher = publisher;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JobResponse> submit(@Valid @RequestBody JobRequest request) {
        final var id = UUID.randomUUID().toString();
        publisher.publish(id, Map.of("id", id, "payload", request.payload()));
        return ResponseEntity.accepted().body(new JobResponse(id, "accepted"));
    }

}
