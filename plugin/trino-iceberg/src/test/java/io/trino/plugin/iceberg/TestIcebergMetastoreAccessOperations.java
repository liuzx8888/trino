/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.iceberg;

import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.Multiset;
import io.trino.Session;
import io.trino.plugin.hive.metastore.CountingAccessHiveMetastore;
import io.trino.plugin.hive.metastore.CountingAccessHiveMetastoreUtil;
import io.trino.plugin.iceberg.catalog.file.TestingIcebergFileMetastoreCatalogModule;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.DistributedQueryRunner;
import org.intellij.lang.annotations.Language;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.util.Optional;

import static com.google.inject.util.Modules.EMPTY_MODULE;
import static io.trino.plugin.hive.metastore.CountingAccessHiveMetastore.Method.CREATE_TABLE;
import static io.trino.plugin.hive.metastore.CountingAccessHiveMetastore.Method.DROP_TABLE;
import static io.trino.plugin.hive.metastore.CountingAccessHiveMetastore.Method.GET_ALL_TABLES_FROM_DATABASE;
import static io.trino.plugin.hive.metastore.CountingAccessHiveMetastore.Method.GET_DATABASE;
import static io.trino.plugin.hive.metastore.CountingAccessHiveMetastore.Method.GET_TABLE;
import static io.trino.plugin.hive.metastore.CountingAccessHiveMetastore.Method.GET_TABLE_WITH_PARAMETER;
import static io.trino.plugin.hive.metastore.CountingAccessHiveMetastore.Method.REPLACE_TABLE;
import static io.trino.plugin.hive.metastore.file.TestingFileHiveMetastore.createTestingFileHiveMetastore;
import static io.trino.plugin.iceberg.IcebergSessionProperties.COLLECT_EXTENDED_STATISTICS_ON_WRITE;
import static io.trino.plugin.iceberg.TableType.DATA;
import static io.trino.plugin.iceberg.TableType.FILES;
import static io.trino.plugin.iceberg.TableType.HISTORY;
import static io.trino.plugin.iceberg.TableType.MANIFESTS;
import static io.trino.plugin.iceberg.TableType.PARTITIONS;
import static io.trino.plugin.iceberg.TableType.PROPERTIES;
import static io.trino.plugin.iceberg.TableType.REFS;
import static io.trino.plugin.iceberg.TableType.SNAPSHOTS;
import static io.trino.testing.TestingNames.randomNameSuffix;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;

@Test(singleThreaded = true) // metastore invocation counters shares mutable state so can't be run from many threads simultaneously
public class TestIcebergMetastoreAccessOperations
        extends AbstractTestQueryFramework
{
    private static final int MAX_PREFIXES_COUNT = 10;
    private static final Session TEST_SESSION = testSessionBuilder()
            .setCatalog("iceberg")
            .setSchema("test_schema")
            .build();

    private CountingAccessHiveMetastore metastore;

    @Override
    protected DistributedQueryRunner createQueryRunner()
            throws Exception
    {
        DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(TEST_SESSION)
                .addCoordinatorProperty("optimizer.experimental-max-prefetched-information-schema-prefixes", Integer.toString(MAX_PREFIXES_COUNT))
                .build();

        File baseDir = queryRunner.getCoordinator().getBaseDataDir().resolve("iceberg_data").toFile();
        metastore = new CountingAccessHiveMetastore(createTestingFileHiveMetastore(baseDir));
        queryRunner.installPlugin(new TestingIcebergPlugin(Optional.of(new TestingIcebergFileMetastoreCatalogModule(metastore)), Optional.empty(), EMPTY_MODULE));
        queryRunner.createCatalog("iceberg", "iceberg");

        queryRunner.execute("CREATE SCHEMA test_schema");
        return queryRunner;
    }

    @Test
    public void testUse()
    {
        String catalog = getSession().getCatalog().orElseThrow();
        String schema = getSession().getSchema().orElseThrow();
        Session session = Session.builder(getSession())
                .setCatalog(Optional.empty())
                .setSchema(Optional.empty())
                .build();
        assertMetastoreInvocations(session, "USE %s.%s".formatted(catalog, schema),
                ImmutableMultiset.builder()
                        .add(GET_DATABASE)
                        .build());
    }

    @Test
    public void testCreateTable()
    {
        assertMetastoreInvocations("CREATE TABLE test_create (id VARCHAR, age INT)",
                ImmutableMultiset.builder()
                        .add(CREATE_TABLE)
                        .add(GET_DATABASE)
                        .add(GET_TABLE)
                        .build());
    }

    @Test
    public void testCreateTableAsSelect()
    {
        assertMetastoreInvocations(
                withStatsOnWrite(getSession(), false),
                "CREATE TABLE test_ctas AS SELECT 1 AS age",
                ImmutableMultiset.builder()
                        .add(GET_DATABASE)
                        .add(CREATE_TABLE)
                        .add(GET_TABLE)
                        .build());

        assertMetastoreInvocations(
                withStatsOnWrite(getSession(), true),
                "CREATE TABLE test_ctas_with_stats AS SELECT 1 AS age",
                ImmutableMultiset.builder()
                        .add(GET_DATABASE)
                        .add(CREATE_TABLE)
                        .addCopies(GET_TABLE, 4)
                        .add(REPLACE_TABLE)
                        .build());
    }

    @Test
    public void testSelect()
    {
        assertUpdate("CREATE TABLE test_select_from (id VARCHAR, age INT)");

        assertMetastoreInvocations("SELECT * FROM test_select_from",
                ImmutableMultiset.builder()
                        .add(GET_TABLE)
                        .build());
    }

    @Test
    public void testSelectWithFilter()
    {
        assertUpdate("CREATE TABLE test_select_from_where AS SELECT 2 as age", 1);

        assertMetastoreInvocations("SELECT * FROM test_select_from_where WHERE age = 2",
                ImmutableMultiset.builder()
                        .add(GET_TABLE)
                        .build());
    }

    @Test
    public void testSelectFromView()
    {
        assertUpdate("CREATE TABLE test_select_view_table (id VARCHAR, age INT)");
        assertUpdate("CREATE VIEW test_select_view_view AS SELECT id, age FROM test_select_view_table");

        assertMetastoreInvocations("SELECT * FROM test_select_view_view",
                ImmutableMultiset.builder()
                        .addCopies(GET_TABLE, 2)
                        .build());
    }

    @Test
    public void testSelectFromViewWithFilter()
    {
        assertUpdate("CREATE TABLE test_select_view_where_table AS SELECT 2 as age", 1);
        assertUpdate("CREATE VIEW test_select_view_where_view AS SELECT age FROM test_select_view_where_table");

        assertMetastoreInvocations("SELECT * FROM test_select_view_where_view WHERE age = 2",
                ImmutableMultiset.builder()
                        .addCopies(GET_TABLE, 2)
                        .build());
    }

    @Test
    public void testSelectFromMaterializedView()
    {
        assertUpdate("CREATE TABLE test_select_mview_table (id VARCHAR, age INT)");
        assertUpdate("CREATE MATERIALIZED VIEW test_select_mview_view AS SELECT id, age FROM test_select_mview_table");

        assertMetastoreInvocations("SELECT * FROM test_select_mview_view",
                ImmutableMultiset.builder()
                        .addCopies(GET_TABLE, 3)
                        .build());
    }

    @Test
    public void testSelectFromMaterializedViewWithFilter()
    {
        assertUpdate("CREATE TABLE test_select_mview_where_table AS SELECT 2 as age", 1);
        assertUpdate("CREATE MATERIALIZED VIEW test_select_mview_where_view AS SELECT age FROM test_select_mview_where_table");

        assertMetastoreInvocations("SELECT * FROM test_select_mview_where_view WHERE age = 2",
                ImmutableMultiset.builder()
                        .addCopies(GET_TABLE, 3)
                        .build());
    }

    @Test
    public void testRefreshMaterializedView()
    {
        assertUpdate("CREATE TABLE test_refresh_mview_table (id VARCHAR, age INT)");
        assertUpdate("CREATE MATERIALIZED VIEW test_refresh_mview_view AS SELECT id, age FROM test_refresh_mview_table");

        assertMetastoreInvocations("REFRESH MATERIALIZED VIEW test_refresh_mview_view",
                ImmutableMultiset.builder()
                        .addCopies(GET_TABLE, 6)
                        .addCopies(REPLACE_TABLE, 1)
                        .build());
    }

    @Test
    public void testJoin()
    {
        assertUpdate("CREATE TABLE test_join_t1 AS SELECT 2 as age, 'id1' AS id", 1);
        assertUpdate("CREATE TABLE test_join_t2 AS SELECT 'name1' as name, 'id1' AS id", 1);

        assertMetastoreInvocations("SELECT name, age FROM test_join_t1 JOIN test_join_t2 ON test_join_t2.id = test_join_t1.id",
                ImmutableMultiset.builder()
                        .addCopies(GET_TABLE, 2)
                        .build());
    }

    @Test
    public void testSelfJoin()
    {
        assertUpdate("CREATE TABLE test_self_join_table AS SELECT 2 as age, 0 parent, 3 AS id", 1);

        assertMetastoreInvocations("SELECT child.age, parent.age FROM test_self_join_table child JOIN test_self_join_table parent ON child.parent = parent.id",
                ImmutableMultiset.builder()
                        .add(GET_TABLE)
                        .build());
    }

    @Test
    public void testExplainSelect()
    {
        assertUpdate("CREATE TABLE test_explain AS SELECT 2 as age", 1);

        assertMetastoreInvocations("EXPLAIN SELECT * FROM test_explain",
                ImmutableMultiset.builder()
                        .add(GET_TABLE)
                        .build());
    }

    @Test
    public void testShowStatsForTable()
    {
        assertUpdate("CREATE TABLE test_show_stats AS SELECT 2 as age", 1);

        assertMetastoreInvocations("SHOW STATS FOR test_show_stats",
                ImmutableMultiset.builder()
                        .add(GET_TABLE)
                        .build());
    }

    @Test
    public void testShowStatsForTableWithFilter()
    {
        assertUpdate("CREATE TABLE test_show_stats_with_filter AS SELECT 2 as age", 1);

        assertMetastoreInvocations("SHOW STATS FOR (SELECT * FROM test_show_stats_with_filter where age >= 2)",
                ImmutableMultiset.builder()
                        .add(GET_TABLE)
                        .build());
    }

    @Test
    public void testSelectSystemTable()
    {
        assertUpdate("CREATE TABLE test_select_snapshots AS SELECT 2 AS age", 1);

        // select from $history
        assertMetastoreInvocations("SELECT * FROM \"test_select_snapshots$history\"",
                ImmutableMultiset.builder()
                        .addCopies(GET_TABLE, 1)
                        .build());

        // select from $snapshots
        assertMetastoreInvocations("SELECT * FROM \"test_select_snapshots$snapshots\"",
                ImmutableMultiset.builder()
                        .addCopies(GET_TABLE, 1)
                        .build());

        // select from $manifests
        assertMetastoreInvocations("SELECT * FROM \"test_select_snapshots$manifests\"",
                ImmutableMultiset.builder()
                        .addCopies(GET_TABLE, 1)
                        .build());

        // select from $partitions
        assertMetastoreInvocations("SELECT * FROM \"test_select_snapshots$partitions\"",
                ImmutableMultiset.builder()
                        .addCopies(GET_TABLE, 1)
                        .build());

        // select from $files
        assertMetastoreInvocations("SELECT * FROM \"test_select_snapshots$files\"",
                ImmutableMultiset.builder()
                        .addCopies(GET_TABLE, 1)
                        .build());

        // select from $properties
        assertMetastoreInvocations("SELECT * FROM \"test_select_snapshots$properties\"",
                ImmutableMultiset.builder()
                        .addCopies(GET_TABLE, 1)
                        .build());

        // This test should get updated if a new system table is added.
        assertThat(TableType.values())
                .containsExactly(DATA, HISTORY, SNAPSHOTS, MANIFESTS, PARTITIONS, FILES, PROPERTIES, REFS);
    }

    @Test
    public void testUnregisterTable()
    {
        assertUpdate("CREATE TABLE test_unregister_table AS SELECT 2 as age", 1);

        assertMetastoreInvocations("CALL system.unregister_table(CURRENT_SCHEMA, 'test_unregister_table')",
                ImmutableMultiset.builder()
                        .add(GET_DATABASE)
                        .add(GET_TABLE)
                        .add(DROP_TABLE)
                        .build());
    }

    @Test(dataProvider = "metadataQueriesTestTableCountDataProvider")
    public void testInformationSchemaColumns(int tables)
    {
        String schemaName = "test_i_s_columns_schema" + randomNameSuffix();
        assertUpdate("CREATE SCHEMA " + schemaName);
        Session session = Session.builder(getSession())
                .setSchema(schemaName)
                .build();

        for (int i = 0; i < tables; i++) {
            assertUpdate(session, "CREATE TABLE test_select_i_s_columns" + i + "(id varchar, age integer)");
            // Produce multiple snapshots and metadata files
            assertUpdate(session, "INSERT INTO test_select_i_s_columns" + i + " VALUES ('abc', 11)", 1);
            assertUpdate(session, "INSERT INTO test_select_i_s_columns" + i + " VALUES ('xyz', 12)", 1);

            assertUpdate(session, "CREATE TABLE test_other_select_i_s_columns" + i + "(id varchar, age integer)"); // won't match the filter
        }

        assertMetastoreInvocations(session, "SELECT * FROM information_schema.columns WHERE table_schema = CURRENT_SCHEMA AND table_name LIKE 'test_select_i_s_columns%'",
                ImmutableMultiset.builder()
                        .add(GET_ALL_TABLES_FROM_DATABASE)
                        .addCopies(GET_TABLE, tables * 2)
                        .addCopies(GET_TABLE_WITH_PARAMETER, 2)
                        .build());

        for (int i = 0; i < tables; i++) {
            assertUpdate(session, "DROP TABLE test_select_i_s_columns" + i);
            assertUpdate(session, "DROP TABLE test_other_select_i_s_columns" + i);
        }
    }

    @Test(dataProvider = "metadataQueriesTestTableCountDataProvider")
    public void testSystemMetadataTableComments(int tables)
    {
        String schemaName = "test_s_m_table_comments" + randomNameSuffix();
        assertUpdate("CREATE SCHEMA " + schemaName);
        Session session = Session.builder(getSession())
                .setSchema(schemaName)
                .build();

        for (int i = 0; i < tables; i++) {
            assertUpdate(session, "CREATE TABLE test_select_s_m_t_comments" + i + "(id varchar, age integer)");
            // Produce multiple snapshots and metadata files
            assertUpdate(session, "INSERT INTO test_select_s_m_t_comments" + i + " VALUES ('abc', 11)", 1);
            assertUpdate(session, "INSERT INTO test_select_s_m_t_comments" + i + " VALUES ('xyz', 12)", 1);

            assertUpdate(session, "CREATE TABLE test_other_select_s_m_t_comments" + i + "(id varchar, age integer)"); // won't match the filter
        }

        // Bulk retrieval
        assertMetastoreInvocations(session, "SELECT * FROM system.metadata.table_comments WHERE schema_name = CURRENT_SCHEMA AND table_name LIKE 'test_select_s_m_t_comments%'",
                ImmutableMultiset.builder()
                        .add(GET_ALL_TABLES_FROM_DATABASE)
                        .addCopies(GET_TABLE, tables * 2)
                        .addCopies(GET_TABLE_WITH_PARAMETER, 2)
                        .build());

        // Pointed lookup
        assertMetastoreInvocations(session, "SELECT * FROM system.metadata.table_comments WHERE schema_name = CURRENT_SCHEMA AND table_name = 'test_select_s_m_t_comments0'",
                ImmutableMultiset.builder()
                        .addCopies(GET_TABLE, 1)
                        .build());

        for (int i = 0; i < tables; i++) {
            assertUpdate(session, "DROP TABLE test_select_s_m_t_comments" + i);
            assertUpdate(session, "DROP TABLE test_other_select_s_m_t_comments" + i);
        }
    }

    @DataProvider
    public Object[][] metadataQueriesTestTableCountDataProvider()
    {
        return new Object[][] {
                {3},
                {MAX_PREFIXES_COUNT},
                {MAX_PREFIXES_COUNT + 3},
        };
    }

    private void assertMetastoreInvocations(@Language("SQL") String query, Multiset<?> expectedInvocations)
    {
        assertMetastoreInvocations(getSession(), query, expectedInvocations);
    }

    private void assertMetastoreInvocations(Session session, @Language("SQL") String query, Multiset<?> expectedInvocations)
    {
        CountingAccessHiveMetastoreUtil.assertMetastoreInvocations(metastore, getQueryRunner(), session, query, expectedInvocations);
    }

    private static Session withStatsOnWrite(Session session, boolean enabled)
    {
        String catalog = session.getCatalog().orElseThrow();
        return Session.builder(session)
                .setCatalogSessionProperty(catalog, COLLECT_EXTENDED_STATISTICS_ON_WRITE, Boolean.toString(enabled))
                .build();
    }
}
