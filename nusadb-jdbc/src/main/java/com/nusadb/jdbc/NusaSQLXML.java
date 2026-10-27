package com.nusadb.jdbc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stax.StAXResult;
import javax.xml.transform.stax.StAXSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

/**
 * An XML value held as text: read from a result column ({@code getSQLXML}) or written by the
 * application ({@code createSQLXML}) and sent as a text parameter. The server has no XML type, so the
 * value lives in a {@code text} column. Parsing never resolves a DTD or an external entity.
 */
final class NusaSQLXML implements SQLXML {
    /** The XML text; null until a written value is finished. */
    private String text;
    /** Where an application is writing the value, until {@link #text()} collects it. */
    private StringWriter chars;
    private ByteArrayOutputStream bytes;
    private DOMResult dom;
    private boolean freed;

    /** A value read from the server. */
    NusaSQLXML(String text) {
        this.text = text;
    }

    /** An empty value to write ({@code Connection.createSQLXML}). */
    NusaSQLXML() {
    }

    private void ensureLive() throws SQLException {
        if (freed) {
            throw new SQLException("nusadb: the SQLXML was freed", "HY010");
        }
    }

    /** The XML text, collecting whatever was written through a stream or a result. */
    String text() throws SQLException {
        ensureLive();
        if (chars != null) {
            text = chars.toString();
        } else if (bytes != null) {
            text = bytes.toString(StandardCharsets.UTF_8);
        } else if (dom != null && dom.getNode() != null) {
            StringWriter out = new StringWriter();
            try {
                transformer().transform(new DOMSource(dom.getNode()), new StreamResult(out));
            } catch (Exception e) {
                throw new SQLException("nusadb: could not serialize the XML: " + e.getMessage(), "22000", e);
            }
            text = out.toString();
        }
        return text;
    }

    private String readable() throws SQLException {
        String t = text();
        if (t == null) {
            throw new SQLException("nusadb: the SQLXML has no value", "HY010");
        }
        return t;
    }

    private void writing() throws SQLException {
        ensureLive();
        chars = null;
        bytes = null;
        dom = null;
        text = null;
    }

    @Override
    public void free() {
        freed = true;
        text = null;
        chars = null;
        bytes = null;
        dom = null;
    }

    @Override
    public InputStream getBinaryStream() throws SQLException {
        return new ByteArrayInputStream(readable().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public OutputStream setBinaryStream() throws SQLException {
        writing();
        bytes = new ByteArrayOutputStream();
        return bytes;
    }

    @Override
    public Reader getCharacterStream() throws SQLException {
        return new StringReader(readable());
    }

    @Override
    public Writer setCharacterStream() throws SQLException {
        writing();
        chars = new StringWriter();
        return chars;
    }

    @Override
    public String getString() throws SQLException {
        return readable();
    }

    @Override
    public void setString(String value) throws SQLException {
        writing();
        text = value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Source> T getSource(Class<T> sourceClass) throws SQLException {
        String xml = readable();
        try {
            if (sourceClass == null || sourceClass == DOMSource.class) {
                DocumentBuilder builder = documents().newDocumentBuilder();
                return (T) new DOMSource(builder.parse(new InputSource(new StringReader(xml))));
            }
            if (sourceClass == StreamSource.class) {
                return (T) new StreamSource(new StringReader(xml));
            }
            if (sourceClass == SAXSource.class) {
                XMLReader reader = sax().newSAXParser().getXMLReader();
                return (T) new SAXSource(reader, new InputSource(new StringReader(xml)));
            }
            if (sourceClass == StAXSource.class) {
                return (T) new StAXSource(stax().createXMLStreamReader(new StringReader(xml)));
            }
        } catch (Exception e) {
            throw new SQLException("nusadb: could not parse the XML: " + e.getMessage(), "2200M", e);
        }
        throw new SQLFeatureNotSupportedException("nusadb: unsupported XML source " + sourceClass.getName());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Result> T setResult(Class<T> resultClass) throws SQLException {
        writing();
        try {
            if (resultClass == null || resultClass == DOMResult.class) {
                dom = new DOMResult();
                return (T) dom;
            }
            if (resultClass == StreamResult.class) {
                chars = new StringWriter();
                return (T) new StreamResult(chars);
            }
            if (resultClass == SAXResult.class) {
                chars = new StringWriter();
                TransformerHandler handler = ((SAXTransformerFactory) transformers()).newTransformerHandler();
                handler.setResult(new StreamResult(chars));
                return (T) new SAXResult(handler);
            }
            if (resultClass == StAXResult.class) {
                chars = new StringWriter();
                return (T) new StAXResult(XMLOutputFactory.newInstance().createXMLStreamWriter(chars));
            }
        } catch (Exception e) {
            throw new SQLException("nusadb: could not set up the XML result: " + e.getMessage(), "HY000", e);
        }
        throw new SQLFeatureNotSupportedException("nusadb: unsupported XML result " + resultClass.getName());
    }

    // --- parsers that do not load a DTD or an external entity ---

    private static DocumentBuilderFactory documents() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f;
    }

    private static SAXParserFactory sax() throws Exception {
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return f;
    }

    private static XMLInputFactory stax() {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f;
    }

    private static TransformerFactory transformers() throws Exception {
        TransformerFactory f = TransformerFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        return f;
    }

    private static Transformer transformer() throws Exception {
        Transformer t = transformers().newTransformer();
        t.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
        return t;
    }
}
