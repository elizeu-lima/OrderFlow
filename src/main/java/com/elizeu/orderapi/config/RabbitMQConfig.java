package com.elizeu.orderapi.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // Exchange names
    public static final String ORDERS_EXCHANGE = "orders.exchange";
    public static final String DEAD_LETTER_EXCHANGE = "orders.dlx";

    // Queue names
    public static final String ORDERS_PROCESSING_QUEUE = "orders.processing.queue";
    public static final String DEAD_LETTER_QUEUE = "orders.dead-letter.queue";
    public static final String RETRY_QUEUE = "orders.retry.queue";

    // Routing keys
    public static final String ORDER_CREATED_ROUTING_KEY = "order.created";
    public static final String ORDER_RETRY_ROUTING_KEY = "order.retry";

    // Retry config
    private static final int RETRY_TTL_MS = 10_000; // 10 seconds
    private static final int MAX_RETRY_COUNT = 3;

    // --- Exchanges ---

    @Bean
    public DirectExchange ordersExchange() {
        return ExchangeBuilder.directExchange(ORDERS_EXCHANGE).durable(true).build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    // --- Queues ---

    @Bean
    public Queue ordersProcessingQueue() {
        return QueueBuilder.durable(ORDERS_PROCESSING_QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ORDER_RETRY_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue retryQueue() {
        return QueueBuilder.durable(RETRY_QUEUE)
                .withArgument("x-dead-letter-exchange", ORDERS_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ORDER_CREATED_ROUTING_KEY)
                .withArgument("x-message-ttl", RETRY_TTL_MS)
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    // --- Bindings ---

    @Bean
    public Binding ordersProcessingBinding() {
        return BindingBuilder.bind(ordersProcessingQueue())
                .to(ordersExchange())
                .with(ORDER_CREATED_ROUTING_KEY);
    }

    @Bean
    public Binding retryBinding() {
        return BindingBuilder.bind(retryQueue())
                .to(deadLetterExchange())
                .with(ORDER_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue())
                .to(deadLetterExchange())
                .with("order.dead-letter");
    }

    // --- Message Converter ---

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        return template;
    }
}
