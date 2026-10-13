package com.mocktroid.ui.icon;

import java.io.InputStream;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

/**
 * Loads simple flat-color icon SVGs into a JavaFX {@link Group} of
 * {@link SVGPath} shapes, without pulling in a full SVG rendering dependency
 * like Batik (JavaFX has no built-in SVG document support).
 * <p>
 * Supported: {@code <svg>} containing one or more {@code <path d="..." fill="...">}
 * elements. Gradients, filters, clip-paths, embedded raster, and text are not
 * supported and are silently ignored -- keep icon source files to flat,
 * single- or multi-path vector shapes (this is exactly what a flat category
 * glyph needs, and it's the only thing SVGPath can render natively).
 */
public final class SvgIconLoader {

    private static final Logger LOG = Logger.getLogger(SvgIconLoader.class.getName());

    private SvgIconLoader() {}

    /**
     * Loads an icon from the classpath, e.g. {@code "/icons/category-texture.svg"}.
     * Never throws -- returns {@code null} if the resource is missing or
     * unparseable so callers can fall back to a placeholder glyph.
     *
     * @param fillOverride if non-null, every path is painted this color
     *                      (lets one icon file serve default/hover/selected
     *                      states via CSS-driven tint rather than needing
     *                      separate art per state); if null, each path's own
     *                      {@code fill} attribute is used (white if absent).
     */
    public static Node loadOrNull(String classpathResource, Color fillOverride) {
        URL url = SvgIconLoader.class.getResource(classpathResource);
        if (url == null) {
            return null;
        }
        try (InputStream in = url.openStream()) {
            var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
            NodeList paths = doc.getElementsByTagName("path");
            Group group = new Group();
            for (int i = 0; i < paths.getLength(); i++) {
                Element el = (Element) paths.item(i);
                String d = el.getAttribute("d");
                if (d == null || d.isBlank()) {
                    continue;
                }
                SVGPath shape = new SVGPath();
                shape.setContent(d);
                if (fillOverride != null) {
                    shape.setFill(fillOverride);
                } else {
                    String fillAttr = el.getAttribute("fill");
                    shape.setFill(fillAttr != null && !fillAttr.isBlank() ? Color.web(fillAttr) : Color.WHITE);
                }
                group.getChildren().add(shape);
            }
            return group.getChildren().isEmpty() ? null : group;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to parse icon SVG " + classpathResource, e);
            return null;
        }
    }
}
