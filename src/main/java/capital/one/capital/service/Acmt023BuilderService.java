package capital.one.capital.service;

import capital.one.capital.config.InstitutionProperties;
import capital.one.capital.dto.AvsRequestDTO;
import capital.one.capital.dto.AvsResponseDTO;

// Generated acmt.023.001.04 classes (available after mvn generate-sources)
import capital.one.capital.iso.acmt.acmt023_001_04.AccountIdentification4Choice;
import capital.one.capital.iso.acmt.acmt023_001_04.BranchAndFinancialInstitutionIdentification8;
import capital.one.capital.iso.acmt.acmt023_001_04.CashAccount40;
import capital.one.capital.iso.acmt.acmt023_001_04.Document;
import capital.one.capital.iso.acmt.acmt023_001_04.FinancialInstitutionIdentification23;
import capital.one.capital.iso.acmt.acmt023_001_04.GenericAccountIdentification1;
import capital.one.capital.iso.acmt.acmt023_001_04.IdentificationAssignment4;
import capital.one.capital.iso.acmt.acmt023_001_04.IdentificationInformation5;
import capital.one.capital.iso.acmt.acmt023_001_04.IdentificationVerification5;
import capital.one.capital.iso.acmt.acmt023_001_04.IdentificationVerificationRequestV04;
import capital.one.capital.iso.acmt.acmt023_001_04.Party50Choice;
import capital.one.capital.iso.acmt.acmt023_001_04.PartyIdentification272;

// Generated acmt.024.001.04 classes for response parsing
import capital.one.capital.iso.acmt.acmt024_001_04.VerificationReport5;

import org.springframework.stereotype.Service;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.time.ZonedDateTime;
import java.util.GregorianCalendar;
import java.util.UUID;

/**
 * Builds acmt.023.001.04 (IdentificationVerificationRequest) documents
 * and parses acmt.024.001.04 (IdentificationVerificationReport) responses.
 *
 * <pre>
 * Assgnmt.Assgnr  → our institution (from application.properties)
 * Assgnmt.Assgne  → queried bank (bankBic from AvsRequestDTO)
 * Vrfctn.PtyAndAcctId.Pty  → account holder name (from DTO)
 * Vrfctn.PtyAndAcctId.Acct → account number (from DTO)
 * Vrfctn.PtyAndAcctId.Agt  → queried bank BIC (from DTO)
 * </pre>
 */
@Service
public class Acmt023BuilderService {

    private final InstitutionProperties institution;

    public Acmt023BuilderService(InstitutionProperties institution) {
        this.institution = institution;
    }

    // ── Build acmt.023 request ────────────────────────────────────────────────

    public BuildResult build(AvsRequestDTO request) {
        String messageId  = "AVS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        String referenceId = "REF-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();

        Document doc = new Document();
        IdentificationVerificationRequestV04 req = new IdentificationVerificationRequestV04();
        doc.setIdVrfctnReq(req);

        // ── Assignment header ─────────────────────────────────────────────────
        IdentificationAssignment4 assgnmt = new IdentificationAssignment4();
        assgnmt.setMsgId(messageId);
        assgnmt.setCreDtTm(xmlNow());

        // Assigner = our institution
        Party50Choice assgnr = new Party50Choice();
        assgnr.setAgt(agentFromBic(institution.getBic()));
        assgnmt.setAssgnr(assgnr);

        // Assignee = the bank we're querying
        Party50Choice assgne = new Party50Choice();
        assgne.setAgt(agentFromBic(request.getBankBic()));
        assgnmt.setAssgne(assgne);

        req.setAssgnmt(assgnmt);

        // ── Verification request ──────────────────────────────────────────────
        IdentificationVerification5 vrfctn = new IdentificationVerification5();
        vrfctn.setId(referenceId);

        IdentificationInformation5 ptyAndAcctId = new IdentificationInformation5();

        // Account holder name to verify
        PartyIdentification272 pty = new PartyIdentification272();
        pty.setNm(request.getFirstName() + " " + request.getLastName());
        ptyAndAcctId.setPty(pty);

        // Account to verify
        ptyAndAcctId.setAcct(accountFromNumber(request.getAccountNumber()));

        // Bank holding the account
        ptyAndAcctId.setAgt(agentFromBic(request.getBankBic()));

        vrfctn.setPtyAndAcctId(ptyAndAcctId);
        req.getVrfctn().add(vrfctn);

        return new BuildResult(doc, messageId, referenceId);
    }

    // ── Parse acmt.024 response ───────────────────────────────────────────────

    public AvsResponseDTO parseResponse(
            capital.one.capital.iso.acmt.acmt024_001_04.Document responseDoc) {

        VerificationReport5 report = responseDoc.getIdVrfctnRpt().getRpt().get(0);

        AvsResponseDTO dto = new AvsResponseDTO();
        dto.setReferenceId(report.getOrgnlId());
        dto.setVerified(report.isVrfctn());

        // Reason code (present when verified = false)
        if (report.getRsn() != null) {
            String code = report.getRsn().getCd() != null
                    ? report.getRsn().getCd()
                    : report.getRsn().getPrtry();
            dto.setReasonCode(code);
        }

        // Registered details returned by the responding institution
        capital.one.capital.iso.acmt.acmt024_001_04.IdentificationInformation5 updated =
                report.getUpdtdPtyAndAcctId();
        if (updated == null) updated = report.getOrgnlPtyAndAcctId();

        if (updated != null) {
            if (updated.getPty() != null) {
                dto.setRegisteredName(updated.getPty().getNm());
            }
            if (updated.getAcct() != null
                    && updated.getAcct().getId() != null
                    && updated.getAcct().getId().getOthr() != null) {
                dto.setRegisteredAccount(updated.getAcct().getId().getOthr().getId());
            }
        }

        return dto;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BranchAndFinancialInstitutionIdentification8 agentFromBic(String bic) {
        FinancialInstitutionIdentification23 finInstn = new FinancialInstitutionIdentification23();
        finInstn.setBICFI(bic);

        BranchAndFinancialInstitutionIdentification8 agent = new BranchAndFinancialInstitutionIdentification8();
        agent.setFinInstnId(finInstn);
        return agent;
    }

    private CashAccount40 accountFromNumber(String accountNumber) {
        GenericAccountIdentification1 othr = new GenericAccountIdentification1();
        othr.setId(accountNumber);

        AccountIdentification4Choice id = new AccountIdentification4Choice();
        id.setOthr(othr);

        CashAccount40 account = new CashAccount40();
        account.setId(id);
        return account;
    }

    private XMLGregorianCalendar xmlNow() {
        try {
            GregorianCalendar cal = GregorianCalendar.from(ZonedDateTime.now());
            return DatatypeFactory.newInstance().newXMLGregorianCalendar(cal);
        } catch (DatatypeConfigurationException e) {
            throw new RuntimeException("Failed to create XMLGregorianCalendar", e);
        }
    }

    public record BuildResult(Document document, String messageId, String referenceId) {}
}
