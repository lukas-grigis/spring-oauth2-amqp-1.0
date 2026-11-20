package dev.lukasgrigis.blog.amqp.reporter.configuration.messaging;

import dev.lukasgrigis.blog.amqp.reporter.messaging.ResultConsumer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.rabbitmq.autoconfigure.AmqpEnvironmentCustomizer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated binding for the reporter's OAuth2 credentials and the one queue it reads from (prefix
 * {@code app.amqp}); host, port and virtual host are Boot's {@code spring.amqp.rabbitmq.*}.
 *
 * <p>The reporter only ever reads from the {@code results.out} queue — it has no exchange or routing key
 * because it cannot publish (its token carries {@code results_read} only). The {@code oauth2} block
 * authenticates the broker connection; the AMQP 1.0 client fetches + refreshes the token itself. The
 * {@code @RabbitListener} resolves the queue name straight from the {@code app.amqp.queues.results-out}
 * placeholder.
 */
@Validated
@ConfigurationProperties(prefix = "app.amqp")
record AmqpProperties(

    @NotNull @Valid OAuth2 oauth2,
    @DefaultValue @Valid Queues queues
) {

    record OAuth2(
        @NotBlank String tokenUri,
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank String scope
    ) {

    }

    record Queues(@DefaultValue("results.out") @NotBlank String resultsOut) {

    }

}

/**
 * Adds the OAuth2 settings to the {@link com.rabbitmq.client.amqp.Environment} that Spring Boot
 * auto-configures from {@code spring.amqp.rabbitmq.*}: the RabbitMQ AMQP 1.0 client fetches a
 * {@code client_credentials} token from Keycloak, uses it as the AMQP SASL credential, and
 * <strong>refreshes it in place on the live connection</strong> (AMQP 1.0 {@code PUT /auth/tokens}) at ~80% of
 * its lifetime. Over AMQP 1.0 the broker <em>disconnects</em> a client whose token expires, so this proactive
 * refresh is what keeps a long-lived connection alive. Boot has no OAuth2 property for the AMQP 1.0 client, so
 * this stays hand-wired.
 *
 * <p>There is deliberately no publisher here — the token carries {@code results_read} only, so the broker
 * would refuse any publish (the integration test proves exactly that).
 */
@Configuration
@EnableConfigurationProperties(AmqpProperties.class)
class AmqpConfiguration {

    @Bean
    AmqpEnvironmentCustomizer oauth2Customizer(AmqpProperties properties) {
        final var oauth2 = properties.oauth2();
        return builder -> builder.connectionSettings()
            .oauth2()
                .tokenEndpointUri(oauth2.tokenUri())
                .clientId(oauth2.clientId())
                .clientSecret(oauth2.clientSecret())
                .grantType("client_credentials")
                // Keycloak alias scope; the broker expands it to read results.out only — no write anywhere
                .parameter("scope", oauth2.scope())
                // Share one token (and its refresh schedule) across all connections in this Environment.
                .shared(true);
    }

    // Boot attaches a MessageConverter bean to its RabbitAmqpTemplate and listener container factory.
    @Bean
    MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    ResultConsumer resultConsumer() {
        return new ResultConsumer();
    }

}
