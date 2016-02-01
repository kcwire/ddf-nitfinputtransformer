/**
 * Copyright (c) Codice Foundation
 * <p>
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package net.frogmouth.ddf.nitfinputtransformer;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.codice.imaging.cgm.CgmParser;
import org.codice.imaging.cgm.CgmRenderer;
import org.codice.imaging.nitf.core.AllDataExtractionParseStrategy;
import org.codice.imaging.nitf.core.NitfFileHeader;
import org.codice.imaging.nitf.core.NitfFileParser;
import org.codice.imaging.nitf.core.SlottedNitfParseStrategy;
import org.codice.imaging.nitf.core.common.NitfDateTime;
import org.codice.imaging.nitf.core.common.NitfInputStreamReader;
import org.codice.imaging.nitf.core.graphic.NitfGraphicSegmentHeader;
import org.codice.imaging.nitf.core.image.ImageCoordinates;
import org.codice.imaging.nitf.core.image.ImageCoordinatesRepresentation;
import org.codice.imaging.nitf.core.image.NitfImageSegmentHeader;
import org.codice.imaging.nitf.core.label.LabelSegmentHeader;
import org.codice.imaging.nitf.core.security.FileSecurityMetadata;
import org.codice.imaging.nitf.core.security.SecurityMetadata;
import org.codice.imaging.nitf.core.symbol.SymbolSegmentHeader;
import org.codice.imaging.nitf.core.text.TextSegmentHeader;
import org.codice.imaging.nitf.core.tre.Tre;
import org.codice.imaging.nitf.core.tre.TreCollection;
import org.codice.imaging.nitf.core.tre.TreEntry;
import org.codice.imaging.nitf.core.tre.TreGroup;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LinearRing;
import com.vividsolutions.jts.geom.MultiPolygon;
import com.vividsolutions.jts.geom.Polygon;
import com.vividsolutions.jts.geom.PrecisionModel;

import ddf.catalog.CatalogFramework;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.impl.AttributeImpl;
import ddf.catalog.data.impl.MetacardImpl;
import ddf.catalog.transform.CatalogTransformerException;
import ddf.catalog.transform.InputTransformer;

/**
 * Converts NITF images into a Metacard.
 */
public class NitfInputTransformer implements InputTransformer {

    private static final String ID = "nitf";

    private static final String MIME_TYPE = "image/nitf";

    private static final Logger LOGGER = LoggerFactory.getLogger(NitfInputTransformer.class);

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormat.
            forPattern("yyyyMMddHHmmss").withZone(DateTimeZone.UTC);


    private CatalogFramework mCatalog;

    /**
     * Transforms NITF images into a {@link Metacard}
     */
    public Metacard transform(InputStream input) throws IOException, CatalogTransformerException {
        return transform(input, null);
    }

    public Metacard transform(InputStream input, String id)
            throws IOException, CatalogTransformerException {
        if (input == null) {
            throw new CatalogTransformerException("Cannot transform null input.");
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        IOUtils.copy(input, baos);

        MetacardImpl metacard = new MetacardImpl(new NitfMetacardType());
        try {
            SlottedNitfParseStrategy parsingStrategy = new AllDataExtractionParseStrategy();

            NitfFileParser.parse(
                    new NitfInputStreamReader(new ByteArrayInputStream(baos.toByteArray())),
                    parsingStrategy);

            metacard.setCreatedDate(getDateTime(parsingStrategy.getNitfHeader()));
            // TODO: modified date from HISTOA?
            metacard.setTitle(parsingStrategy.getNitfHeader().getFileTitle());

            setAttributes(parsingStrategy, metacard);

            setLocation(parsingStrategy, metacard);

            setMetadata(parsingStrategy, metacard);

            if (id != null) {
                metacard.setId(id);
            } else {
                metacard.setId(null);
            }

            metacard.setContentTypeName(MIME_TYPE);

            byte[] thumbnail = getThumbnail(parsingStrategy);

            if (thumbnail != null && thumbnail.length > 0) {
                metacard.setThumbnail(thumbnail);
            }
        } catch (ParseException e) {
            LOGGER.warn("ParseException processing NITF file", e);
            throw new CatalogTransformerException(e);
        }

        return metacard;
    }

    protected Date getDateTime(NitfFileHeader fileHeader) {

        return DATE_TIME_FORMATTER.parseDateTime(fileHeader.getFileDateTime()
                .getSourceString()).toDate();
    }

    protected byte[] getThumbnail(SlottedNitfParseStrategy slottedNitf) {

        if (slottedNitf.getGraphicSegmentHeaders().isEmpty()) {
            LOGGER.debug("Loaded file, but found no graphic segments.");
            return null;
        }
        try {
            NitfGraphicSegmentHeader segment = slottedNitf.getGraphicSegmentHeaders().get(0);
            CgmParser parser = new CgmParser(slottedNitf.getGraphicSegmentData().get(0));
            parser.buildCommandList();

            if (segment.getBoundingBox2Column() > 0 && segment.getBoundingBox2Row() > 0) {
                BufferedImage targetImage = new BufferedImage(segment.getBoundingBox2Column(),
                        segment.getBoundingBox2Row(), BufferedImage.TYPE_INT_ARGB);
                CgmRenderer renderer = new CgmRenderer();
                renderer.setTargetImageGraphics((Graphics2D) targetImage.getGraphics(),
                        segment.getBoundingBox2Column(), segment.getBoundingBox2Row());
                renderer.render(parser.getCommandList());
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(targetImage, "jpg", baos);
                return baos.toByteArray();
            } else {
                LOGGER.debug("No image to generate");
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to parse image from nitf", e);
        }
        return null;
    }

    private void setAttributes(SlottedNitfParseStrategy slottedNitf, MetacardImpl metacard) {

        NitfFileHeader fileHeader = slottedNitf.getNitfHeader();

        // TODO: The Attributes should be obtained from the Nitf library more elegantly.  There's
        // no null checking and it requires explicit object knowledge to obtain an attribute.
        metacard.setAttribute(
                new AttributeImpl(NitfMetacardType.NITF_VERSION, fileHeader.getFileType()));
        if (fileHeader.getFileDateTime() != null) {
            metacard.setAttribute(new AttributeImpl(NitfMetacardType.FILE_DATE_TIME,
                    getDateTime(fileHeader)));
            metacard.setModifiedDate(getDateTime(fileHeader));
        } else {
            Date now = new Date();
            metacard.setModifiedDate(now);
            metacard.setCreatedDate(now);
            metacard.setEffectiveDate(now);
        }

        metacard.setAttribute(
                new AttributeImpl(NitfMetacardType.FILE_TITLE, fileHeader.getFileTitle()));
        metacard.setTitle(fileHeader.getFileTitle());
        //        metacard.setAttribute(new AttributeImpl(NitfMetacardType.FILE_SIZE,
        //                nitfFile.));
        metacard.setAttribute(new AttributeImpl(NitfMetacardType.COMPLEXITY_LEVEL,
                fileHeader.getComplexityLevel()));
        metacard.setAttribute(
                new AttributeImpl(NitfMetacardType.ORIGINATOR_NAME, fileHeader.getOriginatorsName()));
        metacard.setAttribute(new AttributeImpl(NitfMetacardType.ORIGINATING_STATION_ID,
                fileHeader.getOriginatingStationId()));
        if (!slottedNitf.getImageSegmentHeaders().isEmpty()) {
            metacard.setAttribute(new AttributeImpl(NitfMetacardType.IMAGE_ID,
                    slottedNitf.getImageSegmentHeaders().get(0).getImageIdentifier2()));
            metacard.setAttribute(new AttributeImpl(NitfMetacardType.ISOURCE,
                    slottedNitf.getImageSegmentHeaders().get(0).getImageSource()));
            metacard.setAttribute(new AttributeImpl(NitfMetacardType.NUMBER_OF_ROWS,
                    slottedNitf.getImageSegmentHeaders().get(0).getNumberOfRows()));
            metacard.setAttribute(new AttributeImpl(NitfMetacardType.NUMBER_OF_COLUMNS,
                    slottedNitf.getImageSegmentHeaders().get(0).getNumberOfColumns()));
            metacard.setAttribute(new AttributeImpl(NitfMetacardType.NUMBER_OF_BANDS,
                    slottedNitf.getImageSegmentHeaders().get(0).getNumBands()));
            //        metacard.setAttribute(new AttributeImpl(NitfMetacardType.NUMBER_OF_MULTISPECTRAL_BANDS,
            //                nitfFile.getImageSegments().get(0).getNum()));
            metacard.setAttribute(new AttributeImpl(NitfMetacardType.REPRESENTATION,
                    slottedNitf.getImageSegmentHeaders().get(0).getImageRepresentation()));
            metacard.setAttribute(new AttributeImpl(NitfMetacardType.SUBCATEGORY,
                    slottedNitf.getImageSegmentHeaders().get(0).getImageCategory()));
            metacard.setAttribute(new AttributeImpl(NitfMetacardType.BITS_PER_PIXEL_PER_BAND,
                    slottedNitf.getImageSegmentHeaders().get(0).getNumberOfBitsPerPixelPerBand()));
            metacard.setAttribute(new AttributeImpl(NitfMetacardType.IMAGE_MODE,
                    slottedNitf.getImageSegmentHeaders().get(0).getImageMode()));
            metacard.setAttribute(new AttributeImpl(NitfMetacardType.COMPRESSION,
                    slottedNitf.getImageSegmentHeaders().get(0).getImageCompression()));
            metacard.setAttribute(new AttributeImpl(NitfMetacardType.RATE_CODE,
                    slottedNitf.getImageSegmentHeaders().get(0).getCompressionRate()));
            metacard.setAttribute(new AttributeImpl(NitfMetacardType.TARGET_ID,
                    slottedNitf.getImageSegmentHeaders().get(0).getImageTargetId().toString()));
            metacard.setAttribute(new AttributeImpl(NitfMetacardType.COMMENT, Arrays.toString(
                    slottedNitf.getImageSegmentHeaders().get(0).getImageComments().toArray())));
        }
        metacard.setAttribute(new AttributeImpl(NitfMetacardType.CODE_WORDS,
                fileHeader.getFileSecurityMetadata().getCodewords()));
        metacard.setAttribute(new AttributeImpl(NitfMetacardType.CONTROL_CODE,
                fileHeader.getFileSecurityMetadata().getControlAndHandling()));
        metacard.setAttribute(new AttributeImpl(NitfMetacardType.RELEASE_INSTRUCTION,
                fileHeader.getFileSecurityMetadata().getReleaseInstructions()));
        metacard.setAttribute(new AttributeImpl(NitfMetacardType.CONTROL_NUMBER,
                fileHeader.getFileSecurityMetadata().getSecurityControlNumber()));
        metacard.setAttribute(new AttributeImpl(NitfMetacardType.CLASSIFICATION_SYSTEM,
                fileHeader.getFileSecurityMetadata().getSecurityClassificationSystem()));
        metacard.setAttribute(new AttributeImpl(NitfMetacardType.CLASSIFICATION_AUTHORITY,
                fileHeader.getFileSecurityMetadata().getClassificationAuthority()));
        metacard.setAttribute(new AttributeImpl(NitfMetacardType.CLASSIFICATION_AUTHORITY_TYPE,
                fileHeader.getFileSecurityMetadata().getClassificationAuthorityType()));
        metacard.setAttribute(new AttributeImpl(NitfMetacardType.CLASSIFICATION_TEXT,
                fileHeader.getFileSecurityMetadata().getSecurityClassificationSystem()));
        metacard.setAttribute(new AttributeImpl(NitfMetacardType.CLASSIFICATION_REASON,
                fileHeader.getFileSecurityMetadata().getClassificationReason()));
        if (StringUtils.isNotEmpty(fileHeader.getFileSecurityMetadata().getSecuritySourceDate())) {
            // TODO convert to Date
            metacard.setAttribute(new AttributeImpl(NitfMetacardType.CLASSIFICATION_DATE,
                    fileHeader.getFileSecurityMetadata().getSecuritySourceDate()));
        }
        metacard.setAttribute(new AttributeImpl(NitfMetacardType.DECLASSIFICATION_TYPE,
                fileHeader.getFileSecurityMetadata().getDeclassificationType()));
        metacard.setAttribute(new AttributeImpl(NitfMetacardType.DECLASSIFICATION_DATE,
                fileHeader.getFileSecurityMetadata().getDeclassificationDate()));
        // TODO: add the TRE's as attributes to the MetacardType Dynamically?
    }

    private void setLocation(SlottedNitfParseStrategy slottedNitf, MetacardImpl metacard) {

        GeometryFactory geomFactory = new GeometryFactory(
                new PrecisionModel(com.vividsolutions.jts.geom.PrecisionModel.FLOATING), 4326);
        if (slottedNitf.getImageSegmentHeaders().isEmpty()) {
            return;
        }
        if (slottedNitf.getImageSegmentHeaders().size() == 1) {
            NitfImageSegmentHeader segment = slottedNitf.getImageSegmentHeaders().get(0);
            if (segment == null) {
                return;
            }
            // TODO: add more coordinate support
            // TODO: handle case where its really a point.
            if ((segment.getImageCoordinatesRepresentation()
                    == ImageCoordinatesRepresentation.GEOGRAPHIC) || (
                    segment.getImageCoordinatesRepresentation()
                            == ImageCoordinatesRepresentation.DECIMALDEGREES)) {
                Polygon polygon = getPolygonForSegment(segment, geomFactory);
                metacard.setLocation(polygon.toText());
            } else if (segment.getImageCoordinatesRepresentation()
                    != ImageCoordinatesRepresentation.NONE) {
                System.out.println("Unsupported representation:" + segment
                        .getImageCoordinatesRepresentation());
            }
        } else {
            List<Polygon> polygons = new ArrayList<Polygon>();
            for (NitfImageSegmentHeader segment : slottedNitf.getImageSegmentHeaders()) {
                if ((segment.getImageCoordinatesRepresentation()
                        == ImageCoordinatesRepresentation.GEOGRAPHIC) || (
                        segment.getImageCoordinatesRepresentation()
                                == ImageCoordinatesRepresentation.DECIMALDEGREES)) {
                    polygons.add(getPolygonForSegment(segment, geomFactory));
                } else if (segment.getImageCoordinatesRepresentation()
                        != ImageCoordinatesRepresentation.NONE) {
                    System.out.println("Unsupported representation:" + segment
                            .getImageCoordinatesRepresentation());
                }
            }
            Polygon[] polyAry = polygons.toArray(new Polygon[0]);
            MultiPolygon multiPolygon = geomFactory.createMultiPolygon(polyAry);
            metacard.setLocation(multiPolygon.toText());
        }
    }

    private Polygon getPolygonForSegment(NitfImageSegmentHeader segment, GeometryFactory geomFactory) {
        Coordinate[] coords = new Coordinate[5];
        ImageCoordinates imageCoordinates = segment.getImageCoordinates();
        coords[0] = new Coordinate(imageCoordinates.getCoordinate00().getLongitude(),
                imageCoordinates.getCoordinate00().getLatitude());
        coords[4] = new Coordinate(coords[0]);
        coords[1] = new Coordinate(imageCoordinates.getCoordinate0MaxCol().getLongitude(),
                imageCoordinates.getCoordinate0MaxCol().getLatitude());
        coords[2] = new Coordinate(imageCoordinates.getCoordinateMaxRowMaxCol().getLongitude(),
                imageCoordinates.getCoordinateMaxRowMaxCol().getLatitude());
        coords[3] = new Coordinate(imageCoordinates.getCoordinateMaxRow0().getLongitude(),
                imageCoordinates.getCoordinateMaxRow0().getLatitude());
        LinearRing externalRing = geomFactory.createLinearRing(coords);
        return geomFactory.createPolygon(externalRing, null);
    }

    private void setMetadata(SlottedNitfParseStrategy slottedNitf, MetacardImpl metacard) {

        NitfFileHeader fileHeader = slottedNitf.getNitfHeader();

        // TODO: update to XStream or some xml streaming library to create this metadata
        StringBuilder metadataXml = new StringBuilder();
        metadataXml.append("<metadata>\n");
        metadataXml.append("  <file>\n");
        metadataXml.append(buildMetadataEntry("fileType", fileHeader.getFileType().toString()));
        metadataXml.append(buildMetadataEntry("complexityLevel", fileHeader.getComplexityLevel()));
        metadataXml.append(buildMetadataEntry("originatingStationId",
                fileHeader.getOriginatingStationId()));
        metadataXml.append(buildMetadataEntry("fileDateTime", fileHeader.getFileDateTime()));
        metadataXml.append(buildMetadataEntry("fileTitle", fileHeader.getFileTitle()));
        addFileSecurityMetadata(metadataXml, fileHeader);
        if (fileHeader.getFileBackgroundColour() != null) {
            metadataXml.append(buildMetadataEntry("fileBackgroundColour",
                    fileHeader.getFileBackgroundColour().toString()));
        }
        metadataXml.append(buildMetadataEntry("originatorsName", fileHeader.getOriginatorsName()));
        metadataXml.append(buildMetadataEntry("originatorsPhoneNumber",
                fileHeader.getOriginatorsPhoneNumber()));
        metadataXml.append(buildTREsMetadata(fileHeader.getTREsRawStructure()));
        metadataXml.append("  </file>\n");
        for (NitfImageSegmentHeader image : slottedNitf.getImageSegmentHeaders()) {
            metadataXml.append("  <image>\n");
            metadataXml.append(buildMetadataEntry("imageIdentifer1", image.getIdentifier()));
            metadataXml.append(buildMetadataEntry("imageDateTime", image.getImageDateTime()));
            metadataXml.append(buildMetadataEntry("imageBasicEncyclopediaNumber",
                    image.getImageTargetId().getBasicEncyclopediaNumber().trim()));
            metadataXml.append(buildMetadataEntry("imageOSuffix",
                    image.getImageTargetId().getOSuffix().trim()));
            metadataXml.append(buildMetadataEntry("imageCountryCode",
                    image.getImageTargetId().getCountryCode().trim()));
            metadataXml.append(buildMetadataEntry("imageIdentifer2", image.getImageIdentifier2()));
            addSecurityMetadata(metadataXml, image.getSecurityMetadata());
            metadataXml.append(buildMetadataEntry("imageSource", image.getImageSource()));
            metadataXml.append(buildMetadataEntry("numberOfRows", image.getNumberOfRows()));
            metadataXml.append(buildMetadataEntry("numberOfColumns", image.getNumberOfColumns()));
            metadataXml.append(buildMetadataEntry("pixelValueType",
                    image.getPixelValueType().toString()));
            metadataXml.append(buildMetadataEntry("imageRepresentation",
                    image.getImageRepresentation().toString()));
            metadataXml.append(buildMetadataEntry("imageCategory",
                    image.getImageCategory().toString()));
            metadataXml.append(buildMetadataEntry("actualBitsPerPixelPerBand",
                    image.getActualBitsPerPixelPerBand()));
            metadataXml.append(buildMetadataEntry("pixelJustification",
                    image.getPixelJustification().toString()));
            metadataXml.append(buildMetadataEntry("imageCoordinatesRepresentation",
                    image.getImageCoordinatesRepresentation().toString()));
            for (String comment : image.getImageComments()) {
                metadataXml.append(buildMetadataEntry("imageComment", comment));
            }
            metadataXml.append(buildMetadataEntry("imageCompression",
                    image.getImageCompression().toString()));
            metadataXml.append(buildMetadataEntry("compressionRate", image.getCompressionRate()));
            metadataXml.append(buildMetadataEntry("imageMode", image.getImageMode().toString()));
            metadataXml.append(buildMetadataEntry("numberOfBlocksPerRow",
                    image.getNumberOfBlocksPerRow()));
            metadataXml.append(buildMetadataEntry("numberOfBlocksPerColumn",
                    image.getNumberOfBlocksPerColumn()));
            metadataXml.append(buildMetadataEntry("numberOfPixelsPerBlockHorizontal",
                    image.getNumberOfPixelsPerBlockHorizontal()));
            metadataXml.append(buildMetadataEntry("numberOfPixelsPerBlockVertical",
                    image.getNumberOfPixelsPerBlockVertical()));
            metadataXml.append(buildMetadataEntry("numberOfBitsPerPixelPerBand",
                    image.getNumberOfBitsPerPixelPerBand()));
            metadataXml
                    .append(buildMetadataEntry("imageDisplayLevel", image.getImageDisplayLevel()));
            metadataXml
                    .append(buildMetadataEntry("imageAttachmentLevel", image.getAttachmentLevel()));
            metadataXml.append(buildMetadataEntry("imageLocationRow", image.getImageLocationRow()));
            metadataXml.append(buildMetadataEntry("imageLocationColumn",
                    image.getImageLocationColumn()));
            if (image.getImageMagnification() != null) {
                metadataXml.append(buildMetadataEntry("imageMagnification",
                        image.getImageMagnification()));
            }
            if (image.getImageCoordinates() != null) {
                metadataXml.append(buildMetadataEntry("imageCoordinates",
                        image.getImageCoordinates().toString()));
            }
            metadataXml.append(buildTREsMetadata(image.getTREsRawStructure()));
            metadataXml.append("  </image>\n");
        }
        for (NitfGraphicSegmentHeader graphic : slottedNitf.getGraphicSegmentHeaders()) {
            metadataXml.append("  <graphic>\n");
            metadataXml.append(buildMetadataEntry("graphicIdentifier", graphic.getIdentifier()));
            metadataXml.append(buildMetadataEntry("graphicName", graphic.getGraphicName()));
            addSecurityMetadata(metadataXml, graphic.getSecurityMetadata());
            metadataXml.append(buildMetadataEntry("graphicDisplayLevel",
                    graphic.getGraphicDisplayLevel()));
            metadataXml.append(buildMetadataEntry("graphicAttachmentLevel",
                    graphic.getAttachmentLevel()));
            metadataXml.append(buildMetadataEntry("graphicLocationRow",
                    graphic.getGraphicLocationRow()));
            metadataXml.append(buildMetadataEntry("graphicLocationColumn",
                    graphic.getGraphicLocationColumn()));
            metadataXml.append(buildMetadataEntry("graphicBoundingBox1Row",
                    graphic.getBoundingBox1Row()));
            metadataXml.append(buildMetadataEntry("graphicBoundingBox1Column",
                    graphic.getBoundingBox1Column()));
            metadataXml.append(buildMetadataEntry("graphicBoundingBox2Row",
                    graphic.getBoundingBox2Row()));
            metadataXml.append(buildMetadataEntry("graphicBoundingBox2Column",
                    graphic.getBoundingBox2Column()));
            metadataXml.append(buildMetadataEntry("graphicColour",
                    graphic.getGraphicColour().toString()));
            metadataXml.append(buildTREsMetadata(graphic.getTREsRawStructure()));
            metadataXml.append("  </graphic>\n");
        }
        for (SymbolSegmentHeader symbol : slottedNitf.getSymbolSegmentHeaders()) {
            metadataXml.append("  <symbol>\n");
            metadataXml.append(buildMetadataEntry("symbolIdentifier", symbol.getIdentifier()));
            metadataXml.append(buildMetadataEntry("symbolName", symbol.getSymbolName()));
            addSecurityMetadata(metadataXml, symbol.getSecurityMetadata());
            metadataXml.append(buildMetadataEntry("symbolType", symbol.getSymbolType().toString()));
            metadataXml.append(buildMetadataEntry("symbolColour",
                    symbol.getSymbolColour().toString()));
            metadataXml.append(buildMetadataEntry("numberOfLinesPerSymbol",
                    symbol.getNumberOfLinesPerSymbol()));
            metadataXml.append(buildMetadataEntry("numberOfPixelsPerLine",
                    symbol.getNumberOfPixelsPerLine()));
            metadataXml.append(buildMetadataEntry("lineWidth", symbol.getLineWidth()));
            metadataXml.append(buildMetadataEntry("numberOfBitsPerPixel",
                    symbol.getNumberOfBitsPerPixel()));
            metadataXml.append(buildMetadataEntry("symbolDisplayLevel",
                    symbol.getSymbolDisplayLevel()));
            metadataXml.append(buildMetadataEntry("symbolAttachmentLevel",
                    symbol.getAttachmentLevel()));
            metadataXml
                    .append(buildMetadataEntry("symbolLocationRow", symbol.getSymbolLocationRow()));
            metadataXml.append(buildMetadataEntry("symbolLocationColumn",
                    symbol.getSymbolLocationColumn()));
            metadataXml.append(buildMetadataEntry("symbolLocation2Row",
                    symbol.getSymbolLocation2Row()));
            metadataXml.append(buildMetadataEntry("symbolLocation2Column",
                    symbol.getSymbolLocation2Column()));
            metadataXml.append(buildMetadataEntry("symbolNumber", symbol.getSymbolNumber()));
            metadataXml.append(buildMetadataEntry("symbolRotation", symbol.getSymbolRotation()));
            metadataXml.append(buildTREsMetadata(symbol.getTREsRawStructure()));
            metadataXml.append("  </symbol>\n");
        }
        for (LabelSegmentHeader label : slottedNitf.getLabelSegmentHeaders()) {
            metadataXml.append("  <label>\n");
            metadataXml.append(buildMetadataEntry("labelIdentifier", label.getIdentifier()));
            addSecurityMetadata(metadataXml, label.getSecurityMetadata());
            metadataXml.append(buildMetadataEntry("labelLocationRow", label.getLabelLocationRow()));
            metadataXml.append(buildMetadataEntry("labelLocationColumn",
                    label.getLabelLocationColumn()));
            metadataXml.append(buildMetadataEntry("labelCellWidth", label.getLabelCellWidth()));
            metadataXml.append(buildMetadataEntry("labelCellHeight", label.getLabelCellHeight()));
            metadataXml
                    .append(buildMetadataEntry("labelDisplayLevel", label.getLabelDisplayLevel()));
            metadataXml
                    .append(buildMetadataEntry("labelAttachmentLevel", label.getAttachmentLevel()));
            metadataXml.append(buildMetadataEntry("labelTextColour",
                    label.getLabelTextColour().toString()));
            metadataXml.append(buildMetadataEntry("labelBackgroundColour",
                    label.getLabelBackgroundColour().toString()));
            metadataXml.append(buildTREsMetadata(label.getTREsRawStructure()));
            metadataXml.append("  </label>\n");
        }
        for (TextSegmentHeader text : slottedNitf.getTextSegmentHeaders()) {
            metadataXml.append("  <text>\n");
            metadataXml.append(buildMetadataEntry("textIdentifier", text.getIdentifier()));
            addSecurityMetadata(metadataXml, text.getSecurityMetadata());
            metadataXml
                    .append(buildMetadataEntry("textDateTime", text.getTextDateTime().toString()));
            metadataXml.append(buildMetadataEntry("textTitle", text.getTextTitle()));
            metadataXml.append(buildMetadataEntry("textFormat", text.getTextFormat().toString()));
            metadataXml.append(buildTREsMetadata(text.getTREsRawStructure()));
            metadataXml.append("  </text>\n");
        }
        metadataXml.append("</metadata>\n");
        metacard.setMetadata(metadataXml.toString());
    }

    private String buildTREsMetadata(TreCollection treCollection) {
        StringBuilder treXml = new StringBuilder();
        for (Tre tre : treCollection.getTREs()) {
            outputThisTre(treXml, tre);
        }
        return treXml.toString();
    }

    private static void outputThisTre(StringBuilder treXml, Tre tre) {
        treXml.append("    <tre name=\"" + tre.getName().trim() + "\">\n");
        for (TreEntry entry : tre.getEntries()) {
            outputThisEntry(treXml, entry, 2);
        }
        treXml.append("    </tre>\n");
    }

    private static void doIndent(StringBuilder treXml, int indentLevel) {
        for (int i = 0; i < indentLevel; ++i) {
            treXml.append("  ");
        }
    }

    private static void outputThisEntry(StringBuilder treXml, TreEntry entry, int indentLevel) {
        if (entry.getFieldValue() != null) {
            doIndent(treXml, indentLevel);
            treXml.append("<field name=\"" + entry.getName() + "\" value=\"" + entry.getFieldValue()
                    + "\" />\n");
        }
        if ((entry.getGroups() != null) && (!entry.getGroups().isEmpty())) {
            doIndent(treXml, indentLevel);
            treXml.append("<repeated name=\"" + entry.getName() + "\" number=\"" + entry.getGroups()
                    .size() + "\">\n");
            int i = 0;
            for (TreGroup group : entry.getGroups()) {
                doIndent(treXml, indentLevel + 1);
                treXml.append(String.format("<group index=\"%d\">\n", i));
                for (TreEntry groupEntry : group.getEntries()) {
                    outputThisEntry(treXml, groupEntry, indentLevel + 2);
                }
                doIndent(treXml, indentLevel + 1);
                treXml.append(String.format("</group>\n"));
                i = i + 1;
            }
            doIndent(treXml, indentLevel);
            treXml.append("</repeated>\n");
        }
    }

    private String buildMetadataEntry(String label, int value) {
        return buildMetadataEntry(label, Integer.toString(value));
    }

    private String buildMetadataEntry(String label, long value) {
        return buildMetadataEntry(label, Long.toString(value));
    }

    private String buildMetadataEntry(String label, NitfDateTime value) {
        return buildMetadataEntry(label, value.getSourceString());
    }

    private String buildMetadataEntry(String label, String value) {
        StringBuilder entryBuilder = new StringBuilder();
        entryBuilder.append("    <");
        entryBuilder.append(label);
        entryBuilder.append(">");
        entryBuilder.append(StringEscapeUtils.escapeXml(value));
        entryBuilder.append("</");
        entryBuilder.append(label);
        entryBuilder.append(">\n");
        return entryBuilder.toString();
    }

    private void addFileSecurityMetadata(StringBuilder metadataXml, NitfFileHeader nitfFile) {
        FileSecurityMetadata security = nitfFile.getFileSecurityMetadata();
        addSecurityMetadata(metadataXml, security);
        metadataXml.append(buildMetadataEntry("securityFileCopyNumber",
                nitfFile.getFileSecurityMetadata().getFileCopyNumber()));
        metadataXml.append(buildMetadataEntry("securityFileNumberOfCopies",
                nitfFile.getFileSecurityMetadata().getFileNumberOfCopies()));
    }

    private void addSecurityMetadata(StringBuilder metadataXml, SecurityMetadata security) {
        metadataXml.append(buildMetadataEntry("securityClassification",
                security.getSecurityClassification().toString()));
        addMetadataIfNotNull(metadataXml, "securityClassificationSystem",
                security.getSecurityClassificationSystem());
        metadataXml.append(buildMetadataEntry("securityCodewords", security.getCodewords()));
        addMetadataIfNotNull(metadataXml, "securityControlAndHandling",
                security.getControlAndHandling());
        addMetadataIfNotNull(metadataXml, "securityReleaseInstructions",
                security.getReleaseInstructions());
        addMetadataIfNotNull(metadataXml, "securityDeclassificationType",
                security.getDeclassificationType());
        addMetadataIfNotNull(metadataXml, "securityDeclassificationDate",
                security.getDeclassificationDate());
        addMetadataIfNotNull(metadataXml, "securityDeclassificationExemption",
                security.getDeclassificationExemption());
        addMetadataIfNotNull(metadataXml, "securityDowngrade", security.getDowngrade());
        addMetadataIfNotNull(metadataXml, "securityDowngradeDate", security.getDowngradeDate());
        addMetadataIfNotNull(metadataXml, "securityDowngradeDateOrSpecialCase",
                security.getDowngradeDateOrSpecialCase());
        addMetadataIfNotNull(metadataXml, "securityDowngradeEvent", security.getDowngradeEvent());
    }

    private void addMetadataIfNotNull(StringBuilder metadataXml, String label, String value) {
        if (value != null) {
            metadataXml.append(buildMetadataEntry(label, value));
        }
    }

    @Override
    public String toString() {
        return "InputTransformer {Impl=" + this.getClass().getName() + ", id=" + ID + ", mime-type="
                + MIME_TYPE + "}";
    }

    public void setCatalog(CatalogFramework catalog) {
        LOGGER.info("NitfInputTransformer setCatalog()");
        this.mCatalog = catalog;
    }

}
