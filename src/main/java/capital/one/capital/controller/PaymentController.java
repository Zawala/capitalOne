package capital.one.capital.controller;

import capital.one.capital.config.InstitutionProperties;
import capital.one.capital.dto.TransferRequestDTO;
import capital.one.capital.model.TransferLog;
import capital.one.capital.model.TransferStatus;
import capital.one.capital.repository.TransferLogRepository;
import capital.one.capital.service.Iso20022MarshallingService;
import capital.one.capital.service.Pacs008BuilderService;
import capital.one.capital.service.Pacs008BuilderService.BuildResult;
import capital.one.capital.service.PaymentKafkaProducer;
import capital.one.capital.service.WalletPaymentEvent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.xml.bind.JAXBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.math.BigDecimal;

@Tag(name = "Payments", description = "Credit transfers — send and receive ISO 20022 pacs.008 payments")
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final Pacs008BuilderService pacs008Builder;
    private final Iso20022MarshallingService marshaller;
    private final TransferLogRepository transferLogRepository;
    private final InstitutionProperties institution;
    private final RestTemplate restTemplate;
    private final PaymentKafkaProducer kafkaProducer;

    public PaymentController(
            Pacs008BuilderService pacs008Builder,
            Iso20022MarshallingService marshaller,
            TransferLogRepository transferLogRepository,
            InstitutionProperties institution,
            RestTemplate restTemplate,
            PaymentKafkaProducer kafkaProducer) {
        this.pacs008Builder = pacs008Builder;
        this.marshaller = marshaller;
        this.transferLogRepository = transferLogRepository;
        this.institution = institution;
        this.restTemplate = restTemplate;
        this.kafkaProducer = kafkaProducer;
    }

    /**
     * Initiates a credit transfer.
     * <ol>
     *   <li>Builds a pacs.008.001.14 Document from the request.</li>
     *   <li>Persists a PENDING log entry.</li>
     *   <li>Marshals to XML and POSTs to the creditor agent.</li>
     *   <li>Updates the log to SENT or FAILED.</li>
     * </ol>
     */
    /**
     * Receives an inbound pacs.008 credit transfer and records it as a deposit.
     * <p>
     * Accepts any pacs.008.001.xx variant. Mobile numbers in {@code <CtctDtls><MobNb>}
     * are treated as wallet addresses when no dedicated account element is present.
     */
    @Operation(summary = "Receive inbound credit transfer",
               description = "Accepts an inbound pacs.008 XML payload, parses it, persists a deposit record, and publishes to Kafka.")
    @ApiResponse(responseCode = "200", description = "Deposit accepted")
    @ApiResponse(responseCode = "400", description = "Invalid pacs.008 XML payload")
    @PostMapping(value = "/receive", consumes = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> receive(@RequestBody String rawXml) {

        // ── 1. Parse with namespace-agnostic DOM (handles any pacs.008 version) ──
        String messageId;
        String endToEndId;
        String senderInstitution;
        String debtorName;
        String debtorAccount;
        BigDecimal amount;
        String currency;
        String receiverInstitution;
        String creditorName;
        String creditorAccount;

        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);   // ignore namespace differences between versions
            Document dom = dbf.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(rawXml)));

            XPath xp = XPathFactory.newInstance().newXPath();

            messageId      = xpathText(xp, dom, "//MsgId");
            endToEndId     = xpathText(xp, dom, "//EndToEndId");
            debtorName     = xpathText(xp, dom, "//Dbtr/Nm");
            creditorName   = xpathText(xp, dom, "//Cdtr/Nm");

            // Institution IDs — prefer BIC, fall back to Othr/Id (local scheme)
            String instgBic  = xpathText(xp, dom, "//InstgAgt/FinInstnId/BICFI");
            String instgOthr = xpathText(xp, dom, "//InstgAgt/FinInstnId/Othr/Id");
            senderInstitution   = instgBic.isBlank() ? instgOthr : instgBic;

            String instdBic  = xpathText(xp, dom, "//InstdAgt/FinInstnId/BICFI");
            String instdOthr = xpathText(xp, dom, "//InstdAgt/FinInstnId/Othr/Id");
            receiverInstitution = instdBic.isBlank() ? instdOthr : instdBic;

            // Account: prefer explicit account number, fall back to mobile (wallet)
            String dbtrAcct = xpathText(xp, dom, "//DbtrAcct//Id/Othr/Id");
            String dbtrMob  = xpathText(xp, dom, "//Dbtr/CtctDtls/MobNb");
            debtorAccount   = dbtrAcct.isBlank() ? dbtrMob : dbtrAcct;

            String cdtrAcct = xpathText(xp, dom, "//CdtrAcct//Id/Othr/Id");
            String cdtrMob  = xpathText(xp, dom, "//Cdtr/CtctDtls/MobNb");
            creditorAccount = cdtrAcct.isBlank() ? cdtrMob : cdtrAcct;

            String amtText = xpathText(xp, dom, "//IntrBkSttlmAmt");
            amount   = amtText.isBlank() ? BigDecimal.ZERO : new BigDecimal(amtText.trim());
            currency = xpathAttr(xp, dom, "//IntrBkSttlmAmt/@Ccy");

        } catch (Exception e) {
            log.error("Failed to parse inbound pacs.008", e);
            return ResponseEntity.badRequest()
                    .body("Invalid pacs.008 payload: " + e.getMessage());
        }

        // ── 2. Persist as RECEIVED deposit ───────────────────────────────────
        TransferLog transferLog = new TransferLog();
        transferLog.setMessageId(messageId);
        transferLog.setEndToEndId(endToEndId);
        transferLog.setDebtorName(debtorName);
        transferLog.setDebtorAccount(debtorAccount);
        transferLog.setCreditorName(creditorName);
        transferLog.setCreditorAccount(creditorAccount);
        transferLog.setAmount(amount);
        transferLog.setCurrency(currency);
        transferLog.setStatus(TransferStatus.RECEIVED);
        transferLog.setRawXmlSent(rawXml);
        transferLogRepository.save(transferLog);

        log.info("Deposit RECEIVED — msgId={} endToEndId={} creditor={} amount={} {}",
                messageId, endToEndId, creditorName, amount, currency);

        // ── 3. Publish to Kafka ───────────────────────────────────────────────
        kafkaProducer.publishDeposit(new WalletPaymentEvent(
                messageId,
                senderInstitution, debtorName, debtorAccount,
                amount, currency,
                receiverInstitution, creditorName, creditorAccount));

        return ResponseEntity.ok("Deposit accepted: " + messageId);
    }

    // ── XPath helpers ─────────────────────────────────────────────────────────

    private String xpathText(XPath xp, Document dom, String expr) {
        try {
            NodeList nodes = (NodeList) xp.evaluate(expr, dom, XPathConstants.NODESET);
            return (nodes.getLength() > 0) ? nodes.item(0).getTextContent().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String xpathAttr(XPath xp, Document dom, String expr) {
        try {
            String val = (String) xp.evaluate(expr, dom, XPathConstants.STRING);
            return val != null ? val.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    @Operation(summary = "Initiate credit transfer",
               description = "Builds a pacs.008 credit transfer, persists a log, sends XML to the creditor agent, and publishes to Kafka.")
    @ApiResponse(responseCode = "200", description = "Transfer sent successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request — validation failed")
    @ApiResponse(responseCode = "500", description = "Failed to build or marshal the pacs.008 message")
    @ApiResponse(responseCode = "502", description = "Creditor agent endpoint unreachable or returned an error")
    @PostMapping("/transfer")
    public ResponseEntity<String> transfer(@Valid @RequestBody TransferRequestDTO request) {

        // ── 1. Build pacs.008 ─────────────────────────────────────────────────
        BuildResult built;
        String xml;
        try {
            built = pacs008Builder.build(request);
            xml   = marshaller.marshalPayment(built.document());
            log.info("pacs.008 XML:\n{}", xml);
        } catch (JAXBException e) {
            log.error("Failed to build/marshal pacs.008", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to build payment message: " + e.getMessage());
        }

        // ── 2. Persist PENDING log ────────────────────────────────────────────
        TransferLog transferLog = new TransferLog();
        transferLog.setMessageId(built.messageId());
        transferLog.setEndToEndId(built.endToEndId());
        transferLog.setDebtorName(request.getFirstName() + " " + request.getLastName());
        transferLog.setDebtorAccount(request.getDebtorAccountNumber());
        transferLog.setCreditorName(request.getCreditorName());
        transferLog.setCreditorAccount(request.getCreditorAccountNumber());
        transferLog.setAmount(request.getAmount());
        transferLog.setCurrency(request.getCurrency());
        transferLog.setStatus(TransferStatus.PENDING);
        transferLog.setRawXmlSent(xml);
        transferLogRepository.save(transferLog);

        log.info("Transfer PENDING — msgId={} endToEndId={} debtor={} amount={} {}",
                built.messageId(), built.endToEndId(),
                transferLog.getDebtorName(), request.getAmount(), request.getCurrency());

        // ── 3. POST XML to creditor agent ─────────────────────────────────────
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        HttpEntity<String> httpRequest = new HttpEntity<>(xml, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    institution.getCreditorAgentUrl(), httpRequest, String.class);

            transferLog.setStatus(TransferStatus.SENT);
            transferLog.setResponseBody(response.getBody());
            transferLogRepository.save(transferLog);

            // ── 4. Publish to Kafka ───────────────────────────────────────────
            kafkaProducer.publishCredit(new WalletPaymentEvent(
                    built.messageId(),
                    institution.getBic(),
                    request.getFirstName() + " " + request.getLastName(),
                    request.getDebtorAccountNumber(),
                    request.getAmount(), request.getCurrency(),
                    request.getCreditorAgentBic(),
                    request.getCreditorName(),
                    request.getCreditorAccountNumber()));

            log.info("Transfer SENT — msgId={} httpStatus={}", built.messageId(), response.getStatusCode());
            return ResponseEntity.ok(response.getBody());

        } catch (RestClientException e) {
            transferLog.setStatus(TransferStatus.FAILED);
            transferLog.setResponseBody(e.getMessage());
            transferLogRepository.save(transferLog);

            log.error("Transfer FAILED — msgId={} error={}", built.messageId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("Payment submission failed: " + e.getMessage());
        }
    }
}
