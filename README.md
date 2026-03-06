# Spring Boot Outbox Pattern

Production-ready **Transactional Outbox Pattern** implementation with Spring Boot 3.2, Java 21, Apache Kafka, Resilience4j, and Spring AI. Atomik event garantisi, dağıtık kilitleme ve tam gözlemlenebilirlik sunar.

---

## İçindekiler

- [Genel Bakış](#genel-bakış)
- [Mimari](#mimari)
  - [Outbox Pattern Temeli](#outbox-pattern-temeli)
  - [Event Yaşam Döngüsü](#event-yaşam-döngüsü)
  - [Katmanlı Yapı](#katmanlı-yapı)
  - [Polling ve Yayınlama Akışı](#polling-ve-yayınlama-akışı)
  - [Eş Zamanlılık Kontrolü](#eş-zamanlılık-kontrolü)
  - [Resilience4j Devre Kesici](#resilience4j-devre-kesici)
  - [AI Analitik](#ai-analitik)
- [Teknoloji Yığını](#teknoloji-yığını)
- [Proje Yapısı](#proje-yapısı)
- [Veritabanı Şeması](#veritabanı-şeması)
- [Kafka Mimarisi](#kafka-mimarisi)
- [API Referansı](#api-referansı)
- [Kurulum ve Çalıştırma](#kurulum-ve-çalıştırma)
- [Yapılandırma](#yapılandırma)
- [Gözlemlenebilirlik](#gözlemlenebilirlik)
- [Test Stratejisi](#test-stratejisi)

---

## Genel Bakış

**Dual-write problemi**: Bir servis hem veritabanına hem de mesaj kuyruğuna yazıyorsa, bunlardan biri başarısız olursa tutarsızlık oluşur. Outbox Pattern bu problemi ortadan kaldırır:

1. İş verisi ve "outbox event" **tek bir veritabanı transaction'ında** kaydedilir.
2. Ayrı bir poller bu event'leri okuyup Kafka'ya asenkron olarak iletir.
3. İki aşamalı yazma riski yoktur; en az bir kez (at-least-once) teslimat garanti edilir.

```
┌─────────────────────────────────────────────────────────────────┐
│                   Tek Veritabanı Transaction'ı                  │
│                                                                 │
│   TransactionService.createTransaction()                        │
│       ├── transactionRepository.save(transaction)  ──► DB      │
│       └── outboxService.createOutboxEvent(...)      ──► DB      │
│                           (MANDATORY propagation)               │
└─────────────────────────────────────────────────────────────────┘
                              ▼  (commit)
┌─────────────────────────────────────────────────────────────────┐
│                       OutboxScheduler                           │
│                                                                 │
│   [Her 5s]  processPendingEvents()  ──► Kafka ──► Consumer     │
│   [Her 60s] retryFailedEvents()                                 │
│   [Her 10d] detectStuckEvents()                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Mimari

### Outbox Pattern Temeli

`OutboxService.createOutboxEvent()` metodu `Propagation.MANDATORY` kullanır. Bu sayede:

- Çağıran `@Transactional` metod yoksa **exception** fırlatılır.
- İş verisi ve event her zaman aynı transaction içinde commit olur.
- Kısmi başarı (transaction commit + event kayıt edilemedi) senaryosu imkânsızlaşır.

```java
// TransactionService — atomik çift yazma
@Transactional
public TransactionResponse createTransaction(TransactionRequest request) {
    Transaction saved = transactionRepository.save(transaction);     // ① İş verisi
    outboxService.createOutboxEvent(                                  // ② Outbox event
        saved.getId().toString(),
        EventType.TRANSACTION_CREATED,
        saved,
        transactionTopic);
    return mapper.toResponse(saved);
}
```

### Event Yaşam Döngüsü

```
                    ┌─────────┐
                    │ PENDING │  (oluşturuldu)
                    └────┬────┘
                         │ poller tarafından alındı
                    ┌────▼────────┐
                    │ PROCESSING  │  (Kafka'ya gönderiliyor)
                    └────┬───────┘
              ┌──────────┴──────────┐
              │ başarılı            │ başarısız
        ┌─────▼──────┐       ┌──────▼──────┐
        │ PROCESSED  │       │   FAILED    │  (retry_count artar)
        └────────────┘       └──────┬──────┘
                                    │ retry_count >= max_retry (3)
                             ┌──────▼──────┐
                             │ DEAD_LETTER │  (manuel müdahale gerekir)
                             └─────────────┘
```

30 dakikayı aşan `PROCESSING` event'leri **takılı event** olarak tespit edilip `FAILED`'a zorla alınır.

### Katmanlı Yapı

```
┌──────────────────────────────────────────────────────────────────────┐
│                          REST API Katmanı                            │
│   TransactionController  │  OutboxController  │  AnalyticsController │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │
┌──────────────────────────────────▼───────────────────────────────────┐
│                         Servis Katmanı                               │
│  TransactionService  │  OutboxService  │  OutboxPublisher            │
│  AIAnalyticsService  │  OutboxEventProcessor  │  OutboxCleanupService│
└──────────────────────────────────┬───────────────────────────────────┘
                                   │
┌──────────────────────────────────▼───────────────────────────────────┐
│                      Altyapı / Entegrasyon Katmanı                   │
│  KafkaProducer  │  KafkaConsumer  │  OutboxScheduler                 │
│  Resilience4j (CB + Retry)  │  ShedLock                              │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │
┌──────────────────────────────────▼───────────────────────────────────┐
│                        Veri Erişim Katmanı                           │
│  OutboxEventRepository  │  TransactionRepository  │  AuditLogRepo    │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │
                    ┌──────────────▼──────────────┐
                    │        PostgreSQL 16         │
                    │  outbox_events  │  transactions │
                    │  shedlock  │  vector_store   │
                    └─────────────────────────────┘
```

### Polling ve Yayınlama Akışı

`OutboxScheduler` üç görev çalıştırır; her biri **ShedLock** ile korunur (küme ortamında tek pod çalışır):

| Metod | Aralık | ShedLock Kilidi |
|---|---|---|
| `processPendingEvents()` | 5 saniye | maks 4 dakika |
| `retryFailedEvents()` | 60 saniye | maks 4 dakika |
| `detectStuckEvents()` | 10 dakika | maks 5 dakika |

`OutboxPublisher.publishEventAsync()` çağrı zinciri:

```
OutboxScheduler
    └── OutboxPublisher.processPendingEvents()
            ├── findPendingEventsForProcessing()  [PESSIMISTIC_WRITE lock]
            └── publishEventAsync(event)
                    ├── @CircuitBreaker(kafkaPublisher)
                    ├── @Retry(kafkaPublisher)
                    ├── markAsProcessing()
                    ├── kafkaProducer.sendAsync(event)
                    │       ├── success → markAsProcessed() [partition+offset kaydedilir]
                    │       └── failure → markAsFailed()
                    └── publishFallback()  [CB açık ise]
```

### Eş Zamanlılık Kontrolü

| Mekanizma | Kapsam | Açıklama |
|---|---|---|
| `@Version` (JPA) | Entity | İyimser kilitleme; eş zamanlı güncelleme çakışmasını önler |
| `PESSIMISTIC_WRITE` | Repository sorgusu | Aynı event'i iki thread'in almasını engeller |
| ShedLock | Scheduler | Kümedeki birden fazla pod'un aynı görevi çalıştırmasını önler |
| Kafka idempotent producer | Kafka | Ağ yeniden denemelerinde yinelenen mesaj üretimini önler |

### Resilience4j Devre Kesici

```
                    Normal
                   [CLOSED]
                   /       \
  hata eşiği aşıldı         hata azaldı
          ↓                    ↑
        [OPEN]  ──30s──►  [HALF_OPEN]
     (istek reddedilir)   (5 deneme yapılır)
```

| Parametre | Değer |
|---|---|
| Kayan pencere | 20 çağrı |
| Minimum çağrı | 10 |
| Hata eşiği | %50 |
| Yavaş çağrı eşiği | 2 saniye |
| Açık durumda bekleme | 30 saniye |
| Retry girişimi | 3 |
| Retry bekleme | 1 saniye |
| Retry tetikleyicisi | `KafkaException`, `TimeoutException` |
| Zaman sınırı | 30 saniye |

### AI Analitik

`AIAnalyticsService`, Spring AI + Ollama (`deepseek-r1:7b`) kullanarak:

- **Performans analizi** — işlem süresi ve hata dağılımı
- **Anomali tespiti** — normalden sapan event desenleri
- **Kök neden analizi** — başarısızlık pattern'larının yorumlanması
- **Kapasite planlaması** — büyüme projeksiyonları
- **Semantik arama** — `PgVectorStore` ile embedding tabanlı event benzerlik araması (`nomic-embed-text` modeli, 1536 boyut)

`OPENAI_API_KEY` veya yerel Ollama olmadan çekirdek outbox akışı etkilenmez.

---

## Teknoloji Yığını

| Katman | Teknoloji | Sürüm |
|---|---|---|
| Çerçeve | Spring Boot | 3.2.5 |
| Dil | Java | 21 (preview features) |
| Mesajlaşma | Apache Kafka | 3.x |
| Veritabanı | PostgreSQL | 16 + pgvector |
| Migration | Flyway | 10.10.0 |
| Dayanıklılık | Resilience4j | 2.2.0 |
| Dağıtık kilitleme | ShedLock | 5.12.0 |
| AI | Spring AI + Ollama | 1.0.0-M1 |
| Bağlantı havuzu | HikariCP | (Spring Boot) |
| İzleme | Micrometer + Prometheus | — |
| Dağıtık izleme | Zipkin (Brave) | — |
| API belgeleme | SpringDoc OpenAPI | 2.5.0 |
| Haritalama | MapStruct | 1.5.5.Final |
| Performans testi | Gatling | — |
| Yük testi | k6 | — |
| Konteyner | Docker + Docker Compose | — |

---

## Proje Yapısı

```
spring-boot-outbox-pattern/
├── src/
│   ├── main/
│   │   ├── java/com/springboot/outbox/
│   │   │   ├── OutboxApplication.java          # Ana uygulama sınıfı
│   │   │   ├── api/
│   │   │   │   ├── TransactionController.java  # POST /api/v1/transactions
│   │   │   │   ├── OutboxController.java       # GET/POST /api/v1/outbox/*
│   │   │   │   └── AnalyticsController.java    # GET /api/v1/analytics/*
│   │   │   ├── aspect/
│   │   │   │   ├── LoggingAspect.java          # AOP loglama
│   │   │   │   └── MetricsAspect.java          # AOP metrik toplama
│   │   │   ├── config/
│   │   │   │   ├── KafkaConfig.java            # Producer/consumer yapılandırması
│   │   │   │   ├── ResilienceConfig.java       # CB + Retry + TimeLimiter
│   │   │   │   ├── OutboxProperties.java       # @ConfigurationProperties
│   │   │   │   ├── SecurityConfig.java         # Stateless güvenlik
│   │   │   │   ├── SpringAIConfig.java         # Ollama + PgVector
│   │   │   │   └── ObservabilityConfig.java    # Prometheus + Zipkin
│   │   │   ├── domain/
│   │   │   │   ├── entity/
│   │   │   │   │   ├── OutboxEvent.java        # Ana event entity (@Version)
│   │   │   │   │   ├── Transaction.java        # İş aggregate
│   │   │   │   │   └── AuditLog.java           # Denetim kaydı
│   │   │   │   ├── dto/
│   │   │   │   │   ├── TransactionRequest.java
│   │   │   │   │   ├── TransactionResponse.java
│   │   │   │   │   └── OutboxEventDto.java
│   │   │   │   └── enums/
│   │   │   │       ├── EventStatus.java        # PENDING|PROCESSING|PROCESSED|FAILED|DEAD_LETTER
│   │   │   │       └── EventType.java          # TRANSACTION_CREATED|PAYMENT_COMPLETED|...
│   │   │   ├── repository/
│   │   │   │   ├── OutboxEventRepository.java  # PESSIMISTIC_WRITE sorgular
│   │   │   │   ├── TransactionRepository.java
│   │   │   │   └── AuditLogRepository.java
│   │   │   ├── scheduler/
│   │   │   │   ├── OutboxScheduler.java        # ShedLock korumalı zamanlayıcılar
│   │   │   │   └── CleanupScheduler.java       # İşlenmiş event temizleme
│   │   │   ├── service/
│   │   │   │   ├── OutboxService.java          # createOutboxEvent (MANDATORY)
│   │   │   │   ├── OutboxPublisher.java        # Kafka yayın + CB + Retry
│   │   │   │   ├── OutboxEventProcessor.java   # Batch işleme mantığı
│   │   │   │   ├── OutboxCleanupService.java   # Eski event temizleme
│   │   │   │   ├── TransactionService.java     # İş mantığı + atomik yazma
│   │   │   │   └── AIAnalyticsService.java     # Spring AI analitik
│   │   │   ├── kafka/
│   │   │   │   ├── KafkaProducer.java          # Async, idempotent üretici
│   │   │   │   └── KafkaConsumer.java          # Manuel onay tüketici
│   │   │   └── exception/
│   │   │       ├── GlobalExceptionHandler.java
│   │   │       ├── OutboxException.java
│   │   │       └── ErrorResponse.java
│   │   └── resources/
│   │       ├── application.yml                 # Ana yapılandırma
│   │       ├── application-dev.yml             # Geliştirme profili
│   │       ├── application-docker.yml          # Docker ortamı
│   │       ├── application-prod.yml            # Üretim profili
│   │       ├── application-test.yml            # Test profili
│   │       ├── logback-spring.xml              # Yapılandırılmış loglama
│   │       └── db/migration/
│   │           ├── V1__create_outbox_table.sql
│   │           ├── V2__create_transaction_table.sql
│   │           ├── V3__create_shedlock_table.sql
│   │           ├── V4__create_vector_store_table.sql
│   │           ├── V5__create_audit_log_table.sql
│   │           └── V6__alter_vector_embedding_dimension.sql
│   └── test/
│       └── java/com/springboot/outbox/         # Birim + entegrasyon testleri
├── agents/
│   ├── agent1_health_monitor.py               # Actuator sağlık izleme
│   ├── agent2_event_monitor.py                # Canlı event durum tablosu
│   ├── agent3_kafka_consumer.py               # Kafka mesaj görüntüleyici
│   ├── agent4_load_test.py                    # Gecikme raporlu yük testi
│   ├── agent5_ai_analytics.py                 # AI analitik raporu
│   └── requirements.txt
├── docker/
│   ├── prometheus/prometheus.yml
│   ├── grafana/                               # Dashboard tanımları
│   └── init-db.sql
├── scripts/
│   └── load-test.js                           # k6 yük testi
├── docs/                                      # Ek belgeler
├── Dockerfile                                 # Çok aşamalı build
├── docker-compose.yml                         # Tüm servisler
├── pom.xml
└── CLAUDE.md
```

---

## Veritabanı Şeması

### outbox_events

```sql
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id    VARCHAR(100)   NOT NULL,
    event_type      VARCHAR(50)    NOT NULL,
    payload         JSONB          NOT NULL,
    status          VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    retry_count     INTEGER        NOT NULL DEFAULT 0,
    max_retry       INTEGER        NOT NULL DEFAULT 3,
    created_at      TIMESTAMP      NOT NULL,
    updated_at      TIMESTAMP,
    processed_at    TIMESTAMP,
    error_message   TEXT,
    kafka_topic     VARCHAR(100),
    kafka_key       VARCHAR(100),
    kafka_partition INTEGER,
    kafka_offset    BIGINT,
    version         BIGINT                   -- iyimser kilitleme
);

-- Sık kullanılan sorgular için dizinler
CREATE INDEX idx_status_created ON outbox_events (status, created_at);
CREATE INDEX idx_aggregate_id   ON outbox_events (aggregate_id);
CREATE INDEX idx_event_type     ON outbox_events (event_type);
-- Kısmi dizinler (poller sorguları için optimize)
CREATE INDEX idx_pending        ON outbox_events (created_at) WHERE status = 'PENDING';
CREATE INDEX idx_failed         ON outbox_events (updated_at) WHERE status = 'FAILED';
```

### transactions

```sql
CREATE TABLE transactions (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_reference  VARCHAR(100) UNIQUE NOT NULL,
    customer_id            VARCHAR(100) NOT NULL,
    amount                 DECIMAL(19,4) NOT NULL,
    currency               VARCHAR(10)  NOT NULL,
    sender_account         VARCHAR(100),
    receiver_account       VARCHAR(100),
    description            TEXT,
    status                 VARCHAR(50)  NOT NULL,
    created_at             TIMESTAMP    NOT NULL,
    updated_at             TIMESTAMP,
    version                BIGINT
);
```

### shedlock

```sql
CREATE TABLE shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
```

### vector_store (AI embedding)

```sql
CREATE TABLE vector_store (
    id         UUID PRIMARY KEY,
    content    TEXT,
    metadata   JSONB,
    embedding  vector(1536),  -- pgvector, nomic-embed-text
    created_at TIMESTAMP
);
CREATE INDEX ON vector_store USING ivfflat (embedding vector_cosine_ops);
```

### audit_logs

```sql
CREATE TABLE audit_logs (
    id          UUID PRIMARY KEY,
    entity_type VARCHAR(100),
    entity_id   VARCHAR(100),
    action      VARCHAR(50),
    user_id     VARCHAR(100),
    old_value   JSONB,
    new_value   JSONB,
    timestamp   TIMESTAMP,
    ip_address  VARCHAR(45),
    user_agent  TEXT
);
```

---

## Kafka Mimarisi

### Topic'ler

| Topic | Amaç | Üretici | Tüketici |
|---|---|---|---|
| `springboot.transactions` | Transaction domain event'leri | `OutboxPublisher` | `KafkaConsumer` |
| `springboot.payments` | Ödeme event'leri | `OutboxPublisher` | `KafkaConsumer` |
| `springboot.embargo` | Ambargo kontrol event'leri | `OutboxPublisher` | — |

### Üretici Yapılandırması

```yaml
acks: all                    # Tüm replica onayı beklenir
enable-idempotence: true     # Tam olarak bir kez semantiği
retries: 3
batch-size: 16384
linger-ms: 10
compression-type: snappy
```

### Tüketici Yapılandırması

```yaml
group-id: outbox-consumer-group
enable-auto-commit: false    # Manuel onay
max-poll-records: 100
ack-mode: MANUAL
concurrency: 3
auto-offset-reset: earliest
```

### Mesaj Başlıkları

Her Kafka mesajına şu başlıklar eklenir:
- `event-id` — UUID
- `timestamp` — ISO 8601
- `source` — `spring-boot-outbox-pattern`

---

## API Referansı

### Transaction API

#### Transaction Oluştur

```
POST /api/v1/transactions
Content-Type: application/json

{
  "customerId": "CUST-001",
  "amount": 1500.00,
  "currency": "TRY",
  "senderAccount": "TR33 0006 1005 1978 6457 8413 26",
  "receiverAccount": "TR96 0004 6058 1620 7580 5765 44",
  "description": "Fatura ödemesi"
}
```

**Yanıt:** `201 Created`

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "transactionReference": "TXN-20260305-ABC123",
  "status": "PENDING",
  "createdAt": "2026-03-05T10:30:00Z"
}
```

#### Transaction Durumu Güncelle

```
PUT /api/v1/transactions/{transactionId}/status
Content-Type: application/json

{
  "status": "APPROVED"
}
```

---

### Outbox API

| Metod | Yol | Açıklama |
|---|---|---|
| `GET` | `/api/v1/outbox/events` | Tüm event'ler (sayfalı) |
| `GET` | `/api/v1/outbox/events/{eventId}` | Event detayı |
| `GET` | `/api/v1/outbox/events/aggregate/{aggregateId}` | Aggregate event geçmişi |
| `GET` | `/api/v1/outbox/events/status/{status}` | Duruma göre sayım |
| `GET` | `/api/v1/outbox/events/type/{eventType}` | Türe göre event listesi |
| `GET` | `/api/v1/outbox/statistics` | Event durum istatistikleri |
| `GET` | `/api/v1/outbox/dead-letter` | Dead letter kuyruğu |
| `POST` | `/api/v1/outbox/events/{id}/retry` | Manuel yeniden deneme |
| `POST` | `/api/v1/outbox/trigger-processing` | Manuel işlem tetikle |

#### İstatistik Yanıt Örneği

```json
{
  "PENDING": 12,
  "PROCESSING": 3,
  "PROCESSED": 8547,
  "FAILED": 5,
  "DEAD_LETTER": 1
}
```

---

### Analytics API (AI gerektirir)

| Metod | Yol | Açıklama |
|---|---|---|
| `GET` | `/api/v1/analytics/performance` | AI performans analizi |
| `GET` | `/api/v1/analytics/anomalies` | Anomali tespiti |
| `GET` | `/api/v1/analytics/root-cause` | Kök neden analizi |
| `GET` | `/api/v1/analytics/capacity` | Kapasite planlaması |

---

## Kurulum ve Çalıştırma

### Gereksinimler

- Java 21+
- Maven 3.9+
- Docker ve Docker Compose
- (İsteğe bağlı) k6 — yük testi için

### 1. Tüm Servisleri Başlat

```bash
# PostgreSQL, Kafka, Prometheus, Grafana, Zipkin, Ollama
docker-compose up -d

# Servislerin hazır olmasını bekle
docker-compose ps
```

### 2. Uygulamayı Derle

```bash
# Testler atlanarak hızlı derleme
mvn clean package -DskipTests

# Üretilen jar çalıştır
java -jar target/spring-boot-outbox-pattern-*.jar
```

### 3. Geliştirme Modunda Çalıştır

```bash
# Dev profili: kısa zamanlayıcı gecikmeleri, DEBUG loglama
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
```

### 4. Docker İle Çalıştır

```bash
# Uygulamayı docker-compose içinde ayağa kaldır
docker-compose up -d app

# Logları izle
docker-compose logs -f app
```

### Kullanılabilir Servisler

| Servis | URL | Kimlik Bilgisi |
|---|---|---|
| REST API | http://localhost:8080 | — |
| Swagger UI | http://localhost:8080/swagger-ui.html | — |
| Actuator | http://localhost:8080/actuator | — |
| Prometheus | http://localhost:9090 | — |
| Grafana | http://localhost:3000 | admin / admin |
| Zipkin | http://localhost:9411 | — |
| Kafka UI | http://localhost:8081 | — |
| Ollama | http://localhost:11434 | — |

---

## Yapılandırma

### Profiller

| Profil | Amaç | Aktivasyon |
|---|---|---|
| `dev` | Kısa gecikmeler, verbose loglama | `SPRING_PROFILES_ACTIVE=dev` |
| `docker` | Ortam değişkeninden bağlantı | `SPRING_PROFILES_ACTIVE=docker` |
| `prod` | HikariCP=50, loglama minimize | `SPRING_PROFILES_ACTIVE=prod` |
| `test` | H2 bellek içi, EmbeddedKafka | Test sınıfları |

### Önemli Yapılandırma Değerleri

```yaml
outbox:
  scheduler:
    pending:
      fixed-delay: 5000        # Bekleyen event poll aralığı (ms)
    retry:
      fixed-delay: 60000       # Başarısız event retry aralığı (ms)
    stuck-detection:
      fixed-delay: 600000      # Takılı event tespiti (ms)
  cleanup:
    enabled: true
    retention-days: 7          # İşlenmiş event saklama süresi
    batch-size: 1000           # Temizleme batch boyutu

resilience4j:
  circuitbreaker:
    instances:
      kafkaPublisher:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        sliding-window-size: 20
  retry:
    instances:
      kafkaPublisher:
        max-attempts: 3
        wait-duration: 1s
```

### Ortam Değişkenleri (Docker)

```
DATABASE_URL=jdbc:postgresql://postgres:5432/outbox_db
DATABASE_USERNAME=postgres
DATABASE_PASSWORD=postgres
KAFKA_BOOTSTRAP_SERVERS=kafka:9092
OLLAMA_BASE_URL=http://ollama:11434
OPENAI_API_KEY=sk-...    # AI analitik için (isteğe bağlı)
```

---

## Gözlemlenebilirlik

### Prometheus Metrikleri

`http://localhost:8080/actuator/prometheus` üzerinden aşağıdaki metrikler alınır:

| Metrik | Açıklama |
|---|---|
| `outbox.events.pending` | Bekleyen event sayısı |
| `outbox.events.processed.total` | Toplam işlenen event |
| `outbox.events.failed.total` | Toplam başarısız event |
| `outbox.publish.duration` | Kafka yayın süresi histogramı |
| `resilience4j.circuitbreaker.state` | Devre kesici durumu |
| `kafka.producer.record-send-rate` | Kafka üretici oranı |
| `hikaricp.connections.active` | Aktif DB bağlantıları |

### Dağıtık İzleme (Zipkin)

Her istek ve Kafka mesajı `traceId` ve `spanId` ile etiketlenir:

```
HTTP POST /api/v1/transactions
  └─ span: TransactionService.createTransaction (db write)
       └─ span: OutboxPublisher.publishEventAsync (kafka)
```

Örnekleme oranı varsayılan olarak `1.0` (tüm istekler) — üretimde düşürülmesi önerilir.

### Sağlık Kontrolü

```bash
curl http://localhost:8080/actuator/health
```

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "kafka": { "status": "UP" },
    "diskSpace": { "status": "UP" }
  }
}
```

---

## Test Stratejisi

| Tür | Sınıf Deseni | Veritabanı | Kafka |
|---|---|---|---|
| Birim | `*Test.java` | H2 bellek içi | MockBean / EmbeddedKafka |
| Repository | `*RepositoryTest.java` | H2 (`@DataJpaTest`) | — |
| Controller | `*ControllerTest.java` | MockBean (`@WebMvcTest`) | MockBean |
| Entegrasyon | `*IntegrationTest.java` | H2 + EmbeddedKafka | EmbeddedKafka |
| Performans | `OutboxPerformanceTest.scala` | — (Gatling) | — |

```bash
# Tüm birim testleri
mvn test

# Tek test sınıfı
mvn test -Dtest=OutboxServiceTest

# Tek test metodu
mvn test -Dtest=OutboxServiceTest#shouldCreateOutboxEvent

# Entegrasyon testleri (Docker gerekli)
mvn verify -P integration-tests

# k6 yük testi
k6 run scripts/load-test.js
```

**Temel test kuralları:**
- Entegrasyon testleri sınıf başına `@DirtiesContext` kullanır.
- Asenkron doğrulamalar için `await().atMost(15, SECONDS)` (Awaitility) kullanılır.
- `OutboxService.createOutboxEvent()` açık transaction olmadan çağrılırsa test başarısız olmalıdır (Propagation.MANDATORY).

