package capital.one.capital.service;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Marshals and unmarshals ISO 20022 message payloads.
 *
 * <pre>
 * Service                  Message       XSD in use
 * ─────────────────────────────────────────────────────────────────
 * Payment                  pacs.008      pacs.008.001.14
 * Payment Response         pacs.002      pacs.002.001.16
 * Payment Return           pacs.004      pacs.004.001.15
 * Payment Return Response  pacs.002      pacs.002.001.16
 * Status Request           pacs.028      pacs.028.001.07
 * Status Response          pacs.002      pacs.002.001.16
 * AVS Request              acmt.023      acmt.023.001.04
 * AVS Response             acmt.024      acmt.024.001.04
 * </pre>
 *
 * All ISO 20022 schemas declare a global {@code <xs:element name="Document">}, so XJC
 * does not generate {@code @XmlRootElement}. Marshalling therefore uses each schema's
 * {@code ObjectFactory.createDocument()} to produce a {@code JAXBElement}, and
 * unmarshalling casts the returned {@code JAXBElement} back to the concrete type.
 */
@Service
public class Iso20022MarshallingService {

    // Generated package names — must match the packageName values in the .xjb bindings files
    private static final String PKG_PACS_008 = "capital.one.capital.iso.pacs.pacs008_001_14";
    private static final String PKG_PACS_002 = "capital.one.capital.iso.pacs.pacs002_001_16";
    private static final String PKG_PACS_004 = "capital.one.capital.iso.pacs.pacs004_001_15";
    private static final String PKG_PACS_028 = "capital.one.capital.iso.pacs.pacs028_001_07";
    private static final String PKG_ACMT_023 = "capital.one.capital.iso.acmt.acmt023_001_04";
    private static final String PKG_ACMT_024 = "capital.one.capital.iso.acmt.acmt024_001_04";

    // JAXBContext is thread-safe and expensive to create — cache by package name
    private final Map<String, JAXBContext> contextCache = new ConcurrentHashMap<>();

    // ── Core helpers ──────────────────────────────────────────────────────────

    private JAXBContext contextFor(String pkg) throws JAXBException {
        try {
            return contextCache.computeIfAbsent(pkg, p -> {
                try {
                    return JAXBContext.newInstance(p);
                } catch (JAXBException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof JAXBException jaxbEx) throw jaxbEx;
            throw e;
        }
    }

    private String doMarshal(JAXBElement<?> element, String pkg) throws JAXBException {
        StringWriter writer = new StringWriter();
        Marshaller marshaller = contextFor(pkg).createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        marshaller.marshal(element, writer);
        return writer.toString();
    }

    @SuppressWarnings("unchecked")
    private <T> T doUnmarshal(String xml, String pkg) throws JAXBException {
        Object result = contextFor(pkg).createUnmarshaller().unmarshal(new StringReader(xml));
        return (result instanceof JAXBElement<?> el) ? (T) el.getValue() : (T) result;
    }

    // ── Payment — pacs.008.001.14 ─────────────────────────────────────────────

    public String marshalPayment(
            capital.one.capital.iso.pacs.pacs008_001_14.Document doc) throws JAXBException {
        return doMarshal(
                new capital.one.capital.iso.pacs.pacs008_001_14.ObjectFactory().createDocument(doc),
                PKG_PACS_008);
    }

    public capital.one.capital.iso.pacs.pacs008_001_14.Document unmarshalPayment(
            String xml) throws JAXBException {
        return doUnmarshal(xml, PKG_PACS_008);
    }

    // ── Payment Response — pacs.002.001.16 ───────────────────────────────────

    public String marshalPaymentResponse(
            capital.one.capital.iso.pacs.pacs002_001_16.Document doc) throws JAXBException {
        return doMarshal(
                new capital.one.capital.iso.pacs.pacs002_001_16.ObjectFactory().createDocument(doc),
                PKG_PACS_002);
    }

    public capital.one.capital.iso.pacs.pacs002_001_16.Document unmarshalPaymentResponse(
            String xml) throws JAXBException {
        return doUnmarshal(xml, PKG_PACS_002);
    }

    // ── Payment Return — pacs.004.001.15 ─────────────────────────────────────

    public String marshalPaymentReturn(
            capital.one.capital.iso.pacs.pacs004_001_15.Document doc) throws JAXBException {
        return doMarshal(
                new capital.one.capital.iso.pacs.pacs004_001_15.ObjectFactory().createDocument(doc),
                PKG_PACS_004);
    }

    public capital.one.capital.iso.pacs.pacs004_001_15.Document unmarshalPaymentReturn(
            String xml) throws JAXBException {
        return doUnmarshal(xml, PKG_PACS_004);
    }

    // ── Payment Return Response — pacs.002.001.16 (same schema as Payment Response) ──

    public String marshalPaymentReturnResponse(
            capital.one.capital.iso.pacs.pacs002_001_16.Document doc) throws JAXBException {
        return marshalPaymentResponse(doc);
    }

    public capital.one.capital.iso.pacs.pacs002_001_16.Document unmarshalPaymentReturnResponse(
            String xml) throws JAXBException {
        return unmarshalPaymentResponse(xml);
    }

    // ── Status Request — pacs.028.001.07 ─────────────────────────────────────

    public String marshalStatusRequest(
            capital.one.capital.iso.pacs.pacs028_001_07.Document doc) throws JAXBException {
        return doMarshal(
                new capital.one.capital.iso.pacs.pacs028_001_07.ObjectFactory().createDocument(doc),
                PKG_PACS_028);
    }

    public capital.one.capital.iso.pacs.pacs028_001_07.Document unmarshalStatusRequest(
            String xml) throws JAXBException {
        return doUnmarshal(xml, PKG_PACS_028);
    }

    // ── Status Response — pacs.002.001.16 (same schema as Payment Response) ──

    public String marshalStatusResponse(
            capital.one.capital.iso.pacs.pacs002_001_16.Document doc) throws JAXBException {
        return marshalPaymentResponse(doc);
    }

    public capital.one.capital.iso.pacs.pacs002_001_16.Document unmarshalStatusResponse(
            String xml) throws JAXBException {
        return unmarshalPaymentResponse(xml);
    }

    // ── AVS Request — acmt.023.001.04 ────────────────────────────────────────

    public String marshalAvsRequest(
            capital.one.capital.iso.acmt.acmt023_001_04.Document doc) throws JAXBException {
        return doMarshal(
                new capital.one.capital.iso.acmt.acmt023_001_04.ObjectFactory().createDocument(doc),
                PKG_ACMT_023);
    }

    public capital.one.capital.iso.acmt.acmt023_001_04.Document unmarshalAvsRequest(
            String xml) throws JAXBException {
        return doUnmarshal(xml, PKG_ACMT_023);
    }

    // ── AVS Response — acmt.024.001.04 ───────────────────────────────────────

    public String marshalAvsResponse(
            capital.one.capital.iso.acmt.acmt024_001_04.Document doc) throws JAXBException {
        return doMarshal(
                new capital.one.capital.iso.acmt.acmt024_001_04.ObjectFactory().createDocument(doc),
                PKG_ACMT_024);
    }

    public capital.one.capital.iso.acmt.acmt024_001_04.Document unmarshalAvsResponse(
            String xml) throws JAXBException {
        return doUnmarshal(xml, PKG_ACMT_024);
    }
}
