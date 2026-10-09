package io.workshopsentinel;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** Public Web API only; no native Steam callbacks, subscriptions, or in-process downloads. */
public final class SteamWorkshopUpdateProvider implements WorkshopUpdateProvider {
    public interface Transport { String post(String body) throws Exception; }
    private final Transport transport;
    private final Map<String, Long> baseline = new HashMap<>();
    private final Logger log;

    public SteamWorkshopUpdateProvider(long timeoutSeconds, Path installedBaseline, Logger log) throws IOException {
        this(http(timeoutSeconds), installedBaseline, log);
    }
    public SteamWorkshopUpdateProvider(Transport transport, Path installedBaseline, Logger log) throws IOException {
        this.transport = transport;
        this.log = log;
        if (installedBaseline != null) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(installedBaseline, StandardCharsets.UTF_8)) { p.load(r); }
            for (String id : p.stringPropertyNames()) {
                if (Config.ids(id).size() != 1) throw new IOException("Invalid baseline ID");
                long stamp = Long.parseLong(p.getProperty(id).trim());
                if (stamp <= 0) throw new IOException("Invalid baseline timestamp");
                baseline.put(id, stamp);
            }
        }
    }
    private static Transport http(long seconds) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(seconds))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        return body -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/"))
                .timeout(Duration.ofSeconds(seconds)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            // ofByteArray keeps the request timeout active while receiving the whole body.
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) throw new IOException("Steam HTTP " + response.statusCode());
            if (response.body().length > 4 * 1024 * 1024) throw new IOException("Steam response too large");
            return new String(response.body(), StandardCharsets.UTF_8);
        };
    }
    @Override public Set<String> check(Set<String> ids) throws Exception {
        if (ids.isEmpty()) return Collections.emptySet();
        List<String> list = new ArrayList<>(ids);
        Map<String, Long> current = new LinkedHashMap<>();
        for (int start = 0; start < list.size(); start += 100) {
            List<String> batch = list.subList(start, Math.min(start + 100, list.size()));
            StringBuilder body = new StringBuilder("format=xml&itemcount=").append(batch.size());
            for (int i = 0; i < batch.size(); i++) body.append("&publishedfileids%5B").append(i).append("%5D=").append(batch.get(i));
            current.putAll(parse(transport.post(body.toString()), new LinkedHashSet<>(batch)));
        }
        // Commit baselines only after ALL batches succeeded. Never acknowledge a pending update.
        Set<String> updates = new LinkedHashSet<>();
        for (Map.Entry<String, Long> item : current.entrySet()) {
            Long installed = baseline.get(item.getKey());
            if (installed != null && item.getValue() > installed) updates.add(item.getKey());
        }
        for (Map.Entry<String, Long> item : current.entrySet()) {
            if (!baseline.containsKey(item.getKey())) {
                baseline.put(item.getKey(), item.getValue());
                log.info("Steam session baseline id=" + item.getKey() + " time_updated=" + item.getValue()
                    + "; installed revision not verified");
            }
        }
        return updates;
    }
    public static Map<String, Long> parse(String xml, Set<String> expected) throws Exception {
        if (xml.length() > 4 * 1024 * 1024) throw new IOException("Steam response too large");
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        // Steam's XML includes this inert declaration. Remove ONLY this exact header;
        // external/internal DTD declarations remain forbidden by the secure parser.
        String safeXml = xml.replaceFirst("\\A(\\s*(?:<\\?xml[^?]*\\?>)?\\s*)<!DOCTYPE response>", "$1");
        javax.xml.parsers.DocumentBuilder builder = f.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
        });
        Document d = builder.parse(new InputSource(new StringReader(safeXml)));
        if (!d.getDocumentElement().getTagName().equals("response")) throw new IOException("Unexpected Steam root");
        Element details = child(d.getDocumentElement(), "publishedfiledetails");
        Map<String, Long> result = new LinkedHashMap<>();
        for (Node n = details.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            String id = text(e, "publishedfileid");
            if (!expected.contains(id) || result.containsKey(id)) throw new IOException("Unexpected/duplicate Steam ID");
            if (!text(e, "result").equals("1")) throw new IOException("Steam item unavailable: " + id);
            if (!text(e, "consumer_app_id").equals("108600")) throw new IOException("Not a PZ item: " + id);
            long updated = Long.parseLong(text(e, "time_updated"));
            if (updated <= 0) throw new IOException("Missing Steam revision");
            result.put(id, updated);
        }
        if (!result.keySet().equals(expected)) throw new IOException("Incomplete Steam response");
        return result;
    }
    private static Element child(Element e, String name) throws IOException {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && ((Element) n).getTagName().equals(name)) return (Element) n;
        throw new IOException("Missing Steam field " + name);
    }
    private static String text(Element e, String name) throws IOException { return child(e, name).getTextContent().trim(); }
}
