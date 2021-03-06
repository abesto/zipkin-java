/**
 * Copyright 2015-2016 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.server;

import com.github.kristofa.brave.Brave;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.Executor;
import javax.sql.DataSource;
import org.jooq.ExecuteListenerProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.autoconfigure.jdbc.DataSourceBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import zipkin.Codec;
import zipkin.InMemoryStorage;
import zipkin.Sampler;
import zipkin.SpanStore;
import zipkin.StorageComponent;
import zipkin.cassandra.CassandraStorage;
import zipkin.elasticsearch.ElasticsearchStorage;
import zipkin.jdbc.JDBCStorage;
import zipkin.kafka.KafkaTransport;
import zipkin.scribe.ScribeTransport;
import zipkin.server.brave.TracedSpanStore;

@Configuration
public class ZipkinServerConfiguration {

  @Bean
  @ConditionalOnMissingBean(Codec.Factory.class)
  Codec.Factory codecFactory() {
    return Codec.FACTORY;
  }

  @Bean
  @ConditionalOnMissingBean(Sampler.class)
  Sampler traceIdSampler(@Value("${zipkin.collector.sample-rate:1.0}") float rate) {
    return Sampler.create(rate);
  }

  /**
   * SpanStore is explicitly marked as a bean, so that it can be wrapped with a {@link
   * TracedSpanStore}.
   *
   * <p>Lazy to ensure that transient storage errors don't crash bootstrap.
   */
  @Bean @Lazy SpanStore spanStore(StorageComponent storage) {
    return storage.spanStore();
  }

  @Configuration
  @ConditionalOnClass(name = "com.github.kristofa.brave.Brave")
  static class BraveSpanStoreEnhancer implements BeanPostProcessor {

    @Autowired(required = false)
    Brave brave;

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
      return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
      if (bean instanceof SpanStore && brave != null) {
        return new TracedSpanStore(brave, (SpanStore) bean);
      }
      return bean;
    }
  }

  @Configuration
  // "matchIfMissing = true" ensures this is used when there's no configured storage type
  @ConditionalOnProperty(name = "zipkin.storage.type", havingValue = "mem", matchIfMissing = true)
  @ConditionalOnMissingBean(StorageComponent.class)
  static class InMemoryConfiguration {
    @Bean StorageComponent storage() {
      return new InMemoryStorage();
    }
  }

  @Configuration
  @EnableConfigurationProperties(ZipkinMySQLProperties.class)
  @ConditionalOnProperty(name = "zipkin.storage.type", havingValue = "mysql")
  @ConditionalOnMissingBean(StorageComponent.class)
  static class JDBCConfiguration {
    @Autowired(required = false)
    DataSource dataSource;

    @Autowired(required = false)
    ZipkinMySQLProperties mysql;

    @Autowired(required = false)
    @Qualifier("jdbcTraceListenerProvider")
    ExecuteListenerProvider listener;

    @Bean @ConditionalOnMissingBean(Executor.class)
    public Executor executor() {
      ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
      executor.setThreadNamePrefix("JDBCStorage-");
      executor.initialize();
      return executor;
    }

    @Bean StorageComponent storage(Executor executor) {
      return new JDBCStorage.Builder()
          .executor(executor)
          .datasource(dataSource != null ? dataSource : initializeFromMySQLProperties())
          .listenerProvider(listener).build();
    }

    DataSource initializeFromMySQLProperties() {
      StringBuilder url = new StringBuilder("jdbc:mysql://");
      url.append(mysql.getHost()).append(":").append(mysql.getPort());
      url.append("/").append(mysql.getDb());
      url.append("?autoReconnect=true");
      url.append("&useSSL=").append(mysql.isUseSsl());
      url.append("&maxActive=").append(mysql.getMaxActive());
      return DataSourceBuilder.create()
          .driverClassName("org.mariadb.jdbc.Driver")
          .url(url.toString())
          .username(mysql.getUsername())
          .password(mysql.getPassword())
          .build();
    }
  }

  @Configuration
  @EnableConfigurationProperties(ZipkinCassandraProperties.class)
  @ConditionalOnProperty(name = "zipkin.storage.type", havingValue = "cassandra")
  @ConditionalOnMissingBean(StorageComponent.class)
  static class CassandraConfiguration {
    @Bean StorageComponent storage(ZipkinCassandraProperties cassandra) {
      return new CassandraStorage.Builder()
          .keyspace(cassandra.getKeyspace())
          .contactPoints(cassandra.getContactPoints())
          .localDc(cassandra.getLocalDc())
          .maxConnections(cassandra.getMaxConnections())
          .ensureSchema(cassandra.isEnsureSchema())
          .username(cassandra.getUsername())
          .password(cassandra.getPassword())
          .spanTtl(cassandra.getSpanTtl())
          .indexTtl(cassandra.getIndexTtl()).build();
    }
  }

  @Configuration
  @EnableConfigurationProperties(ZipkinElasticsearchProperties.class)
  @ConditionalOnProperty(name = "zipkin.storage.type", havingValue = "elasticsearch")
  @ConditionalOnMissingBean(StorageComponent.class)
  static class ElasticsearchConfiguration {
    @Bean StorageComponent storage(ZipkinElasticsearchProperties elasticsearch) {
      return new ElasticsearchStorage.Builder()
          .cluster(elasticsearch.getCluster())
          .hosts(elasticsearch.getHosts())
          .index(elasticsearch.getIndex())
          .build();
    }
  }

  /**
   * This transport accepts Scribe logs in a specified category. Each log entry is expected to
   * contain a single span, which is TBinaryProtocol big-endian, then base64 encoded. Decoded spans
   * are stored asynchronously.
   */
  @Configuration
  @EnableConfigurationProperties(ZipkinScribeProperties.class)
  @ConditionalOnClass(name = "zipkin.scribe.ScribeTransport")
  static class ScribeConfiguration {
    @Bean ScribeTransport scribe(ZipkinScribeProperties scribe, Sampler sampler,
        StorageComponent storage) {
      return new ScribeTransport.Builder()
          .category(scribe.getCategory())
          .port(scribe.getPort()).writeTo(storage, sampler);
    }
  }

  /**
   * This transport consumes a topic, decodes spans from thrift messages and stores them subject to
   * sampling policy.
   */
  @Configuration
  @EnableConfigurationProperties(ZipkinKafkaProperties.class)
  @ConditionalOnKafkaZookeeper
  static class KafkaConfiguration {
    @Bean KafkaTransport kafka(ZipkinKafkaProperties kafka, Sampler sampler,
        StorageComponent storage) {
      return new KafkaTransport.Builder()
          .topic(kafka.getTopic())
          .zookeeper(kafka.getZookeeper())
          .groupId(kafka.getGroupId())
          .streams(kafka.getStreams()).writeTo(storage, sampler);
    }
  }

  /**
   * This condition passes when Kafka classes are available and {@link
   * ZipkinKafkaProperties#getZookeeper()} is set.
   */
  @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  @Conditional(ConditionalOnKafkaZookeeper.KafkaEnabledCondition.class)
  @ConditionalOnClass(name = "zipkin.kafka.KafkaTransport") @interface ConditionalOnKafkaZookeeper {
    class KafkaEnabledCondition extends SpringBootCondition {
      @Override
      public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata a) {
        String kafkaZookeeper = context.getEnvironment().getProperty("kafka.zookeeper");
        return kafkaZookeeper == null || kafkaZookeeper.isEmpty() ?
            ConditionOutcome.noMatch("kafka.zookeeper isn't set") :
            ConditionOutcome.match();
      }
    }
  }
}
