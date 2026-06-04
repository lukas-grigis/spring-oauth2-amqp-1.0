package dev.lukasgrigis.blog.amqp.dispatcher.configuration.messaging;

import com.rabbitmq.client.amqp.Environment;
import com.rabbitmq.client.amqp.impl.AmqpEnvironmentBuilder;
import dev.lukasgrigis.blog.amqp.dispatcher.messaging.JobPublisher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.amqp.rabbitmq.client.AmqpConnectionFactory;
import org.springframework.amqp.rabbitmq.client.RabbitAmqpTemplate;
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
 * Typed, validated binding for the dispatcher's AMQP 1.0 connection, OAuth2 credentials, and messaging
 * topology (prefix {@code app.amqp}).
 *
 * <p>The {@code oauth2} block is what authenticates the broker connection: the RabbitMQ AMQP 1.0 client
 * obtains a {@code client_credentials} token from Keycloak itself (see {@link AmqpConfiguration}), so there
 * is no Spring Security {@code OAuth2AuthorizedClientManager} here. The target exchange and routing key live
 * alongside it with safe defaults baked in via {@link DefaultValue}, and everything is validated on startup
 * so a blank override fails fast instead of misrouting at runtime.
 */
@Validated
@ConfigurationProperties(prefix = "app.amqp")
record AmqpProperties(

    @DefaultValue @Valid Connection connection,
    @NotNull @Valid OAuth2 oauth2,
    @DefaultValue @Valid Exchanges exchanges,
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

    record Exchanges(@DefaultValue("jobs") @NotBlank String jobs) {

    }

    record RoutingKeys(@DefaultValue("job.submitted") @NotBlank String jobSubmitted) {

    }

}

/**
 * Wires the AMQP 1.0 client stack by hand — there is no Spring Boot auto-configuration for AMQP 1.0 (the
 * {@code spring.rabbitmq.*} properties and {@code ConnectionFactoryCustomizer} apply only to the legacy 0.9.1
 * {@code ConnectionFactory}).
 *
 * <p>The {@link Environment} is the single most important bean: its {@code oauth2()} settings tell the
 * RabbitMQ AMQP 1.0 client to fetch a {@code client_credentials} access token from Keycloak and use it as the
 * AMQP SASL credential. The client then <strong>refreshes the token in place on the live connection</strong>
 * (via the AMQP 1.0 {@code PUT /auth/tokens} management request) at ~80% of its lifetime — the native
 * successor to the 0.9.1 {@code update-secret} + {@code CredentialsRefreshService} dance. Over AMQP 1.0 the
 * broker <em>disconnects</em> a client whose token expires, so this proactive refresh is what keeps a
 * long-lived publishing connection alive.
 *
 * <p>{@link SingleAmqpConnectionFactory} adapts that {@link Environment} to Spring AMQP's
 * {@link AmqpConnectionFactory}, and {@link RabbitAmqpTemplate} publishes through it using the familiar
 * exchange/routing-key API (Spring encodes the AMQP 1.0 v2 target address {@code /exchanges/jobs/job.*}
 * internally) with JSON conversion via the {@link MessageConverter} below.
 */
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
                    // Keycloak alias scope; the broker's scope_aliases expand it to rabbitmq.write:*/jobs/job.*
                    .parameter("scope", oauth2.scope())
                    // Share one token (and its refresh schedule) across all connections in this Environment.
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

    @Bean
    RabbitAmqpTemplate rabbitAmqpTemplate(AmqpConnectionFactory connectionFactory, MessageConverter messageConverter) {
        final var template = new RabbitAmqpTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }

    @Bean
    JobPublisher jobPublisher(RabbitAmqpTemplate rabbitAmqpTemplate, AmqpProperties properties) {
        return new JobPublisher(
            rabbitAmqpTemplate,
            properties.exchanges().jobs(),
            properties.routingKeys().jobSubmitted()
        );
    }

}
