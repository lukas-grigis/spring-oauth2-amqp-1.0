package dev.lukasgrigis.blog.amqp.dispatcher.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbitmq.client.RabbitAmqpTemplate;

import java.util.Map;

public class JobPublisher {

    private static final Logger log = LoggerFactory.getLogger(JobPublisher.class);

    private final RabbitAmqpTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKey;

    public JobPublisher(
        RabbitAmqpTemplate rabbitTemplate,
        String exchange,
        String routingKey
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    public void publish(String id, Map<String, Object> payload) {
        log.info("Publishing job {} to exchange '{}' (routing key '{}')", id, exchange, routingKey);
        // RabbitAmqpTemplate is async: convertAndSend returns a CompletableFuture that completes when the
        // broker settles the message (true = accepted). We don't block the HTTP thread — the REST call already
        // returned 202 — but we log a failed settlement so an unauthorized or unroutable publish is visible.
        rabbitTemplate.convertAndSend(exchange, routingKey, payload)
            .whenComplete((accepted, throwable) -> {
                if (throwable != null) {
                    log.error("Publish of job {} failed", id, throwable);
                } else if (Boolean.FALSE.equals(accepted)) {
                    log.warn("Broker did not accept job {}", id);
                }
            });
    }

}
