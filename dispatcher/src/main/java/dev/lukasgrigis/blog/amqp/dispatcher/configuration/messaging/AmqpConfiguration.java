package dev.lukasgrigis.blog.amqp.dispatcher.configuration.messaging;

import dev.lukasgrigis.blog.amqp.dispatcher.messaging.JobPublisher;
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
 * Typed, validated binding for the dispatcher's OAuth2 credentials and messaging topology (prefix
 * {@code app.amqp}); host, port and virtual host are Boot's {@code spring.amqp.rabbitmq.*}.
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

    @NotNull @Valid OAuth2 oauth2,
    @DefaultValue @Valid Exchanges exchanges,
    @DefaultValue @Valid RoutingKeys routingKeys
) {

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
 * Adds the OAuth2 settings to the {@link com.rabbitmq.client.amqp.Environment} that Spring Boot
 * auto-configures from {@code spring.amqp.rabbitmq.*}: the RabbitMQ AMQP 1.0 client fetches a
 * {@code client_credentials} token from Keycloak, uses it as the AMQP SASL credential, and
 * <strong>refreshes it in place on the live connection</strong> (AMQP 1.0 {@code PUT /auth/tokens}) at ~80% of
 * its lifetime. Over AMQP 1.0 the broker <em>disconnects</em> a client whose token expires, so this proactive
 * refresh is what keeps a long-lived connection alive. Boot has no OAuth2 property for the AMQP 1.0 client, so
 * this stays hand-wired.
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
                // Keycloak alias scope; the broker's scope_aliases expand it to rabbitmq.write:*/jobs/job.*
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
    JobPublisher jobPublisher(RabbitAmqpTemplate rabbitAmqpTemplate, AmqpProperties properties) {
        return new JobPublisher(
            rabbitAmqpTemplate,
            properties.exchanges().jobs(),
            properties.routingKeys().jobSubmitted()
        );
    }

}
