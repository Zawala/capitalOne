package capital.one.capital.model;

/**
 * ISO 20022 reason codes returned in the {@code reasonCode} field of an AVS
 * (acmt.024) verification response. A non-null value indicates the verification
 * did not pass cleanly; the code describes why.
 */
public enum AvsReasonCode {

    /** The account number supplied is not valid or was not included in the request. */
    AC01("Incorrect Account Number"),

    /** The account exists but has been closed and can no longer receive transactions. */
    AC04("Closed Account Number"),

    /** The account is currently blocked; no transactions can be posted to it. */
    AC06("Blocked Account"),

    /** The creditor agent is unavailable and cannot process the verification at this time. */
    AB08("Offline Creditor Agent"),

    /** The agent identifier included in the request does not match a known agent. */
    AGNT("Incorrect Agent"),

    /** An identical request has already been submitted and processed. */
    DUPL("Duplicate Request"),

    /** The account type does not permit this kind of transaction. */
    AG01("Transaction Forbidden On This Account"),

    /** The bank holding the account is not enrolled in the scheme being queried. */
    BNOR("Account Holding Bank Not Registered"),

    /** A technical error at the receiving end caused the request to be returned. */
    DS28("Return For Technical Reason"),

    /** The request message was malformed or did not conform to the expected format. */
    FF01("Invalid File Format"),

    /** The account holder is deceased; the account cannot be verified. */
    MD07("End Customer Deceased"),

    /** The agent returned no specific reason for the outcome. */
    MS03("Not Specified Reason Agent Generated"),

    /** No reply was received from the queried institution within the allowed window. */
    NORR("No Report Received"),

    /** The institution is unable to confirm or deny the account details. */
    NR01("No Reason Possible"),

    /** The sending bank's identifier is not valid or is absent. */
    RC03("Invalid Debtor Bank Identifier"),

    /** The receiving bank's identifier is not valid or is absent. */
    RC04("Invalid Creditor Bank Identifier"),

    /** The request arrived after the processing window had closed. */
    TM01("Invalid Cut Off Time"),

    /** The name supplied does not match the name held against the account. */
    PN01("Account Owner Name Do Not Match"),

    /** The name supplied is similar but not an exact match for the account holder's name. */
    PN02("Account Owner Name Close Match"),

    /** The identification number supplied does not match the account holder's record. */
    PI01("Account Owner Identification Do Not Match");

    private final String description;

    AvsReasonCode(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /** Returns the matching enum constant, or {@code null} if the code is not recognised. */
    public static AvsReasonCode fromCode(String code) {
        if (code == null) return null;
        try {
            return valueOf(code.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
