package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nusadb.testing.NusaTestServer;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Statement;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stax.StAXResult;
import javax.xml.transform.stax.StAXSource;
import javax.xml.transform.stream.StreamResult;
import org.junit.jupiter.api.Test;
import org.xml.sax.helpers.AttributesImpl;

/** SQLXML: the source and result kinds, entity safety, and the ways a value reaches a column. */
class SqlXmlIT {

    private static String read(Connection c, int id) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT body FROM xml_it WHERE id = " + id)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private static void insert(Connection c, int id, SQLXML x) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO xml_it VALUES (?, ?)")) {
            ps.setInt(1, id);
            ps.setObject(2, x);
            ps.executeUpdate();
        }
    }

    private static Connection open() throws SQLException {
        Connection c = NusaTestServer.get().connect();
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS xml_it (id int PRIMARY KEY, body text)");
            st.execute("DELETE FROM xml_it");
        }
        return c;
    }

    @Test
    void saxAndStaxBothWays() throws Exception {
        try (Connection c = open()) {
            SQLXML sax = c.createSQLXML();
            var handler = sax.setResult(SAXResult.class).getHandler();
            handler.startDocument();
            handler.startElement("", "a", "a", new AttributesImpl());
            handler.characters("x".toCharArray(), 0, 1);
            handler.endElement("", "a", "a");
            handler.endDocument();
            insert(c, 1, sax);
            assertTrue(read(c, 1).contains("<a>x</a>"), read(c, 1));

            SQLXML stax = c.createSQLXML();
            XMLStreamWriter w = stax.setResult(StAXResult.class).getXMLStreamWriter();
            w.writeStartElement("b");
            w.writeCharacters("y");
            w.writeEndElement();
            w.flush();
            insert(c, 2, stax);
            assertEquals("<b>y</b>", read(c, 2));

            SQLXML bin = c.createSQLXML();
            try (OutputStream out = bin.setBinaryStream()) {
                out.write("<c>é</c>".getBytes(StandardCharsets.UTF_8));
            }
            insert(c, 3, bin);
            assertEquals("<c>é</c>", read(c, 3));

            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT body FROM xml_it ORDER BY id")) {
                assertTrue(rs.next());
                SAXSource s = rs.getObject(1, SQLXML.class).getSource(SAXSource.class);
                StringWriter out = new StringWriter();
                TransformerFactory.newInstance().newTransformer().transform(s, new StreamResult(out));
                assertTrue(out.toString().contains("<a>x</a>"));
                assertTrue(rs.next());
                XMLStreamReader r = rs.getSQLXML(1).getSource(StAXSource.class).getXMLStreamReader();
                r.nextTag();
                assertEquals("b", r.getLocalName());
                assertEquals("y", r.getElementText());
            }
        }
    }

    @Test
    void entitiesAreNotResolved() throws Exception {
        try (Connection c = open()) {
            SQLXML x = c.createSQLXML();
            x.setString("<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY e SYSTEM \"file:///etc/hostname\">]><r>&e;</r>");
            insert(c, 1, x);
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT body FROM xml_it")) {
                assertTrue(rs.next());
                SQLXML read = rs.getSQLXML(1);
                SQLException dom = assertThrows(SQLException.class, () -> read.getSource(null));
                assertEquals("2200M", dom.getSQLState());
                // StAX reads lazily: the undeclared entity fails when the reader reaches it.
                assertThrows(javax.xml.stream.XMLStreamException.class, () -> {
                    XMLStreamReader r = read.getSource(StAXSource.class).getXMLStreamReader();
                    while (r.hasNext()) {
                        r.next();
                    }
                });
            }
        }
    }

    @Test
    void freedValuesAndUpdatableResultSets() throws Exception {
        try (Connection c = open()) {
            SQLXML x = c.createSQLXML();
            x.setString("<d/>");
            x.free();
            assertEquals("HY010", assertThrows(SQLException.class, x::getString).getSQLState());
            assertEquals("HY010", assertThrows(SQLException.class, () -> c.createSQLXML().getString()).getSQLState());
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO xml_it VALUES (1, '<old/>')");
            }
            try (Statement st = c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
                    ResultSet rs = st.executeQuery("SELECT id, body FROM xml_it")) {
                assertTrue(rs.next());
                SQLXML v = c.createSQLXML();
                v.setString("<new/>");
                rs.updateSQLXML("body", v);
                rs.updateRow();
                assertEquals("<new/>", rs.getSQLXML("body").getString());
            }
            assertEquals("<new/>", read(c, 1));
        }
    }
}
