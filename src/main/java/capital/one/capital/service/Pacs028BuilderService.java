package capital.one.capital.service;

import capital.one.capital.config.InstitutionProperties;

import capital.one.capital.iso.pacs.pacs028_001_07.BranchAndFinancialInstitutionIdentification8;
import capital.one.capital.iso.pacs.pacs028_001_07.Document;
import capital.one.capital.iso.pacs.pacs028_001_07.FIToFIPaymentStatusRequestV07;
import capital.one.capital.iso.pacs.pacs028_001_07.FinancialInstitutionIdentification23;
import capital.one.capital.iso.pacs.pacs028_001_07.GroupHeader109;
import capital.one.capital.iso.pacs.pacs028_001_07.OriginalGroupInformation27;

import org.springframework.stereotype.Service;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.time.ZonedDateTime;
import java.util.GregorianCalendar;
import java.util.UUID;

@Service
public class Pacs028BuilderService {

    private final InstitutionProperties institution;

    public Pacs028BuilderService(InstitutionProperties institution) {
        this.institution = institution;
    }

    /**
     * Builds a pacs.028.001.07 FIToFIPaymentStatusRequest.
     *
     * <pre>
     * GrpHdr.MsgId           → newly generated STS-… identifier
     * GrpHdr.InstgAgt        → our institution BIC
     * OrgnlGrpInf.OrgnlMsgId → the original pacs.008 message ID being queried
     * OrgnlGrpInf.OrgnlMsgNmId → "pacs.008.001.14"
     * </pre>
     *
     * @param originalMsgId the messageId of the pacs.008 transfer to query
     * @return populated Document and the newly generated status-request messageId
     */
    public BuildResult build(String originalMsgId) {
        String msgId = "STS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();

        Document doc = new Document();
        FIToFIPaymentStatusRequestV07 req = new FIToFIPaymentStatusRequestV07();
        doc.setFIToFIPmtStsReq(req);

        // ── Group Header ──────────────────────────────────────────────────────
        GroupHeader109 grpHdr = new GroupHeader109();
        grpHdr.setMsgId(msgId);
        grpHdr.setCreDtTm(xmlNow());
        grpHdr.setInstgAgt(agentFromBic(institution.getBic()));
        req.setGrpHdr(grpHdr);

        // ── Original Group Info — references the pacs.008 being queried ───────
        OriginalGroupInformation27 orgnlGrpInf = new OriginalGroupInformation27();
        orgnlGrpInf.setOrgnlMsgId(originalMsgId);
        orgnlGrpInf.setOrgnlMsgNmId("pacs.008.001.14");
        req.getOrgnlGrpInf().add(orgnlGrpInf);

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
