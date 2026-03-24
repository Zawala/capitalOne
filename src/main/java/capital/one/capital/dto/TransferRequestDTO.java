package capital.one.capital.dto;

import java.math.BigDecimal;

public class TransferRequestDTO {

    /** Debtor (client) first name */
    private String firstName;

    /** Debtor (client) last name */
    private String lastName;

    /** Debtor account number (IBAN or local account) */
    private String debtorAccountNumber;

    /** Transfer amount */
    private BigDecimal amount;

    /** ISO 4217 currency code, e.g. ZAR, USD */
    private String currency;

    /** Creditor (beneficiary) full name */
    private String creditorName;

    /** Creditor account number (IBAN or local account) */
    private String creditorAccountNumber;

    /** BIC of the destination (creditor agent) bank — varies per transaction */
    private String creditorAgentBic;

    /** Free-text payment reference shown on statement */
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
