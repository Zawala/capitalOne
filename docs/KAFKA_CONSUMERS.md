# Kafka Consumer Guide — Capital One Payment Events

This document describes the Kafka topics published by the Capital One payment service and is intended for teams building downstream consumers.

---

## Broker

| Setting           | Value                                          |
|-------------------|------------------------------------------------|
| Bootstrap servers | `KAFKA_BOOTSTRAP_SERVERS` env var (default `localhost:9092`) |
| Key serializer    | `StringSerializer`                             |
| Value serializer  | `StringSerializer` (JSON string)               |

---

## Topics

### `wallet-deposits`

Published when the service **receives an inbound credit transfer** — i.e. funds arriving at this institution from another bank.

- Triggered by: `POST /api/v1/payments/receive`
- Source message: ISO 20022 pacs.008 (any version)
- Use this topic for: crediting customer wallets, deposit notifications, reconciliation

### `wallet-credits`

Published when the service **sends an outbound credit transfer** that has been successfully acknowledged by the destination bank.

- Triggered by: `POST /api/v1/payments/transfer` — only published on `HTTP 200` from the creditor agent; failed transfers do **not** produce a message
- Source message: ISO 20022 pacs.008.001.14
- Use this topic for: debit confirmations, outbound ledger updates, audit trails

---

## Message Format

Both topics publish the same JSON structure. The Kafka record **key** is the ISO 20022 `MsgId`.

```json
{
  "messageId":           "20240416212006PROD1114",
  "senderInstitution":   "212006",
  "senderName":          "Peterson Tengende",
  "senderAccount":       "+27-682584738",
  "amount":              10,
  "currency":            "ZAR",
  "receiverInstitution": "192003",
  "receiverName":        "Yogita Dayabhai",
  "receiverAccount":     "+260-965929918"
}
```

### Field Reference

| Field                | Type           | Description                                                                 |
|----------------------|----------------|-----------------------------------------------------------------------------|
| `messageId`          | String         | ISO 20022 `GrpHdr/MsgId`. Also the Kafka record key. Unique per message.   |
| `senderInstitution`  | String         | BIC or local scheme ID of the sending bank (`InstgAgt`)                     |
| `senderName`         | String         | Full name of the debtor (payer)                                             |
| `senderAccount`      | String         | Debtor account number or mobile number for wallet transfers                 |
| `amount`             | Decimal        | Transaction amount. No rounding applied — use as received.                  |
| `currency`           | String (ISO 4217) | 3-letter currency code e.g. `ZAR`, `USD`, `ZMW`                         |
| `receiverInstitution`| String         | BIC or local scheme ID of the receiving bank (`InstdAgt`)                   |
| `receiverName`       | String         | Full name of the creditor (payee)                                           |
| `receiverAccount`    | String         | Creditor account number or mobile number for wallet transfers               |

**Notes:**
- `senderInstitution` / `receiverInstitution` will be a **BIC** (e.g. `CAPZAZJJXXX`) when the pacs.008 message uses `BICFI`, or a **local clearing code** (e.g. `212006`) when it uses `Othr/Id`.
- `senderAccount` / `receiverAccount` will be a **mobile number** (e.g. `+27-682584738`) for wallet-to-wallet transfers where no formal account number is present in the pacs.008 payload.

---

## Consumer Example (Spring Kafka)

```java
@KafkaListener(topics = "wallet-deposits", groupId = "wallet-service")
public void onDeposit(ConsumerRecord<String, String> record) {
    String messageId = record.key();
    WalletPaymentEvent event = objectMapper.readValue(record.value(), WalletPaymentEvent.class);
    // credit receiverAccount with event.amount() in event.currency()
}

@KafkaListener(topics = "wallet-credits", groupId = "wallet-service")
public void onCredit(ConsumerRecord<String, String> record) {
    String messageId = record.key();
    WalletPaymentEvent event = objectMapper.readValue(record.value(), WalletPaymentEvent.class);
    // confirm debit on senderAccount
}
```

### `WalletPaymentEvent` record (copy into your service)

```java
public record WalletPaymentEvent(
    String     messageId,
    String     senderInstitution,
    String     senderName,
    String     senderAccount,
    BigDecimal amount,
    String     currency,
    String     receiverInstitution,
    String     receiverName,
    String     receiverAccount
) {}
```

---

## Guarantees and Error Handling

| Concern             | Behaviour                                                                 |
|---------------------|---------------------------------------------------------------------------|
| Delivery guarantee  | Producer uses `acks=all` with 3 retries — at-least-once delivery          |
| Duplicates          | Possible on retry. Use `messageId` as an idempotency key.                 |
| Ordering            | Messages with the same `messageId` key land on the same partition         |
| Failed transfers    | `wallet-credits` is only published on confirmed sends — no poison messages |
| Malformed inbound XML | Rejected at `/receive` with HTTP 400 — nothing is published to Kafka    |

---

## Contact

Raise issues against the Capital One payment service team if you observe missing messages, unexpected field values, or schema changes.
