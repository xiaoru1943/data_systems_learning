package org.apache.calcite.example.converter;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.example.CalciteUtil;
import org.apache.calcite.example.overall.SimpleSchema;
import org.apache.calcite.example.overall.SimpleTable;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.sql2rel.StandardConvertletTable;

import java.util.Collections;
import java.util.Properties;

public class CalciteSQLConverter {

  public static void main(String[] args) throws SqlParseException {
    SimpleTable userTable = SimpleTable.newBuilder("users")
            .addField("id", SqlTypeName.VARCHAR)
            .addField("name", SqlTypeName.VARCHAR)
            .addField("age", SqlTypeName.INTEGER)
            .build();
    SimpleTable orderTable = SimpleTable.newBuilder("orders")
            .addField("id", SqlTypeName.VARCHAR)
            .addField("user_id", SqlTypeName.VARCHAR)
            .addField("goods", SqlTypeName.VARCHAR)
            .addField("price", SqlTypeName.DECIMAL)
            .build();
    SimpleSchema schema = SimpleSchema.newBuilder("s")
            .addTable(userTable)
            .addTable(orderTable)
            .build();
    CalciteSchema rootSchema = CalciteSchema.createRootSchema(false, false);
    rootSchema.add(schema.getSchemaName(), schema);

    String sql = "SELECT u.id, name, age, sum(price) " +
            "FROM users AS u join orders AS o ON u.id = o.user_id " +
            "WHERE age >= 20 AND age <= 30 " +
            "GROUP BY u.id, name, age " +
            "ORDER BY u.id";

    Properties configProperties = new Properties();
    configProperties.put(CalciteConnectionProperty.CASE_SENSITIVE.camelName(), Boolean.TRUE.toString());
    configProperties.put(CalciteConnectionProperty.UNQUOTED_CASING.camelName(), Casing.UNCHANGED.toString());
    configProperties.put(CalciteConnectionProperty.QUOTED_CASING.camelName(), Casing.UNCHANGED.toString());
    CalciteConnectionConfig config = new CalciteConnectionConfigImpl(configProperties);

    // parse sql
    SqlParser.Config parserConfig = SqlParser.config()
            .withQuotedCasing(config.quotedCasing())
            .withUnquotedCasing(config.unquotedCasing())
            .withQuoting(config.quoting())
            .withConformance(config.conformance())
            .withCaseSensitive(config.caseSensitive());
    SqlParser parser = SqlParser.create(sql, parserConfig);
    SqlNode sqlNode = parser.parseStmt();
    System.out.println(sqlNode);

    // validate sql
    RelDataTypeFactory factory = new SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT);
    Prepare.CatalogReader catalogReader = new CalciteCatalogReader(
            rootSchema,
            Collections.singletonList(schema.getSchemaName()),
            factory,
            new CalciteConnectionConfigImpl(new Properties()));
    SqlValidator.Config validatorConfig = SqlValidator.Config.DEFAULT
            .withLenientOperatorLookup(config.lenientOperatorLookup())
            .withSqlConformance(config.conformance())
            .withDefaultNullCollation(config.defaultNullCollation())
            .withIdentifierExpansion(true);
    SqlValidator validator = SqlValidatorUtil.newValidator(
            SqlStdOperatorTable.instance(), catalogReader, factory, validatorConfig);
    SqlNode validateNode = validator.validate(sqlNode);

    // convert to RelNode tree
    RexBuilder rexBuilder = new RexBuilder(factory);
    HepProgramBuilder builder = new HepProgramBuilder();
//    builder.addRuleInstance(PruneEmptyRules.PROJECT_INSTANCE);
    HepPlanner planner = new HepPlanner(builder.build());

    RelOptCluster cluster = RelOptCluster.create(planner, rexBuilder);

    SqlToRelConverter.Config converterConfig = SqlToRelConverter.config()
            .withTrimUnusedFields(true)
            .withExpand(false);
    SqlToRelConverter converter = new SqlToRelConverter(
            null,
            validator,
            catalogReader,
            cluster,
            StandardConvertletTable.INSTANCE,
            converterConfig);
    RelRoot relRoot = converter.convertQuery(validateNode, false, true);
    CalciteUtil.print("Convert Result:", relRoot.rel.explain());
  }
}
