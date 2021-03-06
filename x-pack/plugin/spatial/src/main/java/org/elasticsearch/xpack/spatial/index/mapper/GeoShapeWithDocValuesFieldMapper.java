/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.spatial.index.mapper;

import org.apache.lucene.document.LatLonShape;
import org.apache.lucene.document.ShapeField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocValuesFieldExistsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.Version;
import org.elasticsearch.common.Explicit;
import org.elasticsearch.common.geo.GeometryParser;
import org.elasticsearch.common.geo.builders.ShapeBuilder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.geometry.Geometry;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.mapper.AbstractGeometryFieldMapper;
import org.elasticsearch.index.mapper.FieldNamesFieldMapper;
import org.elasticsearch.index.mapper.GeoShapeFieldMapper;
import org.elasticsearch.index.mapper.GeoShapeIndexer;
import org.elasticsearch.index.mapper.LegacyGeoShapeFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.ParseContext;
import org.elasticsearch.index.mapper.TypeParsers;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.index.query.VectorGeoShapeQueryProcessor;
import org.elasticsearch.search.aggregations.support.ValuesSourceType;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Extension of {@link org.elasticsearch.index.mapper.GeoShapeFieldMapper} that supports docValues
 *
 * FieldMapper for indexing {@link LatLonShape}s.
 * <p>
 * Currently Shapes can only be indexed and can only be queried using
 * {@link org.elasticsearch.index.query.GeoShapeQueryBuilder}, consequently
 * a lot of behavior in this Mapper is disabled.
 * <p>
 * Format supported:
 * <p>
 * "field" : {
 * "type" : "polygon",
 * "coordinates" : [
 * [ [100.0, 0.0], [101.0, 0.0], [101.0, 1.0], [100.0, 1.0], [100.0, 0.0] ]
 * ]
 * }
 * <p>
 * or:
 * <p>
 * "field" : "POLYGON ((100.0 0.0, 101.0 0.0, 101.0 1.0, 100.0 1.0, 100.0 0.0))
 */
public class GeoShapeWithDocValuesFieldMapper extends GeoShapeFieldMapper {
    public static final String CONTENT_TYPE = "geo_shape";


    private Explicit<Boolean> docValues;

    @Override
    public void doXContentBody(XContentBuilder builder, boolean includeDefaults, Params params) throws IOException {
        super.doXContentBody(builder, includeDefaults, params);
        if (includeDefaults || docValues.explicit()) {
            builder.field(TypeParsers.DOC_VALUES, docValues.value());
        }
    }

    @SuppressWarnings("rawtypes")
    public static class Builder extends AbstractGeometryFieldMapper.Builder<AbstractGeometryFieldMapper.Builder,
            GeoShapeWithDocValuesFieldMapper> {
        public Builder(String name) {
            super (name, new GeoShapeWithDocValuesFieldType(), new GeoShapeWithDocValuesFieldType());
        }

        @Override
        public GeoShapeWithDocValuesFieldMapper build(BuilderContext context) {
            setupFieldType(context);
            return new GeoShapeWithDocValuesFieldMapper(name, fieldType, defaultFieldType, ignoreMalformed(context), coerce(context),
                ignoreZValue(), docValues(), context.indexSettings(),
                multiFieldsBuilder.build(this, context), copyTo);
        }

        @Override
        public boolean defaultDocValues(Version indexCreated) {
            return Version.V_8_0_0.onOrBefore(indexCreated);
        }

        protected Explicit<Boolean> docValues() {
            if (docValuesSet && fieldType.hasDocValues()) {
                return new Explicit<>(true, true);
            } else if (docValuesSet) {
                return new Explicit<>(false, true);
            }
            return new Explicit<>(fieldType.hasDocValues(), false);
        }

        protected void setupFieldType(BuilderContext context) {
            super.setupFieldType(context);

            GeoShapeWithDocValuesFieldType fieldType = (GeoShapeWithDocValuesFieldType)fieldType();
            boolean orientation = fieldType.orientation() == ShapeBuilder.Orientation.RIGHT;

            GeometryParser geometryParser = new GeometryParser(orientation, coerce(context).value(), ignoreZValue().value());

            fieldType.setGeometryIndexer(new GeoShapeIndexer(orientation, fieldType.name()) {
                @Override
                public List<IndexableField> indexShape(ParseContext context, Geometry shape) {
                    List<IndexableField> fields = super.indexShape(context, shape);
                    if (fieldType().hasDocValues()) {
                        CentroidCalculator calculator = new CentroidCalculator(shape);
                        final byte[] scratch = new byte[7 * Integer.BYTES];
                        // doc values are generated from the indexed fields.
                        ShapeField.DecodedTriangle[] triangles = new ShapeField.DecodedTriangle[fields.size()];
                        for (int i = 0; i < fields.size(); i++) {
                            BytesRef bytesRef = fields.get(i).binaryValue();
                            assert bytesRef.length == 7 * Integer.BYTES;
                            System.arraycopy(bytesRef.bytes, bytesRef.offset, scratch, 0, 7 * Integer.BYTES);
                            ShapeField.decodeTriangle(scratch, triangles[i] = new ShapeField.DecodedTriangle());
                        }
                        BinaryGeoShapeDocValuesField docValuesField =
                            (BinaryGeoShapeDocValuesField) context.doc().getByKey(name);
                        if (docValuesField == null) {
                            docValuesField = new BinaryGeoShapeDocValuesField(name, triangles, calculator);
                            context.doc().addWithKey(name, docValuesField);
                        } else {
                            docValuesField.add(triangles, calculator);
                        }
                    }
                    return fields;
                }
            });
            fieldType.setGeometryParser( (parser, mapper) -> geometryParser.parse(parser));
            fieldType.setGeometryQueryBuilder(new VectorGeoShapeQueryProcessor());
        }
    }

    public static final class GeoShapeWithDocValuesFieldType extends GeoShapeFieldMapper.GeoShapeFieldType {
        public GeoShapeWithDocValuesFieldType() {
            super();
        }

        protected GeoShapeWithDocValuesFieldType(GeoShapeWithDocValuesFieldType ref) {
            super(ref);
        }

        public IndexFieldData.Builder fielddataBuilder(String fullyQualifiedIndexName) {
            failIfNoDocValues();
            return new AbstractLatLonShapeDVIndexFieldData.Builder();
        }

        @Override
        public Query existsQuery(QueryShardContext context) {
            if (hasDocValues()) {
                return new DocValuesFieldExistsQuery(name());
            } else {
                return new TermQuery(new Term(FieldNamesFieldMapper.NAME, name()));
            }
        }

        @Override
        public ValuesSourceType getValuesSourceType() {
            return GeoShapeValuesSourceType.INSTANCE;
        }

        @Override
        public GeoShapeWithDocValuesFieldType clone() {
            return new GeoShapeWithDocValuesFieldType(this);
        }
    }

    public static final class TypeParser extends AbstractGeometryFieldMapper.TypeParser {

        @Override
        @SuppressWarnings("rawtypes")
        protected AbstractGeometryFieldMapper.Builder newBuilder(String name, Map<String, Object> params) {
            if (params.containsKey(DEPRECATED_PARAMETERS_KEY)) {
                return new LegacyGeoShapeFieldMapper.Builder(name,
                    (LegacyGeoShapeFieldMapper.DeprecatedParameters)params.get(DEPRECATED_PARAMETERS_KEY));
            }
            return new GeoShapeWithDocValuesFieldMapper.Builder(name);
        }

        @Override
        @SuppressWarnings("rawtypes")
        public Mapper.Builder parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            AbstractGeometryFieldMapper.Builder builder = (AbstractGeometryFieldMapper.Builder) super.parse(name, node, parserContext);
            Map<String, Object> params = new HashMap<>();
            for (Iterator<Map.Entry<String, Object>> iterator = node.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<String, Object> entry = iterator.next();
                String fieldName = entry.getKey();
                Object fieldNode = entry.getValue();
                if (TypeParsers.DOC_VALUES.equals(fieldName)) {
                    params.put(TypeParsers.DOC_VALUES, XContentMapValues.nodeBooleanValue(fieldNode, name + "." + TypeParsers.DOC_VALUES));
                    iterator.remove();
                }
            }

            if (params.containsKey(TypeParsers.DOC_VALUES)) {
                builder.docValues((Boolean) params.get(TypeParsers.DOC_VALUES));
            }
            return builder;
        }
    }

    public GeoShapeWithDocValuesFieldMapper(String simpleName, MappedFieldType fieldType, MappedFieldType defaultFieldType,
                                            Explicit<Boolean> ignoreMalformed, Explicit<Boolean> coerce,
                                            Explicit<Boolean> ignoreZValue, Explicit<Boolean> docValues, Settings indexSettings,
                                            MultiFields multiFields, CopyTo copyTo) {
        super(simpleName, fieldType, defaultFieldType, ignoreMalformed, coerce, ignoreZValue, indexSettings,
            multiFields, copyTo);
        this.docValues = docValues;
    }

    public Explicit<Boolean> docValues() {
        return docValues;
    }

    @Override
    public GeoShapeWithDocValuesFieldType fieldType() {
        return (GeoShapeWithDocValuesFieldType) super.fieldType();
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

}
