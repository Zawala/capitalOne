package capital.one.capital.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(description = "Request to initiate a credit transfer (pacs.008)")
public class TransferRequestDTO {

    @NotBlank(message = "First name is required")
    @Size(min = 1, max = 70, message = "First name must be between 1 and 70 characters")
    @Schema(description = "Debtor (client) first name", example = "Kelvin")
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(min = 1, max = 70, message = "Last name must be between 1 and 70 characters")
    @Schema(description = "Debtor (client) last name", example = "Zawala")
    private String lastName;

    @NotBlank(message = "Debtor account number is required")
    @Size(min = 5, max = 34, message = "Debtor account number must be between 5 and 34 characters")
    @Schema(description = "Debtor account number (IBAN or local account)", example = "ZA00123456789012")
    private String debtorAccountNumber;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    @Schema(description = "Transfer amount", example = "1500.00")
    private BigDecimal amount;

    @NotBlank(message = "Currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO 4217 code")
    @Schema(description = "ISO 4217 currency code", example = "ZAR")
    private String currency;

    @NotBlank(message = "Creditor name is required")
    @Size(min = 1, max = 140, message = "Creditor name must be between 1 and 140 characters")
    @Schema(description = "Creditor (beneficiary) full name", example = "Jane Doe")
    private String creditorName;

    @NotBlank(message = "Creditor account number is required")
    @Size(min = 5, max = 34, message = "Creditor account number must be between 5 and 34 characters")
    @Schema(description = "Creditor account number (IBAN or local account)", example = "ZA00987654321098")
    private String creditorAccountNumber;

    @NotBlank(message = "Creditor agent BIC is required")
    @Pattern(regexp = "^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$",
             message = "Creditor agent BIC must be a valid 8 or 11 character SWIFT/BIC code")
    @Schema(description = "BIC of the destination (creditor agent) bank", example = "FIABORJJXXX")
    private String creditorAgentBic;

    @Size(max = 140, message = "Remittance info must not exceed 140 characters")
    @Schema(description = "Free-text payment reference shown on statement", example = "Invoice INV-2026-042")
    private String remittanceInfo;

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getDebtorAccountNumber() { return debtorAccountNumber; }
    public void setDebtorAccountNumber(String debtorAccountNumber) { this.debtorAccountNumber = debtorAccountNumber; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getCreditorName() { return creditorName; }
    public void setCreditorName(String creditorName) { this.creditorName = creditorName; }

    public String getCreditorAccountNumber() { return creditorAccountNumber; }
    public void setCreditorAccountNumber(String creditorAccountNumber) { this.creditorAccountNumber = creditorAccountNumber; }

    public String getCreditorAgentBic() { return creditorAgentBic; }
    public void setCreditorAgentBic(String creditorAgentBic) { this.creditorAgentBic = creditorAgentBic; }

    public String getRemittanceInfo() { return remittanceInfo; }
    public void setRemittanceInfo(String remittanceInfo) { this.remittanceInfo = remittanceInfo; }
}
