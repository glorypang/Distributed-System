# 분산 뱅킹 시스템 (Distributed Banking System)

## 프로젝트 개요

금융권 분산 시스템 학습을 위한 토이 프로젝트입니다. **샤딩(Sharding)**, **Saga 패턴**, **동시성 제어**를 실전처럼 구현했습니다.

### 핵심 기술
- **Database Sharding**: 계좌번호 해시 기반 3-way 샤딩
- **Saga Pattern**: RabbitMQ 기반 분산 트랜잭션 + 보상 트랜잭션
- **Concurrency Control**: Thread Pool + 멱등성 보장

---

## 아키텍처

### 시스템 구성도

```
┌─────────────────────────────────────────────────────────────┐
│                     Spring Boot Application                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │ REST API     │  │ Saga         │  │ Thread Pool  │       │
│  │ Controller   │→ │ Orchestrator │→ │ (10-50)      │       │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
│         │                  ↓                                │
│  ┌──────────────┐  ┌──────────────────────────────┐         │
│  │ Sharding     │  │    RabbitMQ Message Queue    │         │
│  │ Service      │  │     (Command/Event)          │         │
│  └──────────────┘  └──────────────────────────────┘         │
└─────────────────────────────┬───────────────────────────────┘
                              │
              ┌─────────┬─────┴────┬─────────┐
              ▼         ▼          ▼         ▼
          ┌────────┐┌────────┐┌────────┐┌──────────┐
          │MySQL   ││MySQL   ││MySQL   ││RabbitMQ  │
          │Shard 0 ││Shard 1 ││Shard 2 ││          │
          │:3309   ││:3307   ││:3308   ││:5672     │
          └────────┘└────────┘└────────┘└──────────┘
```

### 샤딩 전략

**해시 기반 샤딩**:
```java
shardId = accountNumber.hashCode() % 3
```

| 샤드 | 포트 | 예시 계좌 |
|------|------|-----------|
| Shard 0 | 3309 | 1234-5678 (hash % 3 == 0) |
| Shard 1 | 3307 | 2345-6789 (hash % 3 == 1) |
| Shard 2 | 3308 | 3456-7890 (hash % 3 == 2) |

---

## Saga 패턴 구현

### Cross-Shard 송금 흐름

```
[계좌A: Shard 0] ──────> [계좌B: Shard 1]

Step 1: Orchestrator가 출금 명령 발행
   └─> RabbitMQ Command Queue

Step 2: CommandHandler가 출금 처리
   └─> 계좌A에서 -1000원 (멱등성 체크)
   └─> DEBIT_SUCCESS 이벤트 발행

Step 3: EventHandler가 입금 명령 발행
   └─> RabbitMQ Command Queue

Step 4: CommandHandler가 입금 처리
   └─> 계좌B에 +1000원 (멱등성 체크)
   └─> CREDIT_SUCCESS 이벤트 발행

Step 5: Orchestrator가 트랜잭션 완료 처리
```

### 보상 트랜잭션 (Rollback)

**입금 실패 시**:
```
출금 성공 → 입금 실패 → 보상 명령 발행 → 출금 취소 (+1000원)
```

**멱등성 보장**:
```java
// Transaction 엔티티에 플래그 추가
private boolean debitProcessed = false;
private boolean creditProcessed = false;

// 중복 처리 방지
if (transaction.isDebitProcessed()) {
    log.warn("출금 이미 처리됨 (중복 방지)");
    return;
}
```

---

## 빠른 시작

### 1. 환경 요구사항

- **Java**: 17 이상
- **Docker**: 최신 버전
- **Gradle**: 8.x (wrapper 포함)

### 2. Docker 컨테이너 실행

```bash
docker-compose up -d
```

**실행되는 컨테이너**:
- MySQL Shard 0 (3309)
- MySQL Shard 1 (3307)
- MySQL Shard 2 (3308)
- RabbitMQ (5672, 15672)

### 3. 애플리케이션 실행

```bash
./gradlew bootRun
```

### 4. API 테스트

#### 계좌 생성
```bash
curl -X POST http://localhost:8080/api/accounts \
  -H "Content-Type: application/json" \
  -d '{"ownerName": "홍길동", "initialBalance": 100000}'
```

**응답**:
```json
{
  "accountNumber": "1234-5678",
  "ownerName": "홍길동",
  "balance": 100000.00
}
```

#### 계좌 조회
```bash
curl http://localhost:8080/api/accounts/1234-5678
```

#### 송금
```bash
curl -X POST http://localhost:8080/api/accounts/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "fromAccount": "1234-5678",
    "toAccount": "5678-1234",
    "amount": 10000
  }'
```

**응답**:
```json
{
  "sagaId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "PENDING"
}
```

---

## 성능 테스트

### 동시 송금 테스트 (PowerShell)

**concurrent-test.ps1** 실행:
```powershell
.\concurrent-test.ps1
```

**테스트 시나리오**:
- 계좌 2개 생성 (각 1,000,000원)
- 50개 송금 동시 실행 (각 1,000원)
- 최종 잔액 검증

**예상 결과**:
```
계좌1: 950,000원 (1,000,000 - 50,000)
계좌2: 1,050,000원 (1,000,000 + 50,000)
```

### Thread Pool 설정

```java
// AsyncConfig.java
CorePoolSize: 10     // 기본 스레드 수
MaxPoolSize: 50      // 최대 스레드 수
QueueCapacity: 100   // 대기 큐 크기
```

**RabbitMQ Listener**:
```java
@RabbitListener(
    queues = "saga.command.queue",
    concurrency = "5-10"  // 5~10개 동시 처리
)
```

---

## 프로젝트 구조

```
src/main/java/com/example/tempbank/
├── config/
│   ├── AsyncConfig.java              # Thread Pool 설정
│   ├── DataSourceConfig.java         # 3-way Sharding 설정
│   ├── RabbitMQConfig.java           # RabbitMQ Queue/Exchange
│   ├── ShardRoutingDataSource.java   # 동적 DataSource 라우팅
│   └── ShardContextHolder.java       # ThreadLocal 샤드 컨텍스트
├── controller/
│   └── AccountController.java        # REST API
├── domain/
│   ├── Account.java                  # 계좌 엔티티 (@Version 낙관적 락)
│   ├── Transaction.java              # 거래 엔티티 (멱등성 플래그)
│   └── TransactionStatus.java        # 거래 상태 Enum
├── dto/
│   ├── AccountCreateRequest.java
│   ├── AccountResponse.java
│   └── TransferRequest.java
├── repository/
│   ├── AccountRepository.java
│   └── TransactionRepository.java
├── saga/
│   ├── SagaOrchestrator.java         # Saga 조율자
│   ├── SagaCommandHandler.java       # 명령 처리 (멱등성)
│   ├── SagaEventHandler.java         # 이벤트 처리
│   ├── SagaCommand.java              # 명령 DTO
│   └── SagaEvent.java                # 이벤트 DTO
├── service/
│   ├── AccountService.java           # 비즈니스 로직
│   └── ShardingService.java          # 샤딩 로직
├── exception/
│   └── GlobalExceptionHandler.java   # 전역 예외 처리
└── TempBankApplication.java          # Main
```

---

## 기술 스택

| 카테고리 | 기술 |
|---------|------|
| **Framework** | Spring Boot 4.0.4 |
| **Language** | Java 17 |
| **Database** | MySQL 8.0 (3 Shards) |
| **Message Queue** | RabbitMQ 3.x |
| **ORM** | JPA/Hibernate 7.x |
| **Build Tool** | Gradle 8.x |
| **Container** | Docker Compose |

---

## 핵심 학습 포인트

### 1. Database Sharding
- 계좌번호 해시 기반 수평 분할
- `AbstractRoutingDataSource`로 동적 라우팅
- `ThreadLocal`로 샤드 컨텍스트 관리

### 2. Saga Pattern
- Orchestration 방식 구현
- RabbitMQ로 비동기 메시징
- 보상 트랜잭션 (Compensating Transaction)

### 3. Concurrency Control
- Thread Pool (10-50 스레드)
- 낙관적 락 (`@Version`)
- 멱등성 보장 (Idempotency)




