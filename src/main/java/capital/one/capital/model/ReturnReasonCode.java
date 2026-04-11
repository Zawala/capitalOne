package capital.one.capital.model;

/**
 * ISO 20022 return reason codes used in pacs.004 PaymentReturn messages.
 * Pass one of these codes as the {@code reasonCode} query parameter on
 * {@code GET /api/v1/payments/return}.
 */
public enum ReturnReasonCode {

    // ── Amount errors ─────────────────────────────────────────────────────────

    /** The transaction amount is zero, which is not permitted. */
    AM01("Zero Amount"),

    /** The amount exceeds a limit or is otherwise not permitted for this payment type. */
    AM02("Not Allowed Amount"),

    /** The currency specified is not accepted for this payment. */
    AM03("Not Allowed Currency"),

    /** The transaction is a duplicate of a previously submitted payment. */
    DUPL("Duplicate Payment"),

    /** This payment appears to be a duplicate of one already submitted. */
    AM05("Duplication"),

    /** The amount is below the minimum threshold for this payment type. */
    AM06("Too Low Amount"),

    /** The amount has been blocked and cannot be processed. */
    AM07("Blocked Amount"),

    /** The amount received does not match what was expected or agreed. */
    AM09("Wrong Amount"),

    /** The sum of transaction amounts does not match the declared control sum. */
    AM10("Invalid Control Sum"),

    // ── Account errors ────────────────────────────────────────────────────────

    /** The account number is not valid or was not provided. */
    AC01("Incorrect Account Number"),

    /** The account has been closed and can no longer receive funds. */
    AC04("Closed Account Number"),

    /** The account is blocked and no transactions can be posted. */
    AC06("Blocked Account"),

    // ── Agent / transaction errors ────────────────────────────────────────────

    /** This type of transaction is not permitted on the destination account. */
    AG01("Transaction Forbidden"),

    /** The bank operation code in the message is not valid. */
    AG02("Invalid Bank Operation Code"),

    // ── Party / identification errors ─────────────────────────────────────────

    /** The end customer details do not match the account on record. */
    BE01("Inconsistent With End Customer"),

    /** The creditor's address is required but was not supplied. */
    BE04("Missing Creditor Address"),

    /** The party that initiated the payment is not recognised. */
    BE05("Unrecognised Initiating Party"),

    /** The specified end customer is not known to the receiving institution. */
    BE06("Unknown End Customer"),

    /** The debtor's address is required but was not supplied. */
    BE07("Missing Debtor Address"),

    // ── Scheme registration ───────────────────────────────────────────────────

    /** The creditor's mobile network operator is not enrolled in the scheme. */
    CNOR("Creditor MNO Not Registered"),

    /** The debtor's mobile network operator is not enrolled in the scheme. */
    DNOR("Debtor MNO Not Registered"),

    // ── Customer / regulatory ─────────────────────────────────────────────────

    /** The payment is suspected or confirmed to be fraudulent. */
    FRAD("Fraudulent Origin"),

    /** A technical issue prevented the payment from being processed correctly. */
    TECH("Technical Problem"),

    /** The return was requested directly by the account holder. */
    CUST("Requested By Customer"),

    /** The value date or execution date supplied is not valid. */
    DT01("Invalid Date"),

    /** A correspondent bank relationship could not be established for routing. */
    ED01("Correspondent Bank Not Possible"),

    /** The payment is being returned to allow a balance enquiry to be resolved. */
    ED03("Balance Info Request"),

    /** The interbank settlement step failed. */
    ED05("Settlement Failed"),

    /** The end customer has asked for the funds to be refunded. */
    MD06("Refund Request By End Customer"),

    /** The account holder is deceased. */
    MD07("End Customer Deceased"),

    /** No specific reason was given by the customer. */
    MS02("Not Specified Reason Customer Generated"),

    /** No specific reason was given by the agent. */
    MS03("Not Specified Reason Agent Generated"),

    /** The bank identifier (BIC / sort code) is not valid. */
    RC01("Bank Identifier Incorrect"),

    /** The transaction reference is not unique within the message. */
    RF01("Not Unique Transaction Reference"),

    /** The debtor's name or contact number is absent from the payment. */
    RR02("Missing Debtor Name And Mobile Number"),

    /** The creditor's name or contact number is absent from the payment. */
    RR03("Missing Creditor Name And Mobile Number"),

    /** The return is required to satisfy a regulatory obligation. */
    RR04("Regulatory Reason"),

    /** The message was received after the agreed processing cut-off time. */
    TM01("Cut Off Time");

    private final String description;

    ReturnReasonCode(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /** Returns the matching enum constant, or {@code null} if the code is not recognised. */
    public static ReturnReasonCode fromCode(String code) {
        if (code == null) return null;
        try {
            return valueOf(code.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
