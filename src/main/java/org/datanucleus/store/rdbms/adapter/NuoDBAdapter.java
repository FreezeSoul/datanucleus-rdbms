/**********************************************************************
Copyright (c) 2013 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
    ...
**********************************************************************/
package org.datanucleus.store.rdbms.adapter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.exceptions.NucleusDataStoreException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.identity.DatastoreId;
import org.datanucleus.plugin.PluginManager;
import org.datanucleus.store.connection.ManagedConnection;
import org.datanucleus.store.rdbms.identifier.IdentifierFactory;
import org.datanucleus.store.rdbms.key.Index;
import org.datanucleus.store.rdbms.key.PrimaryKey;
import org.datanucleus.store.rdbms.schema.SQLTypeInfo;
import org.datanucleus.store.schema.StoreSchemaHandler;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.StringUtils;

/**
 * Adapter for NuoDB (http://www.nuodb.com).
 * This adapter was written based on v2.0.2 of the NuoDB JDBC driver.
 */
public class NuoDBAdapter extends BaseDatastoreAdapter
{
    public static final String NONSQL92_RESERVED_WORDS =
            "BIGINT,BINARY,BLOB,BOOLEAN,CLOB,LIMIT,NCLOB,OFFSET,ROLE,TRIGGER";

    public static final String NUODB_EXTRA_RESERVED_WORDS =
            "BITS,BREAK,CATCH,CONTAINING,END_FOR,END_IF,END_PROCEDURE,END_TRIGGER,END_TRY,END_WHILE,ENUM,FOR_UPDATE,IF," +
            "LOGICAL_AND,LOGICAL_NOT,LOGICAL_OR,NEXT_VALUE,NOT_BETWEEN,NOT_CONTAINING,NOT_IN,NOT_LIKE,NOT_STARTING,NVARCHAR,OFF," +
            "RECORD_BATCHING,REGEXP,SHOW,SMALLDATETIME,STARTING,STRING_TYPE,THROW,TINYBLOB,TINYINT,TRY,VAR,VER";

    public NuoDBAdapter(DatabaseMetaData metadata)
    {
        super(metadata);

        reservedKeywords.addAll(StringUtils.convertCommaSeparatedStringToSet(NONSQL92_RESERVED_WORDS));
        reservedKeywords.addAll(StringUtils.convertCommaSeparatedStringToSet(NUODB_EXTRA_RESERVED_WORDS));

        supportedOptions.add(IDENTITY_COLUMNS);
        supportedOptions.add(SEQUENCES);
        supportedOptions.add(PRIMARYKEY_IN_CREATE_STATEMENTS);
        supportedOptions.add(LOCK_WITH_SELECT_FOR_UPDATE);
        supportedOptions.add(STORED_PROCEDURES);

        // NuoDB JDBC driver doesn't specify lengths in 2.0.2
        if (maxTableNameLength <= 0)
        {
            maxTableNameLength = 128;
        }
        if (maxColumnNameLength <= 0)
        {
            maxColumnNameLength = 128;
        }
        if (maxConstraintNameLength <= 0)
        {
            maxConstraintNameLength = 128;
        }
        if (maxIndexNameLength <= 0)
        {
            maxIndexNameLength = 128;
        }

        // CROSS JOIN syntax is not supported
        supportedOptions.remove(ANSI_CROSSJOIN_SYNTAX);
        supportedOptions.add(CROSSJOIN_ASINNER11_SYNTAX);

        // Doesn't seem to support FK constraints
        supportedOptions.remove(FK_DELETE_ACTION_RESTRICT);
        supportedOptions.remove(FK_DELETE_ACTION_NULL);
        supportedOptions.remove(FK_UPDATE_ACTION_RESTRICT);
        supportedOptions.remove(FK_UPDATE_ACTION_NULL);
        supportedOptions.remove(FK_DELETE_ACTION_CASCADE);
        supportedOptions.remove(FK_DELETE_ACTION_DEFAULT);
        supportedOptions.remove(FK_UPDATE_ACTION_CASCADE);
        supportedOptions.remove(FK_UPDATE_ACTION_DEFAULT);
        supportedOptions.remove(DEFERRED_CONSTRAINTS);

        supportedOptions.remove(RESULTSET_TYPE_SCROLL_SENSITIVE);
        supportedOptions.remove(RESULTSET_TYPE_SCROLL_INSENSITIVE);

        supportedOptions.remove(TX_ISOLATION_REPEATABLE_READ);
        supportedOptions.remove(TX_ISOLATION_READ_UNCOMMITTED);
        supportedOptions.remove(TX_ISOLATION_NONE);

        supportedOptions.remove(ACCESS_PARENTQUERY_IN_SUBQUERY_JOINED);

        supportedOptions.add(OPERATOR_BITWISE_AND);
        supportedOptions.add(OPERATOR_BITWISE_OR);
        supportedOptions.add(OPERATOR_BITWISE_XOR);
    }

    public String getVendorID()
    {
        return "nuodb";
    }

    public String getCatalogName(Connection conn) throws SQLException
    {
        return null;
    }

    public String getSchemaName(Connection conn) throws SQLException 
    {
        Statement stmt = conn.createStatement();
        try 
        {
            String stmtText = "SELECT CURRENT_SCHEMA FROM DUAL";
            ResultSet rs = stmt.executeQuery(stmtText);
            try 
            {
                if (!rs.next()) 
                {
                    throw new NucleusDataStoreException("No result returned from " + stmtText).setFatal();
                }
                return rs.getString(1);
            }
            finally 
            {
                rs.close();
            }
        }
        finally
        {
            stmt.close();
        }
    }

    public void initialiseTypes(StoreSchemaHandler handler, ManagedConnection mconn) 
    {
        super.initialiseTypes(handler, mconn);

        SQLTypeInfo sqlType = new org.datanucleus.store.rdbms.adapter.NuoDBTypeInfo(
                "FLOAT", (short) Types.DOUBLE, 53, null, null, null, 1, false, (short) 2,
                false, false, false, null, (short) 0, (short) 0, 2);
        addSQLTypeForJDBCType(handler, mconn, (short) Types.DOUBLE, sqlType, true);

        sqlType = new org.datanucleus.store.rdbms.adapter.NuoDBTypeInfo(
                "TEXT", (short) Types.CLOB, 2147483647, null, null, null, 1, true, (short) 1,
                false, false, false, "TEXT", (short) 0, (short) 0, 0);
        addSQLTypeForJDBCType(handler, mconn, (short) Types.CLOB, sqlType, true);
    }

    /**
     * Returns the appropriate DDL to create an index. It should return something like:
     * <pre>
     * CREATE INDEX FOO_N1 ON FOO (BAR,BAZ) [Extended Settings]
     * CREATE UNIQUE INDEX FOO_U1 ON FOO (BAR,BAZ) [Extended Settings]
     * </pre>
     * @param idx An object describing the index.
     * @param factory Identifier factory
     * @return The text of the SQL statement.
     */
    public String getCreateIndexStatement(Index idx, IdentifierFactory factory)
    {
        String extendedSetting = idx.getValueForExtension(Index.EXTENSION_INDEX_EXTENDED_SETTING);

        // Set index name
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("CREATE ").append((idx.getUnique() ? "UNIQUE " : "")).append("INDEX ");
        stringBuilder.append(factory.getIdentifierInAdapterCase(idx.getName()));
        stringBuilder.append(" ON ").append(idx.getTable().toString());
        stringBuilder.append(" ").append(idx.getColumnList());
        if (extendedSetting != null)
        {
            stringBuilder.append(" ").append(extendedSetting);
        }
        return stringBuilder.toString();
    }

    public String getDropDatabaseStatement(String catalogName, String schemaName)
    {
        return "DROP SCHEMA " + schemaName + " CASCADE";
    }

    /**
     * Override the default implementation since we accept the PK in the CREATE TABLE statement.
     * @param pk An object describing the primary key.
     * @param factory Identifier factory
     * @return The PK statement
     */
    public String getAddPrimaryKeyStatement(PrimaryKey pk, IdentifierFactory factory)
    {
        // We generate the PK in the CREATE TABLE statement
        return null;
    }

    public boolean sequenceExists(Connection conn, String catalogName, String schemaName, String seqName)
    {
        /*PreparedStatement ps = null;
        ResultSet rs = null;
        try
        {
            String GET_SEQUENCE_STMT = "SELECT * FROM SYSTEM.SEQUENCES";

            NucleusLogger.DATASTORE_SCHEMA.debug("Retrieving sequence info using the following SQL : " + GET_SEQUENCE_STMT);
            ps = conn.prepareStatement(GET_SEQUENCE_STMT);
            rs = ps.executeQuery();
            while (rs.next())
            {
                
            }
        }
        catch (SQLException sqle)
        {
            NucleusLogger.DATASTORE_SCHEMA.warn(">> Exception caught", sqle);
        }
        finally
        {
            try
            {
                if (rs != null && !rs.isClosed())
                {
                    rs.close();
                }
                ps.close();
            }
            catch (SQLException sqle)
            {

            }
        }*/

        // TODO Make use of the following
        // SELECT * FROM SYSTEM.SEQUENCES;
        //SCHEMA SEQUENCENAME
        //====== ============
        //HOCKEY HOCKEY$IDENTITY_SEQUENCE
        return true;
    }

    /**
     * Accessor for the sequence statement to create the sequence.
     * @param sequenceName Name of the sequence 
     * @param min Minimum value for the sequence
     * @param max Maximum value for the sequence
     * @param start Start value for the sequence
     * @param increment Increment value for the sequence
     * @param cacheSize Cache size for the sequence
     * @return The statement for getting the next id from the sequence
     */
    public String getSequenceCreateStmt(String sequenceName, Integer min, Integer max, Integer start, Integer increment, Integer cacheSize)
    {
        if (sequenceName == null)
        {
            throw new NucleusUserException(Localiser.msg("051028"));
        }

        StringBuilder stmt = new StringBuilder("CREATE SEQUENCE ");
        stmt.append(sequenceName);
        if (start != null)
        {
            stmt.append(" START WITH " + start);
        }
        // TODO Support other parameters if NuoDB ever supports them

        return stmt.toString();
    }

    /**
     * Accessor for the statement for getting the next id from the sequence for this datastore.
     * @param sequenceName Name of the sequence 
     * @return The statement for getting the next id for the sequence
     **/
    public String getSequenceNextStmt(String sequenceName)
    {
        if (sequenceName == null)
        {
            throw new NucleusUserException(Localiser.msg("051028"));
        }
        StringBuilder stmt=new StringBuilder("SELECT NEXT VALUE FOR ");
        stmt.append(sequenceName);
        stmt.append(" FROM DUAL");

        return stmt.toString();
    }

    /**
     * Accessor for the auto-increment keyword for generating DDLs (CREATE TABLEs...).
     * @return The keyword for a column using auto-increment
     */
    public String getAutoIncrementKeyword()
    {
        return "GENERATED BY DEFAULT AS IDENTITY";
    }

    /**
     * Method to return the SQL to append to the WHERE clause of a SELECT statement to handle
     * restriction of ranges using the OFFSET/FETCH keywords.
     * @param offset The offset to return from
     * @param count The number of items to return
     * @param hasOrdering Whether there is ordering present
     * @return The SQL to append to allow for ranges using OFFSET/FETCH.
     */
    @Override
    public String getRangeByLimitEndOfStatementClause(long offset, long count, boolean hasOrdering)
    {
        if (datastoreMajorVersion < 10 || (datastoreMajorVersion == 10 && datastoreMinorVersion < 5))
        {
            return "";
        }
        else if (offset <= 0 && count <= 0)
        {
            return "";
        }

        StringBuilder str = new StringBuilder();
        if (offset > 0)
        {
            str.append("OFFSET " + offset + (offset > 1 ? " ROWS " : " ROW "));
        }
        if (count > 0)
        {
            str.append("FETCH NEXT " + (count > 1 ? (count + " ROWS ONLY ") : "ROW ONLY "));
        }
        return str.toString();
    }

    public String getDatastoreDateStatement()
    {
        return "SELECT CURRENT_DATE FROM DUAL";
    }

    /**
     * Load all datastore mappings for this RDBMS database.
     * @param mgr the PluginManager
     * @param clr the ClassLoaderResolver
     */
    protected void loadDatastoreMappings(PluginManager mgr, ClassLoaderResolver clr)
    {
        // Load up built-in types for this datastore
        registerDatastoreMapping(Boolean.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.BitColumnMapping.class, JDBCType.BIT, "BIT", true);
        registerDatastoreMapping(Boolean.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.CharColumnMapping.class, JDBCType.CHAR, "CHAR", false);
        registerDatastoreMapping(Boolean.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.BooleanColumnMapping.class, JDBCType.BOOLEAN, "BOOLEAN", false);
        registerDatastoreMapping(Boolean.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.SmallIntColumnMapping.class, JDBCType.SMALLINT, "SMALLINT", false);

        registerDatastoreMapping(Byte.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.SmallIntColumnMapping.class, JDBCType.SMALLINT, "SMALLINT", true);

        registerDatastoreMapping(Character.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.CharColumnMapping.class, JDBCType.CHAR, "CHAR", true);
        registerDatastoreMapping(Character.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.IntegerColumnMapping.class, JDBCType.INTEGER, "INTEGER", false);

        registerDatastoreMapping(Double.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.DoubleColumnMapping.class, JDBCType.DOUBLE, "DOUBLE", true);
        registerDatastoreMapping(Double.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.DecimalColumnMapping.class, JDBCType.DECIMAL, "DECIMAL", false);

        registerDatastoreMapping(Float.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.FloatColumnMapping.class, JDBCType.FLOAT, "FLOAT", true);
        registerDatastoreMapping(Float.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.DoubleColumnMapping.class, JDBCType.DOUBLE, "DOUBLE", false);
        registerDatastoreMapping(Float.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.RealColumnMapping.class, JDBCType.REAL, "REAL", false);
        registerDatastoreMapping(Float.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.DecimalColumnMapping.class, JDBCType.DECIMAL, "DECIMAL", false);

        registerDatastoreMapping(Integer.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.IntegerColumnMapping.class, JDBCType.INTEGER, "INTEGER", true);
        registerDatastoreMapping(Integer.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.BigIntColumnMapping.class, JDBCType.BIGINT, "BIGINT", false);
        registerDatastoreMapping(Integer.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.NumericColumnMapping.class, JDBCType.NUMERIC, "NUMERIC", false);
        registerDatastoreMapping(Integer.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.SmallIntColumnMapping.class, JDBCType.SMALLINT, "SMALLINT", false);

        registerDatastoreMapping(Long.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.BigIntColumnMapping.class, JDBCType.BIGINT, "BIGINT", true);
        registerDatastoreMapping(Long.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.IntegerColumnMapping.class, JDBCType.INTEGER, "INT", false);
        registerDatastoreMapping(Long.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.NumericColumnMapping.class, JDBCType.NUMERIC, "NUMERIC", false);
        registerDatastoreMapping(Long.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.SmallIntColumnMapping.class, JDBCType.SMALLINT, "SMALLINT", false);

        registerDatastoreMapping(Short.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.SmallIntColumnMapping.class, JDBCType.SMALLINT, "SMALLINT", true);
        registerDatastoreMapping(Short.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.IntegerColumnMapping.class, JDBCType.INTEGER, "INTEGER", false);

        registerDatastoreMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.VarCharColumnMapping.class, JDBCType.VARCHAR, "VARCHAR", true);
        registerDatastoreMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.CharColumnMapping.class, JDBCType.CHAR, "CHAR", false);
        registerDatastoreMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.BigIntColumnMapping.class, JDBCType.BIGINT, "BIGINT", false);
        registerDatastoreMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.LongVarcharColumnMapping.class, JDBCType.LONGVARCHAR, "LONGVARCHAR", false);
        registerDatastoreMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.ClobColumnMapping.class, JDBCType.CLOB, "CLOB", false);
        registerDatastoreMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.BlobColumnMapping.class, JDBCType.BLOB, "BLOB", false);
        registerDatastoreMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.NVarcharColumnMapping.class, JDBCType.NVARCHAR, "NVARCHAR", false);
        registerDatastoreMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.NCharColumnMapping.class, JDBCType.NCHAR, "NCHAR", false);

        registerDatastoreMapping(BigDecimal.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.DecimalColumnMapping.class, JDBCType.DECIMAL, "DECIMAL", true);
        registerDatastoreMapping(BigDecimal.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.NumericColumnMapping.class, JDBCType.NUMERIC, "NUMERIC", false);

        registerDatastoreMapping(BigInteger.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.NumericColumnMapping.class, JDBCType.NUMERIC, "NUMERIC", true);

        registerDatastoreMapping(java.sql.Date.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.DateColumnMapping.class, JDBCType.DATE, "DATE", true);
        registerDatastoreMapping(java.sql.Date.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.TimestampColumnMapping.class, JDBCType.TIMESTAMP, "TIMESTAMP", false);
        registerDatastoreMapping(java.sql.Date.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.CharColumnMapping.class, JDBCType.CHAR, "CHAR", false);
        registerDatastoreMapping(java.sql.Date.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.VarCharColumnMapping.class, JDBCType.VARCHAR, "VARCHAR", false);
        registerDatastoreMapping(java.sql.Date.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.BigIntColumnMapping.class, JDBCType.BIGINT, "BIGINT", false);

        registerDatastoreMapping(java.sql.Time.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.TimeColumnMapping.class, JDBCType.TIME, "TIME", true);
        registerDatastoreMapping(java.sql.Time.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.TimestampColumnMapping.class, JDBCType.TIMESTAMP, "TIMESTAMP", false);
        registerDatastoreMapping(java.sql.Time.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.CharColumnMapping.class, JDBCType.CHAR, "CHAR", false);
        registerDatastoreMapping(java.sql.Time.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.VarCharColumnMapping.class, JDBCType.VARCHAR, "VARCHAR", false);
        registerDatastoreMapping(java.sql.Time.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.BigIntColumnMapping.class, JDBCType.BIGINT, "BIGINT", false);

        registerDatastoreMapping(java.sql.Timestamp.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.TimestampColumnMapping.class, JDBCType.TIMESTAMP, "TIMESTAMP", true);
        registerDatastoreMapping(java.sql.Timestamp.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.CharColumnMapping.class, JDBCType.CHAR, "CHAR", false);
        registerDatastoreMapping(java.sql.Timestamp.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.VarCharColumnMapping.class, JDBCType.VARCHAR, "VARCHAR", false);
        registerDatastoreMapping(java.sql.Timestamp.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.DateColumnMapping.class, JDBCType.DATE, "DATE", false);
        registerDatastoreMapping(java.sql.Timestamp.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.TimeColumnMapping.class, JDBCType.TIME, "TIME", false);

        registerDatastoreMapping(java.util.Date.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.TimestampColumnMapping.class, JDBCType.TIMESTAMP, "TIMESTAMP", true);
        registerDatastoreMapping(java.util.Date.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.DateColumnMapping.class, JDBCType.DATE, "DATE", false);
        registerDatastoreMapping(java.util.Date.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.CharColumnMapping.class, JDBCType.CHAR, "CHAR", false);
        registerDatastoreMapping(java.util.Date.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.VarCharColumnMapping.class, JDBCType.VARCHAR, "VARCHAR", false);
        registerDatastoreMapping(java.util.Date.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.TimeColumnMapping.class, JDBCType.TIME, "TIME", false);
        registerDatastoreMapping(java.util.Date.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.BigIntColumnMapping.class, JDBCType.BIGINT, "BIGINT", false);

        registerDatastoreMapping(java.io.Serializable.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.LongVarBinaryColumnMapping.class, JDBCType.LONGVARBINARY, "LONGVARBINARY", true);
        registerDatastoreMapping(java.io.Serializable.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.BlobColumnMapping.class, JDBCType.BLOB, "BLOB", false);
        registerDatastoreMapping(java.io.Serializable.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.VarBinaryColumnMapping.class, JDBCType.VARBINARY, "VARBINARY", false);

        registerDatastoreMapping(byte[].class.getName(), org.datanucleus.store.rdbms.mapping.datastore.LongVarBinaryColumnMapping.class, JDBCType.LONGVARBINARY, "LONGVARBINARY", true);
        registerDatastoreMapping(byte[].class.getName(), org.datanucleus.store.rdbms.mapping.datastore.BlobColumnMapping.class, JDBCType.BLOB, "BLOB", false);
        registerDatastoreMapping(byte[].class.getName(), org.datanucleus.store.rdbms.mapping.datastore.VarBinaryColumnMapping.class, JDBCType.VARBINARY, "VARBINARY", false);

        registerDatastoreMapping(java.io.File.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.BinaryStreamColumnMapping.class, JDBCType.LONGVARBINARY, "LONGVARBINARY", true);

        registerDatastoreMapping(DatastoreId.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.BigIntColumnMapping.class, JDBCType.BIGINT, "BIGINT", true);
        registerDatastoreMapping(DatastoreId.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.IntegerColumnMapping.class, JDBCType.INTEGER, "INTEGER", false);
        registerDatastoreMapping(DatastoreId.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.NumericColumnMapping.class, JDBCType.NUMERIC, "NUMERIC", false);
        registerDatastoreMapping(DatastoreId.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.CharColumnMapping.class, JDBCType.CHAR, "CHAR", false);
        registerDatastoreMapping(DatastoreId.class.getName(), org.datanucleus.store.rdbms.mapping.datastore.VarCharColumnMapping.class, JDBCType.VARCHAR, "VARCHAR", false);

        super.loadDatastoreMappings(mgr, clr);
    }
}