package capital.one.capital.service;

import capital.one.capital.config.InstitutionProperties;
import capital.one.capital.model.TransferLog;

import capital.one.capital.iso.pacs.pacs004_001_15.ActiveCurrencyAndAmount;
import capital.one.capital.iso.pacs.pacs004_001_15.BranchAndFinancialInstitutionIdentification8;
import capital.one.capital.iso.pacs.pacs004_001_15.ChargeBearerType1Code;
import capital.one.capital.iso.pacs.pacs004_001_15.Document;
import capital.one.capital.iso.pacs.pacs004_001_15.FinancialInstitutionIdentification23;
import capital.one.capital.iso.pacs.pacs004_001_15.GroupHeader123;
import capital.one.capital.iso.pacs.pacs004_001_15.OriginalGroupInformation33;
import capital.one.capital.iso.pacs.pacs004_001_15.PaymentReturnReason7;
import capital.one.capital.iso.pacs.pacs004_001_15.PaymentReturnV15;
import capital.one.capital.iso.pacs.pacs004_001_15.PaymentTransaction168;
import capital.one.capital.iso.pacs.pacs004_001_15.ReturnReason5Choice;
import capital.one.capital.iso.pacs.pacs004_001_15.SettlementInstruction15;
import capital.one.capital.iso.pacs.pacs004_001_15.SettlementMethod1Code;

import org.springframework.stereotype.Service;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.time.ZonedDateTime;
import java.util.GregorianCalendar;
import java.util.UUID;

@Service
public class Pacs004BuilderService {

    private final InstitutionProperties institution;

    public Pacs004BuilderService(InstitutionProperties institution) {
        this.institution = institution;
    }

    /**
     * Builds a pacs.004.001.15 PaymentReturn from the original transfer log.
     *
     * <pre>
     * GrpHdr.MsgId                → newly generated RTR-… identifier
     * GrpHdr.InstgAgt             → our institution BIC
     * TxInf.OrgnlGrpInf           → original pacs.008 MsgId / MsgNmId
     * TxInf.OrgnlEndToEndId       → original E2E identifier
     * TxInf.RtrdIntrBkSttlmAmt   → original amount + currency
     * TxInf.RtrRsnInf.Rsn.Cd     → caller-supplied reason code (default DUPL)
     * </pre>
     *
     * @param original   the persisted TransferLog for the payment being returned
     * @param reasonCode ISO 20022 external return reason code (e.g. DUPL, FRAD, CUST)
     * @return populated Document and the newly generated return messageId
     */
    public BuildResult build(TransferLog original, String reasonCode) {
        String msgId = "RTR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        String rtrId = "RID-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();

        Document doc = new Document();
        PaymentReturnV15 pmtRtr = new PaymentReturnV15();
        doc.setPmtRtr(pmtRtr);

        // ── Group Header ──────────────────────────────────────────────────────
        GroupHeader123 grpHdr = new GroupHeader123();
        grpHdr.setMsgId(msgId);
        grpHdr.setCreDtTm(xmlNow());
        grpHdr.setNbOfTxs("1");

        SettlementInstruction15 sttlm = new SettlementInstruction15();
        sttlm.setSttlmMtd(SettlementMethod1Code.CLRG);
        grpHdr.setSttlmInf(sttlm);

        grpHdr.setInstgAgt(agentFromBic(institution.getBic()));
        pmtRtr.setGrpHdr(grpHdr);

        // ── Transaction Info ──────────────────────────────────────────────────
        PaymentTransaction168 txInf = new PaymentTransaction168();
        txInf.setRtrId(rtrId);

        // Reference to original pacs.008 group
        OriginalGroupInformation33 orgnlGrpInf = new OriginalGroupInformation33();
        orgnlGrpInf.setOrgnlMsgId(original.getMessageId());
        orgnlGrpInf.setOrgnlMsgNmId("pacs.008.001.14");
        txInf.setOrgnlGrpInf(orgnlGrpInf);

        txInf.setOrgnlEndToEndId(original.getEndToEndId());

        // Returned interbank settlement amount (mandatory field)
        ActiveCurrencyAndAmount rtrdAmt = new ActiveCurrencyAndAmount();
        rtrdAmt.setValue(original.getAmount());
        rtrdAmt.setCcy(original.getCurrency());
        txInf.setRtrdIntrBkSttlmAmt(rtrdAmt);

        txInf.setChrgBr(ChargeBearerType1Code.SHAR);

        // Return reason
        ReturnReason5Choice rsn = new ReturnReason5Choice();
        rsn.setCd(reasonCode != null && !reasonCode.isBlank() ? reasonCode : "DUPL");

        PaymentReturnReason7 rtrRsnInf = new PaymentReturnReason7();
        rtrRsnInf.setRsn(rsn);
        txInf.getRtrRsnInf().add(rtrRsnInf);

        pmtRtr.getTxInf().add(txInf);

        return new BuildResult(doc, msgId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BranchAndFinancialInstitutionIdentification8 agentFromBic(String bic) {
        FinancialInstitutionIdentification23 finInstn = new FinancialInstitutionIdentification23();
        finInstn.setBICFI(bic);

        BranchAndFinancialInstitutionIdentification8 agent = new BranchAndFinancialInstitutionIdentification8();
        agent.setFinInstnId(finInstn);
        return agent;
    }

    private XMLGregorianCalendar xmlNow() {
        try {
            GregorianCalendar cal = GregorianCalendar.from(ZonedDateTime.now());
            return DatatypeFactory.newInstance().newXMLGregorianCalendar(cal);
        } catch (DatatypeConfigurationException e) {
            throw new RuntimeException("Failed to create XMLGregorianCalendar", e);
        }
    }

    public record BuildResult(Document document, String messageId) {}
}
