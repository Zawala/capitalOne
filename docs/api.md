# Capital ISO 20022 Gateway — API Reference

Base URL: `http://localhost:8080`

---

## 1. Credit Transfer

**`POST /api/v1/payments/transfer`**

Sends a payment from a debtor account to a creditor account at another bank.
Internally this builds and forwards a **pacs.008.001.14** FI-to-FI credit transfer message.

**Request body**

| Field | Type | Required | Description |
|---|---|---|---|
| `firstName` | string | yes | Debtor first name |
| `lastName` | string | yes | Debtor last name |
| `debtorAccountNumber` | string | yes | Debtor account number (IBAN or local) |
| `amount` | decimal | yes | Amount to transfer |
| `currency` | string | yes | ISO 4217 currency code, e.g. `ZAR`, `USD` |
| `creditorName` | string | yes | Beneficiary full name |
| `creditorAccountNumber` | string | yes | Beneficiary account number (IBAN or local) |
| `creditorAgentBic` | string | yes | BIC of the destination bank |
| `remittanceInfo` | string | no | Optional free-text payment reference |

**Response** — pacs.002 XML from the creditor agent on success, or a plain-text error message.

```bash
curl -s -X POST http://localhost:8080/api/v1/payments/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "John",
    "lastName": "Doe",
    "debtorAccountNumber": "1234567890",
    "amount": 5000.00,
    "currency": "ZAR",
    "creditorName": "Jane Smith",
    "creditorAccountNumber": "0987654321",
    "creditorAgentBic": "ABCDZAJJXXX",
    "remittanceInfo": "Invoice INV-2024-001"
  }'
```

---

## 2. Payment Status Query

**`GET /api/v1/payments/status?msgId={msgId}`**

Requests a status update on a previously submitted payment.
Internally this builds and forwards a **pacs.028.001.07** FIToFIPaymentStatusRequest.

**Query parameters**

| Parameter | Required | Description |
|---|---|---|
| `msgId` | yes | `messageId` of the original credit transfer, e.g. `MSG-ABCDEF1234567890` |

**Response** — pacs.002 XML status report on success, or a plain-text error message.
Returns **404** if no transfer with that `msgId` is found in the log.

```bash
curl -s "http://localhost:8080/api/v1/payments/status?msgId=MSG-ABCDEF1234567890"
```

### Payment status codes

| Code | Meaning |
|------|---------|
| `ACCP` | Accepted — technical and customer profile checks passed |
| `ACCC` | Accepted — funds have settled on the creditor's account |
| `RJCT` | Rejected — see the reason code in the pacs.002 response for details |

---

## 3. Payment Return

**`GET /api/v1/payments/return?msgId={msgId}&reasonCode={code}`**

Initiates a return of a previously sent payment.
Internally this builds and forwards a **pacs.004.001.15** PaymentReturn message using the
original transfer details looked up from the transaction log.

**Query parameters**

| Parameter | Required | Description |
|---|---|---|
| `msgId` | yes | `messageId` of the original credit transfer to return |
| `reasonCode` | no | Why the payment is being returned (see table below). Defaults to `DUPL`. |

**Response** — pacs.002 XML return response on success, or a plain-text error message.
Returns **400** if `reasonCode` is not in the supported list.
Returns **404** if no transfer with that `msgId` is found in the log.

```bash
# Return with default reason (DUPL — possible duplicate)
curl -s "http://localhost:8080/api/v1/payments/return?msgId=MSG-ABCDEF1234567890"

# Return at customer request
curl -s "http://localhost:8080/api/v1/payments/return?msgId=MSG-ABCDEF1234567890&reasonCode=CUST"

# Return due to fraud
curl -s "http://localhost:8080/api/v1/payments/return?msgId=MSG-ABCDEF1234567890&reasonCode=MD06"
```

### Supported return reason codes

| Code | When to use |
|------|-------------|
| `AM01` | Amount is zero |
| `AM02` | Amount is not permitted for this payment type |
| `AM03` | Currency is not accepted |
| `AM05` | Payment appears to be a duplicate |
| `AM06` | Amount is below the minimum allowed |
| `AM07` | Amount has been blocked |
| `AM09` | Amount does not match what was expected |
| `AM10` | Control sum does not match the sum of transactions |
| `AC01` | Account number is invalid |
| `AC04` | Account has been closed |
| `AC06` | Account is blocked |
| `AG01` | Transaction type is not allowed on this account |
| `AG02` | Bank operation code is not valid |
| `BE01` | Customer details are inconsistent with the account |
| `BE04` | Creditor address is missing |
| `BE05` | Initiating party is not recognised |
| `BE06` | End customer is unknown to the receiving institution |
| `BE07` | Debtor address is missing |
| `CNOR` | Creditor's MNO is not registered in the scheme |
| `CUST` | Return requested by the account holder |
| `DNOR` | Debtor's MNO is not registered in the scheme |
| `DT01` | Date is not valid |
| `ED01` | No correspondent bank relationship available |
| `ED03` | Balance information is needed before processing |
| `ED05` | Interbank settlement failed |
| `MD06` | Refund requested by the end customer |
| `MD07` | Account holder is deceased |
| `MS02` | No reason provided by customer |
| `MS03` | No reason provided by agent |
| `RC01` | Bank identifier is not valid |
| `RF01` | Transaction reference is not unique |
| `RR02` | Debtor name or mobile number is missing |
| `RR03` | Creditor name or mobile number is missing |
| `RR04` | Return required for regulatory compliance |
| `TM01` | Message arrived after the processing cut-off |

---

## 4. AVS Verification

**`POST /api/v1/avs/verify`**

Checks whether a given name matches the account holder on record at another bank.
Internally this builds and sends an **acmt.023.001.04** IdentificationVerificationRequest
and parses the **acmt.024.001.04** response.

**Request body**

| Field | Type | Required | Description |
|---|---|---|---|
| `firstName` | string | yes | Account holder first name to verify |
| `lastName` | string | yes | Account holder last name to verify |
| `accountNumber` | string | yes | Account number to check against |
| `bankBic` | string | yes | BIC of the bank that holds the account |

**Response body**

| Field | Type | Description |
|---|---|---|
| `verified` | boolean | `true` if the name matches the account holder on record |
| `referenceId` | string | Reference ID from the verification response |
| `reasonCode` | string | Present when `verified` is `false` — indicates why the check failed (see table below) |
| `registeredName` | string | Name on file at the responding bank, if disclosed |
| `registeredAccount` | string | Account number on file at the responding bank, if disclosed |

```bash
curl -s -X POST http://localhost:8080/api/v1/avs/verify \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "Jane",
    "lastName": "Smith",
    "accountNumber": "0987654321",
    "bankBic": "ABCDZAJJXXX"
  }'
```

Example — name matches:

```json
{
  "verified": true,
  "referenceId": "REF-A1B2C3D4E5F60001",
  "reasonCode": null,
  "registeredName": "Jane Smith",
  "registeredAccount": "0987654321"
}
```

Example — name does not match:

```json
{
  "verified": false,
  "referenceId": "REF-A1B2C3D4E5F60001",
  "reasonCode": "PN01",
  "registeredName": null,
  "registeredAccount": null
}
```

Example — close match (similar but not identical name):

```json
{
  "verified": false,
  "referenceId": "REF-A1B2C3D4E5F60001",
  "reasonCode": "PN02",
  "registeredName": null,
  "registeredAccount": null
}
```

### AVS reason codes

| Code | When this code is returned |
|------|---------------------------|
| `AC01` | Account number is not valid or was not provided |
| `AC04` | Account exists but has been closed |
| `AC06` | Account is currently blocked |
| `AB08` | The queried bank is not reachable right now |
| `AGNT` | The agent identifier in the request is not recognised |
| `DUPL` | An identical request was already processed |
| `AG01` | The account type does not allow this kind of lookup |
| `BNOR` | The bank holding the account is not enrolled in the scheme |
| `DS28` | A technical problem at the receiving end caused a return |
| `FF01` | The request message was malformed |
| `MD07` | The account holder is deceased |
| `MS03` | The responding agent gave no specific reason |
| `NORR` | No reply was received within the allowed timeframe |
| `NR01` | The institution cannot confirm or deny the details |
| `RC03` | The sending bank's identifier is invalid or absent |
| `RC04` | The receiving bank's identifier is invalid or absent |
| `TM01` | The request was received after the processing window closed |
| `PN01` | The name supplied does not match the account holder's name |
| `PN02` | The name supplied is similar but not an exact match |
| `PI01` | The identification number does not match the account holder's record |

---

## End-to-end example

```bash
# 1. Send a payment
curl -s -X POST http://localhost:8080/api/v1/payments/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "John",
    "lastName": "Doe",
    "debtorAccountNumber": "1234567890",
    "amount": 1000.00,
    "currency": "ZAR",
    "creditorName": "Jane Smith",
    "creditorAccountNumber": "0987654321",
    "creditorAgentBic": "ABCDZAJJXXX"
  }'

# 2. Query its status (replace with the actual msgId from the log)
curl -s "http://localhost:8080/api/v1/payments/status?msgId=MSG-ABCDEF1234567890"

# 3. Return it if needed
curl -s "http://localhost:8080/api/v1/payments/return?msgId=MSG-ABCDEF1234567890&reasonCode=CUST"

# 4. Verify a beneficiary account before sending
curl -s -X POST http://localhost:8080/api/v1/avs/verify \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "Jane",
    "lastName": "Smith",
    "accountNumber": "0987654321",
    "bankBic": "ABCDZAJJXXX"
  }'
```
