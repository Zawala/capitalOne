package capital.one.capital.dto;

public class AvsResponseDTO {

    /** true = account holder name matches the account, false = mismatch */
    private boolean verified;

    /** The reference ID echoed back from the acmt.024 VerificationReport.OrgnlId */
    private String referenceId;

    /**
     * Reason code when verified = false.
     * See {@link capital.one.capital.model.AvsReasonCode} for the full list of possible values.
     * Common codes: PN01 (name mismatch), PN02 (close match), PI01 (ID mismatch),
     * AC01 (invalid account), AC04 (closed), AC06 (blocked), NORR (no response).
     */
    private String reasonCode;

    /** Account holder name as held by the responding institution (if returned) */
    private String registeredName;

    /** Account number as held by the responding institution (if returned) */
    private String registeredAccount;

    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }

    public String getReferenceId() { return referenceId; }
    public void setReferenceId(String referenceId) { this.referenceId = referenceId; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public String getRegisteredName() { return registeredName; }
    public void setRegisteredName(String registeredName) { this.registeredName = registeredName; }

    public String getRegisteredAccount() { return registeredAccount; }
    public void setRegisteredAccount(String registeredAccount) { this.registeredAccount = registeredAccount; }
}
