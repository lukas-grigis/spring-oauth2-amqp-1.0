package dev.lukasgrigis.blog.amqp.worker.configuration.messaging;

import dev.lukasgrigis.blog.amqp.worker.messaging.JobConsumer;
import dev.lukasgrigis.blog.amqp.worker.messaging.ResultPublisher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.amqp.rabbitmq.client.RabbitAmqpTemplate;
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
 * Typed, validated binding for the worker's OAuth2 credentials and messaging topology
 * (prefix {@code app.amqp}); host, port and virtual host are Boot's
 * {@code spring.amqp.rabbitmq.*}.
 *
 * <p>The worker reads from the {@code jobs.in} queue and publishes to the {@code results} exchange with a
 * {@code result.*} routing key. The {@code oauth2} block authenticates the broker connection; the AMQP 1.0
 * client fetches and refreshes the {@code client_credentials} token itself (see {@link AmqpConfiguration}).
 * Defaults live here via {@link DefaultValue} and are validated on startup. The {@code @RabbitListener}
 * resolves the queue name straight from the {@code app.amqp.queues.jobs-in} placeholder.
 */
@Validated
@ConfigurationProperties(prefix = "app.amqp")
record AmqpProperties(

    @NotNull @Valid OAuth2 oauth2,
    @DefaultValue @Valid Exchanges exchanges,
    @DefaultValue @Valid Queues queues,
    @DefaultValue @Valid RoutingKeys routingKeys
) {

    record OAuth2(
        @NotBlank String tokenUri,
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank String scope
    ) {

    }

    record Exchanges(@DefaultValue("results") @NotBlank String results) {

    }

    record Queues(@DefaultValue("jobs.in") @NotBlank String jobsIn) {

    }

    record RoutingKeys(@DefaultValue("result.ready") @NotBlank String resultReady) {

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
 * <p>The listener container factory, the template and {@code @EnableRabbit} are Boot's.
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
                // Keycloak alias scopes; the broker expands them to read jobs.in + write result.* to results
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
    ResultPublisher resultPublisher(RabbitAmqpTemplate rabbitAmqpTemplate, AmqpProperties properties) {
        return new ResultPublisher(
            rabbitAmqpTemplate,
            properties.exchanges().results(),
            properties.routingKeys().resultReady()
        );
    }

    @Bean
    JobConsumer jobConsumer(ResultPublisher resultPublisher) {
        return new JobConsumer(resultPublisher);
    }

}
