/**
 * Copyright (c) Codice Foundation
 *
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or any later version. 
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 *
 **/
package net.frogmouth.ddf.nitfinputtransformer;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import org.junit.Test;
import org.osgi.framework.BundleContext;

import ddf.catalog.data.Metacard;
import ddf.catalog.data.QualifiedMetacardType;
import ddf.catalog.transform.CatalogTransformerException;
import ddf.catalog.source.UnsupportedQueryException;
import ddf.catalog.source.SourceUnavailableException;
import ddf.catalog.federation.FederationException;
import ddf.catalog.operation.QueryRequest;
import ddf.catalog.operation.impl.QueryResponseImpl;

public class TestBasicInputTransformer {
    private static final BundleContext context = mock(BundleContext.class);
    private static List<QualifiedMetacardType> qmtList = new ArrayList<QualifiedMetacardType>();

    private static final String BE_NUM_NITF = "/WithBE.ntf";

    private static final String TRE_NITF = "/i_3128b.ntf";

    public static NitfInputTransformer createTransformer() throws UnsupportedQueryException, SourceUnavailableException, FederationException {
        NitfInputTransformer transformer = new NitfInputTransformer();
        ddf.catalog.CatalogFramework catalog = mock(ddf.catalog.CatalogFramework.class);
        when(catalog.query(any(QueryRequest.class))).thenReturn(new QueryResponseImpl(null, "sourceId"));
        transformer.setCatalog(catalog);

        return transformer;
    }

    @Test(expected = CatalogTransformerException.class)
    public void testNullInput() throws IOException, CatalogTransformerException, UnsupportedQueryException, SourceUnavailableException, FederationException {
        createTransformer().transform(null);
    }

    @Test(expected = CatalogTransformerException.class)
    public void testBadInput() throws IOException, CatalogTransformerException, UnsupportedQueryException, SourceUnavailableException, FederationException {
        createTransformer().transform(new ByteArrayInputStream("{key=".getBytes()));
    }

    @Test()
    public void testSorcerWithBE() throws IOException, CatalogTransformerException, UnsupportedQueryException, SourceUnavailableException, FederationException, ParseException  {
        Metacard metacard = createTransformer().transform(getInputStream(BE_NUM_NITF));

        assertNotNull(metacard);

        assertNotNull(metacard.getCreatedDate());
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        assertThat(formatter.format(metacard.getCreatedDate()), is("2014-08-17 07:22:41"));
        System.out.println("metacard = " + metacard.getMetadata());
    }

    @Test()
    public void testTreParsing() throws IOException, CatalogTransformerException, UnsupportedQueryException, SourceUnavailableException, FederationException, ParseException  {
        Metacard metacard = createTransformer().transform(getInputStream(TRE_NITF));

        assertNotNull(metacard);

        assertNotNull(metacard.getCreatedDate());
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        assertThat(formatter.format(metacard.getCreatedDate()), is("1999-02-10 14:01:44"));
        System.out.println("metacard = " + metacard.getMetadata());
    }

    private InputStream getInputStream(String filename) {
        assertNotNull("Test file missing", getClass().getResource(filename));
        return getClass().getResourceAsStream(filename);
    }
}