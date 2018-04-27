/*
 * Copyright 2018 Esri, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.esri.geoportal.commons.pdf;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import javax.xml.transform.TransformerException;

import com.esri.core.geometry.MultiPoint;
import com.esri.core.geometry.Point;
import com.esri.core.geometry.Point2D;
import com.esri.geoportal.commons.meta.AttributeUtils;
import com.esri.geoportal.commons.meta.MapAttribute;
import com.esri.geoportal.commons.meta.MetaException;
import com.esri.geoportal.commons.meta.util.WKAConstants;
import com.esri.geoportal.commons.meta.xml.SimpleDcMetaBuilder;
import com.esri.geoportal.commons.utils.XmlUtils;
import com.esri.geoportal.geoportal.commons.geometry.GeometryService;

import org.apache.commons.text.StringSubstitutor;
import org.apache.http.impl.client.HttpClients;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.COSDictionaryMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

/**
 * Utilities for reading PDF file metadata
 */
public class PdfUtils {

	private static final int WGS84_WKID = 4326;

	private static final Logger LOG = LoggerFactory.getLogger(PdfUtils.class);

    // Metadata properties read from PDF file
    public static final String PROP_TITLE = "title";
    public static final String PROP_SUBJECT = "description";
    public static final String PROP_MODIFICATION_DATE = "modification_date";
    public static final String PROP_BBOX = "bounding_box";

    public static byte[] generateMetadataXML(byte[] pdfBytes, String fileName, String url) throws IOException {
        byte[] bytes = null;
        Properties metaProps = readMetadata(pdfBytes, fileName);

        if (metaProps != null) {
            Properties props = new Properties();
            props.put(WKAConstants.WKA_TITLE, metaProps.get(PdfUtils.PROP_TITLE));
            props.put(WKAConstants.WKA_DESCRIPTION, metaProps.get(PdfUtils.PROP_SUBJECT));
            props.put(WKAConstants.WKA_MODIFIED, metaProps.get(PdfUtils.PROP_MODIFICATION_DATE));
            props.put(WKAConstants.WKA_BBOX, metaProps.getOrDefault(PdfUtils.PROP_BBOX, "0 0, 0 0"));
            props.put(WKAConstants.WKA_RESOURCE_URL, url);

            try {
                MapAttribute attr = AttributeUtils.fromProperties(props);
                Document document = new SimpleDcMetaBuilder().create(attr);
                bytes = XmlUtils.toString(document).getBytes("UTF-8");
            } catch (MetaException | TransformerException ex) {
                throw new IOException(ex);
            }
        }

        return bytes;
    }

    /**
     * Reads metadata values from a PDF file.
     * 
     * @param rawBytes the PDF to read
     * @return metadata properties or null if the PDF cannot be read.
     * @throws IOException on parsing error
     */
    public static Properties readMetadata(byte[] rawBytes, String defaultTitle) throws IOException {
        Properties ret = new Properties();

        // Attempt to read in the PDF file
        try (PDDocument document = PDDocument.load(rawBytes)) {

            // See if we can read the PDF
            if (!document.isEncrypted()) {
                // Get document metadata
                PDDocumentInformation info = document.getDocumentInformation();

                if (info != null) {

                    if (info.getTitle() != null) {
                        ret.put(PROP_TITLE, info.getTitle());
                    } else {
                        ret.put(PROP_TITLE, defaultTitle);
                    }

                    if (info.getSubject() != null) {
                        ret.put(PROP_SUBJECT, info.getSubject());
                    } else {
                        
                        StringBuilder psudoSubject = new StringBuilder("");
                        psudoSubject.append("\nAuthor: " + info.getAuthor());
                        psudoSubject.append("\nCreator: " + info.getCreator());
                        psudoSubject.append("\nProducer: " + info.getProducer());

                        ret.put(PROP_SUBJECT, psudoSubject.toString());
                    }

                    if (info.getModificationDate() != null) {
                        ret.put(PROP_MODIFICATION_DATE, info.getModificationDate().getTime());
                    } else {
                        ret.put(PROP_MODIFICATION_DATE, info.getCreationDate().getTime());
                    }
                } else {
                    LOG.warn("Got null metadata for PDF file");
                    return null;
                }
                
                COSObject measure = document.getDocument().getObjectByType(COSName.getPDFName("Measure"));
                if (measure != null) {
                    System.out.println("Found Measure element");
                    COSDictionary dictionary = (COSDictionary) measure.getObject();

                    float[] coords = ((COSArray) dictionary.getItem("GPTS")).toFloatArray();
                    
                    // Calculate the bounding box...
                    Float xMin = Float.MAX_VALUE;
                    Float yMin = Float.MAX_VALUE;
                    Float xMax = -Float.MAX_VALUE;
                    Float yMax = -Float.MAX_VALUE;

                    for (int i = 0; i < coords.length; i += 2) {
                        float y = coords[i];
                        float x = coords[i+1];

                        if (x > xMax) {
                            xMax = x;
                        }
                        if (x < xMin) {
                            xMin = x;
                        }

                        if (y > yMax) {
                            yMax = y;
                        }
                        if (y < yMin) {
                            yMin = y;
                        }
                    }
                    String bbox = generateBbox(xMin, yMin, xMax, yMax);
                    System.out.println("Coords: " + bbox);

                    ret.put(PROP_BBOX,  bbox);

                } else {
                    PDPage page = document.getPage(0);
                    if (page.getCOSObject().containsKey(COSName.getPDFName("LGIDict"))) {
                        String bbox = extractGeoPDFProps(page);
                        if (bbox != null) {
                            ret.put(PROP_BBOX, bbox);
                        }
                    }
                }

                
            } else {
                LOG.warn("Cannot read encrypted PDF file");
                return null;
            }

        } catch (IOException ex) {
            LOG.error("Exception reading PDF", ex);
            throw ex;
        }

        return ret;
    }

	private static String extractGeoPDFProps(PDPage page) {
        LOG.info("Found LGI dictionary");
        
        COSArray lgi = (COSArray) page.getCOSObject().getDictionaryObject("LGIDict");

        List<String> bBoxes = new ArrayList<>();

        lgi.iterator().forEachRemaining(item -> {
            try {

                String currentBbox = null;

                // Set up the Coordinate Transformation Matrix
                Double [][] ctmValues = null;

                COSDictionary dictionary = (COSDictionary) item;
                if (dictionary.containsKey("CTM")) {
                    System.out.println("\tCTM");
                    ctmValues = new Double[3][3];
                    ctmValues[0][2] = 0.0;
                    ctmValues[1][2] = 0.0;
                    ctmValues[2][2] = 1.0; 

                    COSArray ctm = (COSArray) dictionary.getDictionaryObject("CTM");
                    for (int i = 0; i < ctm.toList().size(); i += 2) {
                        int ctmRow = i / 2;
                        ctmValues[ctmRow][0] = Double.parseDouble(((COSString)ctm.get(i)).getString());
                        ctmValues[ctmRow][1] = Double.parseDouble(((COSString)ctm.get(i + 1)).getString());
                        System.out.printf("\t\t%s %s\n", ctm.get(i), ctm.get(i+1));
                    }
                }

                Double[][] neatLineValues =  null;
                int neatLineLength = 0;

                if (dictionary.containsKey("Neatline")) {
                    System.out.println("\tNeatline");

                    COSArray neatline = (COSArray) dictionary.getDictionaryObject("Neatline");
                    neatLineLength = neatline.toList().size();
                    neatLineValues = new Double[neatLineLength / 2][3];

                    for (int i = 0; i < neatline.toList().size(); i += 2) {
                        int neatLineRow = i / 2;
                        neatLineValues[neatLineRow][0] = Double.parseDouble(((COSString)neatline.get(i)).getString());
                        neatLineValues[neatLineRow][1] = Double.parseDouble(((COSString)neatline.get(i + 1)).getString());
                        neatLineValues[neatLineRow][2] = 1.0;
                        System.out.printf("\t\t%s, %s\n", neatline.get(i), neatline.get(i+1));
                    }
                }

                MultiPoint mp = new MultiPoint();

                if  (ctmValues != null && neatLineValues != null) {
                    // Transform the PDF coordinates to geospatial ones
                    Double [][] resultCoords = new Double[neatLineLength / 2][3];
                    for (int z = 0; z < neatLineLength / 2; z ++) {
                        for (int i = 0; i < 3; i++) {
                            resultCoords[z][i] = neatLineValues[z][0] * ctmValues[0][i] + neatLineValues[z][1] * ctmValues[1][i] + neatLineValues[z][2] * ctmValues[2][i];
                        }

                        // Set up the multipoint object
                        mp.add(resultCoords[z][0], resultCoords[z][1]);
                    }
                }

                if (dictionary.containsKey("Projection")) {
                    COSDictionary projectionDictionary = (COSDictionary)dictionary.getDictionaryObject("Projection");
                    String projectionType = projectionDictionary.getString("ProjectionType");

                    // UTM projections require slightly different processing
                    if ("UT".equals(projectionType)) {
                        String zone = Integer.toString(projectionDictionary.getInt("Zone"));
                        String hemisphere = projectionDictionary.getString("Hemisphere");

                        // Get the wkt for the geospatial coordinate system
                        String wkt = datumTranslation(projectionDictionary.getItem("Datum"));
                        if (zone != null && hemisphere != null && wkt != null) {
                            // Generate a list of UTM strings
                            List<String> utmCoords = new ArrayList<>();
                            for (Point2D pt : mp.getCoordinates2D()) {
                                String coord = String.format("%s%s %s %s", zone, hemisphere, Math.round(pt.x), Math.round(pt.y));
                                utmCoords.add(coord);
                            }

                            try (GeometryService svc = new GeometryService(HttpClients.custom().useSystemProperties().build(), new URL("https://utility.arcgisonline.com/ArcGIS/rest/services/Geometry/GeometryServer"));) {
                                MultiPoint reproj = svc.fromGeoCoordinateString(utmCoords, "utmDefault", WGS84_WKID);

                                currentBbox = generateBbox(reproj);
                            } catch (Exception e) {
                                LOG.error("Exception reprojecting geometry, skipping this geopdf dictionary instance...", e);
                                // throw new IOException(e);
                            }
                        } else {
                            LOG.warn("Missing UTM argument: zone: {}, hemisphere: {}, datum: {}", zone, hemisphere, wkt);
                        }
                    } else {

                        // Generate Well Known Text for projection and re-projects the points to WGS 84
                        String wkt = getProjectionWKT(projectionDictionary, projectionType);

                        if (wkt != null) {
                            try (GeometryService svc = new GeometryService(HttpClients.custom().useSystemProperties().build(), new URL("https://utility.arcgisonline.com/ArcGIS/rest/services/Geometry/GeometryServer"));) {
                                MultiPoint reproj = svc.project(mp, wkt, WGS84_WKID);

                                currentBbox = generateBbox(reproj);

                            } catch (Exception e) {
                                LOG.error("Exception reprojecting geometry, skipping this geopdf dictionary instance...", e);
                                // throw new IOException(e);
                            }
                        } else {
                            // Print out translated coordinates for debugging purposes
                            System.out.println("Translated Coordinates");
                            for (Point2D pt : mp.getCoordinates2D()) {
                                System.out.println(String.format("\t%s, %s", pt.x, pt.y));
                            }
                        }
                    }
                }

                bBoxes.add(currentBbox);
            } catch (IOException e) {
                LOG.error("Exception parsing PDF, ", e);
            } 
        });

        return bBoxes.get(0);
    }

    private static final String PROJ_WKT_TEMPLATE = "PROJCS[\"${name}\", ${geo_cs}, ${projection}, ${parameters}, ${linear_unit}]";
    private static final String GEO_WKT_TEMPLATE = "GEOGCS[\"${name}\", ${datum}, ${prime_meridian}, ${angular_unit}]";
    private static final String DATUM_TEMPLATE = "DATUM[\"${name}\", ${spheroid} ${to_wgs84}]";
    
    private static String getProjectionWKT(COSDictionary projectionDictionary, String projectionType) throws IOException {
        Map<String,String> tokens = new HashMap<>();

        String type = projectionDictionary.getString("Type");
        
        tokens.put("name", projectionType);

        System.out.println("Projection Type: " + projectionType);

        if ("LE".equalsIgnoreCase(projectionType)) {
            tokens.put("projection", "PROJECTION[\"Lambert_Conformal_Conic\"]");
            
            // Set up the projection parameters
            String paramsString = generateWKTParameters(projectionDictionary);
            tokens.put("parameters", paramsString);

            tokens.put("linear_unit", "UNIT[\"Meter\",1.0]");

            tokens.put("geo_cs", datumTranslation(projectionDictionary.getDictionaryObject("Datum")));
            
            // Set the parameters
            tokens.put("parameters", generateWKTParameters(projectionDictionary));

            StringSubstitutor subber = new StringSubstitutor(tokens);
            return subber.replace(PROJ_WKT_TEMPLATE);            
        } else if ("MC".equalsIgnoreCase(projectionType)) {
            tokens.put("projection", "PROJECTION[\"Mercator\"]");

            // Get the datum
            COSBase datumObj = projectionDictionary.getDictionaryObject("Datum");
            tokens.put("geo_cs", datumTranslation(datumObj));

            // Set the parameters
            tokens.put("parameters", generateWKTParameters(projectionDictionary.asUnmodifiableDictionary()));

            tokens.put("linear_unit", "UNIT[\"Meter\",1.0]");

            StringSubstitutor subber = new StringSubstitutor(tokens);
            return subber.replace(PROJ_WKT_TEMPLATE);  
        } else {
            System.out.println(projectionDictionary);
        }

        return null;
    }

    private static String datumTranslation(COSBase datumObj) {

        // TODO Build out datum if it's not specified as a string

        if (datumObj instanceof COSString) {
            String datumKey = ((COSString) datumObj).getString();

            if (datumKey.startsWith("NAS")) {
                return "GEOGCS[\"GCS_North_American_1927\",DATUM[\"D_North_American_1927\",SPHEROID[\"Clarke_1866\",6378206.4,294.9786982]],PRIMEM[\"Greenwich\",0],UNIT[\"Degree\",0.017453292519943295]]";
            } else if (datumKey.startsWith("WG") || datumKey.equals("WE")) {
                return "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137,298.257223563]],PRIMEM[\"Greenwich\",0],UNIT[\"Degree\",0.017453292519943295]]";
            }
        }

		return null;
    }

    private static String generateWKTParameters(COSDictionary projectionDictionary) throws IOException {
        // Set up the projection parameters
        Properties parameters = new Properties();

        COSDictionaryMap<String, Object> dictionaryMap = COSDictionaryMap.convertBasicTypesToMap(projectionDictionary);

        if (projectionDictionary.containsKey("CentralMeridian")) {
            parameters.put("Central_Meridian", (String) dictionaryMap.get("CentralMeridian"));
        }
        if (projectionDictionary.containsKey("OriginLatitude")){
            parameters.put("Latitude_Of_Origin", (String) dictionaryMap.get("OriginLatitude"));
        }
        if (projectionDictionary.containsKey("StandardParallelOne")){
            parameters.put("Standard_Parallel_1", (String) dictionaryMap.get("StandardParallelOne"));
        }
        if (projectionDictionary.containsKey("StandardParallelTwo")){
            parameters.put("Standard_Parallel_2", (String) dictionaryMap.get("StandardParallelTwo"));
        }
        if (projectionDictionary.containsKey("FalseEasting")){
            parameters.put("False_Easting", (String) dictionaryMap.get("FalseEasting"));
        }
        if (projectionDictionary.containsKey("FalseNorthing")){
            parameters.put("False_Northing", (String) dictionaryMap.get("FalseNorthing"));
        }
        if (projectionDictionary.containsKey("ScaleFactor")) {
            parameters.put("Scale_Factor", (String) dictionaryMap.get("ScaleFactor"));
        }

        return parameters.entrySet().stream().map(entry -> "PARAMETER[\""+ entry.getKey() + "\", "+ entry.getValue() + "]").collect(Collectors.joining(","));
    }

    private static final String generateBbox(MultiPoint points) {
        int count = points.getPointCount();
        Double xMax = -Double.MAX_VALUE;
        Double yMax = -Double.MAX_VALUE;
        Double xMin = Double.MAX_VALUE;
        Double yMin = Double.MAX_VALUE;

        for (int i = 0; i < count; i++) {
            Point pt = points.getPoint(i);
            
            if (pt.getX() > xMax) {
                xMax = pt.getX();
            }
            if (pt.getX() < xMin) {
                xMin = pt.getX();
            }

            if (pt.getY() > yMax) {
                yMax = pt.getY();
            }
            if (pt.getY() < yMin) {
                yMin = pt.getY();
            }
        }

        return generateBbox(xMin, yMin, xMax, yMax);
    }

    private static String generateBbox (Float xMin, Float yMin, Float xMax, Float yMax) {
        return generateBbox(xMin.doubleValue(), yMin.doubleValue(), xMax.doubleValue(), yMax.doubleValue());
    }

    private static String generateBbox (Double xMin, Double yMin, Double xMax, Double yMax) {
        return String.format("%s %s, %s %s", yMin, xMin, yMax, xMax);
    }
}