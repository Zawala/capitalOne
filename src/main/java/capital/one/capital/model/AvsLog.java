package capital.one.capital.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "avs_log")
public class AvsLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String messageId;

    @Column(nullable = false)
    private String referenceId;

    private String accountHolderName;
    private String accountNumber;
    private String bankBic;

    /** null until response received */
    private Boolean verified;

    private String reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferStatus status;

    @Column(columnDefinition = "TEXT")
    private String rawXmlSent;

    @Column(columnDefinition = "TEXT")
    private String rawXmlReceived;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onPersist() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getReferenceId() { return referenceId; }
    public void setReferenceId(String referenceId) { this.referenceId = referenceId; }

    public String getAccountHolderName() { return accountHolderName; }
    public void setAccountHolderName(String accountHolderName) { this.accountHolderName = accountHolderName; }

    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }

    public String getBankBic() { return bankBic; }
    public void setBankBic(String bankBic) { this.bankBic = bankBic; }

    public Boolean getVerified() { return verified; }
    public void setVerified(Boolean verified) { this.verified = verified; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public TransferStatus getStatus() { return status; }
    public void setStatus(TransferStatus status) { this.status = status; }

    public String getRawXmlSent() { return rawXmlSent; }
    public void setRawXmlSent(String rawXmlSent) { this.rawXmlSent = rawXmlSent; }

    public String getRawXmlReceived() { return rawXmlReceived; }
    public void setRawXmlReceived(String rawXmlReceived) { this.rawXmlReceived = rawXmlReceived; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
