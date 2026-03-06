# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Derleme ve Çalıştırma Komutları

```bash
# Derleme (testler atlanır)
mvn clean package -DskipTests

# Tüm birim testlerini çalıştır
mvn test

# Tek bir test sınıfı çalıştır
mvn test -Dtest=OutboxServiceTest

# Tek bir test metodu çalıştır
mvn test -Dtest=OutboxServiceTest#shouldCreateOutboxEvent

# Entegrasyon testleri (Docker gerekli — TestContainers kullanır)
mvn verify -P integration-tests

# Yerel ortamı başlat (PostgreSQL, Kafka, Prometheus, Grafana, Zipkin)
docker-compose up -d

# Yerel ortamı durdur
docker-compose down

# K6 yük testi (k6 kurulu olmalı)
k6 run scripts/load-test.js
```

## Yerel Geliştirme Gereksinimleri

- Java 21 (pom.xml'de preview features etkin)
- PostgreSQL 16 → `localhost:5432`, veritabanı: `outbox_db`, kullanıcı: `postgres/postgres`
- Kafka → `localhost:9092`
- İsteğe bağlı: AI analitik özellikleri için `OPENAI_API_KEY` ortam değişkeni

Profil `SPRING_PROFILES_ACTIVE` ile seçilir (varsayılan: `dev`). `dev` profili zamanlayıcı gecikmelerini kısaltır ve log seviyesini artırır.

## API Endpoint'leri

- REST API: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Sağlık kontrolü: `http://localhost:8080/actuator/health`
- Prometheus metrikleri: `http://localhost:8080/actuator/prometheus`

Önemli API yolları:
- `POST /api/v1/transactions` — Transaction ve outbox event'i tek transaction'da atomik oluşturur
- `GET /api/v1/outbox/statistics` — Durumlara göre event sayıları
- `GET /api/v1/outbox/dead-letter` — Dead letter kuyruğu
- `POST /api/v1/outbox/events/{id}/retry` — FAILED/DEAD_LETTER event'i manuel yeniden dene
- `GET /api/v1/analytics/performance` — AI performans analizi (OpenAI anahtarı gerekli)

## Mimari Genel Bakış

### Outbox Pattern Uygulaması

Temel garanti: bir iş yazma işlemi (örn. `Transaction`) ve outbox event'i **aynı veritabanı transaction'ında** commit edilir; bu sayede ikili yazma riski ortadan kalkar.

```
TransactionService.createTransaction()
  └─ @Transactional
      ├─ transactionRepository.save(transaction)
      └─ outboxService.createOutboxEvent(...)   ← Propagation.MANDATORY
```

`OutboxService.createOutboxEvent()` metodu `Propagation.MANDATORY` kullanır — yalnızca zaten açık bir transaction içinde çağrılabilir. Bağımsız çağrılırsa istisna fırlatır.

### Event Yaşam Döngüsü

```
PENDING → PROCESSING → PROCESSED
                    ↘ FAILED → (yeniden deneme) → DEAD_LETTER (maxRetry=3 aşıldı)
```

Durum geçişleri `OutboxService` tarafından yönetilir. 30 dakikadan uzun süre PROCESSING durumunda kalan event'ler (takılı event'ler) zamanlanmış görev tarafından zorla FAILED'a alınır.

### Polling ve Yayınlama Akışı

`OutboxScheduler` (ShedLock korumalı) üç farklı zaman planlamasıyla `OutboxPublisher`'ı tetikler:

| Zamanlayıcı metodu | Varsayılan aralık | Kilit süresi |
|---|---|---|
| `processPendingEvents()` | 5 saniye | maks 4 dakika |
| `retryFailedEvents()` | 60 saniye | maks 4 dakika |
| `detectStuckEvents()` | 10 dakika | maks 5 dakika |

`OutboxPublisher.publishEventAsync()` metodu Resilience4j ile `@CircuitBreaker` + `@Retry` (her ikisi de `kafkaPublisher` adıyla) ile dekorasyona sahiptir. Başarısız olursa `publishFallback()` devreye girer ve `markAsFailed()` çağrılır.

`OutboxEventRepository.findPendingEventsForProcessing()` eş zamanlı işlemeyi önlemek için **kötümser yazma kilidi** (`PESSIMISTIC_WRITE`) alır. ShedLock dağıtık ortamda ikinci bir dışlama katmanı sağlar.

### Eş Zamanlılık Kontrolü

- `OutboxEvent` ve `Transaction` üzerinde `@Version` → iyimser kilitleme
- Fetch sorgusunda kötümser kilitleme → iki pod'un aynı event'leri almasını engeller
- ShedLock → pod'lar arasında zamanlayıcı tekrar çalışmasını engeller

### Veritabanı Migrasyonları

Flyway şema yönetimini `src/main/resources/db/migration/` altında yürütür:
- `V1` — outbox_events tablosu
- `V2` — transactions tablosu
- `V3` — shedlock tablosu
- `V4` — pgvector store (AI embedding için)
- `V5` — audit_log tablosu

`ddl-auto: validate` — Hibernate yalnızca doğrulama yapar; test dışında hiçbir profilde otomatik tablo oluşturma/silme yapılmaz.

### Kafka Topic'leri

| Topic | Amaç |
|---|---|
| `springboot.transactions` | Transaction domain event'leri |
| `springboot.payments` | Ödeme domain event'leri |
| `springboot.embargo` | Ambargo kontrol event'leri |

Üretici: idempotent, `acks=all`, Snappy sıkıştırma, `CompletableFuture` ile asenkron. Tüketici: manuel onay (`MANUAL`), concurrency=3.

### AI Analitik (Spring AI)

`AIAnalyticsService` performans analizi, anomali tespiti, kapasite planlaması ve kök neden analizi için Spring AI + OpenAI GPT-4 kullanır. RAG tabanlı semantik event pattern araması için `PgVectorStore` (pgvector uzantısı) kullanılır.

Tüm AI özellikleri `OPENAI_API_KEY` gerektirir ve `/api/v1/analytics/*` üzerinden erişilir; çekirdek outbox akışından bağımsızdır.

### Resilience4j Yapılandırması

Circuit breaker `kafkaPublisher`: %50 hata eşiği, 20 çağrılık kayan pencere, açık durumda 30 saniye bekleme. Retry `kafkaPublisher`: 3 deneme, 1 saniye bekleme; yalnızca `KafkaException` ve `TimeoutException` için tetiklenir.

## Test Stratejisi

| Test türü | Framework | Veritabanı | Kafka |
|---|---|---|---|
| Birim (`*Test.java`) | JUnit5 + Mockito | H2 bellek içi | MockBean / EmbeddedKafka |
| Repository (`*RepositoryTest.java`) | `@DataJpaTest` | H2 | — |
| Controller (`*ControllerTest.java`) | `@WebMvcTest` | MockBean | MockBean |
| Entegrasyon (`*IntegrationTest.java`) | `@SpringBootTest` | H2 | EmbeddedKafka |
| Performans (`OutboxPerformanceTest.scala`) | Gatling | — | — |

Entegrasyon testleri sınıf başına `@DirtiesContext` kullanır; asenkron doğrulamalar için Awaitility (`await().atMost(15s)`) tercih edilir.

