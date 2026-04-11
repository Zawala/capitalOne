#!/usr/bin/env bash
# =============================================================================
# test-flow.sh — end-to-end smoke test for the Capital ISO 20022 Gateway
#
# Flow:
#   0. Auth          — login to ZW security microservice, obtain Bearer token
#   1. AVS           — verify the beneficiary account holder's name
#   2. Send          — credit transfer (pacs.008)            → Kafka: wallet-credits
#   3. Status        — query payment status (pacs.028)
#   4. Return        — return the payment (pacs.004)         → Kafka: wallet-returns
#   5. Auth tests    — token validation tests
#   6. Batch         — randomised credit transfers
#   7. Receive       — inbound wallet deposit (pacs.008 XML) → Kafka: wallet-deposits
#   8. Deposit batch — randomised wallet deposits
#   9. Return batch  — return deposited/transferred payments → Kafka: wallet-returns
#
# Usage:
#   ./test-flow.sh                          # uses defaults below
#   BASE_URL=http://myhost:9090 ./test-flow.sh
#   ZW_URL=http://authhost:8080 ZW_USERNAME=alice ZW_PASSWORD=secret ./test-flow.sh
# =============================================================================

set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
BASE_URL="${BASE_URL:-http://localhost:8460}"
ZW_URL="${ZW_URL:-http://localhost:8080}"
ZW_USERNAME="${ZW_USERNAME:-zawala}"
ZW_PASSWORD="${ZW_PASSWORD:-changeme}"
TIMEOUT=10   # curl connect/read timeout in seconds

# ── Colours ───────────────────────────────────────────────────────────────────
RED=$'\033[0;31m'
GREEN=$'\033[0;32m'
YELLOW=$'\033[1;33m'
CYAN=$'\033[0;36m'
BOLD=$'\033[1m'
RESET=$'\033[0m'

# ── Helpers ───────────────────────────────────────────────────────────────────
step() { echo -e "\n${CYAN}${BOLD}━━━  $*  ━━━${RESET}"; }
ok()   { echo -e "${GREEN}✔  $*${RESET}"; }
warn() { echo -e "${YELLOW}⚠  $*${RESET}"; }
fail() { echo -e "${RED}✘  $*${RESET}"; }

# Run curl; captures HTTP status code + body separately.
# Automatically attaches Authorization header when ACCESS_TOKEN is set.
# Usage: do_curl <METHOD> <URL> [extra curl args...]
# Sets globals: HTTP_STATUS  BODY
do_curl() {
  local method="$1"; shift
  local url="$1";    shift
  local tmp
  tmp=$(mktemp)

  local auth_header=()
  if [[ -n "${ACCESS_TOKEN:-}" ]]; then
    auth_header=(-H "Authorization: Bearer $ACCESS_TOKEN")
  fi

  HTTP_STATUS=$(curl -s -o "$tmp" -w "%{http_code}" \
    --max-time "$TIMEOUT" \
    -X "$method" "$url" \
    "${auth_header[@]}" \
    "$@" || echo "000")
  BODY=$(cat "$tmp")
  rm -f "$tmp"
}

# Extract first occurrence of an XML element value.
# Usage: xml_value <tag> <xml_string>
xml_value() {
  local tag="$1" xml="$2"
  echo "$xml" | grep -oP "(?<=<${tag}>)[^<]+" | head -1 || true
}

# Pretty-print XML if xmllint is available, otherwise raw.
pretty_xml() {
  if command -v xmllint &>/dev/null; then
    echo "$1" | xmllint --format - 2>/dev/null || echo "$1"
  else
    echo "$1"
  fi
}

# Print a labelled block
show_response() {
  local label="$1" status="$2" body="$3"
  echo -e "${BOLD}HTTP $status${RESET}"
  if [[ "$body" == "<"* ]]; then
    pretty_xml "$body"
  else
    echo "$body"
  fi
}

check_server() {
  step "Checking Capital server at $BASE_URL"
  if ! curl -s --max-time 5 "$BASE_URL/actuator/health" &>/dev/null && \
     ! curl -s --max-time 5 "$BASE_URL" &>/dev/null; then
    warn "Server may not be reachable — continuing anyway"
  else
    ok "Server is up"
  fi
}

# ── 0. ZW Auth ────────────────────────────────────────────────────────────────
ACCESS_TOKEN=""

login_zw() {
  step "STEP 0 — Auth: Login to ZW at $ZW_URL"
  echo "  Username: $ZW_USERNAME"

  local tmp
  tmp=$(mktemp)
  local status
  status=$(curl -s -o "$tmp" -w "%{http_code}" \
    --max-time "$TIMEOUT" \
    -X POST "$ZW_URL/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$ZW_USERNAME\",\"password\":\"$ZW_PASSWORD\"}" || echo "000")
  local body
  body=$(cat "$tmp")
  rm -f "$tmp"

  if [[ "$status" != "200" ]]; then
    fail "ZW login failed — HTTP $status: $body"
    exit 1
  fi

  ACCESS_TOKEN=$(echo "$body" | grep -oP '"accessToken"\s*:\s*"\K[^"]+' || true)

  if [[ -z "$ACCESS_TOKEN" ]]; then
    fail "ZW login succeeded (HTTP 200) but accessToken missing in response"
    exit 1
  fi

  ok "Authenticated — access token obtained"
}

# ── Name pools (South African) ────────────────────────────────────────────────
SA_FIRST_NAMES=(
  "Lerato" "Thabo" "Nomsa" "Johannes" "Precious"
  "Sipho" "Nomvula" "Pieter" "Bongani" "Refilwe"
  "Tshepo" "Amahle" "Jacob" "Willem" "Ntombi"
)
SA_SURNAMES=(
  "Dlamini" "Nkosi" "Botha" "Van der Merwe" "Smith"
  "Jacobs" "Mbeki" "Zuma" "Mandel" "Tambo"
  "Pienaar" "Coetzee" "Mokoena" "Khumalo" "Ndlovu"
)
SA_BICS=(
  "ABSAZAJJXXX" "FIRNZAJJXXX" "NEDSZAJJXXX"
  "SBZAZAJJXXX" "IRCCZAJJXXX" "CABLZAJJXXX"
)

# Return a random element from a bash array.
# Usage: rand_element "${ARRAY[@]}"
rand_element() {
  local arr=("$@")
  echo "${arr[RANDOM % ${#arr[@]}]}"
}

# Generate a random 10-digit account number.
rand_account() {
  printf '%010d' $(( RANDOM * RANDOM % 9000000000 + 1000000000 ))
}

# Generate a random ZAR amount between 1000.00 and 2500.00.
rand_amount() {
  local cents=$(( RANDOM % 150001 + 100000 ))   # 100000–250000 cents
  printf '%d.%02d' $(( cents / 100 )) $(( cents % 100 ))
}

# ── Test data (defaults — overridden per transaction in batch mode) ───────────
DEBTOR_FIRST="John"
DEBTOR_LAST="Doe"
DEBTOR_ACCOUNT="1234567890"
CREDITOR_NAME="Jane Smith"
CREDITOR_ACCOUNT="0987654321"
CREDITOR_BIC="ABCDZAJJXXX"
AMOUNT="1500.00"
CURRENCY="ZAR"
REMITTANCE="Test payment $(date +%Y%m%d%H%M%S)"

# ── 1. AVS verification ───────────────────────────────────────────────────────
avs_verify() {
  step "STEP 1 — AVS: Verify beneficiary account"
  echo "  Checking: '$CREDITOR_NAME' against account '$CREDITOR_ACCOUNT' at BIC '$CREDITOR_BIC'"

  do_curl POST "$BASE_URL/api/v1/avs/verify" \
    -H "Content-Type: application/json" \
    -d "{
      \"firstName\": \"Jane\",
      \"lastName\":  \"Smith\",
      \"accountNumber\": \"$CREDITOR_ACCOUNT\",
      \"bankBic\":       \"$CREDITOR_BIC\"
    }"

  show_response "AVS /verify" "$HTTP_STATUS" "$BODY"

  case "$HTTP_STATUS" in
    200)
      local verified
      verified=$(echo "$BODY" | grep -oP '"verified"\s*:\s*\K(true|false)' || echo "unknown")
      local reason
      reason=$(echo "$BODY"  | grep -oP '"reasonCode"\s*:\s*"\K[^"]+' || echo "")
      if [[ "$verified" == "true" ]]; then
        ok "AVS passed — account holder verified"
      else
        warn "AVS did not verify — reasonCode: ${reason:-none}. Proceeding anyway for test purposes."
      fi
      ;;
    401) fail "HTTP 401 — Bearer token was rejected. Check that ZW and Capital share the same jwt.secret." ; exit 1 ;;
    502) warn "AVS upstream unavailable (502). Downstream mock not running — continuing." ;;
    *)   warn "Unexpected AVS status $HTTP_STATUS — continuing." ;;
  esac
}

# ── 2. Credit transfer ────────────────────────────────────────────────────────
MSG_ID=""   # will be populated after the transfer call

send_payment() {
  step "STEP 2 — Send: Credit transfer (pacs.008)"
  echo "  $DEBTOR_FIRST $DEBTOR_LAST ($DEBTOR_ACCOUNT) → $CREDITOR_NAME ($CREDITOR_ACCOUNT)"
  echo "  Amount: $AMOUNT $CURRENCY  |  Ref: $REMITTANCE"

  do_curl POST "$BASE_URL/api/v1/payments/transfer" \
    -H "Content-Type: application/json" \
    -d "{
      \"firstName\":             \"$DEBTOR_FIRST\",
      \"lastName\":              \"$DEBTOR_LAST\",
      \"debtorAccountNumber\":   \"$DEBTOR_ACCOUNT\",
      \"amount\":                $AMOUNT,
      \"currency\":              \"$CURRENCY\",
      \"creditorName\":          \"$CREDITOR_NAME\",
      \"creditorAccountNumber\": \"$CREDITOR_ACCOUNT\",
      \"creditorAgentBic\":      \"$CREDITOR_BIC\",
      \"remittanceInfo\":        \"$REMITTANCE\"
    }"

  show_response "POST /payments/transfer" "$HTTP_STATUS" "$BODY"

  case "$HTTP_STATUS" in
    200)
      # Try to pull the original msgId from the pacs.002 XML response
      MSG_ID=$(xml_value "OrgnlMsgId" "$BODY")
      if [[ -z "$MSG_ID" ]]; then
        # Fallback: scrape from any MsgId element (first hit is the pacs.002 own id,
        # second should be OrgnlMsgId — try the raw body for MSG- prefix)
        MSG_ID=$(echo "$BODY" | grep -oP 'MSG-[A-F0-9]{16}' | head -1 || true)
      fi
      if [[ -n "$MSG_ID" ]]; then
        ok "Transfer accepted — msgId: $MSG_ID"
      else
        warn "Transfer accepted but could not extract msgId from response."
        warn "Set MSG_ID manually and re-run steps 3 & 4 if needed."
      fi
      ;;
    502)
      warn "Upstream unavailable (502) — pacs.008 was built and logged; downstream mock not running."
      warn "The msgId was written to the transfer_log. Check the app log for 'Transfer PENDING — msgId=...'"
      ;;
    *)
      fail "Transfer returned HTTP $HTTP_STATUS"
      ;;
  esac
}

# ── 3. Payment status query ───────────────────────────────────────────────────
query_status() {
  step "STEP 3 — Status: Query payment status (pacs.028)"

  if [[ -z "$MSG_ID" ]]; then
    warn "No msgId available — skipping status query."
    warn "Re-run with: MSG_ID=<your-id> ./test-flow.sh"
    return
  fi

  echo "  Querying status for msgId: $MSG_ID"

  do_curl GET "$BASE_URL/api/v1/payments/status?msgId=$MSG_ID"

  show_response "GET /payments/status" "$HTTP_STATUS" "$BODY"

  case "$HTTP_STATUS" in
    200) ok "Status query sent successfully" ;;
    404) fail "No transfer found for msgId: $MSG_ID" ;;
    502) warn "Status upstream unavailable (502) — pacs.028 was built and sent." ;;
    *)   warn "Unexpected status $HTTP_STATUS" ;;
  esac
}

# ── 4. Payment return ─────────────────────────────────────────────────────────
return_payment() {
  local reason="${1:-CUST}"

  step "STEP 4 — Return: Return payment (pacs.004) — reason: $reason"

  if [[ -z "$MSG_ID" ]]; then
    warn "No msgId available — skipping return."
    return
  fi

  echo "  Returning msgId: $MSG_ID  |  Reason: $reason"

  do_curl GET "$BASE_URL/api/v1/payments/return?msgId=$MSG_ID&reasonCode=$reason"

  show_response "GET /payments/return" "$HTTP_STATUS" "$BODY"

  case "$HTTP_STATUS" in
    200) ok "Return request sent successfully" ;;
    400) fail "Bad request — ${BODY}" ;;
    404) fail "No transfer found for msgId: $MSG_ID" ;;
    502) warn "Return upstream unavailable (502) — pacs.004 was built and forwarded." ;;
    *)   warn "Unexpected status $HTTP_STATUS" ;;
  esac
}

# ── 5. Auth security tests ────────────────────────────────────────────────────
test_auth() {
  local saved_token="$ACCESS_TOKEN"
  local passed=0 failed=0

  step "STEP 5 — Auth: Token validation tests"

  # Helper: send a transfer request and check the HTTP status
  assert_auth() {
    local label="$1" expected="$2"
    do_curl POST "$BASE_URL/api/v1/avs/verify" \
      -H "Content-Type: application/json" \
      -d '{"firstName":"Test","lastName":"User","accountNumber":"0000000000","bankBic":"TESTBICXXX"}'
    if [[ "$HTTP_STATUS" == "$expected" ]]; then
      ok "$label — HTTP $HTTP_STATUS (expected)"
      (( ++passed ))
    else
      fail "$label — expected HTTP $expected, got HTTP $HTTP_STATUS"
      (( ++failed ))
    fi
  }

  # No token
  ACCESS_TOKEN=""
  assert_auth "No token" "401"

  # Invalid (malformed) token
  ACCESS_TOKEN="this.is.not.a.valid.jwt"
  assert_auth "Malformed token" "401"

  # Valid structure but wrong signature
  ACCESS_TOKEN="eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmYWtlIn0.invalidsignatureXXXXXXXXXXXXXXXXXXXXXXX"
  assert_auth "Wrong signature" "401"

  # Restore real token and confirm it still works
  ACCESS_TOKEN="$saved_token"
  assert_auth "Valid token (sanity check)" "200"

  echo ""
  if [[ $failed -eq 0 ]]; then
    ok "All $passed auth tests passed"
  else
    fail "$failed/$((passed + failed)) auth tests failed"
  fi
}

# ── Batch transactions ────────────────────────────────────────────────────────
BATCH_COUNT="${BATCH_COUNT:-12}"
BATCH_PASS=0
BATCH_FAIL=0
declare -a BATCH_MSG_IDS=()

run_batch() {
  step "BATCH — Running $BATCH_COUNT randomised transactions (1000–2500 ZAR)"

  for (( i=1; i<=BATCH_COUNT; i++ )); do
    # Randomise all parties for this transaction
    local d_first d_last c_first c_last c_name
    d_first=$(rand_element "${SA_FIRST_NAMES[@]}")
    d_last=$(rand_element "${SA_SURNAMES[@]}")
    c_first=$(rand_element "${SA_FIRST_NAMES[@]}")
    c_last=$(rand_element "${SA_SURNAMES[@]}")
    c_name="$c_first $c_last"

    local d_acct c_acct bic amt
    d_acct=$(rand_account)
    c_acct=$(rand_account)
    bic=$(rand_element "${SA_BICS[@]}")
    amt=$(rand_amount)

    local ref="BATCH-$(printf '%02d' $i)-$(date +%Y%m%d%H%M%S)"

    echo -e "\n${BOLD}── Tx $i/$BATCH_COUNT ──${RESET}"
    echo "  Debtor  : $d_first $d_last  ($d_acct)"
    echo "  Creditor: $c_name  ($c_acct)  BIC: $bic"
    echo "  Amount  : $amt ZAR  |  Ref: $ref"

    # AVS
    do_curl POST "$BASE_URL/api/v1/avs/verify" \
      -H "Content-Type: application/json" \
      -d "{
        \"firstName\":    \"$c_first\",
        \"lastName\":     \"$c_last\",
        \"accountNumber\":\"$c_acct\",
        \"bankBic\":      \"$bic\"
      }"
    local avs_status="$HTTP_STATUS"
    case "$avs_status" in
      200) ok "  AVS HTTP 200" ;;
      401) fail "  AVS HTTP 401 — token rejected"; BATCH_FAIL=$(( BATCH_FAIL+1 )); continue ;;
      502) warn "  AVS 502 — mock unavailable, continuing" ;;
      *)   warn "  AVS unexpected $avs_status" ;;
    esac

    # Transfer
    do_curl POST "$BASE_URL/api/v1/payments/transfer" \
      -H "Content-Type: application/json" \
      -d "{
        \"firstName\":             \"$d_first\",
        \"lastName\":              \"$d_last\",
        \"debtorAccountNumber\":   \"$d_acct\",
        \"amount\":                $amt,
        \"currency\":              \"ZAR\",
        \"creditorName\":          \"$c_name\",
        \"creditorAccountNumber\": \"$c_acct\",
        \"creditorAgentBic\":      \"$bic\",
        \"remittanceInfo\":        \"$ref\"
      }"

    local tx_status="$HTTP_STATUS" tx_body="$BODY"
    case "$tx_status" in
      200)
        local mid
        mid=$(xml_value "OrgnlMsgId" "$tx_body")
        [[ -z "$mid" ]] && mid=$(echo "$tx_body" | grep -oP 'MSG-[A-F0-9]{16}' | head -1 || true)
        if [[ -n "$mid" ]]; then
          ok "  Transfer accepted — msgId: $mid"
          BATCH_MSG_IDS+=("$mid")
        else
          ok "  Transfer accepted (msgId not extracted)"
        fi
        BATCH_PASS=$(( BATCH_PASS+1 ))
        ;;
      502)
        warn "  Transfer 502 — upstream unavailable"
        BATCH_PASS=$(( BATCH_PASS+1 ))
        ;;
      *)
        fail "  Transfer HTTP $tx_status"
        BATCH_FAIL=$(( BATCH_FAIL+1 ))
        ;;
    esac
  done

  echo ""
  echo -e "${BOLD}Batch result: ${GREEN}$BATCH_PASS passed${RESET}  ${RED}$BATCH_FAIL failed${RESET}  (of $BATCH_COUNT)"
}

# ── Summary ───────────────────────────────────────────────────────────────────
summary() {
  echo -e "\n${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
  echo -e "${BOLD}Test flow complete${RESET}"
  echo -e "  Capital  : $BASE_URL"
  echo -e "  ZW Auth  : $ZW_URL"
  echo -e "  msgId    : ${MSG_ID:-not captured}"
  if [[ $BATCH_COUNT -gt 0 && $(( BATCH_PASS + BATCH_FAIL )) -gt 0 ]]; then
    echo -e "  Batch    : ${GREEN}${BATCH_PASS} passed${RESET} / ${RED}${BATCH_FAIL} failed${RESET} of $BATCH_COUNT"
    if [[ ${#BATCH_MSG_IDS[@]} -gt 0 ]]; then
      echo -e "  Batch msgIds captured: ${#BATCH_MSG_IDS[@]}"
      for mid in "${BATCH_MSG_IDS[@]}"; do
        echo -e "    • $mid"
      done
    fi
  fi
  echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}\n"
}

# ── 6. Receive deposit (inbound pacs.008 XML) ────────────────────────────────
RECEIVE_MSG_ID=""

receive_deposit() {
  step "STEP 6 — Receive: Inbound deposit (POST /receive pacs.008 XML)"

  # Randomise parties for the wallet deposit
  local d_first d_last c_first c_last
  d_first=$(rand_element "${SA_FIRST_NAMES[@]}")
  d_last=$(rand_element "${SA_SURNAMES[@]}")
  c_first=$(rand_element "${SA_FIRST_NAMES[@]}")
  c_last=$(rand_element "${SA_SURNAMES[@]}")

  local d_mob c_mob amt instg_id instd_id
  d_mob="+27-$(printf '%09d' $(( RANDOM * RANDOM % 900000000 + 100000000 )))"
  c_mob="+260-$(printf '%09d' $(( RANDOM * RANDOM % 900000000 + 100000000 )))"
  amt=$(rand_amount)
  instg_id="$(printf '%06d' $(( RANDOM % 900000 + 100000 )))"
  instd_id="$(printf '%06d' $(( RANDOM % 900000 + 100000 )))"

  local ts msg_id
  ts=$(date +%Y%m%d%H%M%S)
  msg_id="${ts}${instg_id}PROD$(printf '%04d' $(( RANDOM % 9999 + 1 )))"

  echo "  MsgId       : $msg_id"
  echo "  Debtor      : $d_first $d_last  ($d_mob)"
  echo "  Creditor    : $c_first $c_last  ($c_mob)"
  echo "  Amount      : $amt ZAR"
  echo "  InstgAgt    : $instg_id → InstdAgt: $instd_id"

  local xml_payload
  xml_payload=$(cat <<XMLEOF
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.07">
    <FIToFICstmrCdtTrf>
        <GrpHdr>
            <MsgId>${msg_id}</MsgId>
            <CreDtTm>$(date -u +%Y-%m-%dT%H:%M:%S.000+00:00)</CreDtTm>
            <NbOfTxs>1</NbOfTxs>
            <SttlmInf>
                <SttlmMtd>CLRG</SttlmMtd>
            </SttlmInf>
            <InstgAgt>
                <FinInstnId>
                    <Othr>
                        <Id>${instg_id}</Id>
                    </Othr>
                </FinInstnId>
            </InstgAgt>
            <InstdAgt>
                <FinInstnId>
                    <Othr>
                        <Id>${instd_id}</Id>
                    </Othr>
                </FinInstnId>
            </InstdAgt>
        </GrpHdr>
        <CdtTrfTxInf>
            <PmtId>
                <EndToEndId>${msg_id}</EndToEndId>
                <TxId>${msg_id}</TxId>
            </PmtId>
            <PmtTpInf>
                <SvcLvl>
                    <Cd>NURG</Cd>
                </SvcLvl>
            </PmtTpInf>
            <IntrBkSttlmAmt Ccy="ZAR">${amt}</IntrBkSttlmAmt>
            <ChrgBr>CRED</ChrgBr>
            <Dbtr>
                <Nm>${d_first} ${d_last}</Nm>
                <CtctDtls>
                    <MobNb>${d_mob}</MobNb>
                </CtctDtls>
            </Dbtr>
            <DbtrAgt>
                <FinInstnId>
                    <Othr>
                        <Id>${instg_id}</Id>
                    </Othr>
                </FinInstnId>
            </DbtrAgt>
            <CdtrAgt>
                <FinInstnId>
                    <Othr>
                        <Id>${instd_id}</Id>
                    </Othr>
                </FinInstnId>
            </CdtrAgt>
            <Cdtr>
                <Nm>${c_first} ${c_last}</Nm>
                <CtctDtls>
                    <MobNb>${c_mob}</MobNb>
                </CtctDtls>
            </Cdtr>
            <RgltryRptg>
                <Dtls>
                    <Cd>10402</Cd>
                </Dtls>
            </RgltryRptg>
            <RmtInf>
                <Ustrd>DEPOSIT TO ${c_first} ${c_last}</Ustrd>
            </RmtInf>
        </CdtTrfTxInf>
    </FIToFICstmrCdtTrf>
</Document>
XMLEOF
)

  do_curl POST "$BASE_URL/api/v1/payments/receive" \
    -H "Content-Type: application/xml" \
    -d "$xml_payload"

  show_response "POST /payments/receive" "$HTTP_STATUS" "$BODY"

  case "$HTTP_STATUS" in
    200)
      RECEIVE_MSG_ID="$msg_id"
      ok "Deposit accepted — msgId: $msg_id (Kafka → wallet-deposits)"
      ;;
    400) fail "Bad request — $BODY" ;;
    401) fail "HTTP 401 — token rejected" ;;
    *)   warn "Unexpected status $HTTP_STATUS" ;;
  esac
}

# ── 7. Batch wallet deposits ─────────────────────────────────────────────────
DEPOSIT_BATCH_COUNT="${DEPOSIT_BATCH_COUNT:-5}"
DEPOSIT_BATCH_PASS=0
DEPOSIT_BATCH_FAIL=0
declare -a DEPOSIT_MSG_IDS=()

run_deposit_batch() {
  step "DEPOSIT BATCH — Running $DEPOSIT_BATCH_COUNT randomised wallet deposits"

  for (( i=1; i<=DEPOSIT_BATCH_COUNT; i++ )); do
    local d_first d_last c_first c_last
    d_first=$(rand_element "${SA_FIRST_NAMES[@]}")
    d_last=$(rand_element "${SA_SURNAMES[@]}")
    c_first=$(rand_element "${SA_FIRST_NAMES[@]}")
    c_last=$(rand_element "${SA_SURNAMES[@]}")

    local d_mob c_mob amt instg_id instd_id
    d_mob="+27-$(printf '%09d' $(( RANDOM * RANDOM % 900000000 + 100000000 )))"
    c_mob="+260-$(printf '%09d' $(( RANDOM * RANDOM % 900000000 + 100000000 )))"
    amt=$(rand_amount)
    instg_id="$(printf '%06d' $(( RANDOM % 900000 + 100000 )))"
    instd_id="$(printf '%06d' $(( RANDOM % 900000 + 100000 )))"

    local ts msg_id
    ts=$(date +%Y%m%d%H%M%S)
    msg_id="DEP-${ts}-$(printf '%04d' $i)"

    echo -e "\n${BOLD}── Deposit $i/$DEPOSIT_BATCH_COUNT ──${RESET}"
    echo "  Debtor  : $d_first $d_last  ($d_mob)"
    echo "  Creditor: $c_first $c_last  ($c_mob)"
    echo "  Amount  : $amt ZAR  |  MsgId: $msg_id"

    local xml_payload
    xml_payload=$(cat <<XMLEOF
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.07">
    <FIToFICstmrCdtTrf>
        <GrpHdr>
            <MsgId>${msg_id}</MsgId>
            <CreDtTm>$(date -u +%Y-%m-%dT%H:%M:%S.000+00:00)</CreDtTm>
            <NbOfTxs>1</NbOfTxs>
            <SttlmInf><SttlmMtd>CLRG</SttlmMtd></SttlmInf>
            <InstgAgt><FinInstnId><Othr><Id>${instg_id}</Id></Othr></FinInstnId></InstgAgt>
            <InstdAgt><FinInstnId><Othr><Id>${instd_id}</Id></Othr></FinInstnId></InstdAgt>
        </GrpHdr>
        <CdtTrfTxInf>
            <PmtId>
                <EndToEndId>${msg_id}</EndToEndId>
                <TxId>${msg_id}</TxId>
            </PmtId>
            <PmtTpInf><SvcLvl><Cd>NURG</Cd></SvcLvl></PmtTpInf>
            <IntrBkSttlmAmt Ccy="ZAR">${amt}</IntrBkSttlmAmt>
            <ChrgBr>CRED</ChrgBr>
            <Dbtr>
                <Nm>${d_first} ${d_last}</Nm>
                <CtctDtls><MobNb>${d_mob}</MobNb></CtctDtls>
            </Dbtr>
            <DbtrAgt><FinInstnId><Othr><Id>${instg_id}</Id></Othr></FinInstnId></DbtrAgt>
            <CdtrAgt><FinInstnId><Othr><Id>${instd_id}</Id></Othr></FinInstnId></CdtrAgt>
            <Cdtr>
                <Nm>${c_first} ${c_last}</Nm>
                <CtctDtls><MobNb>${c_mob}</MobNb></CtctDtls>
            </Cdtr>
            <RgltryRptg><Dtls><Cd>10402</Cd></Dtls></RgltryRptg>
            <RmtInf><Ustrd>DEPOSIT TO ${c_first} ${c_last}</Ustrd></RmtInf>
        </CdtTrfTxInf>
    </FIToFICstmrCdtTrf>
</Document>
XMLEOF
)

    do_curl POST "$BASE_URL/api/v1/payments/receive" \
      -H "Content-Type: application/xml" \
      -d "$xml_payload"

    case "$HTTP_STATUS" in
      200)
        ok "  Deposit accepted — msgId: $msg_id (Kafka → wallet-deposits)"
        DEPOSIT_MSG_IDS+=("$msg_id")
        DEPOSIT_BATCH_PASS=$(( DEPOSIT_BATCH_PASS+1 ))
        ;;
      *)
        fail "  Deposit HTTP $HTTP_STATUS — $BODY"
        DEPOSIT_BATCH_FAIL=$(( DEPOSIT_BATCH_FAIL+1 ))
        ;;
    esac
  done

  echo ""
  echo -e "${BOLD}Deposit batch result: ${GREEN}$DEPOSIT_BATCH_PASS passed${RESET}  ${RED}$DEPOSIT_BATCH_FAIL failed${RESET}  (of $DEPOSIT_BATCH_COUNT)"
}

# ── 8. Return batch — returns deposits and verifies Kafka → wallet-returns ───
RETURN_BATCH_PASS=0
RETURN_BATCH_FAIL=0

run_return_batch() {
  local ids=("${DEPOSIT_MSG_IDS[@]}" "${BATCH_MSG_IDS[@]}")
  local count=${#ids[@]}

  if [[ $count -eq 0 ]]; then
    warn "No msgIds available — skipping return batch."
    return
  fi

  local max="${RETURN_BATCH_COUNT:-$count}"
  [[ $max -gt $count ]] && max=$count

  step "RETURN BATCH — Returning $max transactions (Kafka → wallet-returns)"

  local reasons=("CUST" "DUPL" "FRAD" "TECH" "AM09")

  for (( i=0; i<max; i++ )); do
    local mid="${ids[$i]}"
    local reason
    reason=$(rand_element "${reasons[@]}")

    echo -e "\n${BOLD}── Return $(( i+1 ))/$max ──${RESET}"
    echo "  MsgId  : $mid"
    echo "  Reason : $reason"

    do_curl GET "$BASE_URL/api/v1/payments/return?msgId=$mid&reasonCode=$reason"

    case "$HTTP_STATUS" in
      200)
        ok "  Return accepted — msgId: $mid reason: $reason (Kafka → wallet-returns)"
        RETURN_BATCH_PASS=$(( RETURN_BATCH_PASS+1 ))
        ;;
      400) fail "  Bad request — $BODY"; RETURN_BATCH_FAIL=$(( RETURN_BATCH_FAIL+1 )) ;;
      404) warn "  Not found — $mid (may not be persisted yet)"; RETURN_BATCH_FAIL=$(( RETURN_BATCH_FAIL+1 )) ;;
      502) warn "  Return 502 — upstream unavailable"; RETURN_BATCH_PASS=$(( RETURN_BATCH_PASS+1 )) ;;
      *)   fail "  Return HTTP $HTTP_STATUS"; RETURN_BATCH_FAIL=$(( RETURN_BATCH_FAIL+1 )) ;;
    esac
  done

  echo ""
  echo -e "${BOLD}Return batch result: ${GREEN}$RETURN_BATCH_PASS passed${RESET}  ${RED}$RETURN_BATCH_FAIL failed${RESET}  (of $max)"
}

# ── Summary ───────────────────────────────────────────────────────────────────
summary() {
  echo -e "\n${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
  echo -e "${BOLD}Test flow complete${RESET}"
  echo -e "  Capital  : $BASE_URL"
  echo -e "  ZW Auth  : $ZW_URL"
  echo -e "  msgId    : ${MSG_ID:-not captured}"
  if [[ $BATCH_COUNT -gt 0 && $(( BATCH_PASS + BATCH_FAIL )) -gt 0 ]]; then
    echo -e "  Batch    : ${GREEN}${BATCH_PASS} passed${RESET} / ${RED}${BATCH_FAIL} failed${RESET} of $BATCH_COUNT"
    if [[ ${#BATCH_MSG_IDS[@]} -gt 0 ]]; then
      echo -e "  Batch msgIds captured: ${#BATCH_MSG_IDS[@]}"
      for mid in "${BATCH_MSG_IDS[@]}"; do
        echo -e "    • $mid"
      done
    fi
  fi
  if [[ $(( DEPOSIT_BATCH_PASS + DEPOSIT_BATCH_FAIL )) -gt 0 ]]; then
    echo -e "  Deposits : ${GREEN}${DEPOSIT_BATCH_PASS} passed${RESET} / ${RED}${DEPOSIT_BATCH_FAIL} failed${RESET} of $DEPOSIT_BATCH_COUNT"
    if [[ ${#DEPOSIT_MSG_IDS[@]} -gt 0 ]]; then
      echo -e "  Deposit msgIds captured: ${#DEPOSIT_MSG_IDS[@]}"
      for mid in "${DEPOSIT_MSG_IDS[@]}"; do
        echo -e "    • $mid"
      done
    fi
  fi
  if [[ $(( RETURN_BATCH_PASS + RETURN_BATCH_FAIL )) -gt 0 ]]; then
    echo -e "  Returns  : ${GREEN}${RETURN_BATCH_PASS} passed${RESET} / ${RED}${RETURN_BATCH_FAIL} failed${RESET}"
  fi
  echo -e ""
  echo -e "  ${BOLD}Kafka topics exercised:${RESET}"
  echo -e "    • wallet-deposits  (POST /receive)"
  echo -e "    • wallet-credits   (POST /transfer)"
  echo -e "    • wallet-returns   (GET  /return)"
  echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}\n"
}

# ── Entry point ───────────────────────────────────────────────────────────────
# Allow overriding MSG_ID from the environment to skip steps 1 & 2.
# Example: MSG_ID=MSG-ABCDEF1234567890 ./test-flow.sh

if [[ -n "${MSG_ID:-}" ]]; then
  echo -e "${YELLOW}MSG_ID pre-set to '$MSG_ID' — skipping AVS and transfer steps.${RESET}"
  login_zw
  query_status
  return_payment "${REASON_CODE:-CUST}"
else
  check_server
  login_zw
  avs_verify
  send_payment
  query_status
  return_payment "${REASON_CODE:-CUST}"
  test_auth
  run_batch
  receive_deposit
  run_deposit_batch
  run_return_batch
fi

summary
