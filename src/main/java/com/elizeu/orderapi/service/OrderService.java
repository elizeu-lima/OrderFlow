package com.elizeu.orderapi.service;

import com.elizeu.orderapi.config.RabbitMQConfig;
import com.elizeu.orderapi.dto.CreateOrderRequest;
import com.elizeu.orderapi.dto.OrderResponse;
import com.elizeu.orderapi.entity.Order;
import com.elizeu.orderapi.entity.OrderStatus;
import com.elizeu.orderapi.event.OrderCreatedEvent;
import com.elizeu.orderapi.exception.OrderNotFoundException;
import com.elizeu.orderapi.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final RabbitTemplate rabbitTemplate;

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        log.debug("Received request to create order for customer: {}", request.customerEmail());

        Order order = Order.builder()
                .customerName(request.customerName())
                .customerEmail(request.customerEmail())
                .totalAmount(request.totalAmount())
                .status(OrderStatus.CREATED)
                .build();

        Order saved = orderRepository.save(order);
        log.info("Order saved with id={} status={}", saved.getId(), saved.getStatus());

        OrderCreatedEvent event = new OrderCreatedEvent(
                saved.getId(),
                saved.getCustomerName(),
                saved.getCustomerEmail(),
                saved.getTotalAmount(),
                saved.getCreatedAt()
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDERS_EXCHANGE,
                RabbitMQConfig.ORDER_CREATED_ROUTING_KEY,
                event
        );
        log.info("Event published for orderId={}", saved.getId());

        return OrderResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> listOrders() {
        return orderRepository.findAll().stream()
                .map(OrderResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(UUID id) {
        return orderRepository.findById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + id));
    }
}
