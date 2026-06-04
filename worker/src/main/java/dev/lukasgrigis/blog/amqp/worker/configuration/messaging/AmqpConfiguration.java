package dev.lukasgrigis.blog.amqp.worker.configuration.messaging;

import com.rabbitmq.client.amqp.Environment;
import com.rabbitmq.client.amqp.impl.AmqpEnvironmentBuilder;
import dev.lukasgrigis.blog.amqp.worker.messaging.JobConsumer;
import dev.lukasgrigis.blog.amqp.worker.messaging.ResultPublisher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.annotation.RabbitListenerAnnotationBeanPostProcessor;
import org.springframework.amqp.rabbitmq.client.AmqpConnectionFactory;
import org.springframework.amqp.rabbitmq.client.RabbitAmqpTemplate;
import org.springframework.amqp.rabbitmq.client.config.RabbitAmqpListenerContainerFactory;
import org.springframework.amqp.rabbitmq.client.SingleAmqpConnectionFactory;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated binding for the worker's AMQP 1.0 connection, OAuth2 credentials, and messaging topology
 * (prefix {@code app.amqp}).
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

    @DefaultValue @Valid Connection connection,
    @NotNull @Valid OAuth2 oauth2,
    @DefaultValue @Valid Exchanges exchanges,
    @DefaultValue @Valid Queues queues,
    @DefaultValue @Valid RoutingKeys routingKeys
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

    record Exchanges(@DefaultValue("results") @NotBlank String results) {

    }

    record Queues(@DefaultValue("jobs.in") @NotBlank String jobsIn) {

    }

    record RoutingKeys(@DefaultValue("result.ready") @NotBlank String resultReady) {

    }

}

/**
 * Wires the AMQP 1.0 client stack by hand — there is no Spring Boot auto-configuration for AMQP 1.0.
 *
 * <p>The {@link Environment}'s {@code oauth2()} settings make the RabbitMQ AMQP 1.0 client fetch a
 * {@code client_credentials} token from Keycloak and use it as the AMQP SASL credential, then
 * <strong>refresh it in place on the live connection</strong> (AMQP 1.0 {@code PUT /auth/tokens}) at ~80% of
 * its lifetime — so this long-lived consumer is never disconnected when its original token expires. Over AMQP
 * 1.0 an expired token <em>drops</em> the connection, which is exactly what this proactive refresh prevents.
 *
 * <p>{@link SingleAmqpConnectionFactory} adapts the {@link Environment} to Spring AMQP; the
 * {@link RabbitAmqpListenerContainerFactory} (registered under the default bean name so {@code @RabbitListener}
 * uses it) drives {@link JobConsumer}, and the {@link RabbitAmqpTemplate} publishes results — both using the
 * {@link MessageConverter} below. {@code @EnableRabbit} activates {@code @RabbitListener} processing, which
 * Boot's (absent) AMQP auto-configuration would otherwise have switched on.
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
                    // Keycloak alias scopes; the broker expands them to read jobs.in + write result.* to results
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
    RabbitAmqpTemplate rabbitAmqpTemplate(AmqpConnectionFactory connectionFactory, MessageConverter messageConverter) {
        final var template = new RabbitAmqpTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
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
