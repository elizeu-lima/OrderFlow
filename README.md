# OrderFlow

> Sistema backend orientado a eventos para processamento assíncrono de pedidos, construído com Java 21, Spring Boot 3, RabbitMQ e PostgreSQL.

---

## Índice

- [Visão Geral](#visão-geral)
- [Arquitetura](#arquitetura)
- [Stack Tecnológica](#stack-tecnológica)
- [Estrutura do Projeto](#estrutura-do-projeto)
- [Fluxo de Mensagens](#fluxo-de-mensagens)
- [Entidades e Status](#entidades-e-status)
- [Endpoints da API](#endpoints-da-api)
- [Regras de Negócio](#regras-de-negócio)
- [Configuração RabbitMQ](#configuração-rabbitmq)
- [Retry e Dead Letter Queue](#retry-e-dead-letter-queue)
- [Instalação e Execução](#instalação-e-execução)
- [Testes Automatizados](#testes-automatizados)
- [Testes com Postman](#testes-com-postman)
- [Observabilidade](#observabilidade)
- [Melhorias Futuras](#melhorias-futuras)

---

## Visão Geral

O **OrderFlow** é um sistema backend que simula o processamento assíncrono de pedidos utilizando arquitetura orientada a eventos. A aplicação demonstra na prática conceitos avançados de mensageria, processamento assíncrono, resiliência com retry, Dead Letter Queue e boas práticas de desenvolvimento com Spring Boot.

O sistema é dividido em dois microsserviços independentes que se comunicam exclusivamente via RabbitMQ, compartilhando o mesmo banco de dados PostgreSQL.

---

## Arquitetura

```
┌─────────────┐     POST /api/orders     ┌──────────────────────┐
│   Cliente   │ ───────────────────────► │      Order API       │
│  (Postman)  │                          │    (porta 8080)      │
└─────────────┘                          │                      │
                                         │  - Valida request    │
                                         │  - Persiste pedido   │
                                         │  - Publica evento    │
                                         └──────────┬───────────┘
                                                    │
                                         OrderCreatedEvent
                                                    │
                                                    ▼
                              ┌─────────────────────────────────────────┐
                              │               RabbitMQ                  │
                              │                                         │
                              │  orders.exchange (direct)               │
                              │       │ routing key: order.created      │
                              │       ▼                                 │
                              │  orders.processing.queue                │
                              │       │ x-dead-letter → orders.dlx      │
                              │       │                                 │
                              │  [erro] orders.dlx                      │
                              │       │ routing key: order.retry        │
                              │       ▼                                 │
                              │  orders.retry.queue (TTL 10s)           │
                              │       │ x-dead-letter → orders.exchange │
                              │       │                                 │
                              │  [max retries] orders.dlx               │
                              │       │ routing key: order.dead-letter  │
                              │       ▼                                 │
                              │  orders.dead-letter.queue               │
                              └──────────────────┬──────────────────────┘
                                                 │
                                                 ▼
                                    ┌────────────────────────┐
                                    │    Order Processor     │
                                    │     (porta 8081)       │
                                    │                        │
                                    │  - Consome evento      │
                                    │  - Atualiza status     │
                                    │  - Aplica regras       │
                                    │  - Gerencia retry      │
                                    └───────────┬────────────┘
                                                │
                                                ▼
                                    ┌────────────────────────┐
                                    │      PostgreSQL        │
                                    │      (porta 5432)      │
                                    │    (tabela: orders)    │
                                    └────────────────────────┘
```

### Decisões de Design

- **Dois serviços independentes**: separação de responsabilidades real, simulando ambiente de microsserviços com deploys independentes
- **Acknowledge manual**: controle fino do ciclo de vida das mensagens, evitando perda ou processamento duplicado
- **Retry via TTL de fila**: estratégia nativa do RabbitMQ com `x-message-ttl`, sem loops síncronos no consumer
- **Records Java 21**: DTOs e eventos como `record` para imutabilidade e expressividade
- **@ControllerAdvice**: tratamento centralizado de erros com respostas padronizadas
- **Bean Validation**: validações declarativas na camada de entrada

---

## Stack Tecnológica

| Tecnologia | Versão | Papel |
|---|---|---|
| Java | 21 | Linguagem principal |
| Spring Boot | 3.3.0 | Framework principal |
| Spring Web | — | API REST com Tomcat embarcado |
| Spring AMQP | 3.1.5 | Integração com RabbitMQ |
| Spring Data JPA | — | Persistência com Hibernate |
| Bean Validation | — | Validação declarativa de entrada |
| RabbitMQ | 3.13 | Message broker |
| PostgreSQL | 16 | Banco de dados relacional |
| Lombok | 1.18.32 | Redução de boilerplate |
| Jackson | 2.17.1 | Serialização/deserialização JSON |
| Docker Compose | — | Infraestrutura local |
| Maven | 3.9+ | Build e gerenciamento de dependências |
| JUnit 5 | 5.10.2 | Testes unitários e de integração |
| Mockito | 5.11.0 | Mocks em testes unitários |
| Testcontainers | 1.19.8 | Infraestrutura real em testes de integração |
| Awaitility | 4.2.1 | Asserções assíncronas em testes |

---

## Estrutura do Projeto

```
orderflow/
├── order-api/
│   ├── src/main/java/com/elizeu/orderapi/
│   │   ├── controller/
│   │   │   └── OrderController.java          # Endpoints REST
│   │   ├── service/
│   │   │   └── OrderService.java             # Regras de negócio + publicação
│   │   ├── repository/
│   │   │   └── OrderRepository.java          # Acesso ao banco
│   │   ├── entity/
│   │   │   ├── Order.java                    # Entidade JPA
│   │   │   └── OrderStatus.java              # Enum de status
│   │   ├── dto/
│   │   │   ├── CreateOrderRequest.java       # Record com validações
│   │   │   └── OrderResponse.java            # Record de resposta
│   │   ├── event/
│   │   │   └── OrderCreatedEvent.java        # Record do evento
│   │   ├── config/
│   │   │   └── RabbitMQConfig.java           # Exchanges, filas, bindings
│   │   └── exception/
│   │       ├── GlobalExceptionHandler.java   # @ControllerAdvice
│   │       └── OrderNotFoundException.java
│   └── pom.xml
│
├── order-processor/
│   ├── src/main/java/com/elizeu/orderprocessor/
│   │   ├── consumer/
│   │   │   └── OrderConsumer.java            # Listener + retry/DLQ
│   │   ├── service/
│   │   │   └── OrderProcessingService.java   # Lógica de aprovação/rejeição
│   │   ├── repository/
│   │   │   └── OrderRepository.java
│   │   ├── entity/
│   │   │   ├── Order.java
│   │   │   └── OrderStatus.java
│   │   ├── event/
│   │   │   └── OrderCreatedEvent.java
│   │   ├── config/
│   │   │   └── RabbitMQConfig.java           # Converter Jackson
│   │   └── exception/
│   │       └── OrderProcessingException.java
│   └── pom.xml
│
├── docker-compose.yml
├── README.md
└── docs/
    └── architecture.md
```

---

## Fluxo de Mensagens

### Fluxo Normal (Aprovação)

```
1. Cliente envia POST /api/orders
2. Order API valida a requisição (Bean Validation)
3. Order API persiste o pedido com status CREATED
4. Order API publica OrderCreatedEvent em orders.exchange
   └── routing key: order.created
5. Mensagem chega em orders.processing.queue
6. Order Processor consome e atualiza status para PROCESSING
7. Order Processor avalia totalAmount:
   └── totalAmount <= 5000 → status = APPROVED
   └── totalAmount > 5000  → status = REJECTED
8. Status final é persistido no PostgreSQL
```

### Fluxo de Retry (Erro Temporário)

```
1. Order Processor lança exceção durante processamento
2. Consumer executa basicNack(requeue=false)
3. RabbitMQ encaminha para orders.dlx com routing key order.retry
4. Mensagem entra em orders.retry.queue
   └── x-message-ttl = 10.000ms (10 segundos)
5. Após TTL, DLX da retry queue reencaminha para orders.exchange
   └── routing key: order.created
6. Mensagem volta para orders.processing.queue
7. Consumer incrementa header x-retry-count
8. Processo se repete até MAX_RETRY_COUNT = 3
```

### Fluxo Dead Letter Queue (Falha Definitiva)

```
1. Após 3 tentativas sem sucesso
2. Consumer executa basicAck (remove da fila principal)
3. Consumer publica manualmente em orders.dlx
   └── routing key: order.dead-letter
4. Mensagem chega em orders.dead-letter.queue
5. Mensagem fica disponível para análise e reprocessamento manual
```

---

## Entidades e Status

### Order

| Campo | Tipo | Descrição |
|---|---|---|
| id | UUID | Gerado automaticamente |
| customerName | String | Nome do cliente (obrigatório) |
| customerEmail | String | E-mail válido (obrigatório) |
| totalAmount | BigDecimal | Valor positivo (obrigatório) |
| status | OrderStatus | Status atual do pedido |
| createdAt | LocalDateTime | Preenchido no @PrePersist |
| updatedAt | LocalDateTime | Atualizado no @PreUpdate |

### OrderStatus

```
CREATED    → pedido criado, aguardando processamento
PROCESSING → mensagem consumida, processamento em andamento
APPROVED   → processamento concluído com sucesso (totalAmount <= 5000)
REJECTED   → pedido rejeitado por regra de negócio (totalAmount > 5000)
FAILED     → reservado para falhas críticas
```

---

## Endpoints da API

### POST /api/orders — Criar Pedido

**Request:**
```json
{
  "customerName": "João Silva",
  "customerEmail": "joao@email.com",
  "totalAmount": 250.90
}
```

**Response 201 Created:**
```json
{
  "id": "908645c0-7a44-4d95-b5b1-bb3b7d9ebf5c",
  "customerName": "João Silva",
  "customerEmail": "joao@email.com",
  "totalAmount": 250.90,
  "status": "CREATED",
  "createdAt": "2026-06-12T18:52:30.629102"
}
```

**Response 400 Bad Request (validação):**
```json
{
  "status": 400,
  "error": "Validation failed",
  "message": "{customerName=Customer name is required, totalAmount=Total amount must be greater than zero}",
  "timestamp": "2026-06-12T19:00:00"
}
```

---

### GET /api/orders — Listar Pedidos

**Response 200 OK:**
```json
[
  {
    "id": "908645c0-7a44-4d95-b5b1-bb3b7d9ebf5c",
    "customerName": "João Silva",
    "customerEmail": "joao@email.com",
    "totalAmount": 250.90,
    "status": "APPROVED",
    "createdAt": "2026-06-12T18:52:30.629102"
  }
]
```

---

### GET /api/orders/{id} — Buscar por ID

**Response 200 OK:** mesmo formato acima.

**Response 404 Not Found:**
```json
{
  "status": 404,
  "error": "Order not found",
  "message": "Order not found: 908645c0-7a44-4d95-b5b1-bb3b7d9ebf5c",
  "timestamp": "2026-06-12T19:00:00"
}
```

---

## Regras de Negócio

### Criação de Pedido
- `customerName` não pode ser vazio (`@NotBlank`)
- `customerEmail` deve ser um e-mail válido (`@Email`)
- `totalAmount` deve ser maior que zero (`@Positive`)
- Status inicial sempre `CREATED`
- Evento `OrderCreatedEvent` sempre publicado após persistência

### Processamento
- `totalAmount <= 5000` → `APPROVED`
- `totalAmount > 5000` → `REJECTED`
- Exceção inesperada → retry automático via DLQ
- Após 3 retries → `orders.dead-letter.queue`

---

## Configuração RabbitMQ

| Componente | Nome | Tipo |
|---|---|---|
| Exchange principal | `orders.exchange` | direct |
| Exchange DLX | `orders.dlx` | direct |
| Fila principal | `orders.processing.queue` | classic |
| Fila de retry | `orders.retry.queue` | classic + TTL 10s |
| Fila DLQ | `orders.dead-letter.queue` | classic |
| Routing key principal | `order.created` | — |
| Routing key retry | `order.retry` | — |
| Routing key DLQ | `order.dead-letter` | — |

---

## Retry e Dead Letter Queue

A estratégia de retry é implementada via **TTL de fila nativa do RabbitMQ**, sem retry síncrono no consumer:

```
orders.processing.queue
  x-dead-letter-exchange:    orders.dlx
  x-dead-letter-routing-key: order.retry

orders.retry.queue
  x-message-ttl:             10000 (10s)
  x-dead-letter-exchange:    orders.exchange
  x-dead-letter-routing-key: order.created

orders.dead-letter.queue
  (fila terminal — sem DLX)
```

O consumer usa **acknowledge manual** (`ackMode = MANUAL`) e controla o fluxo via:
- `basicAck` → processamento bem-sucedido
- `basicNack(requeue=false)` → envia para retry via DLX
- Após `MAX_RETRY_COUNT`: `basicAck` + publicação manual na DLQ

---

## Instalação e Execução

### Pré-requisitos

- Java 21+
- Maven 3.9+
- Docker Desktop

### 1. Clone o repositório

```bash
git clone https://github.com/seu-usuario/orderflow.git
cd orderflow
```

### 2. Suba a infraestrutura

```bash
docker compose up -d
```

Verifique se os containers estão saudáveis:

```bash
docker compose ps
```

Aguarde ambos aparecerem como `healthy`.

### 3. Execute o Order API

```bash
cd order-api
./mvnw spring-boot:run
```

Aguarde a mensagem:
```
Started OrderApiApplication in X.XXX seconds
```

### 4. Execute o Order Processor

Em outro terminal:

```bash
cd order-processor
./mvnw spring-boot:run
```

### 5. Acesse os serviços

| Serviço | URL |
|---|---|
| Order API | http://localhost:8080 |
| Order Processor | http://localhost:8081 |
| RabbitMQ Management | http://localhost:15672 |
| PostgreSQL | localhost:5432 |

**Credenciais RabbitMQ:** `guest` / `guest`  
**Credenciais PostgreSQL:** `postgres` / `postgres` / database: `orderflow`

---

## Testes Automatizados

### Testes Unitários

#### OrderServiceTest (order-api)

Testa a camada de serviço isolada com Mockito:

| Teste | Descrição | Resultado |
|---|---|---|
| `shouldCreateOrderWithStatusCreated` | Verifica que o status inicial é CREATED | ✅ PASSED |
| `shouldPublishOrderCreatedEvent` | Verifica que o evento é publicado no RabbitMQ com os dados corretos | ✅ PASSED |
| `shouldThrowWhenOrderNotFound` | Verifica que OrderNotFoundException é lançada para ID inexistente | ✅ PASSED |

#### OrderProcessingServiceTest (order-processor)

Testa a lógica de processamento com Mockito:

| Teste | Descrição | Resultado |
|---|---|---|
| `shouldApproveOrderBelowThreshold` | Pedido com R$250,00 deve ser APPROVED | ✅ PASSED |
| `shouldRejectOrderAboveThreshold` | Pedido com R$6000,00 deve ser REJECTED | ✅ PASSED |
| `shouldThrowWhenOrderNotFound` | Lança exceção quando pedido não existe no banco | ✅ PASSED |
| `shouldApproveOrderAtExactThreshold` | Pedido com exatamente R$5000,00 deve ser APPROVED | ✅ PASSED |

---

### Testes de Integração

Utilizam **Testcontainers** para subir PostgreSQL e RabbitMQ reais em containers Docker durante a execução dos testes. Nenhuma configuração adicional necessária.

#### OrderControllerIntegrationTest (order-api)

Testa o fluxo completo da API com banco e mensageria reais:

| Teste | Descrição | Resultado |
|---|---|---|
| `shouldCreateOrderAndReturnCreated` | POST válido retorna 201 com body correto | ✅ PASSED |
| `shouldPersistOrderInDatabase` | Pedido criado é encontrado no PostgreSQL com status CREATED | ✅ PASSED |
| `shouldReturn400WhenRequestIsInvalid` | Request com todos os campos inválidos retorna 400 | ✅ PASSED |
| `shouldReturn400WhenCustomerNameIsBlank` | customerName vazio retorna 400 com mensagem | ✅ PASSED |
| `shouldListOrders` | GET /api/orders retorna lista com 1 elemento | ✅ PASSED |
| `shouldReturn404WhenOrderNotFound` | GET com UUID inexistente retorna 404 | ✅ PASSED |

#### OrderConsumerIntegrationTest (order-processor)

Testa o ciclo completo de consumo assíncrono com Awaitility:

| Teste | Descrição | Resultado |
|---|---|---|
| `shouldConsumeEventAndApproveOrder` | Evento publicado resulta em status APPROVED no banco | ✅ PASSED |
| `shouldConsumeEventAndRejectOrderAboveThreshold` | Evento com R$9999 resulta em status REJECTED no banco | ✅ PASSED |

### Executando os Testes

```bash
# Todos os testes do order-api
cd order-api && ./mvnw test

# Todos os testes do order-processor
cd order-processor && ./mvnw test

# Apenas unitários
./mvnw test -Dtest="OrderServiceTest"
./mvnw test -Dtest="OrderProcessingServiceTest"

# Apenas integração (requer Docker)
./mvnw test -Dtest="OrderControllerIntegrationTest"
./mvnw test -Dtest="OrderConsumerIntegrationTest"
```

**Resultado geral:** 12/12 testes passando ✅

---

## Testes com Postman

Todos os cenários abaixo foram validados manualmente com a aplicação rodando.

### Cenário 1 — Criar pedido e verificar aprovação

**Request:**
```http
POST http://localhost:8080/api/orders
Content-Type: application/json

{
  "customerName": "João Silva",
  "customerEmail": "joao@email.com",
  "totalAmount": 250.90
}
```

**Response 201:**
```json
{
  "id": "908645c0-7a44-4d95-b5b1-bb3b7d9ebf5c",
  "status": "CREATED",
  ...
}
```

**GET após ~1s:**
```json
{
  "id": "908645c0-7a44-4d95-b5b1-bb3b7d9ebf5c",
  "status": "APPROVED"
}
```
✅ **PASSED**

---

### Cenário 2 — Rejeição por valor alto

**Request:**
```http
POST http://localhost:8080/api/orders

{
  "customerName": "Maria Souza",
  "customerEmail": "maria@email.com",
  "totalAmount": 9999.00
}
```

**GET após ~1s:**
```json
{
  "status": "REJECTED"
}
```
✅ **PASSED**

---

### Cenário 3 — Validação de entrada

**Request:**
```http
POST http://localhost:8080/api/orders

{
  "customerName": "",
  "customerEmail": "email-invalido",
  "totalAmount": -10
}
```

**Response 400:**
```json
{
  "status": 400,
  "error": "Validation failed",
  "message": "{customerName=Customer name is required, ...}"
}
```
✅ **PASSED**

---

### Cenário 4 — Listagem de todos os pedidos

```http
GET http://localhost:8080/api/orders
```

Retornou array com 12 pedidos, todos com status `APPROVED` ou `REJECTED` conforme esperado. ✅ **PASSED**

---

### Cenário 5 — Pedido não encontrado

```http
GET http://localhost:8080/api/orders/00000000-0000-0000-0000-000000000000
```

**Response 404** com mensagem de erro padronizada. ✅ **PASSED**

---

## Observabilidade

A aplicação registra logs estruturados para todo o ciclo de vida do pedido:

```
[order-api]       DEBUG - POST /api/orders - customerEmail=joao@email.com
[order-api]       DEBUG - Received request to create order for customer: joao@email.com
[order-api]       INFO  - Order saved with id=UUID status=CREATED
[order-api]       INFO  - Attempting to connect to: [localhost:5672]
[order-api]       INFO  - Event published for orderId=UUID

[order-processor] INFO  - Message consumed for orderId=UUID attempt=1
[order-processor] DEBUG - orderId=UUID status updated to PROCESSING
[order-processor] INFO  - orderId=UUID APPROVED
[order-processor] DEBUG - Message acked for orderId=UUID

# Em caso de erro:
[order-processor] ERROR - Error processing orderId=UUID attempt=1: ...
[order-processor] INFO  - Retry scheduled for orderId=UUID
[order-processor] WARN  - Max retries exceeded for orderId=UUID — sending to DLQ
[order-processor] INFO  - orderId=UUID sent to dead-letter queue
```

---

## Melhorias Futuras

- [ ] Autenticação e autorização com Spring Security + JWT
- [ ] Endpoint `GET /api/orders/dead-letter` para consultar DLQ via API
- [ ] Métricas com Micrometer + Prometheus
- [ ] Dashboard com Grafana
- [ ] Tracing distribuído com OpenTelemetry + Jaeger
- [ ] Notificação por e-mail ao aprovar pedido (Spring Mail)
- [ ] CI/CD com GitHub Actions
- [ ] Deploy em cloud com Kubernetes
- [ ] Separação de banco de dados por serviço (padrão Database per Service)
- [ ] Outbox Pattern para garantia de entrega do evento

---

## Autor

Desenvolvido por **Elizeu** como projeto de portfólio para demonstração de habilidades em desenvolvimento backend com Java e arquitetura orientada a eventos.
