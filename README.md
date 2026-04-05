# Capital ISO 20022 Gateway

A Spring Boot gateway that accepts JSON over REST and translates it into ISO 20022 SWIFT XML messages. Outbound messages are marshalled from generated JAXB classes, forwarded to the destination institution, and every interaction is persisted to a database for audit and reconciliation.

---

## Overview

| Concern | Technology |
|---|---|
| Runtime | Java 25 / Spring Boot 4.0.4 |
| Message standard | ISO 20022 (SWIFT XML) |
| Schema binding | JAXB 4 via `jaxb2-maven-plugin` |
| Persistence | Spring Data JPA / PostgreSQL (H2 in dev) |
| Security | Spring Security |
| Build | Maven |

---

## Supported Message Types

| Flow | Direction | ISO 20022 Message | XSD in use |
|---|---|---|---|
| Payment | Outbound | FIToFI Customer Credit Transfer | `pacs.008.001.14` |
| Payment Response | Inbound | FIToFI Payment Status Report | `pacs.002.001.16` |
| Payment Return | Outbound | Payment Return | `pacs.004.001.15` |
| Payment Return Response | Inbound | FIToFI Payment Status Report | `pacs.002.001.16` |
| Status Request | Outbound | FIToFI Payment Status Request | `pacs.028.001.07` |
| Status Response | Inbound | FIToFI Payment Status Report | `pacs.002.001.16` |
| AVS Request | Outbound | Identification Verification Request | `acmt.023.001.04` |
| AVS Response | Inbound | Identification Verification Report | `acmt.024.001.04` |

---

## Architecture

```
Client (JSON)
     │
     ▼
┌─────────────────────────────────────────┐
│           REST Controllers              │
│  POST /api/v1/payments/transfer         │
│  POST /api/v1/avs/verify                │
└────────────────┬────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────┐
│           Builder Services              │
│  Pacs008BuilderService                  │  ← populates ISO 20022 object graph
│  Acmt023BuilderService                  │
└────────────────┬────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────┐
│       Iso20022MarshallingService         │
│  marshal(Document) → XML string         │  ← JAXB / ObjectFactory
│  unmarshal(XML)    → Document           │
└───────────┬─────────────┬───────────────┘
            │             │
            ▼             ▼
    ┌───────────┐   ┌─────────────┐
    │  DB Log   │   │  HTTP POST  │
    │ (JPA)     │   │ (RestTemp.) │  → destination institution
    └───────────┘   └─────────────┘
```

### Message population

**Payment (pacs.008)**

| pacs.008 field | Source |
|---|---|
| `DbtrAgt` (Debtor Agent — our bank) | `application.properties` → `institution.*` |
| `Dbtr.Nm` (client name) | `TransferRequestDTO.firstName + lastName` |
| `DbtrAcct` (client account) | `TransferRequestDTO.debtorAccountNumber` |
| `CdtrAgt` (destination bank BIC) | `TransferRequestDTO.creditorAgentBic` |
| `Cdtr.Nm` (beneficiary name) | `TransferRequestDTO.creditorName` |
| `CdtrAcct` (beneficiary account) | `TransferRequestDTO.creditorAccountNumber` |

**AVS (acmt.023)**

| acmt.023 field | Source |
|---|---|
| `Assgnmt.Assgnr` (our institution) | `application.properties` → `institution.bic` |
| `Assgnmt.Assgne` (queried bank) | `AvsRequestDTO.bankBic` |
| `Vrfctn.PtyAndAcctId.Pty.Nm` | `AvsRequestDTO.firstName + lastName` |
| `Vrfctn.PtyAndAcctId.Acct` | `AvsRequestDTO.accountNumber` |

---

## Project Structure

```
src/
├── main/
│   ├── java/capital/one/capital/
│   │   ├── config/
│   │   │   ├── InstitutionProperties.java   # @ConfigurationProperties for institution.*
│   │   │   └── RestTemplateConfig.java
│   │   ├── controller/
│   │   │   ├── PaymentController.java       # POST /api/v1/payments/transfer
│   │   │   └── AvsController.java           # POST /api/v1/avs/verify
│   │   ├── dto/
│   │   │   ├── TransferRequestDTO.java
│   │   │   ├── AvsRequestDTO.java
│   │   │   └── AvsResponseDTO.java
│   │   ├── model/
│   │   │   ├── TransferLog.java             # DB entity — every payment attempt
│   │   │   ├── AvsLog.java                  # DB entity — every AVS check
│   │   │   └── TransferStatus.java          # PENDING | SENT | FAILED
│   │   ├── repository/
│   │   │   ├── TransferLogRepository.java
│   │   │   └── AvsLogRepository.java
│   │   └── service/
│   │       ├── Pacs008BuilderService.java   # builds pacs.008 Document
│   │       ├── Acmt023BuilderService.java   # builds acmt.023 Document + parses acmt.024
│   │       └── Iso20022MarshallingService.java  # JAXB marshal / unmarshal for all types
│   ├── resources/
│   │   ├── application.properties           # base config + institution details
│   │   ├── application-dev.properties       # H2 in-memory
│   │   ├── application-uat.properties       # PostgreSQL (capital_uat)
│   │   └── application-prod.properties      # PostgreSQL (capital_prod)
│   └── xjb/
│       ├── acmt-bindings.xjb               # per-schema package assignments (acmt)
│       └── pacs-bindings.xjb               # per-schema package assignments (pacs)
└── iso/
    ├── acmt/   # 34 acmt.*.xsd files
    └── pacs/   # 10 pacs.*.xsd files
```

---

## Getting Started

### Prerequisites

- Java 25+
- Maven 3.9+
- PostgreSQL (for `uat` / `prod` profiles)

### 1. Generate JAXB classes from XSD

This must be run before the first compile. It generates Java classes from all XSD files into `target/generated-sources/jaxb/`.

```bash
mvn generate-sources
```

### 2. Run locally (H2, no database setup required)

```bash
mvn spring-boot:run -P dev
```

H2 console is available at `http://localhost:81460/h2-console`
- JDBC URL: `jdbc:h2:mem:capitaldb`
- Username: `sa` / Password: *(empty)*

### 3. Run against PostgreSQL

```bash
# UAT
DB_USERNAME=capital DB_PASSWORD=secret mvn spring-boot:run -P uat

# Production
DB_USERNAME=capital DB_PASSWORD=secret mvn spring-boot:run -P prod
```

### 4. Build a deployable JAR

```bash
mvn clean package -P prod -DskipTests
java -jar target/capital-0.0.1-SNAPSHOT.jar
```

---

## Configuration

All institution-level settings live in `application.properties` and are bound via `InstitutionProperties`.

```properties
institution.name=Capital One Bank
institution.bic=CAPZAZJJXXX
institution.country=ZA
institution.street=123 Bank Street
institution.city=Johannesburg
institution.postal-code=2001
institution.creditor-agent-url=https://receive.org/api/v2/credit
institution.avs-url=https://receive.org/api/v2/avs
```

> The **destination bank BIC** (`creditorAgentBic` / `bankBic`) is supplied per request in the JSON body — it is not stored in config because it changes per transaction.

---

## API Reference

### POST `/api/v1/payments/transfer`

Initiates a credit transfer. Builds a `pacs.008.001.14` message and forwards it to the configured creditor agent URL.

**Request**
```json
{
  "firstName": "John",
  "lastName": "Doe",
  "debtorAccountNumber": "4083265412",
  "creditorAgentBic": "ABCDZAJJXXX",
  "creditorName": "Jane Smith",
  "creditorAccountNumber": "9876543210",
  "amount": 1500.00,
  "currency": "ZAR",
  "remittanceInfo": "Invoice INV-2026-001"
}
```

**Response** — `200 OK`: raw XML response from the destination institution.

**Transfer lifecycle logged to `transfer_log`:**

| Status | Meaning |
|---|---|
| `PENDING` | Message built and persisted, not yet sent |
| `SENT` | Destination returned HTTP 2xx |
| `FAILED` | Network error or non-2xx response |

---

### POST `/api/v1/avs/verify`

Verifies that an account holder name matches an account at a given bank. Builds an `acmt.023.001.04` message and parses the `acmt.024.001.04` response.

**Request**
```json
{
  "firstName": "Jane",
  "lastName": "Smith",
  "accountNumber": "9876543210",
  "bankBic": "ABCDZAJJXXX"
}
```

**Response** — `200 OK`
```json
{
  "verified": true,
  "referenceId": "REF-A1B2C3D4E5F6G7H8",
  "reasonCode": null,
  "registeredName": "Jane Smith",
  "registeredAccount": "9876543210"
}
```

When `verified` is `false`, `reasonCode` contains the ISO 20022 reason code returned by the responding bank (e.g. `NMAT` = no match, `NMNS` = name mismatch).

**AVS lifecycle logged to `avs_log`.**

---

## Database Schema

Two audit tables are created automatically by JPA (`ddl-auto=create-drop` in dev, `validate` in uat/prod — run migrations manually for uat/prod).

**`transfer_log`**

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | Auto-increment |
| `message_id` | VARCHAR | Unique pacs.008 MsgId |
| `end_to_end_id` | VARCHAR | pacs.008 EndToEndId |
| `debtor_name` | VARCHAR | Client full name |
| `debtor_account` | VARCHAR | Client account number |
| `creditor_name` | VARCHAR | Beneficiary name |
| `creditor_account` | VARCHAR | Beneficiary account |
| `amount` | DECIMAL(18,5) | Transfer amount |
| `currency` | CHAR(3) | ISO 4217 |
| `status` | VARCHAR | PENDING / SENT / FAILED |
| `raw_xml_sent` | TEXT | Full pacs.008 XML |
| `response_body` | TEXT | Raw response from institution |
| `created_at` | TIMESTAMP | Set on insert |

**`avs_log`**

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | Auto-increment |
| `message_id` | VARCHAR | Unique acmt.023 MsgId |
| `reference_id` | VARCHAR | acmt.023 Vrfctn.Id |
| `account_holder_name` | VARCHAR | Name submitted for verification |
| `account_number` | VARCHAR | Account submitted for verification |
| `bank_bic` | VARCHAR | Queried bank BIC |
| `verified` | BOOLEAN | null = no response yet |
| `reason_code` | VARCHAR | ISO reason code on mismatch |
| `status` | VARCHAR | PENDING / SENT / FAILED |
| `raw_xml_sent` | TEXT | Full acmt.023 XML |
| `raw_xml_received` | TEXT | Full acmt.024 XML |
| `created_at` | TIMESTAMP | Set on insert |

---

## XSD / JAXB Notes

All XSD files are under `src/main/java/capital/one/capital/iso/`. Each schema is assigned its own Java package via the `.xjb` bindings files to avoid `ObjectFactory` collisions (every ISO 20022 schema defines a root element named `Document`).

| Schema family | Java packages |
|---|---|
| `acmt.*` | `capital.one.capital.iso.acmt.acmt{msg}_{func}_{ver}` |
| `pacs.*` | `capital.one.capital.iso.pacs.pacs{msg}_{func}_{ver}` |

Example: `pacs.008.001.14` → `capital.one.capital.iso.pacs.pacs008_001_14`

The `Iso20022MarshallingService` caches one `JAXBContext` per package (thread-safe, expensive to create) and exposes named marshal/unmarshal methods for each supported message type.

---

## Profiles

| Profile | Database | `ddl-auto` | `show-sql` | Active by default |
|---|---|---|---|---|
| `dev` | H2 in-memory | `create-drop` | yes | yes |
| `uat` | PostgreSQL `capital_uat` | `validate` | yes | no |
| `prod` | PostgreSQL `capital_prod` | `validate` | no | no |

Activate with `-P <profile>` on any Maven command.

---

## Local End-to-End Test

A shell script exercises the full payment flow against the running dev server.

```bash
chmod +x test-flow.sh
./test-flow.sh
```

The dev profile runs a built-in `MockDownstreamController` on the same port so no external institution is needed. All four downstream calls are intercepted locally and return positive responses.

### Sample output

```
━━━  Checking server at http://localhost:81460  ━━━
✔  Server is up

━━━  STEP 1 — AVS: Verify beneficiary account  ━━━
  Checking: 'Jane Smith' against account '0987654321' at BIC 'ABCDZAJJXXX'
HTTP 200
{"reasonCode":null,"referenceId":"REF-D93CA5F7A95C4F97","registeredAccount":null,"registeredName":null,"verified":true}
✔  AVS passed — account holder verified

━━━  STEP 2 — Send: Credit transfer (pacs.008)  ━━━
  John Doe (1234567890) → Jane Smith (0987654321)
  Amount: 1500.00 ZAR  |  Ref: Test payment 20260325061230
HTTP 200
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.16">
  <FIToFIPmtStsRpt>
    <GrpHdr>
      <MsgId>MOCK-1A65AF7147404418</MsgId>
      <CreDtTm>2026-03-25T06:12:31.809971031+02:00</CreDtTm>
    </GrpHdr>
    <TxInfAndSts>
      <OrgnlGrpInf>
        <OrgnlMsgId>MSG-5D75E258EFE448EF</OrgnlMsgId>
        <OrgnlMsgNmId>pacs.008.001.14</OrgnlMsgNmId>
      </OrgnlGrpInf>
      <OrgnlEndToEndId>E2E-4AE4BC46D579455D</OrgnlEndToEndId>
      <TxSts>ACCC</TxSts>
    </TxInfAndSts>
  </FIToFIPmtStsRpt>
</Document>
✔  Transfer accepted — msgId: MSG-5D75E258EFE448EF

━━━  STEP 3 — Status: Query payment status (pacs.028)  ━━━
  Querying status for msgId: MSG-5D75E258EFE448EF
HTTP 200
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.16">
  <FIToFIPmtStsRpt>
    <GrpHdr>
      <MsgId>MOCK-AE9366E74DC24C27</MsgId>
      <CreDtTm>2026-03-25T06:12:32.343214418+02:00</CreDtTm>
    </GrpHdr>
    <TxInfAndSts>
      <OrgnlGrpInf>
        <OrgnlMsgId>MSG-5D75E258EFE448EF</OrgnlMsgId>
        <OrgnlMsgNmId>pacs.008.001.14</OrgnlMsgNmId>
      </OrgnlGrpInf>
      <OrgnlEndToEndId>N/A</OrgnlEndToEndId>
      <TxSts>ACCC</TxSts>
    </TxInfAndSts>
  </FIToFIPmtStsRpt>
</Document>
✔  Status query sent successfully

━━━  STEP 4 — Return: Return payment (pacs.004) — reason: CUST  ━━━
  Returning msgId: MSG-5D75E258EFE448EF  |  Reason: CUST
HTTP 200
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.16">
  <FIToFIPmtStsRpt>
    <GrpHdr>
      <MsgId>MOCK-6446052CE5804BEC</MsgId>
      <CreDtTm>2026-03-25T06:12:32.469128151+02:00</CreDtTm>
    </GrpHdr>
    <TxInfAndSts>
      <OrgnlGrpInf>
        <OrgnlMsgId>MSG-5D75E258EFE448EF</OrgnlMsgId>
        <OrgnlMsgNmId>pacs.008.001.14</OrgnlMsgNmId>
      </OrgnlGrpInf>
      <OrgnlEndToEndId>N/A</OrgnlEndToEndId>
      <TxSts>ACCP</TxSts>
    </TxInfAndSts>
  </FIToFIPmtStsRpt>
</Document>
✔  Return request sent successfully

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Test flow complete
  Base URL : http://localhost:81460
  msgId    : MSG-5D75E258EFE448EF
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

### Script options

```bash
# Run against a different host
BASE_URL=http://myserver:9090 ./test-flow.sh

# Skip AVS + transfer if you already have a msgId
MSG_ID=MSG-5D75E258EFE448EF ./test-flow.sh

# Use a specific return reason code
REASON_CODE=MD06 ./test-flow.sh
```

### Bulk / batch payments

After the single-transaction flow the script automatically runs a batch of randomised payments. Each batch transaction performs AVS verification followed by a credit transfer (pacs.008) with randomly generated South African parties and amounts.

**Control the batch size**

```bash
# Run 50 randomised transactions (default is 12)
BATCH_COUNT=50 ./test-flow.sh

# Combine with a custom host
BATCH_COUNT=100 BASE_URL=http://myserver:9090 ./test-flow.sh
```

**What each batch transaction randomises**

| Field | Range / pool |
|---|---|
| Debtor / Creditor first name | 15 South African first names |
| Debtor / Creditor surname | 15 South African surnames |
| Debtor / Creditor account | Random 10-digit number |
| Creditor BIC | 6 South African bank BICs |
| Amount | ZAR 1 000.00 – 2 500.00 (random cents) |
| Remittance ref | `BATCH-<seq>-<timestamp>` |

**Batch summary output**

At the end of the run a summary line is printed along with every `msgId` that was captured from a successful response:

```
Batch result: 12 passed  0 failed  (of 12)

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Test flow complete
  Capital  : http://localhost:8460
  ZW Auth  : http://localhost:8080
  msgId    : MSG-5D75E258EFE448EF
  Batch    : 12 passed / 0 failed of 12
  Batch msgIds captured: 12
    • MSG-A1B2C3D4E5F6G7H8
    • MSG-...
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

A transaction counts as **passed** on HTTP 200 or 502 (upstream mock unavailable). It counts as **failed** on any other status (e.g. 401 token rejection). The script continues even when individual transactions fail so the full batch always runs.

### Mock downstream responses (dev only)

In the `dev` profile all outbound ISO 20022 calls are routed to `/mock/*` on the same server:

| Endpoint | Message sent | Mock response |
|---|---|---|
| `POST /mock/credit` | pacs.008 | pacs.002 `TxSts=ACCC` |
| `POST /mock/status` | pacs.028 | pacs.002 `TxSts=ACCC` |
| `POST /mock/return` | pacs.004 | pacs.002 `TxSts=ACCP` |
| `POST /mock/avs`    | acmt.023 | acmt.024 `Vrfctn=true` |

The mock controller (`MockDownstreamController`) is excluded from `uat` and `prod` builds via `@Profile("dev")`.
