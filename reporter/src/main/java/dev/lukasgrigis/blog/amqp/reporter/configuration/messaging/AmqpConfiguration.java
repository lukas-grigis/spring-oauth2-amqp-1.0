package dev.lukasgrigis.blog.amqp.reporter.configuration.messaging;

import com.rabbitmq.client.amqp.Environment;
import com.rabbitmq.client.amqp.impl.AmqpEnvironmentBuilder;
import dev.lukasgrigis.blog.amqp.reporter.messaging.ResultConsumer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.annotation.RabbitListenerAnnotationBeanPostProcessor;
import org.springframework.amqp.rabbitmq.client.AmqpConnectionFactory;
import org.springframework.amqp.rabbitmq.client.SingleAmqpConnectionFactory;
import org.springframework.amqp.rabbitmq.client.config.RabbitAmqpListenerContainerFactory;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated binding for the reporter's AMQP 1.0 connection, OAuth2 credentials, and the one queue it
 * reads from (prefix {@code app.amqp}).
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

    @DefaultValue @Valid Connection connection,
    @NotNull @Valid OAuth2 oauth2,
    @DefaultValue @Valid Queues queues
) {

    record Connection(
        @DefaultValue("localhost") @NotBlank String host,
        @DefaultValue("5672") int port,
        @DefaultValue("/") @NotBlank String virtualHost
    ) {

    }

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
 * Wires the AMQP 1.0 client stack by hand — there is no Spring Boot auto-configuration for AMQP 1.0.
 *
 * <p>The {@link Environment}'s {@code oauth2()} settings make the RabbitMQ AMQP 1.0 client fetch a
 * {@code client_credentials} token from Keycloak and refresh it in place on the live connection (AMQP 1.0
 * {@code PUT /auth/tokens}) at ~80% of its lifetime — so this long-lived consumer is never disconnected when
 * its token expires.
 *
 * <p>There is deliberately no {@link org.springframework.amqp.rabbitmq.client.RabbitAmqpTemplate} here — the
 * reporter's token carries {@code results_read} only, so the broker would refuse any publish anyway (the
 * integration test proves exactly that). Only the {@link RabbitAmqpListenerContainerFactory} that drives
 * {@link ResultConsumer} is wired, using the {@link MessageConverter} below. {@code @EnableRabbit} activates
 * {@code @RabbitListener} processing.
 */
@EnableRabbit
@Configuration
@EnableConfigurationProperties(AmqpProperties.class)
class AmqpConfiguration {

    @Bean(destroyMethod = "close")
    Environment amqpEnvironment(AmqpProperties properties) {
        final var connection = properties.connection();
        final var oauth2 = properties.oauth2();
        return new AmqpEnvironmentBuilder()
            .connectionSettings()
                .host(connection.host())
                .port(connection.port())
                .virtualHost(connection.virtualHost())
                .oauth2()
                    .tokenEndpointUri(oauth2.tokenUri())
                    .clientId(oauth2.clientId())
                    .clientSecret(oauth2.clientSecret())
                    .grantType("client_credentials")
                    // Keycloak alias scope; the broker expands it to read results.out only — no write anywhere
                    .parameter("scope", oauth2.scope())
                    .shared(true)
                .connection()
            .environmentBuilder()
            .build();
    }

    @Bean
    AmqpConnectionFactory connectionFactory(Environment environment) {
        return new SingleAmqpConnectionFactory(environment);
    }

    @Bean
    MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean(RabbitListenerAnnotationBeanPostProcessor.DEFAULT_RABBIT_LISTENER_CONTAINER_FACTORY_BEAN_NAME)
    RabbitAmqpListenerContainerFactory rabbitAmqpListenerContainerFactory(
        AmqpConnectionFactory connectionFactory,
        MessageConverter messageConverter
    ) {
        final var factory = new RabbitAmqpListenerContainerFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        return factory;
    }

    @Bean
    ResultConsumer resultConsumer() {
        return new ResultConsumer();
    }

}
