package com.foodflow.platform.autoconfigure;

import com.foodflow.events.AvroClassTrust;
import com.foodflow.platform.outbox.AvroPayloadCodec;
import com.foodflow.platform.outbox.JdbcOutboxStore;
import com.foodflow.platform.outbox.JdbcOutboxWriter;
import com.foodflow.platform.outbox.MicrometerTraceparentProvider;
import com.foodflow.platform.outbox.OutboxProperties;
import com.foodflow.platform.outbox.OutboxRelay;
import com.foodflow.platform.outbox.OutboxWriter;
import com.foodflow.platform.outbox.TraceparentProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Transactional outbox (ADR-0004). Opt-in with {@code foodflow.outbox.enabled=true}; requires a
 * PostgreSQL {@link DataSource}, Kafka and the event contracts on the classpath, plus the
 * {@code outbox_events} table in the service's own migrations (see {@link JdbcOutboxWriter}).
 */
@AutoConfiguration(
        after = {DataSourceAutoConfiguration.class, KafkaAutoConfiguration.class, TransactionAutoConfiguration.class,
                FoodflowWebAutoConfiguration.class},
        afterName = "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration")
@ConditionalOnClass({JdbcClient.class, KafkaTemplate.class, SpecificRecord.class, AvroClassTrust.class})
@ConditionalOnBooleanProperty("foodflow.outbox.enabled")
@EnableConfigurationProperties(OutboxProperties.class)
public class FoodflowOutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AvroPayloadCodec foodflowAvroPayloadCodec() {
        return new AvroPayloadCodec();
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxWriter foodflowOutboxWriter(DataSource dataSource, AvroPayloadCodec codec,
                                             ObjectProvider<Clock> clock,
                                             ObjectProvider<TraceparentProvider> traceparent) {
        return new JdbcOutboxWriter(JdbcClient.create(dataSource), codec,
                clock.getIfAvailable(Clock::systemUTC),
                traceparent.getIfAvailable(() -> TraceparentProvider.NONE));
    }

    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("unchecked")
    public OutboxRelay foodflowOutboxRelay(DataSource dataSource, PlatformTransactionManager transactionManager,
                                           KafkaTemplate<?, ?> kafkaTemplate, AvroPayloadCodec codec,
                                           OutboxProperties properties, ObjectProvider<Clock> clock,
                                           ObjectProvider<MeterRegistry> meters) {
        return new OutboxRelay(new JdbcOutboxStore(JdbcClient.create(dataSource)), new TransactionTemplate(transactionManager),
                (KafkaTemplate<Object, Object>) kafkaTemplate, codec, properties,
                clock.getIfAvailable(Clock::systemUTC),
                meters.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Tracer.class)
    static class TracingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        TraceparentProvider foodflowTraceparentProvider(ObjectProvider<Tracer> tracer) {
            Tracer resolved = tracer.getIfAvailable();
            return resolved == null ? TraceparentProvider.NONE : new MicrometerTraceparentProvider(resolved);
        }
    }
}
