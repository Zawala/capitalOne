package capital.one.capital.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Request to verify that an account holder name matches an account number")
public class AvsRequestDTO {

    @NotBlank(message = "First name is required")
    @Size(min = 1, max = 70, message = "First name must be between 1 and 70 characters")
    @Schema(description = "Account holder first name", example = "Kelvin")
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(min = 1, max = 70, message = "Last name must be between 1 and 70 characters")
    @Schema(description = "Account holder last name", example = "Zawala")
    private String lastName;

    @NotBlank(message = "Account number is required")
    @Size(min = 5, max = 34, message = "Account number must be between 5 and 34 characters")
    @Schema(description = "Account number (IBAN or local) to verify", example = "ZA00123456789012")
    private String accountNumber;

    @NotBlank(message = "Bank BIC is required")
    @Pattern(regexp = "^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$",
             message = "Bank BIC must be a valid 8 or 11 character SWIFT/BIC code")
    @Schema(description = "BIC of the bank holding the account", example = "CAPZAZJJXXX")
    private String bankBic;

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }

    public String getBankBic() { return bankBic; }
    public void setBankBic(String bankBic) { this.bankBic = bankBic; }
}
