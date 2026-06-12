package com.elizeu.orderapi.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record OrderCreatedEvent(
        UUID orderId,
        String customerName,
        String customerEmail,
        BigDecimal totalAmount,
        LocalDateTime createdAt
) {}
