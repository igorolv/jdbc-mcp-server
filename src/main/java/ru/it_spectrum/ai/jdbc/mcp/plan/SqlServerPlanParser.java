package ru.it_spectrum.ai.jdbc.mcp.plan;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.sql.SQLXML;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses SQL Server {@code SET SHOWPLAN_XML ON} output into the common plan model.
 *
 * <p>SQL Server estimated plans are XML documents with {@code StmtSimple} /
 * {@code QueryPlan} / {@code RelOp} elements. We keep a compact typed subset for plan
 * analysis and carry the original operator attributes in {@link PlanNode#raw()}.
 */
public final class SqlServerPlanParser implements PlanParser {

    @Override
    public ParsedPlan parse(QueryResult result, boolean analyzed) {
        List<PlanNode> statementNodes = new ArrayList<>();
        int xmlDocuments = 0;
        for (Map<String, Object> row : result.rows()) {
            for (Object value : row.values()) {
                String xml = asXmlString(value);
                if (xml == null || xml.isBlank()) continue;
                PlanNode statement = parseDocument(xml);
                if (statement != null) {
                    statementNodes.add(statement);
                    xmlDocuments++;
                }
            }
        }

        PlanNode root;
        if (statementNodes.isEmpty()) {
            root = null;
        } else if (statementNodes.size() == 1) {
            root = statementNodes.getFirst();
        } else {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("statement_count", statementNodes.size());
            root = new PlanNode("BATCH", null, null, null, null,
                    null, null, null, raw, statementNodes);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("xml_documents", xmlDocuments);
        meta.put("rows", result.rows().size());
        return new ParsedPlan("mssql", root, false, null, null, meta);
    }

    private PlanNode parseDocument(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            Document doc = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml)));
            Element statement = firstStatement(doc);
            if (statement == null) return null;
            return parseStatement(statement);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse SQL Server SHOWPLAN_XML: " + e.getMessage(), e);
        }
    }

    private PlanNode parseStatement(Element statement) {
        Map<String, Object> raw = attrs(statement);
        String type = attr(statement, "StatementType");
        if (type == null || type.isBlank()) type = localName(statement);
        Double cost = asDouble(attr(statement, "StatementSubTreeCost"));
        Long estRows = asLong(attr(statement, "StatementEstRows"));

        Element queryPlan = firstDirectChild(statement, "QueryPlan");
        List<PlanNode> children = new ArrayList<>();
        if (queryPlan != null) {
            for (Element relOp : childRelOps(queryPlan, null)) {
                children.add(parseRelOp(relOp));
            }
        }
        return new PlanNode(type, null, cost, null, estRows,
                null, null, null, raw, children);
    }

    private PlanNode parseRelOp(Element relOp) {
        Map<String, Object> raw = attrs(relOp);
        String physical = attr(relOp, "PhysicalOp");
        String logical = attr(relOp, "LogicalOp");
        String nodeType = physical != null && !physical.isBlank()
                ? physical
                : logical != null && !logical.isBlank() ? logical : "RelOp";

        Element object = firstDescendantBeforeNestedRelOp(relOp, "Object");
        String relation = relationName(object);
        if (relation != null) raw.put("object", relation);

        List<PlanNode> children = new ArrayList<>();
        for (Element child : childRelOps(relOp, relOp)) {
            children.add(parseRelOp(child));
        }

        return new PlanNode(
                nodeType,
                relation,
                asDouble(attr(relOp, "EstimatedTotalSubtreeCost")),
                asDouble(attr(relOp, "EstimateCPU")),
                asLong(attr(relOp, "EstimateRows")),
                null,
                null,
                null,
                raw,
                children);
    }

    private static Element firstStatement(Document doc) {
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (!(n instanceof Element e)) continue;
            String name = localName(e);
            if (name != null && name.startsWith("Stmt") && firstDirectChild(e, "QueryPlan") != null) {
                return e;
            }
        }
        return null;
    }

    private static List<Element> childRelOps(Element scope, Element parentRelOp) {
        List<Element> out = new ArrayList<>();
        NodeList all = scope.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (!(n instanceof Element e) || !"RelOp".equals(localName(e))) continue;
            Element nearest = nearestAncestorRelOp(e);
            if (nearest == parentRelOp) out.add(e);
        }
        return out;
    }

    private static Element nearestAncestorRelOp(Element element) {
        Node cur = element.getParentNode();
        while (cur != null) {
            if (cur instanceof Element e && "RelOp".equals(localName(e))) return e;
            cur = cur.getParentNode();
        }
        return null;
    }

    private static Element firstDirectChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element e && localName.equals(localName(e))) return e;
        }
        return null;
    }

    private static Element firstDescendantBeforeNestedRelOp(Element scope, String wantedLocalName) {
        NodeList all = scope.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (!(n instanceof Element e)) continue;
            if (nearestAncestorRelOp(e) != scope) continue;
            if (wantedLocalName.equals(localName(e))) return e;
        }
        return null;
    }

    private static String relationName(Element object) {
        if (object == null) return null;
        String table = stripBrackets(attr(object, "Table"));
        if (table == null || table.isBlank()) return null;
        String schema = stripBrackets(attr(object, "Schema"));
        return schema == null || schema.isBlank() ? table : schema + "." + table;
    }

    private static Map<String, Object> attrs(Element element) {
        Map<String, Object> raw = new LinkedHashMap<>();
        if (element == null || !element.hasAttributes()) return raw;
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Node attr = element.getAttributes().item(i);
            raw.put(attr.getNodeName(), attr.getNodeValue());
            raw.put(attr.getNodeName().toLowerCase(Locale.ROOT), attr.getNodeValue());
        }
        return raw;
    }

    private static String attr(Element element, String name) {
        if (element == null) return null;
        if (element.hasAttribute(name)) return element.getAttribute(name);
        if (element.hasAttribute(name.toLowerCase(Locale.ROOT))) {
            return element.getAttribute(name.toLowerCase(Locale.ROOT));
        }
        return null;
    }

    private static String localName(Node node) {
        String local = node.getLocalName();
        if (local != null) return local;
        String name = node.getNodeName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static String stripBrackets(String value) {
        if (value == null) return null;
        String out = value.trim();
        if (out.startsWith("[") && out.endsWith("]") && out.length() >= 2) {
            out = out.substring(1, out.length() - 1);
        }
        return out;
    }

    private static String asXmlString(Object value) {
        try {
            if (value instanceof SQLXML xml) return xml.getString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot read SQLXML plan: " + e.getMessage(), e);
        }
        return value == null ? null : value.toString();
    }

    private static Double asDouble(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long asLong(String value) {
        Double d = asDouble(value);
        return d == null ? null : Math.round(d);
    }

    private static void trySetFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
            // Parser implementations vary; secure-processing remains enabled above.
        }
    }
}
