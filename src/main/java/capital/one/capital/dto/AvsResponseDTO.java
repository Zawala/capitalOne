package capital.one.capital.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of an account verification request")
public class AvsResponseDTO {

    @Schema(description = "true = account holder name matches the account, false = mismatch", example = "true")
    private boolean verified;

    @Schema(description = "Reference ID echoed back from the acmt.024 VerificationReport", example = "AVS-20260411-ABC123")
    private String referenceId;

    @Schema(description = "Reason code when verified = false (e.g. PN01, PN02, AC01, AC04, AC06, NORR)", example = "PN01")
    private String reasonCode;

    @Schema(description = "Account holder name as held by the responding institution", example = "Kelvin Zawala")
    private String registeredName;

    @Schema(description = "Account number as held by the responding institution", example = "ZA00123456789012")
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
