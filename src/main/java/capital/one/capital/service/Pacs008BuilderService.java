package capital.one.capital.service;

import capital.one.capital.config.InstitutionProperties;
import capital.one.capital.dto.TransferRequestDTO;

// Generated pacs.008.001.14 classes (available after mvn generate-sources)
import capital.one.capital.iso.pacs.pacs008_001_14.AccountIdentification4Choice;
import capital.one.capital.iso.pacs.pacs008_001_14.ActiveCurrencyAndAmount;
import capital.one.capital.iso.pacs.pacs008_001_14.BranchAndFinancialInstitutionIdentification8;
import capital.one.capital.iso.pacs.pacs008_001_14.CashAccount40;
import capital.one.capital.iso.pacs.pacs008_001_14.ChargeBearerType1Code;
import capital.one.capital.iso.pacs.pacs008_001_14.CreditTransferTransaction73;
import capital.one.capital.iso.pacs.pacs008_001_14.Document;
import capital.one.capital.iso.pacs.pacs008_001_14.FIToFICustomerCreditTransferV14;
import capital.one.capital.iso.pacs.pacs008_001_14.FinancialInstitutionIdentification23;
import capital.one.capital.iso.pacs.pacs008_001_14.GenericAccountIdentification1;
import capital.one.capital.iso.pacs.pacs008_001_14.GroupHeader131;
import capital.one.capital.iso.pacs.pacs008_001_14.PartyIdentification272;
import capital.one.capital.iso.pacs.pacs008_001_14.PaymentIdentification13;
import capital.one.capital.iso.pacs.pacs008_001_14.PostalAddress27;
import capital.one.capital.iso.pacs.pacs008_001_14.RemittanceInformation26;
import capital.one.capital.iso.pacs.pacs008_001_14.SettlementInstruction15;
import capital.one.capital.iso.pacs.pacs008_001_14.SettlementMethod1Code;

import org.springframework.stereotype.Service;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.time.ZonedDateTime;
import java.util.GregorianCalendar;
import java.util.UUID;

@Service
public class Pacs008BuilderService {

    private final InstitutionProperties institution;

    public Pacs008BuilderService(InstitutionProperties institution) {
        this.institution = institution;
    }

    /**
     * Builds a pacs.008.001.14 Document from the inbound transfer request.
     *
     * <pre>
     * Debtor Agent (DbtrAgt)  → our institution (from application.properties)
     * Debtor       (Dbtr)     → client name + account (from DTO)
     * Creditor Agent (CdtrAgt)→ destination bank BIC (from DTO — varies per transaction)
     * Creditor     (Cdtr)     → beneficiary name + account (from DTO)
     * </pre>
     *
     * @return populated Document and the generated messageId (index 0) and endToEndId (index 1)
     */
    public BuildResult build(TransferRequestDTO request) {
        String messageId   = "MSG-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        String endToEndId  = "E2E-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        String uetr        = UUID.randomUUID().toString();

        Document doc = new Document();
        FIToFICustomerCreditTransferV14 fiToFi = new FIToFICustomerCreditTransferV14();
        doc.setFIToFICstmrCdtTrf(fiToFi);

        // ── Group Header ──────────────────────────────────────────────────────
        GroupHeader131 grpHdr = new GroupHeader131();
        grpHdr.setMsgId(messageId);
        grpHdr.setCreDtTm(xmlNow());
        grpHdr.setNbOfTxs("1");

        // Settlement instruction — mandatory in pacs.008
        SettlementInstruction15 sttlm = new SettlementInstruction15();
        sttlm.setSttlmMtd(SettlementMethod1Code.CLRG);
        grpHdr.setSttlmInf(sttlm);

        // Instructing Agent = our institution
        grpHdr.setInstgAgt(agentFromBic(institution.getBic()));

        // Instructed Agent = destination bank (per-transaction, from DTO)
        grpHdr.setInstdAgt(agentFromBic(request.getCreditorAgentBic()));

        fiToFi.setGrpHdr(grpHdr);

        // ── Credit Transfer Transaction ───────────────────────────────────────
        CreditTransferTransaction73 tx = new CreditTransferTransaction73();

        // Payment identification
        PaymentIdentification13 pmtId = new PaymentIdentification13();
        pmtId.setEndToEndId(endToEndId);
        pmtId.setUETR(uetr);
        tx.setPmtId(pmtId);

        // Interbank settlement amount
        ActiveCurrencyAndAmount amt = new ActiveCurrencyAndAmount();
        amt.setValue(request.getAmount());
        amt.setCcy(request.getCurrency());
        tx.setIntrBkSttlmAmt(amt);

        // Charges borne by debtor (SHA = shared is common; DEBT = debtor pays all)
        tx.setChrgBr(ChargeBearerType1Code.SHAR);

        // ── Debtor Agent = our institution ────────────────────────────────────
        tx.setDbtrAgt(agentFromBic(institution.getBic()));

        // ── Debtor = client ───────────────────────────────────────────────────
        PartyIdentification272 dbtr = new PartyIdentification272();
        dbtr.setNm(request.getFirstName() + " " + request.getLastName());
        dbtr.setPstlAdr(institutionAddress());
        tx.setDbtr(dbtr);

        // Debtor account (client's account number)
        tx.setDbtrAcct(accountFromNumber(request.getDebtorAccountNumber()));

        // ── Creditor Agent = destination bank (per-transaction, from DTO) ────────
        tx.setCdtrAgt(agentFromBic(request.getCreditorAgentBic()));

        // ── Creditor = beneficiary ────────────────────────────────────────────
        PartyIdentification272 cdtr = new PartyIdentification272();
        cdtr.setNm(request.getCreditorName());
        tx.setCdtr(cdtr);

        // Creditor account
        tx.setCdtrAcct(accountFromNumber(request.getCreditorAccountNumber()));

        // Remittance information
        if (request.getRemittanceInfo() != null && !request.getRemittanceInfo().isBlank()) {
            RemittanceInformation26 rmtInf = new RemittanceInformation26();
            rmtInf.getUstrd().add(request.getRemittanceInfo());
            tx.setRmtInf(rmtInf);
        }

        fiToFi.getCdtTrfTxInf().add(tx);

        return new BuildResult(doc, messageId, endToEndId);
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

    private PostalAddress27 institutionAddress() {
        PostalAddress27 addr = new PostalAddress27();
        addr.setStrtNm(institution.getStreet());
        addr.setTwnNm(institution.getCity());
        addr.setPstCd(institution.getPostalCode());
        addr.setCtry(institution.getCountry());
        return addr;
    }

    private XMLGregorianCalendar xmlNow() {
        try {
            GregorianCalendar cal = GregorianCalendar.from(ZonedDateTime.now());
            return DatatypeFactory.newInstance().newXMLGregorianCalendar(cal);
        } catch (DatatypeConfigurationException e) {
            throw new RuntimeException("Failed to create XMLGregorianCalendar", e);
        }
    }

    // ── Result carrier ────────────────────────────────────────────────────────

    public record BuildResult(Document document, String messageId, String endToEndId) {}
}
