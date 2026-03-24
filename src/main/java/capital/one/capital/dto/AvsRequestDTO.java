package capital.one.capital.dto;

public class AvsRequestDTO {

    /** Account holder first name */
    private String firstName;

    /** Account holder last name */
    private String lastName;

    /** Account number (IBAN or local) to verify */
    private String accountNumber;

    /** BIC of the bank holding the account — the institution being queried */
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
