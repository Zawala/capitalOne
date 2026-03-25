#!/usr/bin/env bash
# =============================================================================
# test-flow.sh — end-to-end smoke test for the Capital ISO 20022 Gateway
#
# Flow:
#   1. AVS  — verify the beneficiary account holder's name
#   2. Send — credit transfer (pacs.008)
#   3. Status — query payment status (pacs.028)
#   4. Return — return the payment (pacs.004)
#
# Usage:
#   ./test-flow.sh              # uses defaults below
#   BASE_URL=http://myhost:9090 ./test-flow.sh
# =============================================================================

set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
BASE_URL="${BASE_URL:-http://localhost:81460}"
TIMEOUT=10   # curl connect/read timeout in seconds

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

# ── Helpers ───────────────────────────────────────────────────────────────────
step() { echo -e "\n${CYAN}${BOLD}━━━  $*  ━━━${RESET}"; }
ok()   { echo -e "${GREEN}✔  $*${RESET}"; }
warn() { echo -e "${YELLOW}⚠  $*${RESET}"; }
fail() { echo -e "${RED}✘  $*${RESET}"; }

# Run curl; captures HTTP status code + body separately.
# Usage: do_curl <METHOD> <URL> [extra curl args...]
# Sets globals: HTTP_STATUS  BODY
do_curl() {
  local method="$1"; shift
  local url="$1";    shift
  local tmp
  tmp=$(mktemp)
  HTTP_STATUS=$(curl -s -o "$tmp" -w "%{http_code}" \
    --max-time "$TIMEOUT" \
    -X "$method" "$url" "$@" || echo "000")
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
  step "Checking server at $BASE_URL"
  if ! curl -s --max-time 5 "$BASE_URL/actuator/health" &>/dev/null && \
     ! curl -s --max-time 5 "$BASE_URL" &>/dev/null; then
    warn "Server may not be reachable — continuing anyway"
  else
    ok "Server is up"
  fi
}

# ── Test data ─────────────────────────────────────────────────────────────────
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
    401) fail "HTTP 401 — server requires auth. Is Spring Security permitting requests? Check SecurityConfig (dev profile must be active)." ; exit 1 ;;
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

# ── Summary ───────────────────────────────────────────────────────────────────
summary() {
  echo -e "\n${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
  echo -e "${BOLD}Test flow complete${RESET}"
  echo -e "  Base URL : $BASE_URL"
  echo -e "  msgId    : ${MSG_ID:-not captured}"
  echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}\n"
}

# ── Entry point ───────────────────────────────────────────────────────────────
# Allow overriding MSG_ID from the environment to skip steps 1 & 2.
# Example: MSG_ID=MSG-ABCDEF1234567890 ./test-flow.sh

if [[ -n "${MSG_ID:-}" ]]; then
  echo -e "${YELLOW}MSG_ID pre-set to '$MSG_ID' — skipping AVS and transfer steps.${RESET}"
  query_status
  return_payment "${REASON_CODE:-CUST}"
else
  check_server
  avs_verify
  send_payment
  query_status
  return_payment "${REASON_CODE:-CUST}"
fi

summary
