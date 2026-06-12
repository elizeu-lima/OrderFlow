package com.elizeu.orderapi;

import com.elizeu.orderapi.config.RabbitMQConfig;
import com.elizeu.orderapi.dto.CreateOrderRequest;
import com.elizeu.orderapi.dto.OrderResponse;
import com.elizeu.orderapi.entity.Order;
import com.elizeu.orderapi.entity.OrderStatus;
import com.elizeu.orderapi.event.OrderCreatedEvent;
import com.elizeu.orderapi.exception.OrderNotFoundException;
import com.elizeu.orderapi.repository.OrderRepository;
import com.elizeu.orderapi.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private OrderService orderService;

    @Test
    void shouldCreateOrderWithStatusCreated() {
        CreateOrderRequest request = new CreateOrderRequest(
                "João Silva", "joao@email.com", new BigDecimal("250.90"));

        Order savedOrder = Order.builder()
                .id(UUID.randomUUID())
                .customerName(request.customerName())
                .customerEmail(request.customerEmail())
                .totalAmount(request.totalAmount())
                .status(OrderStatus.CREATED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);

        OrderResponse response = orderService.createOrder(request);

        assertThat(response.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(response.customerEmail()).isEqualTo("joao@email.com");
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    void shouldPublishOrderCreatedEvent() {
        CreateOrderRequest request = new CreateOrderRequest(
                "Maria", "maria@email.com", new BigDecimal("100.00"));

        Order savedOrder = Order.builder()
                .id(UUID.randomUUID())
                .customerName(request.customerName())
                .customerEmail(request.customerEmail())
                .totalAmount(request.totalAmount())
                .status(OrderStatus.CREATED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);

        orderService.createOrder(request);

        ArgumentCaptor<OrderCreatedEvent> eventCaptor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.ORDERS_EXCHANGE),
                eq(RabbitMQConfig.ORDER_CREATED_ROUTING_KEY),
                eventCaptor.capture()
        );

        OrderCreatedEvent event = eventCaptor.getValue();
        assertThat(event.orderId()).isEqualTo(savedOrder.getId());
        assertThat(event.customerEmail()).isEqualTo("maria@email.com");
    }

    @Test
    void shouldThrowWhenOrderNotFound() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.findById(id))
                .isInstanceOf(OrderNotFoundException.class);
    }
}
