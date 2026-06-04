package dev.lukasgrigis.blog.amqp.reporter;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

// Offline smoke test: verifies the Spring context (beans + validated AmqpProperties) wires without a broker.
// Listener auto-startup is disabled so the @RabbitListener container does not try to connect on context load,
// and the AMQP 1.0 Environment/connection factory connect lazily — so no broker or Keycloak is required.
// The live broker authorization is exercised end-to-end by the integration suite in test/ (mise run test:integration).
@SpringBootTest(properties = "app.amqp.listener.auto-startup=false")
class ReporterApplicationTest {

    private final ApplicationContext context;

    @Autowired
    ReporterApplicationTest(ApplicationContext context) {
        this.context = context;
    }

    @Test
    @DisplayName("Context successfully loads")
    void contextSuccessfullyLoads() {
        Assertions.assertNotNull(context);
    }

}
