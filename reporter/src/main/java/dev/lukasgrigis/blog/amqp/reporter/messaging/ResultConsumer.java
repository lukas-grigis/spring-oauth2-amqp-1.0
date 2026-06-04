package dev.lukasgrigis.blog.amqp.reporter.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.util.Map;

public class ResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(ResultConsumer.class);

    @RabbitListener(queues = "${app.amqp.queues.results-out}", autoStartup = "${app.amqp.listener.auto-startup:true}")
    public void handle(Map<String, Object> result) {
        log.info("Received result: id={} result={}", result.get("id"), result.get("result"));
    }

}
