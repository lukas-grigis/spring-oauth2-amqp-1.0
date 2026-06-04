package dev.lukasgrigis.blog.amqp.dispatcher;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

// Offline smoke test: verifies the Spring context (beans + validated AmqpProperties) wires without a broker.
// The AMQP 1.0 Environment/connection factory and RabbitAmqpTemplate connect lazily (on first publish), so the
// context loads without reaching Keycloak or RabbitMQ. The live broker authorization is exercised end-to-end by
// the integration suite in test/ (mise run test:integration).
@SpringBootTest
class DispatcherApplicationTest {

    private final ApplicationContext context;

    @Autowired
    DispatcherApplicationTest(ApplicationContext context) {
        this.context = context;
    }

    @Test
    @DisplayName("Context successfully loads")
    void contextSuccessfullyLoads() {
        Assertions.assertNotNull(context);
    }

}
